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

    def test_inbox_counter_requires_exactly_one_row(self):
        xml = self.home / 'inbox.xml'
        session = 'live-fresh-start-3000'
        xml.write_text(f'''<hierarchy>
  <node text="{session}" bounds="[40,400][900,520]" class="android.widget.TextView" />
  <node text="{session}" bounds="[40,900][900,1020]" class="android.widget.TextView" />
</hierarchy>''')
        self.assertEqual(2, len(inbox_tool.session_rows(xml, session)))


if __name__ == '__main__':
    unittest.main()