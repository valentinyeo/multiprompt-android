import io
import json
import os
import shlex
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
READER = ROOT / 'app/src/main/assets/chat_history_reader.py'
sys.path.insert(0, str(HERE))

import inbox as inbox_tool
import fixture


def row(session, title, top, clickable='true', enabled='true'):
    return (
        f'<node clickable="{clickable}" enabled="{enabled}" bounds="[0,{top}][1080,{top + 179}]">'
        f'<node content-desc="Robot {session}" bounds="[42,{top + 27}][168,{top + 153}]" />'
        f'<node text="{title}" bounds="[200,{top + 34}][485,{top + 87}]" class="android.widget.TextView" />'
        '</node>')


class FreshStartFixtureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        reader = self.home / 'session-fresh-start-reader.py'
        reader.write_bytes(READER.read_bytes())
        os.environ['GITHUB_ACTIONS'] = 'true'
        fixture.HOME = self.home
        fixture.READER = reader
        fixture.CWD = self.home / 'fresh-start-work'

    def test_fresh_start_chooses_native_after_and_hides_old_stale(self):
        payload = fixture.seed_before(self.home)
        fixture.write_json(self.home / 'session-fresh-start.json', payload)
        with patch.dict(os.environ, dict(HOME=str(self.home))), \
                patch.object(subprocess, 'run', return_value=None):
            fixture.apply_fresh_start(self.home)
        with patch.dict(os.environ, dict(HOME=str(self.home))):
            visible = fixture.visible_transcript(self.home)['visible']
        archive = list((self.home / '.local/state/multiprompt/agent-host').glob('*.history.jsonl'))
        self.assertEqual(1, len(archive))
        self.assertIn(fixture.MARKERS['old'], archive[0].read_text())
        self.assertNotIn(fixture.MARKERS['old'], visible)
        self.assertEqual(['HANDOVER_3000', 'FRESH_REPLY_3000'], visible[-2:])

    def test_fixture_setup_starts_tmux_before_host_start(self):
        setup = (
            ROOT / '.claude/skills/verify-multiprompt-android/scripts/session-fresh-start-setup.sh'
        ).read_text()
        console = (HERE / 'fixture.py').read_text()
        self.assertIn('HOME=/home/verifyssh tmux new-session', setup)
        self.assertIn('session-fresh-start-reader.py host start', setup)
        host_start = setup.index('host start')
        tmux_start = setup.index('tmux new-session')
        self.assertLess(tmux_start, host_start)
        self.assertNotIn("cli('start'", console)

    def test_setup_script_resolves_the_repository_root(self):
        setup = (
            ROOT / '.claude/skills/verify-multiprompt-android/scripts/session-fresh-start-setup.sh'
        )
        root_line = next(line for line in setup.read_text().splitlines() if line.startswith('root='))
        resolved = subprocess.run(
            ['bash', '-c', f'scripts={shlex.quote(str(setup.parent))}; {root_line}; printf %s "$root"'],
            capture_output=True, text=True, check=True).stdout
        self.assertEqual(str(ROOT), resolved)
        self.assertTrue(Path(resolved, 'tools/session-fresh-start/fixture.py').is_file())

    def test_claude_binary_serves_the_agent_host(self):
        requests = io.StringIO(json.dumps(dict(type='control_request', request_id='req-3000')) + '\n')
        output = io.StringIO()
        with patch.dict(os.environ, dict(HOME=str(self.home))), \
                patch.object(sys, 'argv', ['claude', '--session-id', fixture.SID]), \
                patch.object(sys, 'stdin', requests), patch.object(sys, 'stdout', output):
            self.assertEqual(0, fixture.entry(sys.argv))
        events = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(fixture.SID, events[0]['session_id'])
        self.assertEqual(dict(subtype='success', request_id='req-3000', response={}),
                         events[1]['response'])
        self.assertEqual('control_response', events[1]['type'])

    def test_cache_command_emits_one_json_object(self):
        with patch.dict(os.environ, dict(HOME=str(self.home))):
            output = subprocess.run(
                [sys.executable, str(HERE / 'fixture.py'), 'cache'],
                capture_output=True, text=True, check=True).stdout
        payload = json.loads(output)
        self.assertIn('OLD_STALE_3000', payload['stale'])

    def inbox_xml(self, rows):
        xml = self.home / 'inbox.xml'
        body = ''.join(rows)
        xml.write_text(f'''<hierarchy>{body}</hierarchy>''')
        return xml

    def test_inbox_counter_requires_exactly_one_row(self):
        session = 'live-fresh-start-3000'
        xml = self.inbox_xml([row(session, 'verify-session', 400), row(session, 'verify-session', 900)])
        self.assertEqual(2, len(inbox_tool.session_rows(xml, session)))

    def test_inbox_row_is_found_by_avatar_not_window_title(self):
        session = 'live-fresh-start-3000'
        xml = self.inbox_xml([row(session, 'verify-session', 400)])
        self.assertEqual(
            [dict(session=session, description='Robot ' + session, bounds='[0,400][1080,579]', top=400)],
            inbox_tool.session_rows(xml, session))
        self.assertEqual(0, inbox_tool.main(['inbox.py', str(xml), session]))

    def test_inbox_counter_ignores_disabled_rows(self):
        session = 'live-fresh-start-3000'
        xml = self.inbox_xml([row(session, 'verify-session', 400, clickable='false', enabled='false')])
        self.assertEqual(0, len(inbox_tool.session_rows(xml, session)))

    def test_inbox_counter_rejects_a_duplicate_row(self):
        session = 'live-fresh-start-3000'
        xml = self.inbox_xml([
            row(session, 'verify-session', 400),
            row('c0030000-3000-4000-8000-000000003002', 'verify-session', 900)])
        self.assertEqual(1, len(inbox_tool.session_rows(xml, session)))
        self.assertEqual(1, inbox_tool.main(['inbox.py', str(xml), session]))

    def test_inbox_replay_allows_only_the_two_named_stress_sessions(self):
        session = fixture.TMUX
        extras = ['live-pi_verify-breakit-probe', 'verify-breakit-other']
        allowed = [argument for name in extras for argument in ('--allow-session', name)]
        rows = [row(session, 'verify-session', 400)] + [row(name, name, 650 + index * 200) for index, name in enumerate(extras)]
        xml = self.inbox_xml(rows)
        self.assertEqual(1, inbox_tool.main(['inbox.py', str(xml), session]))
        self.assertEqual(0, inbox_tool.main(['inbox.py', str(xml), session, *allowed]))
        for broken in ([*rows, row(session, 'verify-session', 1200)],
                       [*rows, row(fixture.NATIVE_AFTER, 'verify-session', 1200)],
                       rows[1:], [*rows, row('verify-breakit-other-duplicate', 'verify-session', 1200)]):
            xml = self.inbox_xml(broken)
            self.assertEqual(1, inbox_tool.main(['inbox.py', str(xml), session, *allowed]))

    def test_drive_script_reaches_the_row_by_avatar(self):
        drive = (
            ROOT / '.claude/skills/verify-multiprompt-android/scripts/session-fresh-start-drive.sh'
        ).read_text()
        self.assertIn('tap desc "Robot $fixture_session"', drive)
        self.assertNotIn('tap text "$fixture_session"', drive)
        self.assertNotIn('read --wait-text "$fixture_session"', drive)


if __name__ == '__main__':
    unittest.main()