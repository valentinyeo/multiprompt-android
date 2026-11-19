import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / '.claude/skills/verify-multiprompt-android/scripts'
MPA = SCRIPTS / 'mpa'
UI = SCRIPTS / 'ui.py'
SCREEN = """<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="dev.multiprompt.companion.debug" visible-to-user="true" bounds="[0,0][1080,1920]">
    <node text="Inbox" package="dev.multiprompt.companion.debug" bounds="[40,80][300,140]" class="android.widget.TextView" />
    <node content-desc="Robot verify-session" package="dev.multiprompt.companion.debug" bounds="[0,200][1080,400]" clickable="true" enabled="true" class="android.view.View">
      <node text="verify-session" package="dev.multiprompt.companion.debug" bounds="[40,240][600,300]" class="android.widget.TextView" />
    </node>
    <node content-desc="Session actions" package="dev.multiprompt.companion.debug" bounds="[900,80][1050,140]" clickable="true" enabled="true" class="android.view.View" />
  </node>
</hierarchy>
"""
MENU = """<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="dev.multiprompt.companion.debug" visible-to-user="true" bounds="[0,0][1080,1920]">
    <node text="Message verify-session" package="dev.multiprompt.companion.debug" bounds="[40,300][900,360]" class="android.widget.TextView" />
    <node text="Sign in to sync" package="dev.multiprompt.companion.debug" bounds="[40,700][700,760]" class="android.widget.TextView" />
  </node>
</hierarchy>
"""
PICKER = """<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" package="dev.multiprompt.companion.debug" visible-to-user="true" bounds="[0,0][1080,1920]">
    <node class="android.widget.ScrollView" package="dev.multiprompt.companion.debug" scrollable="true" bounds="[0,600][1080,1200]">
      <node text="/clear" package="dev.multiprompt.companion.debug" bounds="[40,640][600,700]" class="android.widget.TextView" />
      <node text="/client" package="dev.multiprompt.companion.debug" bounds="[40,720][600,780]" class="android.widget.TextView" />
      <node text="/aaa-paper" package="dev.multiprompt.companion.debug" bounds="[40,800][600,860]" class="android.widget.TextView" />
    </node>
  </node>
</hierarchy>
"""
CLIPPED = PICKER.replace('bounds="[40,800][600,860]"', 'bounds="[40,1200][600,1260]"')


def environment(**values):
    return {key: value for key, value in os.environ.items() if key not in ('EVIDENCE', 'FIXTURE', 'EXPECTED_VERSION', 'GITHUB_ACTIONS', 'VERIFY_PACKAGE', 'ADB', 'HOME', 'PATH')} | values


class MpaCliTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.evidence = self.root / 'evidence'
        self.fixture = self.root / 'fixture'
        self.home = self.root / 'home'
        for path in (self.evidence, self.fixture, self.home):
            path.mkdir()
        (self.home / 'console.py').write_text('')
        self.screen = self.root / 'screen.xml'
        self.screen.write_text(SCREEN)
        self.menu = self.root / 'menu.xml'
        self.menu.write_text(MENU)
        self.picker = self.root / 'picker.xml'
        self.picker.write_text(PICKER)
        self.clipped = self.root / 'clipped.xml'
        self.clipped.write_text(CLIPPED)
        self.env = environment(EVIDENCE=str(self.evidence), FIXTURE=str(self.fixture), HOME=str(self.home), GITHUB_ACTIONS='true', ADB=str(self.root / 'no-adb'))

    def run_cli(self, *args, env=None):
        return subprocess.run([sys.executable, '-B', str(MPA), *args], capture_output=True, text=True, env=env or self.env)

    def payload(self, *args, env=None):
        result = self.run_cli(*args, env=env)
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        return json.loads(result.stdout)

    def test_every_command_has_rich_help_and_a_description(self):
        result = self.run_cli('--help')
        self.assertEqual(0, result.returncode)
        for command in ('doctor', 'launch', 'seed', 'tap', 'read', 'fill', 'screenshot', 'proof', 'cleanup', 'key'):
            self.assertIn(command, result.stdout)
        for command in ('doctor', 'launch', 'seed', 'tap', 'read', 'fill', 'screenshot', 'proof', 'cleanup', 'key'):
            with self.subTest(command=command):
                help_text = self.run_cli(command, '--help')
                self.assertEqual(0, help_text.returncode, help_text.stderr)
                self.assertIn('usage:', help_text.stdout)
                self.assertIn('Examples:', help_text.stdout)
                self.assertIn('--help', help_text.stdout)

    def test_read_prints_the_tree_as_json_with_stable_node_fields(self):
        payload = self.payload('read', '--xml', str(self.screen))
        self.assertTrue(payload['ok'])
        self.assertEqual('read', payload['command'])
        self.assertIn('Inbox', payload['texts'])
        self.assertIn('Session actions', payload['descriptions'])
        self.assertTrue(payload['package_present'])
        self.assertTrue(payload['nodes'])
        for node in payload['nodes']:
            self.assertEqual({'text', 'description', 'bounds', 'class', 'package', 'clickable', 'enabled', 'checked', 'focused', 'scrollable'}, set(node))

    def test_read_select_returns_one_node_and_its_tap_point(self):
        payload = self.payload('read', '--xml', str(self.screen), '--select', 'desc', '--value', 'Session actions')
        self.assertEqual(1, payload['matches'])
        self.assertEqual([975, 110], payload['point'])
        self.assertTrue(payload['node']['clickable'])

    def test_selector_failure_says_what_to_do_instead(self):
        result = self.run_cli('read', '--xml', str(self.screen), '--select', 'text', '--value', 'Checkout')
        self.assertEqual(1, result.returncode)
        payload = json.loads(result.stdout)
        self.assertFalse(payload['ok'])
        self.assertEqual('SELECTOR_MISSING', payload['error'])
        self.assertIn('Checkout', payload['message'])
        self.assertIn('mpa read', payload['next'])

    def test_dry_run_tap_resolves_a_reachable_point_without_touching_the_phone(self):
        payload = self.payload('tap', 'text', 'verify-session', '--xml', str(self.screen), '--dry-run')
        self.assertTrue(payload['ok'])
        self.assertEqual('tap', payload['action'])
        self.assertEqual([540, 300], payload['point'])
        self.assertTrue(payload['dry_run'])

    def test_tap_uses_the_seeded_session_row_on_the_inbox(self):
        (self.evidence / 'seed-counts.json').write_text(json.dumps({'session_names': ['verify-session']}))
        payload = self.payload('tap', 'text', 'verify-session', '--xml', str(self.screen), '--dry-run')
        self.assertEqual('Robot verify-session', payload['value'])
        self.assertEqual('desc', payload['mode'])

    def test_dry_run_seed_prints_tmux_work_and_creates_nothing(self):
        binary = self.root / 'bin'
        binary.mkdir()
        marker = self.root / 'tmux-called'
        tmux = binary / 'tmux'
        tmux.write_text(f'#!/bin/sh\ntouch {marker}\nexit 1\n')
        tmux.chmod(0o755)
        env = environment(EVIDENCE=str(self.evidence), HOME=str(self.home), GITHUB_ACTIONS='true', PATH=str(binary) + os.pathsep + os.environ['PATH'])
        payload = self.payload('seed', '--dry-run', env=env)
        self.assertTrue(payload['dry_run'])
        self.assertEqual(40, payload['rows'])
        self.assertFalse(marker.exists())
        self.assertFalse((self.home / 'pins-names.txt').exists())

    def test_seed_refuses_to_run_off_the_runner(self):
        env = environment(EVIDENCE=str(self.evidence), HOME=str(self.home))
        result = self.run_cli('seed', '--dry-run', env=env)
        self.assertEqual(1, result.returncode)
        payload = json.loads(result.stdout)
        self.assertEqual('SEED_NOT_ON_RUNNER', payload['error'])
        self.assertIn('GitHub runner', payload['next'])

    def test_dry_run_cleanup_plans_the_stop_without_deleting_anything(self):
        (self.fixture / 'tmux.pid').write_text('424242')
        key = self.fixture / 'user-key'
        key.write_text('secret')
        payload = self.payload('cleanup', '--dry-run')
        self.assertTrue(payload['dry_run'])
        self.assertEqual([{'process': 'tmux', 'pid': 424242}], payload['stopped'])
        self.assertTrue(any('kill' in operation for operation in payload['operations']))
        self.assertTrue(key.exists())
        self.assertFalse((self.fixture / 'tmux.pid').read_text() != '424242')

    def test_dry_run_launch_and_key_print_commands_without_pressing_anything(self):
        launch = self.payload('launch', '--force-stop', '--dry-run')
        self.assertTrue(launch['dry_run'])
        self.assertEqual(2, len(launch['commands']))
        self.assertIn('force-stop', ' '.join(launch['commands'][0]))
        key = self.payload('key', 'back', '--dry-run')
        self.assertEqual('KEYCODE_BACK', key['key'])
        self.assertEqual(1, len(key['commands']))

    def test_unknown_key_names_the_known_keys(self):
        result = self.run_cli('key', 'sideways', '--dry-run')
        self.assertEqual(1, result.returncode)
        payload = json.loads(result.stdout)
        self.assertEqual('KEY_UNKNOWN', payload['error'])
        self.assertIn('back', payload['next'])

    def test_proof_dry_run_checks_text_and_writes_no_evidence(self):
        payload = self.payload('proof', '--name', 'sync', '--require-text', 'Sign in to sync', '--forbid-text', 'Sync: signed in', '--xml', str(self.menu), '--dry-run')
        self.assertTrue(payload['record']['passed'])
        self.assertEqual([], list(self.evidence.glob('*.json')))

    def test_proof_writes_the_note_first_and_fails_on_a_wrong_screen(self):
        note = self.evidence / 'sync-menu-proof.txt'
        payload = self.payload('proof', '--name', 'sync', '--require-text', 'Sign in to sync', '--note', 'SIGNED_OUT_SYNC_MENU_OK', '--proof-file', str(note), '--xml', str(self.menu))
        self.assertTrue(payload['record']['passed'])
        self.assertEqual('SIGNED_OUT_SYNC_MENU_OK', note.read_text().splitlines()[0])
        record = json.loads((self.evidence / 'sync.json').read_text())
        self.assertEqual([{'kind': 'present', 'value': 'Sign in to sync', 'passed': True}], record['checks'])
        result = self.run_cli('proof', '--name', 'closed', '--forbid-text', 'Sign in to sync', '--xml', str(self.menu))
        self.assertEqual(1, result.returncode)
        self.assertEqual('PROOF_FAILED', json.loads(result.stdout)['error'])

    def test_proof_order_accepts_rows_top_to_bottom_and_records_them(self):
        payload = self.payload('proof', '--name', 'clear-query', '--order', '/clear', '--order', '/client', '--order', '/aaa-paper', '--xml', str(self.picker))
        self.assertTrue(payload['record']['passed'])
        self.assertEqual([{'kind': 'order', 'values': ['/clear', '/client', '/aaa-paper'], 'passed': True}], payload['record']['checks'])
        record = json.loads((self.evidence / 'clear-query.json').read_text())
        self.assertEqual(['/clear', '/client', '/aaa-paper'], record['checks'][0]['values'])

    def test_proof_order_rejects_a_wrong_order_and_keeps_the_failure_as_evidence(self):
        result = self.run_cli('proof', '--name', 'clear-query', '--order', '/client', '--order', '/clear', '--xml', str(self.picker))
        self.assertEqual(1, result.returncode)
        self.assertEqual('PROOF_FAILED', json.loads(result.stdout)['error'])
        record = json.loads((self.evidence / 'clear-query.json').read_text())
        self.assertFalse(record['passed'])
        self.assertIn('ORDER_MISMATCH', record['checks'][0]['detail'])

    def test_proof_order_rejects_a_row_scrolled_out_of_its_container(self):
        result = self.run_cli('proof', '--name', 'clear-query', '--order', '/clear', '--order', '/client', '--order', '/aaa-paper', '--xml', str(self.clipped))
        self.assertEqual(1, result.returncode)
        record = json.loads((self.evidence / 'clear-query.json').read_text())
        self.assertFalse(record['passed'])
        self.assertIn('ORDER_OFFSCREEN', record['checks'][0]['detail'])

    def test_proof_order_names_a_missing_row_and_the_next_step(self):
        result = self.run_cli('proof', '--name', 'clear-query', '--order', '/clear', '--order', '/ghost', '--xml', str(self.picker))
        self.assertEqual(1, result.returncode)
        record = json.loads((self.evidence / 'clear-query.json').read_text())
        self.assertIn('/ghost', record['checks'][0]['detail'])
        self.assertIn('mpa read', record['checks'][0]['detail'] + json.loads(result.stdout)['next'])

    def test_the_migrated_journeys_drive_only_through_the_cli(self):
        helper = re.compile(r'(?m)^\s*(shot|tap_text|tap_desc|wait_for_text|wait_for_absence|dump_ui|select_node|relaunch_app)\b')
        for name in ('clear-ranking-drive.sh', 'message-reply-drive.sh', 'remote-control-drive.sh'):
            with self.subTest(journey=name):
                source = (SCRIPTS / name).read_text()
                self.assertNotIn('scripts/ui.py', source)
                self.assertIsNone(helper.search(source), name)

    def test_doctor_without_an_expected_version_explains_the_next_step(self):
        result = self.run_cli('doctor')
        self.assertEqual(1, result.returncode)
        payload = json.loads(result.stdout)
        self.assertEqual('EXPECTED_VERSION_MISSING', payload['error'])
        self.assertIn('EXPECTED_VERSION', payload['next'])

    def test_every_failure_prints_one_json_object(self):
        for args in (('doctor',), ('tap', 'text', 'Inbox', '--dry-run'), ('read', '--select', 'text', '--value', 'Nope', '--xml', str(self.screen))):
            with self.subTest(args=args):
                result = self.run_cli(*args)
                self.assertEqual(1, result.returncode, result.stderr)
                payload = json.loads(result.stdout)
                self.assertFalse(payload['ok'])
                self.assertTrue(payload['error'])
                self.assertTrue(payload['next'])

    def test_legacy_entry_point_keeps_the_old_output(self):
        result = subprocess.run([sys.executable, '-B', str(UI), str(self.screen), 'desc', 'Session actions'], capture_output=True, text=True, env=self.env)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('975 110', result.stdout.strip())
        absent = subprocess.run([sys.executable, '-B', str(UI), str(self.screen), 'absent', 'Sign in to sync'], capture_output=True, text=True, env=self.env)
        self.assertEqual(0, absent.returncode)
        self.assertEqual('Absent text: Sign in to sync', absent.stdout.strip())
        missing = subprocess.run([sys.executable, '-B', str(UI), str(self.screen), 'text', 'Nope'], capture_output=True, text=True, env=self.env)
        self.assertEqual(1, missing.returncode)
        self.assertEqual('Missing text: Nope', missing.stderr.strip())


