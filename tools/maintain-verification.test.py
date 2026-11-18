#!/usr/bin/env python3
import copy
import importlib.util
import json
from pathlib import Path
import os
import tempfile
import sys
import subprocess
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SKILL = ROOT / '.claude/skills/verify-multiprompt-android'
SPEC = importlib.util.spec_from_file_location('maintain', SKILL / 'scripts/maintain.py')
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)
REGISTRY = json.loads((SKILL / 'features/registry.json').read_text())


def passed_jobs():
    return [{'name': f'journey {feature} / verify', 'conclusion': 'success', 'status': 'completed'}
            for feature, journey in REGISTRY['journeys'].items() if journey['runner']['kind'] != 'manual' and feature not in M.plan(REGISTRY)['blocked']]


class MaintenanceTest(unittest.TestCase):
    def test_weekly_workflow_and_dispatch_bridge_supersede_runs(self):
        workflow = (ROOT / '.github/workflows/maintain-verification.yml').read_text()
        self.assertIn("cron: '17 5 * * 1'", workflow)
        self.assertIn('  workflow_dispatch:', workflow)
        self.assertIn('  workflow_call:', workflow)
        self.assertIn('  group: maintain-android-feature-map\n  cancel-in-progress: true', workflow)
        self.assertIn('      fail-fast: false\n      max-parallel: 2', workflow)
        self.assertIn('uses: ./.github/workflows/verify.yml', workflow)
        self.assertIn('side: ${{ matrix.journey }}', workflow)
        bridge = (ROOT / '.github/workflows/emulator-stability.yml').read_text()
        self.assertIn('cancel-in-progress: ${{ inputs.feature_map == true }}', bridge)
        self.assertIn('uses: ./.github/workflows/maintain-verification.yml', bridge)

    def test_every_registered_journey_has_a_route_or_explicit_blocker(self):
        result = M.plan(REGISTRY)
        routed = {item['journey'] for item in result['matrix']['include']}
        dedicated = {feature for feature, (_, flag) in M.DEDICATED.items() if result[flag]}
        self.assertEqual(set(REGISTRY['journeys']), routed | dedicated | set(result['blocked']) | set(result['manual']))
        self.assertEqual({}, result['blocked'])
        self.assertEqual({'session-settings.md'}, set(result['manual']))
        self.assertNotIn('session-settings.md', routed)

    def test_signed_routes_keep_their_inputs_and_use_branch_candidates(self):
        matrix = {item['journey']: item for item in M.plan(REGISTRY)['matrix']['include']}
        for feature, journey in REGISTRY['journeys'].items():
            if journey['runner']['kind'] != 'signed':
                continue
            self.assertTrue(matrix[feature]['candidate'])
            for key, value in journey['runner']['inputs'].items():
                self.assertEqual(value, matrix[feature][key])
        self.assertTrue(matrix['session-pins.md']['pins_scroll'])
        self.assertEqual('session-favorites.md', matrix['session-pins.md']['feature'])
        self.assertNotEqual(matrix['session-pins.md']['journey'], matrix['session-favorites.md']['journey'])

    def test_future_runner_or_input_is_blocked_not_silently_omitted(self):
        registry = copy.deepcopy(REGISTRY)
        registry['journeys']['sessions.md']['runner']['inputs']['new_flag'] = True
        registry['journeys']['new.md'] = {'runner': {'kind': 'dedicated', 'workflow': 'new.yml', 'prerequisites': []}}
        blocked = M.plan(registry)['blocked']
        self.assertIn('new_flag', blocked['sessions.md'])
        self.assertIn('new.yml', blocked['new.md'])

    def test_pagination_failures_missing_skipped_and_canceled_journeys(self):
        jobs = passed_jobs()
        self.assertGreater(len(jobs), 30)
        failed = jobs[0]['name'].split(' ')[1]
        jobs[0]['conclusion'] = 'failure'
        skipped = jobs[1]['name'].split(' ')[1]
        jobs[1]['conclusion'] = 'skipped'
        canceled = jobs[2]['name'].split(' ')[1]
        jobs[2]['conclusion'] = 'cancelled'
        missing = jobs.pop()['name'].split(' ')[1]
        failures, blocked = M.conclusions(REGISTRY, [{'jobs': jobs[:20]}, {'jobs': jobs[20:]}])
        self.assertEqual({failed, skipped, canceled, missing}, set(failures))
        self.assertIn('missing job', failures[missing])
        self.assertEqual({}, blocked)

    def test_existing_dispatch_bridge_preserves_journey_names(self):
        jobs = [{**job, 'name': 'maintenance / ' + job['name']} for job in passed_jobs()]
        failures, _ = M.conclusions(REGISTRY, [{'jobs': jobs}])
        self.assertEqual({}, failures)

    def test_success_cannot_hide_a_failed_nested_job(self):
        jobs = passed_jobs()
        jobs.append({'name': jobs[0]['name'].replace('/ verify', '/ build'), 'conclusion': 'failure', 'status': 'completed'})
        failures, _ = M.conclusions(REGISTRY, [{'jobs': jobs}])
        self.assertEqual(1, len(failures))

    def test_clean_report_cli_exits_zero_with_manual_prerequisites(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / M.SKILL / 'scripts/maintain.py'
            script.parent.mkdir(parents=True)
            script.write_text((SKILL / 'scripts/maintain.py').read_text())
            (root / M.SKILL / 'features').mkdir()
            (root / M.SKILL / 'features/registry.json').write_text(json.dumps(REGISTRY))
            (root / 'source-result.json').write_text('{"before":0,"after":0,"registry":0}')
            (root / 'jobs.json').write_text(json.dumps([{'jobs': passed_jobs()}]))
            result = subprocess.run([sys.executable, '-B', str(script), 'report', str(root)], env={'RUN_URL': 'https://example.test/run/1', 'GITHUB_STEP_SUMMARY': str(root / 'summary.md')}, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertFalse(json.loads((root / 'publication.json').read_text())['published'])

    def test_clean_report_exits_zero_without_a_pr_and_drift_opens_exactly_one(self):
        for drift, dry_run in [(False, False), (True, False), (False, True), (True, True)]:
            with self.subTest(drift=drift, dry_run=dry_run), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / M.SKILL / 'features').mkdir(parents=True)
                (root / M.SKILL / 'features/registry.json').write_text(json.dumps(REGISTRY))
                evidence = root / 'evidence'
                evidence.mkdir()
                (evidence / 'source-result.json').write_text(json.dumps({'before': int(drift), 'after': 0, 'registry': 0}))
                (evidence / 'jobs.json').write_text(json.dumps([{'jobs': [] if dry_run else passed_jobs()}]))
                env = {'RUN_URL': 'https://example.test/run/1', 'GITHUB_STEP_SUMMARY': str(root / 'summary.md'), 'DRY_RUN': str(dry_run).lower()}
                with patch.object(M, 'ROOT', root), patch.object(M, 'publish', return_value='https://example.test/pr/42') as publish, patch.object(sys, 'argv', ['maintain.py', 'report', str(evidence)]), patch.dict(os.environ, env):
                    if drift:
                        with self.assertRaises(SystemExit):
                            M.main()
                    else:
                        self.assertIsNone(M.main())
                    self.assertEqual(int(drift and not dry_run), publish.call_count)
                report = (evidence / 'report.md').read_text()
                self.assertIn('session-settings.md', report)
                publication = json.loads((evidence / 'publication.json').read_text())
                self.assertEqual(drift, publication['needs_pr'])
                self.assertEqual(drift and not dry_run, publication['published'])
                if dry_run:
                    self.assertIn('planned, not driven', report)

    def test_failures_create_one_pr_and_retries_update_it_without_merging(self):
        for existing in [[], [{'number': 42, 'baseRefName': 'main', 'url': 'https://example.test/pr/42'}]]:
            with self.subTest(existing=bool(existing)), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / M.SKILL / 'features').mkdir(parents=True)
                evidence = root / 'evidence'
                evidence.mkdir()
                (evidence / 'controls.json').write_text('{"corrected":true}\n')
                calls = []
                def command(*args):
                    calls.append(args)
                    if args[:3] == ('gh', 'pr', 'list'):
                        return json.dumps(existing)
                    if args[:3] == ('git', 'ls-remote', '--heads'):
                        return 'remote-sha' if existing else ''
                    return 'https://example.test/pr/42'
                env = {'GITHUB_REPOSITORY': 'owner/repo', 'DEFAULT_BRANCH': 'main', 'RUN_URL': 'https://example.test/run/7'}
                with patch.object(M, 'ROOT', root), patch.object(M, 'run', side_effect=command), patch.object(M.subprocess, 'run') as diff, patch.dict(os.environ, env):
                    diff.return_value.returncode = 1
                    url = M.publish(evidence, 'Drift report\n', {'before': 1, 'after': 0}, {'sessions.md': 'failure'}, {})
                self.assertEqual('https://example.test/pr/42', url)
                mutations = [c for c in calls if c[:3] in [('gh', 'pr', 'create'), ('gh', 'pr', 'edit')]]
                self.assertEqual(1, len(mutations))
                self.assertEqual('edit' if existing else 'create', mutations[0][2])
                self.assertFalse(any('merge' in c or '--force' in c for c in calls))
                self.assertEqual('{"corrected":true}\n', (root / M.SKILL / 'features/controls.json').read_text())
                self.assertLessEqual(len((evidence / 'pr-body.md').read_text().splitlines()), 40)
                self.assertIn('`sessions.md` (failure)', (evidence / 'pr-body.md').read_text())

    def test_clean_automated_routes_do_not_claim_manual_coverage(self):
        failures, blocked = M.conclusions(REGISTRY, [{'jobs': passed_jobs()}])
        self.assertEqual({}, failures)
        text = M.body({'before': 1, 'after': 0}, failures, blocked, 'https://example.test/run/1', M.plan(REGISTRY)['manual'])
        self.assertIn('1 / 0', text)
        self.assertIn('not live coverage', text)
        self.assertIn('session-settings.md', text)
        self.assertIn('https://example.test/run/1', text)


if __name__ == '__main__':
    unittest.main()
