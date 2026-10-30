import base64
from contextlib import closing
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
SPEC = importlib.util.spec_from_file_location("reader", ROOT / ASSET)
reader = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reader)


class SharedReceiptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.path = self.home / 'sid.jsonl'
        self.path.write_text('')
        self.base = dict(message_protocol=1, provider='claude', sid='sid', tmux='term-1')
        sources = {name: base64.b64encode((ROOT / 'app/src/main/assets/chat_connectors' / (name + '.py')).read_bytes()).decode()
                   for name in ('claude', 'codex')}
        self.addCleanup(patch.stopall)
        patch.object(reader, 'HOME', self.home).start()
        patch.object(reader, 'CONNECTOR_SOURCES', sources, create=True).start()
        patch.object(reader, 'resolve_tmux_agent', return_value=('sid', self.path)).start()
        self.send = patch.object(reader, 'send_response_preserve_draft', return_value={'status': 'sent'}).start()

    def request(self, op, **values):
        return reader.message_store_request(dict(self.base, op='message_' + op, **values))

    def submit(self, text, number):
        return self.request('submit', message_id='message_receipt_%04d' % number, text=text)['message']

    def status(self, number):
        return self.request('status', message_id='message_receipt_%04d' % number)['message']

    def poll(self, sid='sid'):
        reader.message_poll('term-1', 'claude', sid)

    def append(self, event, newline=True):
        with self.path.open('a') as source:
            source.write(json.dumps(event) + ('\n' if newline else ''))

    def user(self, text, sid='sid'):
        return dict(type='user', sessionId=sid, message=dict(role='user', content=text), timestamp='2026-10-28T20:00:00Z')

    def dispatched(self):
        return [call.args[0]['text'] for call in self.send.call_args_list]

    def assert_one_receipt(self, message):
        with closing(reader.message_store_db('term-1')) as db:
            facts = [json.loads(row[0]) for row in db.execute("SELECT body FROM connector_facts WHERE provider='claude' AND sid='sid'")]
        kinds = [fact['kind'] for fact in facts if fact.get('id') == message['id'] and fact.get('attempt') == message['attempt']]
        self.assertEqual(kinds, ['accepted', 'started'])

    def cursor(self):
        with closing(reader.message_store_db('term-1')) as db:
            row = db.execute("SELECT state FROM connector_states WHERE provider='claude' AND sid='sid'").fetchone()
        return json.loads(row[0])['cursor']['offset']

    def test_large_preceding_record_does_not_block_exact_receipt(self):
        first = self.submit('target', 1)
        self.submit('next', 2)
        self.request('dispatch')
        self.request('dispatch')
        self.append(dict(type='assistant', sessionId='sid', message=dict(role='assistant', content='x' * (reader.TAIL_BYTES + 1))))
        self.append(self.user('target extra'))
        self.poll()
        self.assertEqual(self.status(1)['state'], 'sent')
        self.append(self.user('target'))
        self.poll()
        self.poll()
        self.assertEqual((self.status(1)['id'], self.status(1)['attempt'], self.status(1)['state']), (first['id'], first['attempt'], 'started'))
        self.assert_one_receipt(first)
        self.assertEqual(self.status(2)['state'], 'sent')
        self.request('dispatch')
        self.assertEqual(self.dispatched(), ['target', 'next'])

    def test_large_exact_user_receipt_unblocks_queue_once(self):
        text = 'x' * (reader.TAIL_BYTES + 1)
        first = self.submit(text, 1)
        self.submit('next', 2)
        self.request('dispatch')
        self.request('dispatch')
        self.append(self.user(text))
        self.poll()
        self.poll()
        self.assertEqual((self.status(1)['id'], self.status(1)['attempt'], self.status(1)['state']), (first['id'], first['attempt'], 'started'))
        self.assert_one_receipt(first)
        self.assertEqual(self.status(2)['state'], 'sent')
        self.request('dispatch')
        self.assertEqual(self.dispatched(), [text, 'next'])

    def test_incomplete_large_receipt_retains_anchor_until_completed(self):
        text = 'x' * (reader.TAIL_BYTES + 1)
        first = self.submit(text, 1)
        self.submit('next', 2)
        self.request('dispatch')
        self.request('dispatch')
        self.append(self.user(text, 'other-sid'))
        self.poll()
        self.assertEqual(self.status(1)['state'], 'sent')
        anchor = self.path.stat().st_size
        self.append(self.user(text), newline=False)
        self.poll('other-sid')
        self.poll()
        self.assertEqual(self.status(1)['state'], 'sent')
        self.assertEqual(self.cursor(), anchor)
        with self.path.open('a') as source:
            source.write('\n')
        self.poll()
        self.poll()
        self.assertEqual((self.status(1)['id'], self.status(1)['attempt'], self.status(1)['state']), (first['id'], first['attempt'], 'started'))
        self.assert_one_receipt(first)
        self.assertEqual(self.status(2)['state'], 'sent')
        self.request('dispatch')
        self.assertEqual(self.dispatched(), [text, 'next'])


if __name__ == '__main__':
    with tempfile.TemporaryDirectory() as directory:
        baseline = Path(directory) / 'reader.py'
        baseline.write_bytes(subprocess.check_output(['git', 'show', '9e643f9:' + ASSET], cwd=ROOT))
        control = subprocess.run([sys.executable, '-B', str(ROOT / 'tools/queue-receipts-legacy-control.py')],
                                 env={**os.environ, 'QUEUE_READER': str(baseline)}, capture_output=True, text=True, timeout=60)
        print(control.stdout + control.stderr)
        if control.returncode == 0 or 'FAILED (failures=3)' not in control.stderr or 'Ran 3 tests' not in control.stderr:
            raise SystemExit('Original queue stall was not reproduced')
        print('ORIGINAL_ANDROID_QUEUE_STALL_REPRODUCED')
    unittest.main(verbosity=2)