FAKE_ADB = """#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

state = Path(os.environ["FAKE_ADB_STATE"])
log = Path(os.environ["FAKE_ADB_LOG"])
args = sys.argv[1:]
with log.open("a") as stream:
    stream.write(" ".join(args) + "\\n")


def events():
    return state.read_text().split() if state.exists() else []


def record(kind):
    state.write_text(" ".join(events() + [kind]))


def node(extra):
    return '<node package="dev.multiprompt.companion.debug" ' + extra + ' />'


def window(*nodes):
    return '<hierarchy rotation="0"><node class="android.widget.FrameLayout" package="dev.multiprompt.companion.debug" visible-to-user="true" bounds="[0,0][1080,1920]">' + "".join(nodes) + "</node></hierarchy>"


def composer():
    data = json.loads(state.read_text()) if state.exists() else {}
    text = data.get("text", "")
    focused = ' focused="true"' if data.get("focused") else ""
    placeholder = '' if text else '<node text="Message verify-session" package="dev.multiprompt.companion.debug" bounds="[40,1700][900,1740]" class="android.widget.TextView" />'
    return window('<node class="android.widget.EditText" package="dev.multiprompt.companion.debug" text="' + text + '"' + focused + ' bounds="[40,1700][900,1780]">' + placeholder + '</node>')


def save(**changes):
    data = json.loads(state.read_text()) if state.exists() else {"focused": False, "text": ""}
    data.update(changes)
    state.write_text(json.dumps(data))


def fill_mode():
    if args[:2] == ["shell", "input"] and len(args) > 2:
        if args[2] == "tap":
            save(focused=True)
        if args[2] == "keyevent" and args[3] == "KEYCODE_DEL":
            save(text="leftover" if os.environ.get("FAKE_ADB_STICKY") else "")
        if args[2] == "keyevent" and args[3] == "KEYCODE_BACK":
            save(focused=False)
        if args[2] == "text":
            data = json.loads(state.read_text())
            character = " " if args[3] == "%s" else args[3]
            if os.environ.get("FAKE_ADB_DROP_SPACES") and character == " ":
                character = ""
            save(text=data["text"] + character)
        sys.exit(0)
    if args[:1] == ["pull"]:
        Path(args[2]).write_text(composer())
        sys.exit(0)
    sys.exit(0)


if os.environ.get("FAKE_ADB_MODE") == "fill":
    fill_mode()


INBOX = window(node('text="Inbox" bounds="[40,80][300,140]" class="android.widget.TextView"'),
               node('content-desc="Robot verify-session" clickable="true" enabled="true" bounds="[0,200][1080,400]" class="android.view.View"'))
READER = window(node('text="Message verify-session" bounds="[40,1700][900,1780]" class="android.widget.TextView"'),
                node('content-desc="Session actions" clickable="true" enabled="true" bounds="[900,80][1050,140]" class="android.view.View"'))
SHEET = window(node('text="Message verify-session" bounds="[40,1700][900,1780]" class="android.widget.TextView"'),
               node('content-desc="Session actions" clickable="true" enabled="true" bounds="[900,80][1050,140]" class="android.view.View"'),
               node('text="More" clickable="true" enabled="true" bounds="[40,700][700,760]" class="android.view.View"'))
MENU = window(node('text="Message verify-session" bounds="[40,1700][900,1780]" class="android.widget.TextView"'),
              node('text="Sign in to sync" clickable="true" enabled="true" bounds="[40,900][700,960]" class="android.view.View"'))

if args[:2] == ["shell", "input"] and len(args) > 2:
    if args[2] == "tap":
        record("tap")
    if args[2] == "keyevent" and args[3] == "KEYCODE_BACK":
        record("back")
    sys.exit(0)
if args[:1] == ["pull"]:
    taps = events().count("tap")
    backs = events().count("back")
    pulls = len([line for line in log.read_text().splitlines() if line.startswith("pull ")])
    screen = INBOX if taps == 0 else READER if taps == 1 else SHEET if taps == 2 else MENU
    if backs:
        screen = READER
    if os.environ.get("FAKE_ADB_LATE"):
        screen = INBOX if pulls <= 2 else READER
    Path(args[2]).write_text(screen)
    sys.exit(0)
if args[:1] == ["exec-out"]:
    sys.stdout.buffer.write(b"\\x89PNG\\r\\n\\x1a\\n" + b"0" * 200)
    sys.exit(0)
sys.exit(0)
"""


class MigratedJourneyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.evidence = self.root / 'evidence'
        self.evidence.mkdir()
        self.binary = self.root / 'adb'
        self.binary.write_text(FAKE_ADB)
        self.binary.chmod(0o755)
        self.state = self.root / 'state'
        self.log = self.root / 'adb.log'
        (self.evidence / 'seed-counts.json').write_text(json.dumps({'session_names': ['verify-session']}))

    def run_cli(self, *args, env=None):
        return subprocess.run([sys.executable, '-B', str(MPA), *args], capture_output=True, text=True, env=env)

    def payload(self, *args, env=None):
        result = self.run_cli(*args, env=env)
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        return json.loads(result.stdout)

    def test_wait_text_dumps_the_screen_again_on_every_poll(self):
        env = environment(EVIDENCE=str(self.evidence), ADB=str(self.binary), FAKE_ADB_STATE=str(self.state), FAKE_ADB_LOG=str(self.log), FAKE_ADB_LATE='true', VERIFY_PACKAGE='dev.multiprompt.companion.debug')
        result = subprocess.run([sys.executable, '-B', str(MPA), 'read', '--wait-text', 'Message verify-session', '--timeout', '15'], capture_output=True, text=True, env=env)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('Message verify-session', json.loads(result.stdout)['texts'])
        self.assertGreaterEqual(len([line for line in self.log.read_text().splitlines() if line.startswith('pull ')]), 3)

    def test_fill_dry_run_prints_the_planned_typing_without_touching_the_phone(self):
        env = environment(EVIDENCE=str(self.evidence), ADB=str(self.binary), FAKE_ADB_STATE=str(self.state), FAKE_ADB_LOG=str(self.log), FAKE_ADB_MODE='fill')
        payload = self.payload('fill', 'Message verify-session', '/cl', '--dry-run', env=env)
        self.assertTrue(payload['dry_run'])
        self.assertEqual('/cl', payload['value'])
        self.assertEqual([470, 1740], payload['point'])
        self.assertEqual(4, len(payload['commands']))
        self.assertIn('KEYCODE_CTRL_LEFT', payload['commands'][0])
        self.assertNotIn('input tap', self.log.read_text())
        self.assertFalse(self.state.exists())

    def test_fill_clears_types_and_reads_the_value_back(self):
        env = environment(EVIDENCE=str(self.evidence), ADB=str(self.binary), FAKE_ADB_STATE=str(self.state), FAKE_ADB_LOG=str(self.log), FAKE_ADB_MODE='fill')
        payload = self.payload('fill', 'Message verify-session', 'repl%sdraft', env=env)
        self.assertTrue(payload['verified'])
        self.assertEqual('repl draft', payload['value'])
        self.assertEqual('repl draft', json.loads(self.state.read_text())['text'])
        commands = [line for line in self.log.read_text().splitlines() if line.startswith('shell input')]
        self.assertEqual('shell input tap 470 1740', commands[0])
        self.assertIn('keycombination -t 100 KEYCODE_CTRL_LEFT KEYCODE_A', commands[1])
        self.assertEqual('shell input keyevent KEYCODE_DEL', commands[2])
        self.assertEqual('shell input keyevent KEYCODE_BACK', commands[-1])

    def test_fill_refuses_to_type_when_the_field_did_not_clear(self):
        env = environment(EVIDENCE=str(self.evidence), ADB=str(self.binary), FAKE_ADB_STATE=str(self.state), FAKE_ADB_LOG=str(self.log), FAKE_ADB_MODE='fill', FAKE_ADB_STICKY='true')
        result = self.run_cli('fill', 'Message verify-session', '/cl', env=env)
        self.assertEqual(1, result.returncode)
        payload = json.loads(result.stdout)
        self.assertEqual('FIELD_CLEAR_FAILED', payload['error'])
        self.assertIn('never type into an uncleared field', payload['next'])
        self.assertNotIn('input text', self.log.read_text())

    def test_fill_reports_a_value_that_did_not_read_back(self):
        env = environment(EVIDENCE=str(self.evidence), ADB=str(self.binary), FAKE_ADB_STATE=str(self.state), FAKE_ADB_LOG=str(self.log), FAKE_ADB_MODE='fill', FAKE_ADB_DROP_SPACES='true')
        result = self.run_cli('fill', 'Message verify-session', 'a b', env=env)
        self.assertEqual(1, result.returncode)
        payload = json.loads(result.stdout)
        self.assertEqual('FIELD_INPUT_FAILED', payload['error'])
        self.assertIn('mpa read --select field-value', payload['next'])

    def test_the_migrated_journey_drives_the_whole_flow_through_the_cli(self):
        env = dict(os.environ, EVIDENCE=str(self.evidence), ADB=str(self.binary), FAKE_ADB_STATE=str(self.state), FAKE_ADB_LOG=str(self.log), VERIFY_PACKAGE='dev.multiprompt.companion.debug')
        script = f'set -euo pipefail\nscripts={SCRIPTS}\nstep=0\nsource {SCRIPTS / "signed-out-sync-drive.sh"}\nprintf "%s" "$step"'
        result = subprocess.run(['bash', '-c', script], capture_output=True, text=True, env=env)
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        calls = self.log.read_text()
        self.assertIn('shell input tap', calls)
        self.assertIn('shell input keyevent KEYCODE_BACK', calls)
        self.assertEqual('2', result.stdout.splitlines()[-1])
        record = json.loads((self.evidence / 'reader-sync.json').read_text())
        self.assertTrue(record['passed'])
        self.assertEqual([{'kind': 'present', 'value': 'Sign in to sync', 'passed': True}, {'kind': 'absent', 'value': 'Sync: signed in', 'passed': True}], record['checks'])
        proof = (self.evidence / 'sync-menu-proof.txt').read_text().splitlines()
        self.assertEqual('SIGNED_OUT_SYNC_MENU_OK', proof[0])
        self.assertIn('present', proof[1])
        closed = json.loads((self.evidence / 'reader-sync-closed.json').read_text())
        self.assertTrue(closed['passed'])
        for name in ('01-reader-sync.png', '01-reader-sync.xml', '02-reader-sync-closed.png', '02-reader-sync-closed.xml'):
            self.assertTrue((self.evidence / name).is_file(), name)
        self.assertEqual(['tap', 'tap', 'tap', 'back'], self.state.read_text().split())


