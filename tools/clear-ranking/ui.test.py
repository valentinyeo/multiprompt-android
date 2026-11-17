import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('clear_ui', Path(__file__).with_name('ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


class ClearUiTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.xml = Path(self.temporary.name) / 'screen.xml'
        self.png = self.xml.with_suffix('.png')
        self.png.write_bytes(b'\x89PNG\r\n\x1a\n' + b'\0' * 8 + struct.pack('>II', 400, 800))
        self.root = ET.Element('hierarchy')
        self.node(**{'content-desc': 'Session actions'})
        self.editor = self.node(text='/cl', **{'class': 'android.widget.EditText'})
        self.rows = []
        for index, label in enumerate(('/clear', '/client', '/aaa-paper')):
            self.rows.append(self.node(text=label, clickable='true', enabled='true', bounds=f'[10,{100 + index * 70}][390,{160 + index * 70}]'))
        self.description = self.node(text='Start a new session with empty context; previous session stays on disk (resumable with /resume)')

    def node(self, **attrs):
        return ET.SubElement(self.root, 'node', {'package': 'dev.multiprompt.companion.debug', **attrs})

    def check(self, phase='query'):
        ET.ElementTree(self.root).write(self.xml)
        return ui.check(self.xml, self.png, phase)

    def test_name_matches_beat_description_and_clear_is_first(self):
        self.assertEqual(self.check()['order'], ['/clear', '/client', '/aaa-paper'])

    def test_wrong_order_missing_rows_duplicate_or_disabled_clear_fail(self):
        for row, attr, wrong in ((self.rows[0], 'bounds', '[10,500][390,560]'),
                                 (self.rows[1], 'text', '/other'),
                                 (self.rows[1], 'text', '/clear'),
                                 (self.rows[0], 'enabled', 'false'),
                                 (self.rows[0], 'bounds', '[10,790][390,850]'),
                                 (self.description, 'text', 'Session duplicate must not replace built-in'),
                                 (self.editor, 'text', '/clear')):
            with self.subTest(attr=attr, wrong=wrong):
                previous = row.get(attr)
                row.set(attr, wrong)
                with self.assertRaises(AssertionError):
                    self.check()
                row.set(attr, previous)

    def test_selected_requires_exact_command_and_closed_picker(self):
        self.editor.set('text', '/clear ')
        with self.assertRaises(AssertionError):
            self.check('selected')
        for row in self.rows:
            self.root.remove(row)
        self.assertEqual(self.check('selected')['composer'], '/clear ')
        self.editor.set('text', '/cl /clear ')
        with self.assertRaises(AssertionError):
            self.check('selected')

    def test_sent_requires_host_reply_and_empty_composer(self):
        self.editor.set('text', '')
        with self.assertRaises(AssertionError):
            self.check('sent')
        self.node(text='Received /clear. Claude Opus 5.5 medium')
        self.check('sent')
        tag = self.node(text='Received [mp:2094e4496b523bb3]. Claude Opus 5.5 medium')
        with self.assertRaises(AssertionError):
            self.check('sent')
        self.root.remove(tag)
        self.editor.set('text', '/clear ')
        with self.assertRaises(AssertionError):
            self.check('sent')

    def test_ready_requires_fixture_reply(self):
        with self.assertRaises(AssertionError):
            self.check('ready')
        self.node(text='Native session skills are ready.')
        self.check('ready')

    def test_host_receipt_allows_only_exact_clear_and_optional_delivery_trailer(self):
        receipt = Path(self.temporary.name) / 'receipts.txt'
        for trailer in ('', 'VERIFY_RECEIVED:[mp:2094e4496b523bb3]\n'):
            receipt.write_text('VERIFY_RECEIVED:/clear\n' + trailer)
            result = ui.check_receipts(receipt)
            self.assertEqual(result['command'], '/clear')
            self.assertEqual(result['command_count'], 1)
        for raw in ('', 'VERIFY_RECEIVED:/clear \n',
                    'VERIFY_RECEIVED:/clear\nVERIFY_RECEIVED:/clear\n',
                    'VERIFY_RECEIVED:/clear\nVERIFY_RECEIVED:extra draft\n',
                    'VERIFY_RECEIVED:/clear\nVERIFY_RECEIVED:[mp:wrong]\n',
                    'VERIFY_RECEIVED:/clear\nVERIFY_RECEIVED:[mp:2094e4496b523bb3]\nVERIFY_RECEIVED:extra\n'):
            with self.subTest(raw=raw):
                receipt.write_text(raw)
                with self.assertRaises(AssertionError):
                    ui.check_receipts(receipt)

    def test_fixture_retains_raw_delivery_tag_without_inventing_another_user_turn(self):
        home = Path(self.temporary.name)
        receipt = home / 'receipts.txt'
        script = Path(__file__).resolve().parents[2] / '.claude/skills/verify-multiprompt-android/scripts/skills-console.py'
        result = subprocess.run([sys.executable, str(script), str(receipt), 'clear-ranking'],
                                input='/clear\n[mp:2094e4496b523bb3]\n', text=True, capture_output=True,
                                env={**os.environ, 'HOME': str(home)}, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(receipt.read_text(), 'VERIFY_RECEIVED:/clear\nVERIFY_RECEIVED:[mp:2094e4496b523bb3]\n')
        transcript = next(home.glob('.claude/projects/*/*.jsonl'))
        rows = [json.loads(line) for line in transcript.read_text().splitlines()]
        users = [row['message']['content'] for row in rows if row['type'] == 'user']
        self.assertEqual(users, ['VERIFY_READY_528', '/clear'])
        self.assertEqual(rows[-1]['message']['content'], 'Received /clear. Claude Opus 5.5 medium')


if __name__ == '__main__':
    unittest.main()
