#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

RUNTIME = Path(__file__).with_name('room-runtime.py')


class RoomRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.TemporaryDirectory()
        self.env = dict(os.environ, HOME=self.home.name)

    def tearDown(self):
        self.home.cleanup()

    def run_runtime(self, args, payload=None):
        return subprocess.run(
            [sys.executable, str(RUNTIME), *args],
            input=payload,
            capture_output=True,
            text=True,
            env=self.env,
        )

    def serve(self, query):
        result = self.run_runtime(['--serve'], json.dumps(query) + '\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def test_host_status_names_the_fixture_agents(self):
        result = self.run_runtime(['host', 'status', 'claude', 'beta'])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['tmux'], 'tmux-beta')

    def test_unknown_agent_is_rejected(self):
        result = self.run_runtime(['host', 'status', 'claude', 'gamma'])
        self.assertNotEqual(result.returncode, 0)

    def test_engine_info_offers_room_messages(self):
        self.assertEqual(self.serve({'op': 'engine_info'})['room_message_protocols'], [1])

    def test_beta_waits_until_alpha_has_the_later_send(self):
        scope = {'room_id': 'room'}
        log = Path(self.home.name) / 'room-submits.jsonl'

        def submit(sid, message_id, text):
            return self.serve({
                'op': 'message_submit', 'sid': sid, 'message_id': message_id, 'text': text, 'room_scope': scope,
            })

        def texts(sid):
            if not log.exists():
                return []
            return [json.loads(line)['text'] for line in log.read_text().splitlines() if json.loads(line)['sid'] == sid]

        held = submit('beta', 'm1', 'one')
        self.assertEqual(held['result']['message']['id'], 'm1')
        self.assertFalse(log.exists())
        submit('alpha', 'a1', 'one')
        submit('beta', 'm1', 'one')
        self.assertEqual(texts('alpha'), ['one'])
        self.assertEqual(texts('beta'), [])
        submit('alpha', 'a2', 'two')
        accepted = submit('beta', 'm1', 'one')
        self.assertEqual(accepted['result']['message']['id'], 'm1')
        submit('beta', 'm2', 'two')
        self.assertEqual(texts('beta'), ['one', 'two'])
        page = self.serve({'op': 'events_subscribe', 'sid': 'beta', 'after': 0})
        self.assertEqual(page['result']['next_after'], 6)
        self.assertEqual(page['result']['events'][1]['event']['payload']['text'], 'one')


if __name__ == '__main__':
    unittest.main()
