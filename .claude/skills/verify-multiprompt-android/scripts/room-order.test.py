#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import tempfile
import unittest
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT = Path(__file__).with_name('room-order.py')
ASSETS = Path(__file__).resolve().parents[4] / 'app/src/main/assets/rooms'
HEADER = 'Room messages since your last turn, oldest first:'
PACKAGE = 'dev.multiprompt.companion.debug'


def hierarchy(rows):
    root = ET.Element('hierarchy')
    for text, top, bottom in rows:
        ET.SubElement(root, 'node', {
            'package': PACKAGE,
            'text': text,
            'bounds': f'[40,{top}][600,{bottom}]',
        })
    return root


def write_xml(directory, name, rows):
    path = Path(directory) / name
    ET.ElementTree(hierarchy(rows)).write(path, encoding='utf-8')
    return path


class RoomOrderScriptTest(unittest.TestCase):
    def run_script(self, *args, home=None):
        env = dict(os.environ)
        if home:
            env['HOME'] = home
        return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True, env=env)

    def test_missing_submit_log_means_nothing_sent(self):
        missing = Path(tempfile.mkdtemp()) / 'absent.jsonl'
        result = self.run_script('nothing-sent', str(missing))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('ROOM_NOTHING_SENT', result.stdout)
        present = missing.with_name('present.jsonl')
        present.write_text('{"sid":"alpha","text":"one"}\n')
        refused = self.run_script('nothing-sent', str(present))
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn('a room message was submitted', refused.stderr)

    def test_screen_requires_oldest_message_above_the_context_reply(self):
        with tempfile.TemporaryDirectory() as directory:
            current = write_xml(directory, 'ui.xml', [
                ('one', 100, 140),
                ('two', 240, 280),
                (f'{HEADER}\n\nalpha: one\n\nNew message from the user:\n\ntwo', 360, 520),
            ])
            result = self.run_script('shown', str(current))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('ROOM_ORDER_SHOWN', result.stdout)
            inverted = write_xml(directory, 'inverted.xml', [
                ('two', 100, 140),
                ('one', 240, 280),
                (f'{HEADER}\n\nalpha: one\n\ntwo', 360, 520),
            ])
            refused = self.run_script('shown', str(inverted))
            self.assertNotEqual(refused.returncode, 0)
            self.assertIn('not oldest first', refused.stderr)

    def test_context_can_use_an_earlier_dump_when_the_first_message_scrolled_away(self):
        with tempfile.TemporaryDirectory() as directory:
            earlier = write_xml(directory, 'earlier.xml', [('one', 80, 120), ('two', 200, 240)])
            current = write_xml(directory, 'later.xml', [
                ('two', 80, 120),
                (f'{HEADER}\n\nalpha: one\n\ntwo', 200, 360),
            ])
            result = self.run_script('shown', str(current), str(earlier))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('ROOM_ORDER_SHOWN', result.stdout)

    def test_out_of_order_beta_submit_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'room-submits.jsonl'
            path.write_text('\n'.join([
                json.dumps({'sid': 'alpha', 'text': 'one'}),
                json.dumps({'sid': 'beta', 'text': 'two'}),
                json.dumps({'sid': 'alpha', 'text': 'two'}),
                json.dumps({'sid': 'beta', 'text': 'one'}),
            ]) + '\n')
            result = self.run_script('submitted', str(path))
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('earlier message', result.stderr)

    def test_fixture_room_gives_beta_the_earlier_answer_before_the_new_message(self):
        with tempfile.TemporaryDirectory() as home:
            installed = self.run_script('install', home=home)
            self.assertEqual(installed.returncode, 0, installed.stderr)
            self.assertIn('ROOM_READY', installed.stdout)
            previous = os.environ.get('HOME')
            os.environ['HOME'] = home
            sys.path.insert(0, str(ASSETS))
            import room_bridge
            bridge = room_bridge.RoomBridge(str(Path(home) / '.local/state/multiprompt/rooms-accounts' / __import__('hashlib').sha256(b'verify-rooms').hexdigest()))
            try:
                host = bridge.execute({'op': 'capabilities'})['host_uuid']
                room_id = bridge.execute({'op': 'list', 'host_uuid': host, 'after': 0, 'through': None, 'limit': 20})['rooms'][0]['id']

                def post(text):
                    bridge.execute({
                        'op': 'post_human',
                        'host_uuid': host,
                        'command_id': str(uuid.uuid4()),
                        'room_id': room_id,
                        'expected_revision': 1,
                        'text': text,
                        'recipients': [],
                    })

                def sync():
                    return bridge.execute({'op': 'events', 'host_uuid': host, 'room_id': room_id, 'after': 0, 'limit': 50})

                post('one')
                sync()
                first = self.run_script('alpha-only', str(Path(home) / 'room-submits.jsonl'))
                self.assertEqual(first.returncode, 0, first.stderr)
                sync()
                held = self.run_script('alpha-only', str(Path(home) / 'room-submits.jsonl'))
                self.assertEqual(held.returncode, 0, held.stderr)
                post('two')
                page = None
                proved = None
                for _ in range(8):
                    page = sync()
                    proved = self.run_script('submitted', str(Path(home) / 'room-submits.jsonl'))
                    if proved.returncode == 0:
                        break
                self.assertEqual(proved.returncode, 0, proved.stderr)
                replies = [event['text'] for event in page['events'] if event.get('type') == 'agent_reply']
                self.assertTrue(any(text.startswith(HEADER) and 'alpha: one' in text and text.endswith('two') for text in replies))
            finally:
                bridge.close()
                if previous is None:
                    os.environ.pop('HOME', None)
                else:
                    os.environ['HOME'] = previous


if __name__ == '__main__':
    unittest.main()
