import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / ".claude/skills/verify-multiprompt-android/scripts"
spec = importlib.util.spec_from_file_location("journey_action", SCRIPTS / "journey_action.py")
action = importlib.util.module_from_spec(spec)
spec.loader.exec_module(action)
spec = importlib.util.spec_from_file_location("journey_check", Path(__file__).with_name("check.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def window(content):
    return f'<hierarchy><node bounds="[0,0][1080,1920]">{content}</node></hierarchy>'


def button(label="Open live terminal", rect="[20,500][500,580]", attributes=""):
    return f'<node clickable="true" enabled="true" bounds="{rect}" {attributes}><node text="{label}" bounds="{rect}" /></node>'


PIN = window(button("pin-00", "[20,150][180,250]") + '<node text="Frequent" selected="true" bounds="[0,100][1080,220]" />')
TERMINAL = window('<node scrollable="true" bounds="[0,300][1080,1100]">' + button(rect="[20,1120][500,1200]") + '</node><node class="android.widget.HorizontalScrollView" scrollable="true" bounds="[0,1700][1080,1800]" />')


class ActionTest(unittest.TestCase):
    def plan(self, xml, mode="text", value="Open live terminal", direction="forward"):
        return action.plan(ET.fromstring(xml), mode, value, direction)

    def test_terminal_typing_area_is_reachable_without_a_clickable_text_node(self):
        xml = window('<node content-desc="Show keyboard" bounds="[900,40][1000,140]" /><node class="android.widget.HorizontalScrollView" scrollable="true" bounds="[0,1000][1080,1100]"><node content-desc="Esc key" bounds="[10,1010][100,1090]" /></node>')
        self.assertEqual(self.plan(xml, mode='terminal', value=''), 'tap 505 575')
        for changed in (xml.replace('[10,1010][100,1090]', '[10,990][100,1090]'), xml.replace('Show keyboard', 'Hidden keyboard')):
            with self.subTest(xml=changed), self.assertRaisesRegex(action.Unreachable, 'typing anchor'):
                self.plan(changed, mode='terminal', value='')
        covered = xml.replace('</node></hierarchy>', '<node clickable="true" bounds="[400,500][600,650]" /></node></hierarchy>')
        with self.assertRaisesRegex(action.Unreachable, 'overlap'):
            self.plan(covered, mode='terminal', value='')

    def test_complete_button_uses_clickable_ancestor(self):
        xml = window('<node clickable="true" bounds="[20,400][500,600]"><node text="Open live terminal" bounds="[50,450][300,500]" /></node>')
        self.assertEqual(self.plan(xml), "tap 260 500")

    def test_pr129_pin_center_obscured_by_fixed_tabs_is_not_tapped(self):
        with self.assertRaisesRegex(action.Unreachable, "overlap"):
            self.plan(PIN, value="pin-00")

    def test_foreground_send_button_is_not_occluded_by_its_background_editor(self):
        xml = window('<node class="android.widget.EditText" clickable="true" bounds="[179,872][1048,998]" />' + button('Send prompt', '[927,872][1053,998]'))
        self.assertEqual(self.plan(xml, value='Send prompt'), 'tap 990 935')

    def test_drawing_order_takes_precedence_over_accessibility_sibling_order(self):
        xml = window('<node clickable="true" drawing-order="2" bounds="[0,400][600,700]" />' + button(attributes='drawing-order="1"'))
        with self.assertRaisesRegex(action.Unreachable, 'overlap'):
            self.plan(xml)

    def test_edge_touch_target_overlap_does_not_hide_the_clickable_center(self):
        xml = window(button() + '<node clickable="true" bounds="[480,500][600,580]" />')
        self.assertEqual(self.plan(xml), 'tap 260 540')

    def test_fully_covered_button_is_not_tapped(self):
        xml = window(button() + '<node clickable="true" bounds="[0,400][600,700]" />')
        with self.assertRaisesRegex(action.Unreachable, "overlap"):
            self.plan(xml)

    def test_pr383_terminal_below_sheet_uses_vertical_not_horizontal_scroll(self):
        self.assertEqual(self.plan(TERMINAL), "swipe 540 940 540 460")

    def test_above_viewport_scrolls_in_opposite_direction(self):
        xml = TERMINAL.replace('[20,1120][500,1200]', '[20,200][500,280]')
        self.assertEqual(self.plan(xml), "swipe 540 460 540 940")

    def test_partly_visible_button_is_scrolled_even_when_center_is_visible(self):
        xml = TERMINAL.replace('[20,1120][500,1200]', '[20,1000][500,1150]')
        self.assertTrue(self.plan(xml).startswith("swipe "))

    def test_horizontal_key_offscreen_uses_its_horizontal_ancestor(self):
        xml = window('<node class="android.widget.HorizontalScrollView" scrollable="true" bounds="[0,1000][1080,1100]">' + button("Up key", '[1050,1010][1150,1090]') + '</node>')
        self.assertEqual(self.plan(xml, value="Up key"), "swipe 864 1050 216 1050")

    def test_nested_horizontal_row_does_not_capture_vertical_scroll(self):
        xml = window('<node scrollable="true" bounds="[0,300][1080,1100]"><node class="android.widget.HorizontalScrollView" scrollable="true" bounds="[0,1120][1080,1220]">' + button(rect='[20,1120][500,1200]') + '</node></node>')
        self.assertEqual(self.plan(xml), "swipe 540 940 540 460")

    def test_disabled_ancestor_and_hidden_label_fail(self):
        for attributes in ('enabled="false"', 'visible-to-user="false"'):
            xml = window(f'<node {attributes} bounds="[0,0][1080,1920]">{button()}</node>')
            with self.subTest(attributes=attributes), self.assertRaisesRegex(action.Unreachable, "disabled or hidden"):
                self.plan(xml)

    def test_underlying_window_cannot_supply_a_sheet_action(self):
        xml = window(button()).replace('</hierarchy>', '<node bounds="[0,200][1080,1920]" /></hierarchy>')
        with self.assertRaisesRegex(action.Unreachable, "label missing"):
            self.plan(xml)

    def test_duplicate_in_background_does_not_make_active_action_ambiguous(self):
        xml = window(button()).replace('</hierarchy>', f'<node bounds="[0,200][1080,1920]">{button()}</node></hierarchy>')
        self.assertEqual(self.plan(xml), "tap 260 540")

    def test_ambiguous_active_labels_fail(self):
        with self.assertRaisesRegex(action.Unreachable, "ambiguous"):
            self.plan(window(button() + button()))

    def test_negative_or_empty_bounds_cannot_become_a_tap(self):
        for rect in ('[-100,500][100,580]', '[20,500][20,580]', 'bad'):
            with self.subTest(rect=rect), self.assertRaises(action.Unreachable):
                self.plan(window(button(rect=rect)))

    def test_missing_lazy_label_searches_only_unique_vertical_region_both_ways(self):
        xml = TERMINAL.replace('Open live terminal', 'Different label')
        self.assertEqual(self.plan(xml), 'swipe 540 940 540 460')
        self.assertEqual(self.plan(xml, direction='backward'), 'swipe 540 460 540 940')
        with self.assertRaisesRegex(action.Unreachable, 'no unique'):
            self.plan(xml.replace('</hierarchy>', '<node bounds="[0,0][1080,1920]" /></hierarchy>'))

    def test_cli_failure_names_the_unreachable_label(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'ui.xml'
            path.write_text(PIN)
            result = subprocess.run(['python3', '-B', str(SCRIPTS / 'ui.py'), str(path), 'reach-text', 'pin-00'], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("JOURNEY_ACTION_UNREACHABLE: 'pin-00'", result.stderr)
            self.assertEqual(result.stdout, '')

    def test_pr129_historical_selector_taps_the_obscured_pin_center(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'ui.py'
            path.write_text(subprocess.check_output(['git', 'show', 'f6594348^:.claude/skills/verify-multiprompt-android/scripts/ui.py'], cwd=ROOT, text=True))
            xml = Path(directory) / 'pin.xml'
            xml.write_text(PIN)
            result = subprocess.run(['python3', '-B', str(path), str(xml), 'text', 'pin-00'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), '100 200')
            with self.assertRaisesRegex(action.Unreachable, 'overlap'):
                self.plan(PIN, value='pin-00')
            print('PR129_REPLAY: f6594348^ taps obscured center 100 200; current planner rejects overlap')

    def test_pr383_historical_selector_confuses_the_two_scroll_axes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'ui.py'
            path.write_text(subprocess.check_output(['git', 'show', '8dada246^:.claude/skills/verify-multiprompt-android/scripts/ui.py'], cwd=ROOT, text=True))
            xml = Path(directory) / 'terminal.xml'
            xml.write_text(TERMINAL.replace('<node ', '<node package="dev.multiprompt.companion.debug" '))
            result = subprocess.run(['python3', '-B', str(path), str(xml), 'scroll-back', ''], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Ambiguous scroll-back', result.stderr)
            self.assertEqual(self.plan(TERMINAL), 'swipe 540 940 540 460')
            print('PR383_REPLAY: 8dada246^ confuses both axes; current planner selects the vertical sheet')

    def test_real_past_helpers_are_rejected_by_ratchet(self):
        for commit in ('f6594348', '8dada246'):
            with self.subTest(commit=commit), tempfile.TemporaryDirectory() as directory:
                source = subprocess.check_output(['git', 'show', f'{commit}:{check.DRIVER}'], cwd=ROOT, text=True)
                path = Path(directory) / check.DRIVER
                path.parent.mkdir(parents=True)
                path.write_text(source)
                self.assertIn(f'{check.DRIVER}: tap_node bypasses reachability', check.violations(Path(directory)))

    def test_new_raw_tap_is_rejected_but_legacy_inventory_is_allowed(self):
        check.check()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'tools/new-driver.sh'
            path.parent.mkdir(parents=True)
            path.write_text('adb shell input tap 100 200\n')
            self.assertEqual(sum(check.violations(Path(directory)).values()), 1)
            baseline = Path(directory) / 'tools/journey-actions/baseline.json'
            baseline.parent.mkdir(parents=True)
            baseline.write_text('{}')
            with self.assertRaisesRegex(ValueError, 'use drive.sh tap_text/tap_desc/tap_node'):
                check.check(Path(directory))

    def test_raw_tap_added_inside_the_trusted_helper_is_not_exempt(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / check.DRIVER
            path.parent.mkdir(parents=True)
            source = (ROOT / check.DRIVER).read_text()
            path.write_text(source.replace('local attempt action x1 y1 x2 y2 direction plan', 'adb shell input tap 1 2\n    local attempt action x1 y1 x2 y2 direction plan'))
            self.assertIn(f'{check.DRIVER}: adb shell input tap 1 2', check.violations(Path(directory)))

    def test_app_label_requires_clickable_ancestor_but_native_picker_row_can_use_label(self):
        for package in ('dev.multiprompt.companion.debug', 'dev.multiprompt.companion.m3'):
            xml = window(f'<node text="Open live terminal" package="{package}" bounds="[20,500][500,580]" />')
            with self.subTest(package=package), self.assertRaisesRegex(action.Unreachable, 'no enabled clickable ancestor'):
                self.plan(xml)
        xml = window('<node text="Open live terminal" package="com.android.documentsui" bounds="[20,500][500,580]" />')
        self.assertEqual(self.plan(xml), 'tap 260 540')

    def test_driver_scrolls_then_redumps_and_taps_once(self):
        source = (SCRIPTS / 'drive.sh').read_text()
        helper = 'tap_node() {' + source.split('tap_node() {', 1)[1].split('\ntap_text()', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / 'before.xml').write_text(TERMINAL)
            (folder / 'after.xml').write_text(TERMINAL.replace('[20,1120][500,1200]', '[20,900][500,980]'))
            shell = '''set -eu
scripts=$SCRIPTS
EVIDENCE=$TEST_DIR
calls=0
dump_ui() {
    calls=$((calls + 1))
    if (( calls == 1 )); then cp "$TEST_DIR/before.xml" "$EVIDENCE/current.xml"; else cp "$TEST_DIR/after.xml" "$EVIDENCE/current.xml"; fi
}
adb() { printf '%s\n' "$*" >> "$TEST_DIR/actions"; }
'''
            result = subprocess.run(['bash', '-c', shell + helper + '\ntap_node text "Open live terminal"'], env={**os.environ, 'SCRIPTS': str(SCRIPTS), 'TEST_DIR': directory, 'PYTHONDONTWRITEBYTECODE': '1'}, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((folder / 'actions').read_text().splitlines(), ['shell input swipe 540 940 540 460 500', 'shell input tap 260 940'])


if __name__ == '__main__':
    unittest.main()
