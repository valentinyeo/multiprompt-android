import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
ASSET = "app/src/main/assets/chat_history_reader.py"
SPEC = importlib.util.spec_from_file_location("reader", os.environ.get("QUEUE_READER", str(ROOT / ASSET)))
reader = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reader)


class HeldQueueTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(callable(getattr(reader, 'enqueue_response', None)), 'safe held-send boundary is missing')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.path = self.home / 'session.jsonl'
        self.path.write_text('')
        self.addCleanup(patch.stopall)
        patch.object(reader, 'HOME', self.home).start()
        patch.object(reader, 'resolve_tmux_agent', return_value=('sid', self.path)).start()
        self.send = patch.object(reader, 'send_response', return_value='@mp\t1\tsend\tok\t-\t0\n@@MP_END@@\n').start()
        self.ready = patch.object(reader, 'held_queue_ready', return_value=False).start()

    def enqueue(self, text, stamp):
        return reader.enqueue_response(dict(id=1, tmux='term-1', text=text, queued_at=stamp, message_id=f'message-{stamp}'))

    def test_large_preceding_record_does_not_block_exact_receipt(self):
        self.enqueue('target', 1)
        self.enqueue('next', 2)
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        preceding = json.dumps(dict(type='assistant', message=dict(role='assistant', content='x' * (reader.TAIL_BYTES + 1)))) + '\n'
        wrong = json.dumps(dict(type='user', message=dict(role='user', content='target extra'))) + '\n'
        receipt = json.dumps(dict(type='user', message=dict(role='user', content='target'), timestamp='2026-10-28T20:00:00Z')) + '\n'
        self.path.write_text(preceding + wrong + receipt)
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], [])
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], ['target'])
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual([call.args[0]['text'] for call in self.send.call_args_list], ['target', 'next'])

    def test_large_exact_user_receipt_unblocks_queue_once(self):
        text = 'x' * (reader.TAIL_BYTES + 1)
        self.enqueue(text, 1)
        self.enqueue('next', 2)
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.path.write_text(json.dumps(dict(type='user', message=dict(role='user', content=text), timestamp='2026-10-28T20:00:00Z')) + '\n')
        reader.drain_held_queue('term-1', 'sid', self.path)
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertTrue(reader.held_queue_fields('term-1', 'sid')['sent'] == [text])
        self.assertTrue([call.args[0]['text'] for call in self.send.call_args_list] == [text, 'next'])

    def test_incomplete_large_receipt_retains_anchor_until_completed(self):
        text = 'x' * (reader.TAIL_BYTES + 1)
        self.enqueue(text, 1)
        self.enqueue('next', 2)
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        inode = self.path.stat().st_ino
        record = json.dumps(dict(type='user', message=dict(role='user', content=text), timestamp='2026-10-28T20:00:00Z'))
        self.path.write_text(record)
        reader.drain_held_queue('term-1', 'other-sid', self.path)
        reader.drain_held_queue('term-1', 'sid', self.path)
        with reader.held_queue_db('term-1') as db:
            self.assertEqual(db.execute("SELECT sid,source_inode,scan,state FROM messages WHERE key='message-1'").fetchone(), ('sid', inode, 0, 'submitted'))
        self.send.assert_called_once()
        with self.path.open('a') as source:
            source.write('\n')
        reader.drain_held_queue('term-1', 'sid', self.path)
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertTrue(reader.held_queue_fields('term-1', 'sid')['sent'] == [text])
        self.assertTrue([call.args[0]['text'] for call in self.send.call_args_list] == [text, 'next'])


if __name__ == "__main__":
    if "QUEUE_READER" not in os.environ:
        with tempfile.TemporaryDirectory() as directory:
            baseline = Path(directory) / "reader.py"
            baseline.write_bytes(subprocess.check_output(["git", "show", "9e643f9:" + ASSET], cwd=ROOT))
            control = subprocess.run([sys.executable, "-B", __file__], env={**os.environ, "QUEUE_READER": str(baseline)}, capture_output=True, text=True, timeout=60)
            print(control.stdout + control.stderr)
            if control.returncode == 0 or "FAILED (failures=3)" not in control.stderr:
                raise SystemExit("Original queue stall was not reproduced")
            print("ORIGINAL_ANDROID_QUEUE_STALL_REPRODUCED")
    unittest.main(verbosity=2)
