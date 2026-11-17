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

    def test_composer_text_is_not_the_message_row(self):
        with tempfile.TemporaryDirectory() as directory:
            root = ET.Element('hierarchy')
            editor = ET.SubElement(root, 'node', {
                'package': PACKAGE,
                'class': 'android.widget.EditText',
                'text': 'one',
                'bounds': '[40,800][700,880]',
            })
            ET.SubElement(editor, 'node', {
                'package': PACKAGE,
                'class': 'android.view.View',
                'content-desc': 'Message Order room',
                'bounds': '[40,800][700,880]',
            })
            path = Path(directory) / 'ui.xml'
            ET.ElementTree(root).write(path, encoding='utf-8')
            present = self.run_script('composer', str(path))
            self.assertEqual(present.returncode, 0, present.stderr)
            self.assertIn('ROOM_COMPOSER', present.stdout)
            held = self.run_script('holding', str(path), 'one')
            self.assertEqual(held.returncode, 0, held.stderr)
            self.assertIn('ROOM_DRAFT_HELD', held.stdout)
            refused = self.run_script('first', str(path))
            self.assertNotEqual(refused.returncode, 0)
            self.assertIn('not visible', refused.stderr)
            ET.SubElement(root, 'node', {
                'package': PACKAGE,
                'class': 'android.widget.TextView',
                'text': 'one',
                'bounds': '[40,100][400,160]',
            })
            ET.ElementTree(root).write(path, encoding='utf-8')
            still = self.run_script('first', str(path))
            self.assertNotEqual(still.returncode, 0)
            self.assertIn('still in the composer', still.stderr)
            editor.set('text', '')
            ET.ElementTree(root).write(path, encoding='utf-8')
            cleared = self.run_script('first', str(path))
            self.assertEqual(cleared.returncode, 0, cleared.stderr)
            self.assertIn('ROOM_FIRST_MESSAGE', cleared.stdout)
            released = self.run_script('holding', str(path), 'one')
            self.assertNotEqual(released.returncode, 0)

    def test_sibling_placeholder_and_filled_field_stay_in_the_room(self):
        with tempfile.TemporaryDirectory() as directory:
            root = ET.Element('hierarchy')
            ET.SubElement(root, 'node', {'package': PACKAGE, 'class': 'android.widget.TextView', 'text': 'Order room', 'bounds': '[40,80][400,140]'})
            editor = ET.SubElement(root, 'node', {'package': PACKAGE, 'class': 'android.widget.EditText', 'text': '', 'bounds': '[40,800][700,880]'})
            ET.SubElement(root, 'node', {'package': PACKAGE, 'class': 'android.widget.TextView', 'text': 'Message Order room', 'bounds': '[48,820][400,860]'})
            ET.SubElement(root, 'node', {'package': PACKAGE, 'class': 'android.widget.TextView', 'text': 'Send', 'bounds': '[720,820][800,860]'})
            path = Path(directory) / 'ui.xml'
            ET.ElementTree(root).write(path, encoding='utf-8')
            empty = self.run_script('composer', str(path))
            self.assertEqual(empty.returncode, 0, empty.stderr)
            self.assertIn('ROOM_COMPOSER', empty.stdout)
            editor.set('text', 'one')
            for node in list(root):
                if node.get('text') == 'Message Order room':
                    root.remove(node)
            ET.ElementTree(root).write(path, encoding='utf-8')
            filled = self.run_script('composer', str(path))
            self.assertEqual(filled.returncode, 0, filled.stderr)
            held = self.run_script('holding', str(path), 'one')
            self.assertEqual(held.returncode, 0, held.stderr)
            inbox = ET.Element('hierarchy')
            ET.SubElement(inbox, 'node', {'package': PACKAGE, 'class': 'android.widget.TextView', 'text': 'Order room', 'bounds': '[40,200][400,260]'})
            inbox_path = Path(directory) / 'inbox.xml'
            ET.ElementTree(inbox).write(inbox_path, encoding='utf-8')
            left = self.run_script('composer', str(inbox_path))
            self.assertNotEqual(left.returncode, 0)
            self.assertIn('room composer is missing', left.stderr)

    def test_account_block_accepts_each_resolved_address_once(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'ips.txt'
            path.write_text('1.2.3.4\n1.2.3.4\n2606:4700:4700::1111\n')
            result = self.run_script('block-rules', str(path))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines(), ['v4 1.2.3.4', 'v6 2606:4700:4700::1111'])
            path.write_text('account.multiprompt.dev\n')
            refused = self.run_script('block-rules', str(path))
            self.assertNotEqual(refused.returncode, 0)
            self.assertIn('not an ip', refused.stderr)
            path.write_text('\n')
            empty = self.run_script('block-rules', str(path))
            self.assertNotEqual(empty.returncode, 0)
            self.assertIn('did not resolve', empty.stderr)

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