FAKE_SCREEN_ADB = """#!/usr/bin/env python3
import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "dev.multiprompt.companion.debug"
state_path = Path(os.environ["FAKE_ADB_STATE"])
cache = Path(os.environ["FAKE_ADB_CACHE"])
receipts = Path(os.environ["FAKE_RECEIPTS"])
mode = os.environ["FAKE_ADB_MODE"]
args = sys.argv[1:]
with Path(os.environ["FAKE_ADB_LOG"]).open("a") as stream:
    stream.write(" ".join(args) + "\\n")


def load():
    return json.loads(state_path.read_text()) if state_path.exists() else {"screen": "inbox", "focused": False, "text": ""}


def save(data):
    state_path.write_text(json.dumps(data))


def text(value, top, clickable=False):
    if clickable:
        return ('<node class="android.view.View" package="' + PKG + '" bounds="[0,' + str(top) + '][1080,' + str(top + 80) + ']" clickable="true" enabled="true">'
                '<node text="' + value + '" package="' + PKG + '" bounds="[40,' + str(top + 10) + '][900,' + str(top + 70) + ']" class="android.widget.TextView" /></node>')
    return '<node text="' + value + '" package="' + PKG + '" bounds="[40,' + str(top) + '][1000,' + str(top + 60) + ']" class="android.widget.TextView" />'


def desc(value, top, left=900, right=1060):
    return '<node class="android.view.View" package="' + PKG + '" bounds="[' + str(left) + ',' + str(top) + '][' + str(right) + ',' + str(top + 60) + ']" content-desc="' + value + '" clickable="true" enabled="true" />'


def composer(data):
    placeholder = '' if data["text"] else '<node text="Message verify-session" package="' + PKG + '" bounds="[40,1700][900,1740]" class="android.widget.TextView" />'
    focused = ' focused="true"' if data["focused"] else ''
    return '<node class="android.widget.EditText" package="' + PKG + '" text="' + data["text"] + '"' + focused + ' bounds="[40,1700][900,1780]">' + placeholder + '</node>'


def window(body):
    return '<hierarchy rotation="0"><node class="android.widget.FrameLayout" package="' + PKG + '" visible-to-user="true" bounds="[0,0][1080,1920]">' + body + '</node></hierarchy>'


CHAT = text('Native session skills are ready.', 1200) + desc('Session actions', 80) + desc('Send prompt', 1400)
REPLY_CHAT = text('REPLY_BUBBLE_418', 600, clickable=True) + desc('Session actions', 80) + desc('Send prompt', 1400)
REPLY_BAR = text('Replying', 1500) + desc('Cancel reply', 120, left=700, right=820)
PICKER = ('<node class="android.widget.ScrollView" package="' + PKG + '" scrollable="true" bounds="[0,600][1080,1200]">'
          + text('/clear', 620, clickable=True) + text('/client', 720, clickable=True) + text('/aaa-paper', 820, clickable=True)
          + text('Start a new session with empty context', 920) + '</node>')
SHEET = (text('AI usage', 120)
         + '<node class="android.view.View" package="' + PKG + '" scrollable="true" bounds="[0,300][1080,1500]">'
         + text('Claude', 340) + '<node text="37.5%" package="' + PKG + '" bounds="[800,340][1040,400]" class="android.widget.TextView" />'
         + text('BEHIND', 420) + text('WEEK 37.5% | 5H 12.5% | EMPTY IN 42H', 500)
         + text('Codex', 700) + '<node text="62.5%" package="' + PKG + '" bounds="[800,700][1040,760]" class="android.widget.TextView" />'
         + text('AHEAD', 780) + text('WEEK 62.5% | 5H 25% | EMPTY IN 18H', 860) + '</node>')


def xml_for(data):
    screen = data["screen"]
    if screen == "inbox":
        return window(text('Inbox', 80) + desc('Robot verify-session', 200, left=0, right=1080) + desc('Session list actions', 80))
    if mode == "reply":
        if screen == "menu":
            return window(REPLY_CHAT + text('Reply', 900, clickable=True))
        if screen == "replying":
            return window(REPLY_CHAT + REPLY_BAR + composer(data))
        return window(REPLY_CHAT + composer(data))
    if mode == "usage":
        if screen == "menu":
            return window(text('Inbox', 80) + desc('Session list actions', 80) + text('AI usage', 300, clickable=True) + text('Refresh', 420, clickable=True))
        if screen == "sheet":
            return window(SHEET)
        return window(text('Inbox', 80) + desc('Robot verify-session', 200, left=0, right=1080) + text('verify-session', 240))
    if screen == "ready":
        return window(CHAT + composer(data))
    if screen == "picker":
        return window(CHAT + composer(data) + PICKER)
    if screen == "selected":
        return window(CHAT + composer(data))
    return window(text('Received /clear. Claude', 1000) + CHAT + composer(data))


def containing(where):
    x, y = where
    found = ET.parse(cache).getroot()
    texts, descs, classes = set(), set(), set()
    for node in found.iter("node"):
        numbers = [int(value) for value in __import__("re").findall(r"-?\\d+", node.get("bounds", ""))]
        if len(numbers) != 4 or not (numbers[0] <= x <= numbers[2] and numbers[1] <= y <= numbers[3]):
            continue
        if node.get("text"):
            texts.add(node.get("text"))
        if node.get("content-desc"):
            descs.add(node.get("content-desc"))
        classes.add(node.get("class"))
    return texts, descs, classes


if args[:1] == ["pull"]:
    Path(args[2]).write_text(xml_for(load()))
    cache.write_text(xml_for(load()))
    sys.exit(0)
if args[:2] == ["shell", "input"] and len(args) > 2:
    data = load()
    if args[2] == "tap":
        texts, descs, classes = containing((int(args[3]), int(args[4])))
        if "android.widget.EditText" in classes:
            data["focused"] = True
        elif "Send prompt" in descs:
            data["screen"] = "sent"
            with receipts.open("a") as stream:
                stream.write("VERIFY_RECEIVED:/clear\\n" if mode == "ranking" else "> REPLY_BUBBLE_418\\n" + data["text"] + "\\n")
            data["text"] = ""
        elif "/clear" in texts:
            data["text"] = "/clear "
            data["screen"] = "selected"
        elif "AI usage" in texts:
            data["screen"] = "sheet"
            with receipts.open("a") as stream:
                stream.write(json.dumps({"command": "cat ~/.local/state/token-ladder/status.json", "exitCode": 0, "stdout": json.dumps({"ts": 1, "accounts": [{"provider": "claude", "week": {"used": 37.5}}, {"provider": "codex", "week": {"used": 62.5}}]})}) + "\\n")
        elif "Reply" in texts:
            data["screen"] = "replying"
        elif "Cancel reply" in descs:
            data["screen"] = "chat"
        elif "Robot verify-session" in descs:
            data["screen"] = "chat" if mode == "reply" else "ready"
        elif "Session list actions" in descs:
            data["screen"] = "menu"
        save(data)
        sys.exit(0)
    if args[2] == "swipe":
        texts, descs, classes = containing((int(args[3]), int(args[4])))
        if "REPLY_BUBBLE_418" in texts and data["screen"] == "chat":
            data["screen"] = "menu"
        save(data)
        sys.exit(0)
    if args[2] == "text":
        character = " " if args[3] == "%s" else args[3]
        data["text"] = data["text"] + character
        if mode == "ranking" and data["screen"] == "ready" and data["text"] == "/cl":
            data["screen"] = "picker"
    if args[2] == "keyevent" and args[3] == "KEYCODE_DEL":
        data["text"] = ""
    if args[2] == "keyevent" and args[3] == "KEYCODE_BACK":
        data["focused"] = False
        if data["screen"] == "sheet":
            data["screen"] = "closed"
    save(data)
    sys.exit(0)
if args[:1] == ["exec-out"]:
    sys.stdout.buffer.write(b"\\x89PNG\\r\\n\\x1a\\n" + b"0" * 200)
    sys.exit(0)
sys.exit(0)
"""


