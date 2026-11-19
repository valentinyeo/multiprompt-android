import json
import os
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
        for command in ('doctor', 'launch', 'seed', 'tap', 'read', 'screenshot', 'proof', 'cleanup', 'key'):
            self.assertIn(command, result.stdout)
        for command in ('doctor', 'launch', 'seed', 'tap', 'read', 'screenshot', 'proof', 'cleanup', 'key'):
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

    def test_wait_text_dumps_the_screen_again_on_every_poll(self):
        env = environment(EVIDENCE=str(self.evidence), ADB=str(self.binary), FAKE_ADB_STATE=str(self.state), FAKE_ADB_LOG=str(self.log), FAKE_ADB_LATE='true', VERIFY_PACKAGE='dev.multiprompt.companion.debug')
        result = subprocess.run([sys.executable, '-B', str(MPA), 'read', '--wait-text', 'Message verify-session', '--timeout', '15'], capture_output=True, text=True, env=env)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('Message verify-session', json.loads(result.stdout)['texts'])
        self.assertGreaterEqual(len([line for line in self.log.read_text().splitlines() if line.startswith('pull ')]), 3)

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


if __name__ == '__main__':
    unittest.main()
