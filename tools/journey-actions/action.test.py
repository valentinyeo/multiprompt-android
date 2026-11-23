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

    def test_missing_split_searches_the_horizontal_viewport_in_both_directions(self):
        xml = (Path(__file__).parent / "fixtures/46978da4-hidden-split.xml").read_text()
        backward = self.plan(xml, value="Runners", direction="backward").split()
        forward = self.plan(xml, value="Runners", direction="forward").split()
        self.assertEqual(backward[0], "swipe")
        self.assertEqual(backward[1:], [forward[3], forward[4], forward[1], forward[2]])
        x1, y1, x2, y2 = map(int, backward[1:])
        self.assertTrue(32 < x1 < x2 < 1048)
        self.assertEqual(y1, y2)
        self.assertTrue(1710 < y1 < 1836)

    def test_hidden_favorites_scrolls_split_strip_not_session_list(self):
        xml = (Path(__file__).parent / "fixtures/9cce26ad-hidden-favorites.xml").read_text()
        result = subprocess.run(['python3', str(SCRIPTS / 'ui.py'), str(Path(__file__).parent / 'fixtures/9cce26ad-hidden-favorites.xml'), 'reach-text', 'Favorites', 'forward', 'horizontal'], capture_output=True, text=True, check=True)
        self.assertEqual(result.stdout.strip(), 'swipe 845 1773 235 1773')
        self.assertEqual(self.plan(xml, value='Favorites'), 'swipe 540 1402 540 540')

    def test_split_search_switches_direction_at_the_actual_strip_edge(self):
        source = (ROOT / 'tools/account-journey/drive.sh').read_text()
        helper = 'tap_split() {' + source.split('tap_split() {', 1)[1].split('\n}', 1)[0] + '\n}'
        fixtures = Path(__file__).parent / 'fixtures'
        with tempfile.TemporaryDirectory() as directory:
            shell = '''set -euo pipefail
EVIDENCE="$1"
here="$2/tools/account-journey"
snapshot="$3"
revealed="$4"
dump_ui() { cp "$snapshot" "$EVIDENCE/current.xml"; }
shot() { :; }
adb() {
    printf '%s\\n' "$*" >> "$EVIDENCE/actions"
    if [[ "$3" == swipe && "$4" -gt "$6" ]]; then snapshot="$revealed"; fi
}
''' + helper + '\ntap_split Favorites\n'
            result = subprocess.run(['bash', '-c', shell, 'split-edge', directory, str(ROOT), str(fixtures / '9cce26ad-hidden-favorites.xml'), str(fixtures / 'ccd4df11-favorites-revealed.xml')], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            actions = (Path(directory) / 'actions').read_text().splitlines()
            self.assertEqual(len(actions), 3, actions)
            backward, forward = [row.split() for row in actions[:2]]
            self.assertLess(int(backward[3]), int(backward[5]))
            self.assertGreater(int(forward[3]), int(forward[5]))
            self.assertTrue(actions[-1].startswith('shell input tap '), actions)

    def test_terminal_typing_area_is_reachable_without_a_clickable_text_node(self):
        xml = window('<node content-desc="Show keyboard" bounds="[900,40][1000,140]" /><node class="android.widget.HorizontalScrollView" scrollable="true" bounds="[0,1000][1080,1100]"><node content-desc="Esc key" bounds="[10,1010][100,1090]" /></node>')
        self.assertEqual(self.plan(xml, mode='terminal', value=''), 'tap 505 575')
        for changed in (xml.replace('[10,1010][100,1090]', '[10,990][100,1090]'), xml.replace('Show keyboard', 'Hidden keyboard')):
            with self.subTest(xml=changed), self.assertRaisesRegex(action.Unreachable, 'typing anchor'):
                self.plan(changed, mode='terminal', value='')
        covered = xml.replace('</node></hierarchy>', '<node clickable="true" bounds="[400,500][600,650]" /></node></hierarchy>')
        with self.assertRaisesRegex(action.Unreachable, 'overlap'):
            self.plan(covered, mode='terminal', value='')

    def test_release_robot_hidden_under_inbox_header_is_revealed_before_tapping(self):
        fixtures = Path(__file__).parent / 'fixtures'
        clipped = (fixtures / '3e0b2df2-release-robot-clipped.xml').read_text()
        visible = (fixtures / '962eecad-release-robot-visible.xml').read_text()
        for mode, value in [('text', 'verify-session'), ('desc', 'Robot verify-session')]:
            with self.subTest(mode=mode):
                self.assertEqual(self.plan(clipped, mode, value), 'swipe 540 527 540 1352')
                self.assertEqual(self.plan(visible, mode, value), 'tap 540 353')
        old = subprocess.check_output(['git', 'show', '3e0b2df2:.claude/skills/verify-multiprompt-android/scripts/journey_action.py'], cwd=ROOT, text=True)
        namespace = {}
        exec(compile(old, 'release-parent-journey-action', 'exec'), namespace)
        self.assertEqual(namespace['plan'](ET.fromstring(clipped), 'desc', 'Robot verify-session'), 'tap 540 62')
        print('RELEASE_ROBOT_REPLAY: 3e0b2df2 taps hidden row at 540 62; head scrolls below the header')

    def test_nonclickable_foreground_edge_panels_bound_scroll_and_tap_area(self):
        xml = window('<node scrollable="true" bounds="[0,0][1080,1920]">' + button(rect='[20,0][500,100]') + '</node><node bounds="[0,0][1080,250]" /><node bounds="[0,1600][1080,1920]" />')
        self.assertEqual(self.plan(xml), 'swipe 540 520 540 1330')
        bottom = xml.replace('[20,0][500,100]', '[20,1550][500,1700]')
        self.assertEqual(self.plan(bottom), 'swipe 540 1330 540 520')
        for attributes in ('drawing-order="-1"', 'enabled="false"', 'visible-to-user="false"'):
            with self.subTest(attributes=attributes):
                background = xml.replace('<node bounds="[0,0][1080,250]"', f'<node {attributes} bounds="[0,0][1080,250]"')
                self.assertEqual(self.plan(background), 'tap 260 50')

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

    def test_counted_newest_control_is_reachable_by_description_prefix(self):
        xml = window('<node clickable="true" enabled="true" bounds="[477,682][603,808]"><node content-desc="Jump to newest, 4 new" bounds="[477,682][603,808]" /></node>')
        self.assertEqual(self.plan(xml, mode='desc-prefix', value='Jump to newest'), 'tap 540 745')
        with self.assertRaisesRegex(action.Unreachable, 'no unique active scroll container'):
            self.plan(xml, mode='desc', value='Jump to newest')

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

    def fill(self, text='NO_MATCH_2540', target='orchard', blocked_clear=False, blocked_type=False, stale_batch=False):
        source = (SCRIPTS / 'drive.sh').read_text()
        helpers = '\n'.join(name + '() {' + source.split(name + '() {', 1)[1].split('\n}', 1)[0] + '\n}'
                            for name in ('clear_field', 'fill') if name + '() {' in source)
        shell = '''set -eu
scripts=$SCRIPTS
EVIDENCE=$TEST_DIR
text=$INITIAL
cursor=8
selected=false
typed=0
back=false
dump_ui() {
    FIELD_TEXT="$text" python3 - "$EVIDENCE/current.xml" <<'PYXML'
import os,sys,xml.etree.ElementTree as ET
root=ET.Element('hierarchy')
ET.SubElement(root,'node',{'text':os.environ['FIELD_TEXT'],'class':'android.widget.EditText','package':'dev.multiprompt.companion.debug','focused':'true','enabled':'true','bounds':'[0,0][500,100]'})
ET.ElementTree(root).write(sys.argv[1])
PYXML
}
tap_node() { :; }
break_it_draft() { :; }
select_node() { dump_ui; python3 "$scripts/ui.py" "$EVIDENCE/current.xml" "$@"; }
adb() {
    case "$3" in
        keycombination) if [[ "$*" == *'KEYCODE_CTRL_LEFT KEYCODE_A' ]]; then selected=true; fi ;;
        keyevent)
            if [[ "$4" == KEYCODE_BACK ]]; then
                back=true
            elif [[ "$BLOCK_CLEAR" != true ]]; then
                if [[ "$selected" == true ]]; then
                    text=''
                else
                    text=${text:cursor}
                fi
                cursor=0
                selected=false
            fi
            ;;
        text)
            typed=$((typed + 1))
            if [[ "$BLOCK_TYPE" != true ]]; then
                insertion=${4//%s/ }
                if [[ "$STALE_BATCH" == true && ${#insertion} -gt 13 ]]; then insertion=${insertion:0:13}; fi
                text="${text:0:cursor}$insertion${text:cursor}"
                cursor=$((cursor + ${#insertion}))
            fi
            ;;
    esac
}
'''
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(['bash', '-c', shell + helpers + '''
set +e
fill "$INITIAL" "$TARGET"
code=$?
printf 'FINAL=%s\\nTYPED=%s\\nBACK=%s\\n' "$text" "$typed" "$back"
exit "$code"
'''], env={**os.environ, 'SCRIPTS': str(SCRIPTS), 'TEST_DIR': directory, 'INITIAL': text,
           'TARGET': target, 'BLOCK_CLEAR': str(blocked_clear).lower(), 'BLOCK_TYPE': str(blocked_type).lower(),
           'STALE_BATCH': str(stale_batch).lower(), 'PYTHONDONTWRITEBYTECODE': '1'}, capture_output=True, text=True)
        return result

    def test_busy_stream_input_does_not_lose_the_tail_to_a_stale_event_batch(self):
        result = self.fill(text='', target='PHONE_DURING_STREAM_2569', stale_batch=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('FINAL=PHONE_DURING_STREAM_2569\n', result.stdout)

    def test_restore_query_replaces_every_character_even_with_cursor_in_middle(self):
        result = self.fill()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('FINAL=orchard\n', result.stdout)
        self.assertIn('BACK=true', result.stdout)

    def test_replace_multiline_draft_and_clear_draft_share_verified_input(self):
        for target, expected in [('saved%ssnippet', 'saved snippet'), ('', '')]:
            with self.subTest(target=target):
                result = self.fill(text='old draft\nsecond line', target=target)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(f'FINAL={expected}\n', result.stdout)

    def test_numeric_fields_are_replaced_and_emptied_without_old_digits(self):
        for text in ('22', '22222222222222', ''):
            for target in ('2222', ''):
                with self.subTest(text=text, target=target):
                    result = self.fill(text=text, target=target)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn(f'FINAL={target}\n', result.stdout)

    def test_failed_clear_never_types_or_continues(self):
        result = self.fill(blocked_clear=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('JOURNEY_FIELD_CLEAR_FAILED', result.stderr)
        self.assertIn('TYPED=0', result.stdout)
        self.assertIn('BACK=false', result.stdout)

    def test_wrong_typed_value_fails_instead_of_continuing(self):
        result = self.fill(blocked_type=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('JOURNEY_FIELD_INPUT_FAILED', result.stderr)
        self.assertIn('BACK=false', result.stdout)

    def test_driver_scrolls_then_redumps_and_taps_once(self):
        source = (SCRIPTS / 'drive.sh').read_text()
        helper = source[source.index('session_name() {'):source.index('\ntap_text()')]
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / 'before.xml').write_text(TERMINAL)
            (folder / 'after.xml').write_text(TERMINAL.replace('[20,1120][500,1200]', '[20,900][500,980]'))
            (folder / 'opened.xml').write_text(window('<node text="Live terminal" />'))
            shell = '''set -eu
scripts=$SCRIPTS
EVIDENCE=$TEST_DIR
calls=0
dump_ui() {
    calls=$((calls + 1))
    case "$calls" in
        1) cp "$TEST_DIR/before.xml" "$EVIDENCE/current.xml" ;;
        2) cp "$TEST_DIR/after.xml" "$EVIDENCE/current.xml" ;;
        *) cp "$TEST_DIR/opened.xml" "$EVIDENCE/current.xml" ;;
    esac
}
adb() { printf '%s\n' "$*" >> "$TEST_DIR/actions"; }
'''
            result = subprocess.run(['bash', '-c', shell + helper + '\ntap_node text "Open live terminal"'], env={**os.environ, 'SCRIPTS': str(SCRIPTS), 'TEST_DIR': directory, 'PYTHONDONTWRITEBYTECODE': '1'}, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((folder / 'actions').read_text().splitlines(), ['shell input swipe 540 940 540 460 500', 'shell input tap 260 940'])


if __name__ == '__main__':
    unittest.main()