SUDO_STUB = """#!/usr/bin/env python3
import os
import sys

host = os.environ["FAKE_HOST"]
args = [host + arg[len("/home/verifyssh"):] if arg.startswith("/home/verifyssh") else arg for arg in sys.argv[1:]]
os.execvp(args[0], args)
"""


class MigratedScreenJourneyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.evidence = self.root / 'evidence'
        self.evidence.mkdir()
        self.host = self.root / 'host'
        self.host.mkdir()
        self.binary = self.root / 'adb'
        self.binary.write_text(FAKE_SCREEN_ADB)
        self.binary.chmod(0o755)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        (self.bin / 'sudo').write_text(SUDO_STUB)
        (self.bin / 'sudo').chmod(0o755)
        self.state = self.root / 'screen-state.json'
        self.cache = self.root / 'screen-cache.xml'
        self.log = self.root / 'adb.log'
        self.receipts = self.host / 'receipts.txt'
        self.receipts.write_text('')
        self.usage_receipts = self.host / 'ai-usage-receipts.txt'
        self.usage_receipts.write_text('')
        (self.evidence / 'seed-counts.json').write_text(json.dumps({'session_names': ['verify-session']}))

    def run_journey(self, name, mode):
        env = dict(os.environ)
        env.update({'PATH': str(self.bin) + os.pathsep + os.environ['PATH'], 'EVIDENCE': str(self.evidence), 'ADB': str(self.binary), 'VERIFY_PACKAGE': 'dev.multiprompt.companion.debug', 'FAKE_ADB_MODE': mode, 'FAKE_ADB_STATE': str(self.state), 'FAKE_ADB_CACHE': str(self.cache), 'FAKE_ADB_LOG': str(self.log), 'FAKE_RECEIPTS': str(self.usage_receipts if mode == 'usage' else self.receipts), 'FAKE_HOST': str(self.host)})
        script = f'set -euo pipefail\nscripts={SCRIPTS}\nstep=0\nsource {SCRIPTS / name}\nprintf "%s" "$step"'
        return subprocess.run(['bash', '-c', script], capture_output=True, text=True, env=env)

    def test_the_message_reply_journey_runs_end_to_end_through_the_cli(self):
        result = self.run_journey('message-reply-drive.sh', 'reply')
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertEqual('4', result.stdout.splitlines()[-1])
        opened = json.loads((self.evidence / 'reply-open.json').read_text())
        self.assertTrue(opened['passed'])
        self.assertEqual(['present', 'present', 'present'], [check['kind'] for check in opened['checks']])
        self.assertEqual(['Replying', 'REPLY_BUBBLE_418', 'Cancel reply'], [check['value'] for check in opened['checks']])
        cancelled = json.loads((self.evidence / 'reply-cancelled.json').read_text())
        self.assertTrue(cancelled['passed'])
        self.assertEqual('MESSAGE_REPLY_JOURNEY_OK', (self.evidence / 'reply-proof.txt').read_text().splitlines()[0])
        self.assertEqual(['> REPLY_BUBBLE_418', 'REPLY_BODY_418'], (self.evidence / 'reply-receipts.txt').read_text().splitlines()[:2])
        self.assertRegex(self.log.read_text(), r'shell input swipe 540 640 540 640 800')
        for name in ('01-reply-menu.xml', '02-reply-open.xml', '03-reply-cancelled.xml', '04-reply-sent.xml'):
            self.assertTrue((self.evidence / name).is_file(), name)

    def test_the_clear_ranking_journey_runs_end_to_end_through_the_cli(self):
        result = self.run_journey('clear-ranking-drive.sh', 'ranking')
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertEqual('5', result.stdout.splitlines()[-1])
        query = json.loads((self.evidence / 'clear-query.json').read_text())
        self.assertTrue(query['passed'])
        self.assertEqual(['/clear', '/client', '/aaa-paper'], [check for check in query['checks'] if check['kind'] == 'order'][0]['values'])
        self.assertTrue(json.loads((self.evidence / 'clear-selected.json').read_text())['passed'])
        self.assertTrue(json.loads((self.evidence / 'clear-sent.json').read_text())['passed'])
        self.assertEqual({'command': '/clear', 'command_count': 1, 'delivery_tag': '', 'raw_receipts': 'clear-receipts.txt'}, json.loads((self.evidence / 'clear-host.json').read_text()))
        self.assertEqual(['VERIFY_RECEIVED:/clear'], (self.evidence / 'clear-receipts.txt').read_text().splitlines())
        self.assertEqual('CLAUDE_CLEAR_RANKING_JOURNEY_OK', (self.evidence / 'clear-proof.txt').read_text().splitlines()[0])


if __name__ == '__main__':
    unittest.main()
