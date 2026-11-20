#!/usr/bin/env python3
import copy
import hashlib
import tempfile
import importlib.util
import json
import os
import subprocess
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('control_map', Path(__file__).with_name('control_map.py'))
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)
FEATURES = M.ROOT / M.FEATURES
REGISTRY = json.loads((FEATURES / 'registry.json').read_text())
MAP = json.loads((FEATURES / 'controls.json').read_text())
BASELINE = json.loads((FEATURES / 'control-gaps.json').read_text())
PATH = 'app/src/main/java/dev/multiprompt/companion/ui/Example.kt'
LIVE = os.environ.get('CONTROL_MAP_LIVE') == '1'


def mapped(sites):
    controls = [{
        'id': f'existing.{index}', 'site': site['site'], 'source': site['source'],
        'screen': 'Example', 'label': site['kind'], 'journey': None,
        'missing': 'Existing test fixture gap',
    } for index, site in enumerate(sites)]
    return {'controls': controls, 'forwarding': []}, {'missing': [c['id'] for c in controls]}


class ControlMapTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sites = M.inventory()

    @unittest.skipUnless(LIVE, 'set CONTROL_MAP_LIVE=1 to compare the map with the live app inventory')
    def test_current_inventory_and_gap_baseline(self):
        self.assertEqual([], M.validate(self.sites, MAP, BASELINE, REGISTRY))
        self.assertGreater(len(MAP['controls']), 450)
        self.assertEqual(len(BASELINE['missing']), len(set(BASELINE['missing'])))
        self.assertEqual(
            len(MAP['controls']) - len(BASELINE['missing']),
            sum(c['journey'] is not None for c in MAP['controls']),
        )

    def test_every_new_interaction_fails_with_its_source_and_fix(self):
        original = '@Composable fun Example() { KitPrimaryButton("Save", onClick = save) }'
        sites = M.scan(original, PATH)
        data, baseline = mapped(sites)
        additions = [
            'KitMenuRow(label = "New menu item", onClick = run)',
            'KitPrimaryButton("New button", run)',
            'Modifier.clickable { run() }',
            'Modifier.combinedClickable(onClick = run, onLongClick = hold)',
            'Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = run) }',
            'Modifier.horizontalSwipe(next)',
            'Modifier.toggleable(value, onValueChange = change)',
            'KitSheet(onDismiss = close) { KitMenuRow("Sheet item", run) }',
            'OutlinedTextField(value, change)',
            'KeyboardActions(onSend = run)',
            'KitRoundButton(run, icon, "New close")',
        ]
        for addition in additions:
            with self.subTest(addition=addition):
                source = original[:-1] + '\n' + addition + '\n}'
                errors = M.validate(M.scan(source, PATH), data, baseline, REGISTRY)
                unmapped = '\n'.join(e for e in errors if e.startswith('UNMAPPED'))
                self.assertIn(PATH + ':', unmapped)
                self.assertIn('Fix: add each control', unmapped)

    def test_comments_strings_definitions_and_movement(self):
        source = 'fun Example() { KitMenuRow("Modifier.clickable { fake() }", onClick = run) }'
        sites = M.scan(source, PATH)
        self.assertEqual(['KitMenuRow'], [s['kind'] for s in sites])
        moved = M.scan('\n\n' + source, PATH)
        self.assertEqual(sites[0]['site'], moved[0]['site'])
        self.assertNotEqual(sites[0]['source'], moved[0]['source'])
        comment = '/' + '/ KitMenuRow("fake", run)\n'
        self.assertEqual([], M.scan(comment, PATH))
        self.assertEqual([], M.scan('fun KitPrimaryButton(onClick: () -> Unit) {}', PATH))

    def test_nested_functions_do_not_steal_the_screen_owner(self):
        source = 'fun ReaderScreen() { fun helper() { IconButton(run) {} }; KitPrimaryButton("Save", run) }'
        self.assertEqual(['helper', 'ReaderScreen'], [s['owner'] for s in M.scan(source, PATH)])

    def test_new_identical_buttons_are_not_hidden_by_a_set(self):
        source = 'fun Example() { KitMenuRow("Repeat", run) }'
        before = M.scan(source, PATH)
        after = M.scan(source.replace(' }', '; KitMenuRow("Repeat", run) }'), PATH)
        data, baseline = mapped(before)
        errors = M.validate(after, data, baseline, REGISTRY)
        self.assertEqual(1, sum(e.startswith('UNMAPPED') for e in errors))

    def test_new_missing_entry_and_baseline_growth_fail_even_if_an_old_gap_is_removed(self):
        data = copy.deepcopy(MAP)
        baseline = copy.deepcopy(BASELINE)
        covered = next(c for c in data['controls'] if c['journey'] is not None)
        covered['journey'] = None
        covered['missing'] = 'A new gap'
        baseline['missing'].append(covered['id'])
        baseline['missing'].pop(0)
        errors = M.validate(self.sites, data, baseline, REGISTRY, (MAP, BASELINE))
        self.assertTrue(any(e.startswith('BASELINE GROWTH ' + covered['id']) for e in errors))

    def test_unknown_and_manual_recipes_do_not_count(self):
        for journey in ['invented.md', 'session-settings.md']:
            with self.subTest(journey=journey):
                data = copy.deepcopy(MAP)
                c = next(c for c in data['controls'] if c['journey'] is not None)
                c['journey'] = journey
                errors = M.validate(self.sites, data, BASELINE, REGISTRY)
                self.assertTrue(any('live journey must exist' in e for e in errors))

    def test_removed_controls_and_wrong_locations_fail(self):
        data = copy.deepcopy(MAP)
        data['controls'][0]['source'] += '0'
        errors = M.validate(self.sites, data, BASELINE, REGISTRY)
        self.assertTrue(any(e.startswith('Moved source') for e in errors))
        errors = M.validate(self.sites[1:], MAP, BASELINE, REGISTRY)
        self.assertTrue(any(e.startswith('Stale map entry') for e in errors))

    def test_aliases_are_still_controls(self):
        source = 'import androidx.compose.foundation.clickable as tap\nfun Example() { Modifier.tap { run() } }'
        self.assertEqual(['clickable'], [s['kind'] for s in M.scan(source, PATH)])

    def test_added_choice_fails_even_when_the_loop_control_did_not_change(self):
        before = 'val presets = listOf("Later today", "Tomorrow")'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'Choices.kt'
            path.write_text(before)
            data = {'choice_sources': [{'path': 'Choices.kt', 'label': 'Reminder presets', 'sha256': hashlib.sha256(before.encode()).hexdigest()}]}
            self.assertEqual([], M.choice_errors(data, root))
            path.write_text(before.replace('"Tomorrow"', '"Tomorrow", "Next week"'))
            errors = M.choice_errors(data, root)
            self.assertIn('CHOICES CHANGED Choices.kt: Reminder presets', errors[0])
            self.assertIn('new controls need live journeys', errors[0])

    @unittest.skipUnless(LIVE, 'set CONTROL_MAP_LIVE=1 to compare choice digests with the live app inventory')
    def test_choice_watch_covers_ui_and_external_finite_controls(self):
        self.assertEqual([], M.choice_errors(MAP))
        watched = {c['path'] for c in MAP['choice_sources']}
        interaction_paths = {s['source'].rsplit(':', 1)[0] for s in self.sites if '/ui/' in s['source']}
        self.assertFalse(interaction_paths - watched)
        for name in ['AgentHarness.kt', 'TerminalKeys.kt', 'HostSettings.kt', 'SwitchAi.kt', 'ChatReactions.kt']:
            self.assertTrue(any(p.endswith('/' + name) for p in watched), name)

    def test_real_pr_380_search_controls_fail_the_parent_inventory(self):
        ref = '50eb80bbcc560ee2813d0eb869ab2364663275a8'
        path = 'app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt'
        def source(revision):
            return subprocess.check_output(['git', 'show', f'{revision}:{path}'], cwd=M.ROOT, text=True)
        before = M.scan(source(ref + '^'), path)
        after = M.scan(source(ref), path)
        data, baseline = mapped(before)
        errors = M.validate(after, data, baseline, REGISTRY)
        for name in ['KitRoundButton', 'KitSearchBox']:
            self.assertTrue(any(e.startswith('UNMAPPED') and f'ReaderSearchBar / {name}' in e for e in errors), name)
        self.assertTrue(any(e.startswith('UNMAPPED') and 'Search in chat' in e for e in errors))
        print(f'REAL_PR_380_UNMAPPED_SEARCH_REJECTED {ref}: Close search, Search this chat, Previous match, Next match, Search in chat')


if __name__ == '__main__':
    unittest.main()
