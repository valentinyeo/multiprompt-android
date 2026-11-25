import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
from contextlib import closing
from unittest.mock import MagicMock, call, patch

ROOT = Path(__file__).resolve().parents[1]
ASSET = 'app/src/main/assets/chat_history_reader.py'
BASE = '3412dfdbe8567e46f1651826b91819461f0f77f4'
SCRIPT = Path(os.environ.get('HISTORY_READER', ROOT / ASSET))
SPEC = importlib.util.spec_from_file_location('reader', SCRIPT)
reader = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reader)


class MessageWorkerRequestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.base = dict(message_protocol=1, provider='claude', sid='idle-chat', tmux='idle-chat')
        self.replies = []
        for name, value in [('HOME', self.home), ('ensure_runtime_tools', lambda: None),
                            ('host_switch_follow', lambda sid: None),
                            ('resolve_tmux_agent', lambda *args, **kwargs: None),
                            ('host_dispatch_switch', lambda *args: False),
                            ('host_enabled', lambda *args: False),
                            ('serve_print', self.replies.append)]:
            patcher = patch.object(reader, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.spawn = patch.object(reader.subprocess, 'Popen').start()
        self.addCleanup(patch.stopall)

    def request(self, op, **values):
        before = len(self.replies)
        query = dict(self.base, id=before + 1, op='message_' + op, **values)
        reader.serve_request(json.dumps(query), 'worker source', {}, threading.Lock())
        self.assertEqual(len(self.replies), before + 1)
        reply = self.replies[-1]
        self.assertEqual(reply['id'], query['id'])
        self.assertEqual(reply.get('message_protocol'), 1, reply)
        return reply['result']

    def submit(self):
        return self.request('submit', message_id='worker_message_0001', text='Send this now')['message']

    def test_idle_snapshot_polls_spawn_no_workers(self):
        for _ in range(12):
            result = self.request('snapshot')
            self.assertEqual(result['items'], [])
        self.assertEqual(self.spawn.call_count, 0, 'IDLE_WORKER_SPAWN')

    def test_submit_spawns_exactly_one_worker_immediately(self):
        message = self.submit()
        self.assertEqual(message['state'], 'queued')
        self.assertEqual(self.spawn.call_count, 1)
        command = self.spawn.call_args.args[0]
        worker = command.index('--message-worker')
        self.assertEqual(command[worker + 1:worker + 4], ['idle-chat', 'claude', 'idle-chat'])
        self.spawn.return_value.stdin.write.assert_called_once()
        self.spawn.return_value.stdin.close.assert_called_once()

    def test_starter_hands_its_lock_to_the_child(self):
        self.submit()
        command = self.spawn.call_args.args[0]
        self.assertEqual(self.spawn.call_args.kwargs['pass_fds'], (int(command[-1]),))

    def test_child_takes_the_handed_lock_instead_of_a_second_one(self):
        handed = {}

        class Store:
            def close(self):
                pass

            def execute(self, *args):
                return self

            def fetchone(self):
                return None

        def capture(command, **kwargs):
            handed['fd'] = os.dup(kwargs['pass_fds'][0])
            return MagicMock()

        self.spawn.side_effect = capture
        reader.message_store_path().parent.mkdir(parents=True)
        self.submit()
        with patch.object(reader, 'message_store_db', lambda tmux: Store()), \
                patch.object(reader, 'host_log', lambda *args, **kwargs: None), \
                patch.object(reader.os, 'fdopen', wraps=os.fdopen) as fdopen:
            reader.message_worker(self.base['tmux'], 'claude', 'idle-chat', str(handed['fd']))
        self.assertIn(call(handed['fd'], 'a'), fdopen.call_args_list)

    def test_retry_spawns_exactly_one_worker_immediately(self):
        message = self.submit()
        with closing(reader.message_store_db(self.base['tmux'])) as db, db:
            db.execute("UPDATE shared_messages SET state='failed',deadline=NULL WHERE id=?", (message['id'],))
        self.spawn.reset_mock()
        result = self.request('retry', message_id=message['id'], retry_id='worker_retry_000001')
        self.assertEqual(result['message']['state'], 'queued')
        self.assertNotEqual(result['message']['attempt'], message['attempt'])
        self.assertEqual(self.spawn.call_count, 1)

    def test_all_five_requests_spawn_for_each_work_state(self):
        message = self.submit()
        for state in ('queued', 'sent', 'failed', 'accepted', 'started'):
            for op in ('snapshot', 'submit', 'retry', 'events', 'status'):
                with self.subTest(state=state, op=op):
                    with closing(reader.message_store_db(self.base['tmux'])) as db, db:
                        db.execute('UPDATE shared_messages SET state=?,deadline=NULL WHERE id=?', (state, message['id']))
                    values = dict(message_id=message['id'])
                    if op == 'submit':
                        values['text'] = 'Send this now'
                    if op == 'retry':
                        values['retry_id'] = 'worker_retry_000001'
                        with closing(reader.message_store_db(self.base['tmux'])) as db, db:
                            db.execute('INSERT OR IGNORE INTO message_retries VALUES (?,?,?)', (message['id'], values['retry_id'], message['attempt']))
                    if op == 'events':
                        values['after'] = 0
                    self.spawn.reset_mock()
                    self.request(op, **values)
                    self.assertEqual(self.spawn.call_count, 1)

    def test_cancelled_chat_does_not_spawn_for_another_chats_work(self):
        message = self.submit()
        with closing(reader.message_store_db(self.base['tmux'])) as db, db:
            db.execute("UPDATE shared_messages SET state='cancelled',deadline=NULL WHERE id=?", (message['id'],))
        reader.message_store_request(dict(self.base, sid='another-chat', op='message_submit',
                                         message_id='worker_message_0002', text='Other chat'))
        self.spawn.reset_mock()
        for op, values in [('snapshot', {}), ('events', dict(after=0)),
                           ('status', dict(message_id=message['id'])),
                           ('submit', dict(message_id=message['id'], text='Send this now'))]:
            self.request(op, **values)
        self.spawn.assert_not_called()

    def test_work_check_does_not_create_a_store_or_folder(self):
        reader.start_message_worker(self.base, 'worker source')
        self.assertFalse(reader.message_store_path().parent.exists())
        self.spawn.assert_not_called()

    def test_work_check_does_not_create_tables(self):
        path = reader.message_store_path()
        path.parent.mkdir(parents=True)
        with closing(sqlite3.connect(path)):
            pass
        reader.start_message_worker(self.base, 'worker source')
        with closing(sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)) as db:
            self.assertEqual(db.execute('SELECT name FROM sqlite_master').fetchall(), [])
        self.spawn.assert_not_called()

    def test_work_check_opens_only_the_read_only_store(self):
        self.submit()
        self.spawn.reset_mock()
        with patch.object(reader.sqlite3, 'connect', wraps=sqlite3.connect) as connect:
            reader.start_message_worker(self.base, 'worker source')
        connect.assert_called_once_with(reader.message_store_path().as_uri() + '?mode=ro', uri=True)
        self.assertEqual(self.spawn.call_count, 1)

    def test_live_worker_is_woken_without_another_spawn(self):
        self.submit()
        self.spawn.reset_mock()
        path = reader.message_store_path()
        lock_path = path.with_suffix('.' + hashlib.sha256(b'claude:idle-chat').hexdigest() + '.message-worker')
        wake_path = reader.message_worker_wake_path(lock_path)
        os.mkfifo(wake_path, 0o600)
        wake = os.open(wake_path, os.O_RDWR | os.O_NONBLOCK)
        self.addCleanup(os.close, wake)
        with lock_path.open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.request('snapshot')
            self.assertEqual(os.read(wake, 1), b'\0')
        self.spawn.assert_not_called()


@unittest.skipIf('HISTORY_READER' in os.environ, 'historical control child')
class MessageWorkerHistoricalTest(unittest.TestCase):
    def test_origin_main_fails_idle_spawn_assertion_but_submit_still_passes(self):
        source = subprocess.run(['git', 'show', f'{BASE}:{ASSET}'], cwd=ROOT,
                                check=True, capture_output=True, timeout=30).stdout
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'base_reader.py'
            path.write_bytes(source)
            result = subprocess.run([sys.executable, '-B', __file__,
                                     'MessageWorkerRequestTest.test_idle_snapshot_polls_spawn_no_workers',
                                     'MessageWorkerRequestTest.test_submit_spawns_exactly_one_worker_immediately', '-v'],
                                    env=dict(os.environ, HISTORY_READER=str(path)),
                                    capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('12 != 0 : IDLE_WORKER_SPAWN', result.stderr)
        self.assertIn('FAILED (failures=1)', result.stderr)
        self.assertIn('Ran 2 tests', result.stderr)
        print(f'IDLE_WORKER_RED_OK: {BASE} starts 12 workers for 12 idle polls; submit passes')


if __name__ == '__main__':
    unittest.main()
