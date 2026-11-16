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

    def test_beta_refuses_one_once_then_keeps_submit_order(self):
        scope = {'room_id': 'room'}
        refused = self.run_runtime(['--serve'], json.dumps({
            'op': 'message_submit', 'sid': 'beta', 'message_id': 'm1', 'text': 'one', 'room_scope': scope,
        }) + '\n')
        self.assertNotEqual(refused.returncode, 0)
        self.assertFalse((Path(self.home.name) / 'room-submits.jsonl').exists())
        accepted = self.serve({
            'op': 'message_submit', 'sid': 'beta', 'message_id': 'm1', 'text': 'one', 'room_scope': scope,
        })
        self.assertEqual(accepted['result']['message']['id'], 'm1')
        later = self.serve({
            'op': 'message_submit', 'sid': 'beta', 'message_id': 'm2', 'text': 'two', 'room_scope': scope,
        })
        self.assertEqual(later['result']['message']['id'], 'm2')
        rows = [json.loads(line) for line in (Path(self.home.name) / 'room-submits.jsonl').read_text().splitlines()]
        self.assertEqual([row['text'] for row in rows], ['one', 'two'])
        page = self.serve({'op': 'events_subscribe', 'sid': 'beta', 'after': 0})
        self.assertEqual(page['result']['next_after'], 6)
        self.assertEqual(page['result']['events'][1]['event']['payload']['text'], 'one')


if __name__ == '__main__':
    unittest.main()
