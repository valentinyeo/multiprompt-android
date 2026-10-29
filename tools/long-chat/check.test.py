import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('fixture', Path(__file__).with_name('fixture.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
check_spec = importlib.util.spec_from_file_location('frame_check', Path(__file__).with_name('check.py'))
frame_check = importlib.util.module_from_spec(check_spec)
check_spec.loader.exec_module(frame_check)


class LongChatTest(unittest.TestCase):
    def test_rejects_the_actual_stale_switch_frame(self):
        path = Path(__file__).with_name('fixtures') / 'stale-switch.png'
        with self.assertRaisesRegex(AssertionError, 'does not show the latest reply'):
            frame_check.verify_frame(path)

    def test_fixture_visible_name_cannot_drift_from_session_identity(self):
        workflow = Path(os.environ.get('LONG_CHAT_WORKFLOW', root / '.github/workflows/long-chat.yml')).read_text()
        self.assertIn('tmux set-window-option -t cl-long-chat automatic-rename off', workflow)
        self.assertIn('tmux rename-window -t cl-long-chat cl-long-chat', workflow)

    def test_large_real_reader_tail_includes_latest_reply(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            cwd, transcript = fixture.create(home)
            self.assertGreater(transcript.stat().st_size, 18 * 1024 * 1024)
            self.assertEqual(len(transcript.read_text().splitlines()), 1882)
            namespace = {}
            exec((root / 'app/src/main/assets/chat_history_reader.py').read_text(), namespace)
            reader = {}
            exec(namespace['READER_SOURCE'], reader)
            reader['HOME'] = home
            source = reader['conversation_path']('claude', fixture.SID, str(cwd))
            self.assertEqual(source, transcript)
            with source.open('rb') as stream:
                base, end, data = reader['read_tail'](stream, source.stat().st_size, 'claude')
            self.assertGreater(base, 0)
            self.assertEqual(end, source.stat().st_size)
            self.assertIn(fixture.LATEST, data.decode() if isinstance(data, bytes) else data)
            rows = [json.loads(line) for line in transcript.read_text().splitlines()]
            self.assertEqual(rows[-1]['message']['content'][0]['text'], fixture.LATEST)


if __name__ == '__main__':
    unittest.main()
