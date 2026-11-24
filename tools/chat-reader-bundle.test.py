#!/usr/bin/env python3
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOL = pathlib.Path(__file__).resolve().parent / 'chat-reader-bundle.py'
PIN = ROOT / 'app/src/main/assets/chat_history_reader.sha'
REVISION = 'fb1e1b1e0b7b5b7f4cbe57d0a6b87a2e1f5cf0c2'
FIXTURE_FINGERPRINT = '203 96241174ad31e5885d071163c9861d66540155d9eaa948d29021e6eb8c72d930'


def write_assets(root):
    assets = root / 'app/src/main/assets'
    (assets / 'chat_connectors').mkdir(parents=True)
    (assets / 'chat_history_reader.py').write_bytes(b'MULTIBOT_APP_VERSION = 0\n')
    (assets / 'chat_connectors/claude.py').write_bytes(b'claude connector\n')
    (assets / 'chat_connectors/codex.py').write_bytes(b'codex connector\n')
    (assets / 'mp_send.py').write_bytes(b'print("send")\n')
    (assets / 'multibot_feedback.py').write_bytes(b'print("feedback")\n')
    return assets


def run(root, mode, revision=REVISION):
    return subprocess.run([sys.executable, '-B', str(TOOL), '--root', str(root), '--revision', revision, mode], capture_output=True, text=True)


class ChatReaderBundlePinTest(unittest.TestCase):
    def test_the_pin_follows_the_assets_and_the_desktop_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            assets = write_assets(root)
            written = run(root, 'write')
            self.assertEqual(written.returncode, 0, written.stderr)
            lines = (assets / 'chat_history_reader.sha').read_text().splitlines()
            self.assertEqual(lines, [REVISION, FIXTURE_FINGERPRINT])
            self.assertEqual(run(root, 'check').returncode, 0)

    def test_a_hand_edited_reader_fails_the_check_and_names_the_sync_script(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            assets = write_assets(root)
            run(root, 'write')
            reader = assets / 'chat_history_reader.py'
            reader.write_bytes(reader.read_bytes() + b'def live_session(): pass\n')
            checked = run(root, 'check')
            self.assertEqual(checked.returncode, 1)
            self.assertIn('tools/sync-chat-reader.sh', checked.stderr)

    def test_a_stale_pin_fails_the_check(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            assets = write_assets(root)
            run(root, 'write')
            pin = assets / 'chat_history_reader.sha'
            pin.write_text(f'{REVISION}\n506327 b490b690099bf6438fe0cfa6f0bf5172500299dcaaa59864cf4251227e44aaaa\n')
            self.assertEqual(run(root, 'check').returncode, 1)
            self.assertEqual(run(root, 'check', revision='0' * 40).returncode, 1)

    def test_the_checked_in_pin_matches_the_bundled_assets(self):
        revision = PIN.read_text().splitlines()[0]
        checked = run(ROOT, 'check', revision=revision)
        self.assertEqual(checked.returncode, 0, checked.stderr)


if __name__ == '__main__':
    unittest.main()
