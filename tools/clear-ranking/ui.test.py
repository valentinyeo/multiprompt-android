import importlib.util
from pathlib import Path
import struct
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
        self.editor.set('text', '/clear ')
        with self.assertRaises(AssertionError):
            self.check('sent')

    def test_ready_requires_fixture_reply(self):
        with self.assertRaises(AssertionError):
            self.check('ready')
        self.node(text='Native session skills are ready.')
        self.check('ready')


if __name__ == '__main__':
    unittest.main()
