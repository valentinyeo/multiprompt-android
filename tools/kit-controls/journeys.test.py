from pathlib import Path
import json
import importlib.util
import hashlib
import base64
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / ".claude/skills/verify-multiprompt-android/scripts"


class ControlJourneyTest(unittest.TestCase):
    def test_pins_scroll_away_from_the_pinned_end_in_both_list_orders(self):
        source = (SCRIPTS / 'pins-drive.sh').read_text()
        helpers = source[source.index('pins_top() {'):source.index('pins_instrument pins-seed')]
        start = source.index('    pins_top\n', source.index('    wait_for_text Inbox'))
        phase = source[start:source.index('    pins_capture "pins-$reverse-scrolled"', start)]
        for reverse in ('false', 'true'):
            with self.subTest(reverse=reverse), tempfile.TemporaryDirectory() as directory:
                script = '''set -euo pipefail
EVIDENCE="$1"
reverse="$2"
scripts=unused
package=dev.multiprompt.companion
python3() {
    case "$2" in
        top) return 0 ;;
        pin-bounds) return 0 ;;
        swipe-area) printf '540 606 1569\\n' ;;
        *) return 1 ;;
    esac
}
dump_ui() { :; }
sleep() { :; }
adb() { printf '%s\\n' "$*" >> "$EVIDENCE/swipes"; }
''' + helpers + phase + '\npins_swipe start\n'
                result = subprocess.run(['bash', '-c', script, 'pins-scroll', directory, reverse], capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)
                swipes = (Path(directory) / 'swipes').read_text().splitlines()
                start_y, end_y = (1569, 606) if reverse == 'false' else (606, 1569)
                self.assertEqual(swipes, [f'shell input swipe 540 {start_y} 540 {end_y} 500'] * 4
                                 + [f'shell input swipe 540 {end_y} 540 {start_y} 500'])

    def test_settings_reveals_workspace_colors_before_waiting_for_offscreen_text(self):
        source = (SCRIPTS / 'drive.sh').read_text()
        start = source.index('    tap_text Settings', source.index('mode workspace-colors-seed'))
        phase = source[start:source.index('    shot settings-workspace-colors', start)]
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            prefix = '<hierarchy><node package="dev.multiprompt.companion.debug" bounds="[0,0][1080,1920]"><node text="Session list settings" bounds="[0,0][1080,100]"/><node package="dev.multiprompt.companion.debug" scrollable="true" bounds="[0,200][1080,1800]">'
            (folder / 'before.xml').write_text(prefix + '<node text="All split on right"/></node></node></hierarchy>')
            (folder / 'after.xml').write_text(prefix + '<node text="Workspace colors"/><node text="Auto"/><node text="Auto"/></node></node></hierarchy>')
            shell = '''set -euo pipefail
EVIDENCE="$1"
scripts="$2"
page=before
dump_ui() { cp "$EVIDENCE/$page.xml" "$EVIDENCE/current.xml"; }
tap_text() { [[ "$1" == Settings ]]; dump_ui; }
wait_for_text() { python3 "$scripts/ui.py" "$EVIDENCE/current.xml" contains "$1" >/dev/null; }
adb() { [[ "$*" == 'shell input swipe 540 1480 540 520 400' ]]; page=after; }
''' + phase + '\n[[ "$page" == after ]]\n'
            result = subprocess.run(['bash', '-c', shell, 'settings-scroll', directory, str(SCRIPTS)], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_changed_key_waits_for_the_sheet_before_using_underlying_controls(self):
        source = (SCRIPTS / "drive.sh").read_text()
        rotated = source.split('> "$EVIDENCE/rotated-sshd-pid.txt"', 1)[1]
        before_warning = rotated.split("wait_for_text 'SSH host key changed'", 1)[0]
        self.assertNotIn("refresh_inbox", before_warning)
        self.assertIn("wait_for_text 'Mark as unread'", source)

    def test_device_denial_returns_to_the_screen_that_started_sign_in(self):
        source = (SCRIPTS / 'device-approval-drive.sh').read_text()
        after_no = source.split('tap_text No\n', 1)[1].split('if ssh', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'current.xml').write_text((Path(__file__).parent / 'fixtures/987d3f8e-after-device-denial.xml').read_text())
            script = '''set -euo pipefail
EVIDENCE="$1"
scripts="$2"
wait_for_text() { python3 "$scripts/ui.py" "$EVIDENCE/current.xml" text "$1"; }
wait_for_absence() { python3 "$scripts/ui.py" "$EVIDENCE/current.xml" absent "$1"; }
shot() { :; }
''' + after_no
            result = subprocess.run(['bash', '-c', script, 'device-denial', directory, str(SCRIPTS)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            state = json.loads((root / 'device-decisions-ui.json').read_text())
            self.assertIn('Session list settings', state['texts'])
            self.assertIn('Switch / renew login', state['texts'])
            self.assertNotIn('Approve this device?', state['texts'])

    def test_agent_picker_navigation_requires_an_unchanged_tmux_session_list(self):
        source = (SCRIPTS / 'drive.sh').read_text()
        start = source.index("    sudo -u verifyssh tmux list-sessions -F '#{session_name}' > \"$EVIDENCE/sessions-before-agent-picker.txt\"")
        phase = source[start:source.index('    exit 0', start)]
        for changed in (False, True):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory() as directory:
                script = '''set -euo pipefail
EVIDENCE="$1"
changed="$2"
tap_desc() { [[ "$1" == 'New session' ]]; }
tap_text() { [[ "$1" == 'Use this folder' || "$1" == Back ]]; }
wait_for_text() { :; }
shot() { printf '<hierarchy />' > "$EVIDENCE/current.xml"; }
adb() { [[ "$*" == 'shell input keyevent KEYCODE_BACK' ]]; }
sudo() {
    [[ "$*" == '-u verifyssh tmux list-sessions -F #{session_name}' ]]
    printf 'verify-session\n'
    if [[ -e "$EVIDENCE/listed" && "$changed" == True ]]; then printf 'unexpected-agent\n'; fi
    touch "$EVIDENCE/listed"
}
''' + phase
                result = subprocess.run(['bash', '-c', script, 'agent-picker', directory, str(changed)], capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, not changed, result.stderr)

    def test_device_fixture_requires_pkce_and_observes_real_authorized_key_changes(self):
        spec = importlib.util.spec_from_file_location("approval_fixture", SCRIPTS / "device-approval.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.ssh').mkdir()
            authorized = root / '.ssh/authorized_keys'
            authorized.write_text('ssh-ed25519 ORIGINAL\n')
            (root / 'pending-device.pub').write_text('ssh-ed25519 APPROVE comment')
            (root / 'denied-device.pub').write_text('ssh-ed25519 DENY comment')
            fixture = module.ApprovalFixture(root, root)
            verifier = 'v' * 43
            fixture.codes['code'] = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
            status, reply = fixture.request('POST', '/oauth/token', dict(grant_type='authorization_code', code='code', code_verifier=verifier, redirect_uri='dev.multiprompt.companion.debug://auth/callback'), None)
            self.assertEqual(status, 200)
            auth = 'Bearer ' + reply['access_token']
            approve = '/v1/device-keys/fixture-approve/approve'
            self.assertEqual(fixture.request('POST', approve, {}, auth)[0], 409)
            authorized.write_text(authorized.read_text() + 'ssh-ed25519 APPROVE\n')
            self.assertEqual(fixture.request('POST', approve, {}, auth), (200, dict(status='approved')))
            self.assertIn('ssh-ed25519 DENY', authorized.read_text())
            deny = '/v1/device-keys/fixture-deny/deny'
            self.assertEqual(fixture.request('POST', deny, {}, auth)[0], 409)
            authorized.write_text('ssh-ed25519 ORIGINAL\nssh-ed25519 APPROVE\n')
            self.assertEqual(fixture.request('POST', deny, {}, auth), (200, dict(status='denied')))
            self.assertEqual(fixture.request('GET', '/v1/device-keys', {}, None)[0], 401)
            self.assertEqual(fixture.request('POST', '/oauth/token', dict(grant_type='authorization_code', code='code', code_verifier=verifier), None)[0], 400)
            receipts = [json.loads(line) for line in (root / 'device-approval-receipts.jsonl').read_text().splitlines()]
            self.assertEqual([row['action'] for row in receipts], ['browser_pkce_exchange', 'approve', 'deny'])

    def test_screencast_fixture_requires_account_auth_and_never_records_the_key(self):
        spec = importlib.util.spec_from_file_location("approval_fixture", SCRIPTS / "device-approval.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'pending-device.pub').write_text('ssh-ed25519 APPROVE')
            (root / 'denied-device.pub').write_text('ssh-ed25519 DENY')
            fixture = module.ApprovalFixture(root, root, screencast=True)
            route = '/v1/secrets/screencast2'
            self.assertEqual(fixture.request('GET', route, {}, None)[0], 401)
            self.assertEqual(fixture.request('GET', route, {}, 'Bearer wrong')[0], 401)
            status, reply = fixture.request('GET', route, {}, 'Bearer ' + fixture.access)
            self.assertEqual(status, 200)
            self.assertRegex(reply['key'], r'^[!-~]{1,256}$')
            receipts = (root / 'device-approval-receipts.jsonl').read_text()
            self.assertNotIn(reply['key'], receipts)
            self.assertEqual(json.loads(receipts), dict(action='screencast_key_fetch', authenticated=True, status=200))
            self.assertEqual(fixture.keys, [])

    def test_reaction_fixture_consumes_the_prompt_and_delivery_marker_as_one_turn(self):
        wire = "👍 to: First reply [mp:6d8e353d25c5fe23]"
        marker = "[mp:5e5cdaa77f3dcfe4]"
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            receipts = home / "receipts.txt"
            result = subprocess.run(
                ["python3", str(SCRIPTS / "reaction-console.py"), str(receipts)],
                input=wire + "\n" + marker + "\n", text=True, capture_output=True,
                env={**os.environ, "HOME": directory},
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(receipts.read_text().splitlines(), ["VERIFY_RECEIVED:" + wire])
            transcript = next((home / ".claude/projects").rglob("*.jsonl"))
            messages = [json.loads(line)["message"] for line in transcript.read_text().splitlines()]
            self.assertEqual(messages[-2]["content"], wire + "\n" + marker)
            self.assertEqual(sum(message["content"] == "Reaction received." for message in messages), 1)

    def test_host_rotation_closes_only_the_owned_listener_and_its_connections(self):
        source = (SCRIPTS / "drive.sh").read_text()
        start = source.index("    mapfile -t old_connections")
        end = source.index("    for attempt", start)
        rotation = source[start:end]
        with tempfile.TemporaryDirectory() as directory:
            script = '''set -euo pipefail
old_pid=123
EVIDENCE="$1"
sudo() {
    if [[ "$1" == ps ]]; then printf '111\n222\n';
    elif [[ "$1" == kill ]]; then shift; printf '%s\n' "$@" > "$EVIDENCE/killed.txt";
    else return 1; fi
}
''' + rotation
            result = subprocess.run(["bash", "-c", script, "rotation", directory], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((Path(directory) / "killed.txt").read_text().splitlines(), ["123", "111", "222"])
            self.assertEqual((Path(directory) / "sshd-children-before-rotation.txt").read_text().splitlines(), ["111", "222"])

    def test_a_pin_hidden_behind_the_tab_bar_is_not_tappable(self):
        package = 'dev.multiprompt.companion'
        def node(bounds, **attributes):
            written = {'package': package, 'bounds': bounds, **attributes}
            return '<node ' + ' '.join(f'{key}="{value}"' for key, value in written.items()) + ' />'
        def labelled(text, bounds, **attributes):
            return node(bounds, text=text, **attributes)
        def clickable(child, bounds, text_bounds):
            return ('<node package="' + package + '" clickable="true" bounds="' + bounds + '">'
                    + labelled(child, text_bounds) + '</node>')
        hierarchy = (
            '<hierarchy><node package="' + package + '" bounds="[0,0][1080,1920]">'
            + labelled('Inbox', '[74,118][178,167]')
            + labelled('Search sessions', '[696,116][749,169]')
            + labelled('New session', '[833,116][886,169]')
            + labelled('Session list actions', '[970,116][1023,169]')
            + clickable('Frequent', '[828,1647][1080,1773]', '[891,1686][1059,1735]')
            + clickable('All', '[309,1647][485,1773]', '[372,1686][422,1735]')
            + clickable('Favorites', '[506,1647][807,1773]', '[569,1686][744,1735]')
            + clickable('pin-00', '[0,1400][1080,1550]', '[200,1420][328,1473]')
            + clickable('pin-01', '[0,1700][1080,1750]', '[200,1720][328,1773]')
            + '</node></hierarchy>')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'pins.xml'
            path.write_text(hierarchy)
            oracle = str(SCRIPTS / 'pins-oracle.py')
            def bound(pin):
                return subprocess.run(['python3', oracle, 'pin-bounds', str(path), package, pin],
                                      capture_output=True, text=True)
            inside = bound('pin-00')
            self.assertEqual(inside.returncode, 0, inside.stderr)
            self.assertEqual(inside.stdout.split(), ['135', '1475', '945', '1475'])
            hidden = bound('pin-01')
            self.assertNotEqual(hidden.returncode, 0)
            self.assertIn('pin-01 is not fully inside content viewport', hidden.stderr)
            any_pin = subprocess.run(['python3', oracle, 'pin-bounds', str(path), package, 'any'],
                                     capture_output=True, text=True)
            self.assertEqual(any_pin.returncode, 0, any_pin.stderr)
            self.assertEqual(any_pin.stdout.split(), ['135', '1475', '945', '1475'])


    def test_scroll_to_newest_taps_the_control_whose_label_carries_a_count(self):
        source = (SCRIPTS / 'drive.sh').read_text()
        helpers = ''
        for name in ('select_node() {', 'tap_node() {', 'scroll_to_newest() {'):
            start = source.index(name)
            helpers += source[start:source.index('\n}', start) + 2] + '\n'
        newest = Path(__file__).parent / 'fixtures/soft3030-jump-to-newest-counted.xml'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'after.xml').write_text(
                '<hierarchy rotation="0"><node class="android.widget.FrameLayout" package="dev.multiprompt.companion.debug" visible-to-user="true" bounds="[0,0][1080,1920]">'
                '<node text="Received /model fable. Claude Fable 5.5 high" package="dev.multiprompt.companion.debug" bounds="[32,1640][1048,1790]" class="android.view.View" />'
                '</node></hierarchy>'
            )
            script = """set -euo pipefail
EVIDENCE="$1"
snapshot="$2"
scripts="$3"
after="$4"
session_name() { return 1; }
sleep() { :; }
dump_ui() { cp "$snapshot" "$EVIDENCE/current.xml"; }
adb() { printf '%s\\n' "$*" >> "$EVIDENCE/actions"; snapshot="$after"; }
""" + helpers + '\nscroll_to_newest\n'
            result = subprocess.run(
                ['bash', '-c', script, 'scroll-newest', directory, str(newest), str(SCRIPTS), str(root / 'after.xml')],
                capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            actions = (root / 'actions').read_text().splitlines()
            self.assertEqual(actions, ['shell input tap 540 745'])


    def test_terminal_history_reopens_the_live_terminal_instead_of_scrolling_the_reader(self):
        source = (SCRIPTS / 'drive.sh').read_text()
        helpers = ''
        for name in ('terminal_screen() {', 'open_shell_history() {', 'reopen_terminal_history() {'):
            start = source.index(name)
            helpers += source[start:source.index('\n    }\n', start) + len('\n    }\n')] + '\n'
        fixtures = Path(__file__).parent / 'fixtures'
        reader = fixtures / 'soft3030-reader-without-terminal.xml'
        terminal = fixtures / 'soft3030-terminal-attached.xml'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for case, expected, returncode in (
                ('reuse', [], 0),
                ('reopen', ['Session actions', 'Open live terminal', 'Show keyboard'], 0),
                ('lost', ['Session actions', 'Open live terminal', 'Show keyboard'] * 3, 1),
            ):
                with self.subTest(case=case):
                    (root / 'actions').write_text('')
                    script = """set -euo pipefail
EVIDENCE="$1"
scripts="$2"
keyboard_state=open
snapshot="$3"
terminal="$4"
case="$5"
dump_ui() { cp "$snapshot" "$EVIDENCE/current.xml"; }
wait_for_text() { dump_ui; }
select_node() { dump_ui; }
tap_text() { printf 'tap:%s\\n' "$1" >> "$EVIDENCE/actions"; if [[ "$1" == 'Open live terminal' && "$case" != lost ]]; then snapshot="$terminal"; fi; }
tap_desc() { printf 'tap:%s\\n' "$1" >> "$EVIDENCE/actions"; }
adb() { printf '%s\\n' "$*" >> "$EVIDENCE/actions"; }
""" + helpers + '\nreopen_terminal_history\n'
                    start = terminal if case == 'reuse' else reader
                    result = subprocess.run(
                        ['bash', '-c', script, 'terminal-history', directory, str(SCRIPTS), str(start), str(terminal), case],
                        capture_output=True, text=True, timeout=30,
                    )
                    self.assertEqual(result.returncode, returncode, result.stderr)
                    actions = [row for row in (root / 'actions').read_text().splitlines() if row.startswith('tap:')]
                    self.assertEqual(actions, [f'tap:{label}' for label in expected], result.stderr)
                    if returncode:
                        self.assertIn('TERMINAL_SCREEN_LOST', result.stderr)
                    else:
                        self.assertEqual(result.stderr, '')
        block = source[source.index("if [[ \"$feature\" == terminal.md ]]") : source.index("send_timing() {")]
        scroll_loop = block[block.index('for attempt in {1..100}; do'):block.index('wait_for_text SHELL_HISTORY_0001')]
        self.assertIn('if ! terminal_screen "$EVIDENCE/current.xml"; then\n                reopen_terminal_history\n                continue', scroll_loop)
        self.assertNotIn('120\n', scroll_loop)
        self.assertIn('sleep 1', scroll_loop)


if __name__ == "__main__":
    unittest.main()
