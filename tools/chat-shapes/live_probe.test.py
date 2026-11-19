import importlib.machinery
import importlib.util
import io
import json
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PRE_FIX_REVISION = 'af4d369923a28d27890d81fcb3335c181583f05d'


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


fixture = load('live_probe_fixture', HERE / 'fixture.py')
live_probe = load('live_probe', HERE / 'live_probe.py')


def run(arguments):
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        code = live_probe.main(['live_probe.py', *arguments])
    return code, out.getvalue(), err.getvalue()


class LiveProbeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name) / 'home'
        self.home.mkdir()
        self.manifest = fixture.create(self.home)

    def markers(self):
        return [marker for marker in fixture.plan()['markers'] if marker]

    def test_seeded_chat_shapes_all_load_their_latest_transcript(self):
        code, out, err = run(['--home', str(self.home)])
        report = json.loads(out)
        self.assertEqual(code, 0, err)
        self.assertEqual(report['chats'], len(fixture.SHAPES))
        self.assertEqual(report['mismatch'], 0)
        self.assertEqual({result['status'] for result in report['results']}, {'match'})
        for result in report['results']:
            self.assertEqual(result['session'], result['latest'])

    def test_report_never_carries_chat_text(self):
        planted = Path(self.directory.name) / 'planted'
        planted.mkdir()
        live_probe.plant(planted)
        code, out, err = run(['--home', str(planted)])
        self.assertEqual(code, 1)
        for text in (out, err):
            for marker in self.markers():
                self.assertNotIn(marker, text)

    def test_planted_stale_session_is_a_mismatch(self):
        code, out, err = run(['--plant-mismatch'])
        report = json.loads(out)
        self.assertEqual(code, 1, err)
        self.assertEqual(report['home'], 'synthetic')
        failures = [result for result in report['results'] if result['status'] == 'mismatch']
        self.assertEqual(len(failures), 1)
        self.assertEqual(failures[0]['reason'], 'older_session')
        self.assertNotEqual(failures[0]['session'], failures[0]['latest'])
        self.assertIn('CHAT_PROBE_MISMATCH', err)

    def test_pinned_reader_before_the_live_session_fix_fails_the_seeded_home(self):
        source = subprocess.run(['git', '-C', str(ROOT), 'show', f'{PRE_FIX_REVISION}:app/src/main/assets/chat_history_reader.py'],
                                capture_output=True, text=True)
        self.assertEqual(source.returncode, 0, source.stderr)
        self.assertNotIn('hosted_claude_transcript_sid', source.stdout)
        pinned = Path(self.directory.name) / 'pinned-reader.py'
        pinned.write_text(source.stdout)
        code, out, err = run(['--home', str(self.home), '--reader', str(pinned)])
        report = json.loads(out)
        self.assertEqual(code, 1, err)
        self.assertEqual({result['reason'] for result in report['results'] if result['status'] == 'mismatch'},
                         {'older_session', 'missing_transcript'})

    def test_daily_timer_unit_runs_the_probe(self):
        service = (HERE / 'multiprompt-chat-probe.service').read_text()
        timer = (HERE / 'multiprompt-chat-probe.timer').read_text()
        self.assertIn('ExecStart=', service)
        self.assertIn('tools/chat-shapes/live_probe.py', service)
        self.assertIn('OnCalendar=', timer)
        self.assertIn('Persistent=true', timer)
        self.assertIn('WantedBy=timers.target', timer)


if __name__ == '__main__':
    unittest.main()
