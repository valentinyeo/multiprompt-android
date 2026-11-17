import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('menu_ui', Path(__file__).with_name('ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


class MenuUiTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.xml = Path(self.temp.name) / 'menu.xml'
        self.png = self.xml.with_suffix('.png')
        self.png.write_bytes(b'\x89PNG\r\n\x1a\n' + b'\x00' * 8 + struct.pack('>II', 400, 800) + b'\x00' * 100)
        self.root = ET.Element('hierarchy')
        self.window = ET.SubElement(self.root, 'node', bounds='[0,0][400,800]')
        self.scroll = ET.SubElement(self.window, 'node', bounds='[0,100][400,700]', scrollable='true')
        self.row = ET.SubElement(self.scroll, 'node', bounds='[0,500][400,550]', clickable='true', enabled='true')
        self.text = ET.SubElement(self.row, 'node', text='Set effort: max', bounds='[20,510][380,540]')

    def check(self, mode='visible', value='Set effort: max', before=None):
        ET.ElementTree(self.root).write(self.xml)
        return ui.check(self.xml, self.png, mode, value, before)

    def test_visible_row_passes(self):
        self.assertEqual(self.check()['text'], 'Set effort: max')

    def test_missing_disabled_and_clipped_rows_fail(self):
        for node, key, value in [(self.text, 'text', 'wrong'), (self.row, 'enabled', 'false'),
                                 (self.row, 'bounds', '[0,650][400,750]'),
                                 (self.text, 'bounds', '[20,710][380,740]'),
                                 (self.scroll, 'visible-to-user', 'false')]:
            with self.subTest(key=key, value=value):
                old = dict(node.attrib)
                node.set(key, value)
                with self.assertRaises(AssertionError):
                    self.check()
                node.attrib.clear()
                node.attrib.update(old)

    def test_background_row_cannot_prove_active_menu(self):
        ET.SubElement(self.root, 'node', bounds='[0,0][400,800]')
        with self.assertRaises(AssertionError):
            self.check()

    def test_current_model_requires_disabled_ancestor(self):
        self.text.set('text', 'Current: Sonnet 5.5 · high')
        with self.assertRaisesRegex(AssertionError, 'disabled'):
            self.check('current', 'Sonnet')
        self.row.set('enabled', 'false')
        self.check('current', 'Sonnet')
        with self.assertRaises(AssertionError):
            self.check('current', 'Opus')

    def test_scroll_must_reveal_previously_hidden_text(self):
        before = self.xml.with_name('before.xml')
        ET.ElementTree(self.root).write(before)
        with self.assertRaisesRegex(AssertionError, 'no new row'):
            self.check('scroll', 'Set effort: max', before)
        self.text.set('bounds', '[20,710][380,740]')
        ET.ElementTree(self.root).write(before)
        self.text.set('bounds', '[20,510][380,540]')
        self.check('scroll', 'Set effort: max', before)

    def test_closed_menu_requires_intact_visible_draft(self):
        self.scroll.remove(self.row)
        field = ET.SubElement(self.window, 'node', {'class': 'android.widget.EditText', 'text': 'keep this draft', 'bounds': '[20,710][380,760]'})
        self.check('draft', 'keep this draft')
        field.set('text', 'changed')
        with self.assertRaises(AssertionError):
            self.check('draft', 'keep this draft')
        field.set('text', 'keep this draft')
        self.scroll.append(self.row)
        with self.assertRaises(AssertionError):
            self.check('draft', 'keep this draft')

    def test_screenshot_required(self):
        self.png.write_bytes(b'')
        with self.assertRaises(AssertionError):
            self.check()


if __name__ == '__main__':
    unittest.main()
