import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

spec = importlib.util.spec_from_file_location('table_ui', Path(__file__).with_name('ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


class TableUiTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.xml = Path(self.temporary.name) / 'screen.xml'
        self.png = self.xml.with_suffix('.png')
        self.root = ET.Element('hierarchy')
        self.window = self.node(self.root, bounds='[0,0][400,800]')
        self.sheet = self.node(self.window, **{'resource-id': 'table-sheet', 'bounds': '[10,100][390,750]'})
        self.node(self.sheet, text='Garden harvest table')
        self.close = self.node(self.sheet, **{'resource-id': 'table-close', 'content-desc': 'Close'})
        self.viewport = self.node(self.sheet, **{'resource-id': 'table-sheet-scroll', 'bounds': '[20,180][380,720]', 'scrollable': 'true'})
        self.table = self.node(self.viewport, **{'resource-id': 'md-table-expanded', 'bounds': '[20,180][380,720]'})
        self.cell = self.node(self.table, text='R24-C06 harvest', bounds='[40,600][210,630]')
        self.hidden = self.node(self.table, text='R01-C06 harvest', bounds='[40,-400][210,-370]')
        self.image = Image.new('RGB', (400, 800), (16, 16, 16))
        ImageDraw.Draw(self.image).text((40, 605), 'R24-C06 harvest', fill=(242, 242, 242))

    def node(self, parent, **attrs):
        return ET.SubElement(parent, 'node', {'package': ui.PACKAGE, **attrs})

    def check(self, state='down', target='R24-C06 harvest', hidden='R01-C06 harvest'):
        ET.ElementTree(self.root).write(self.xml)
        self.image.save(self.png)
        return ui.check(self.xml, self.png, state, target, hidden)

    def test_far_cell_inside_expanded_table_passes(self):
        self.assertEqual(self.check()['target'], 'R24-C06 harvest')

    def test_missing_wrong_and_clipped_cells_fail(self):
        for text, bounds in [('wrong cell', '[40,600][210,630]'),
                             ('R24-C06 harvest', '[40,710][210,740]'),
                             ('R24-C06 harvest', '[40,800][210,830]'),
                             ('R24-C06 harvest', '[40,600][40,630]')]:
            with self.subTest(text=text, bounds=bounds):
                self.cell.set('text', text)
                self.cell.set('bounds', bounds)
                with self.assertRaises(AssertionError):
                    self.check()

    def test_cell_in_background_does_not_prove_expanded_table(self):
        self.table.remove(self.cell)
        self.window.append(self.cell)
        with self.assertRaisesRegex(AssertionError, 'outside the expanded table'):
            self.check()

    def test_blank_screenshot_is_rejected(self):
        self.image = Image.new('RGB', (400, 800), (16, 16, 16))
        with self.assertRaisesRegex(AssertionError, 'pixels are blank'):
            self.check()

    def test_no_vertical_progress_is_rejected(self):
        self.hidden.set('bounds', '[40,200][210,230]')
        with self.assertRaisesRegex(AssertionError, 'Expected cell to be off screen'):
            self.check()

    def test_sheet_title_close_and_full_size_are_required(self):
        for node, attr, value in [(self.sheet[0], 'text', 'Wrong title'),
                                  (self.close, 'content-desc', 'Wrong action'),
                                  (self.sheet, 'bounds', '[10,100][200,300]')]:
            old = node.get(attr)
            node.set(attr, value)
            with self.subTest(attr=attr), self.assertRaises(AssertionError):
                self.check()
            node.set(attr, old)

    def test_close_requires_chat_without_sheet(self):
        with self.assertRaisesRegex(AssertionError, 'remains open'):
            self.check('closed')
        self.window.remove(self.sheet)
        self.node(self.window, text='Message verify-session')
        self.node(self.window, **{'content-desc': 'Session actions'})
        self.node(self.window, **{'content-desc': 'Expand table'})
        table = self.node(self.window, **{'resource-id': 'md-table', 'bounds': '[20,180][380,720]'})
        self.node(table, text='R24-C06 harvest', bounds='[40,600][210,630]')
        self.assertEqual(self.check('closed', hidden='')['state'], 'closed')
        self.window.remove(self.window[0])
        with self.assertRaisesRegex(AssertionError, 'Chat did not return'):
            self.check('closed', hidden='')

    def test_swipes_stay_inside_the_table_and_use_the_requested_axis(self):
        x1, y1, x2, y2 = ui.swipe(self.root, 'sideways')
        self.assertTrue(20 < x2 < x1 < 380)
        self.assertEqual(y1, y2)
        x1, y1, x2, y2 = ui.swipe(self.root, 'down')
        self.assertEqual(x1, x2)
        self.assertTrue(180 < y2 < y1 < 720)
        self.viewport.set('scrollable', 'false')
        with self.assertRaisesRegex(AssertionError, 'not scrollable'):
            ui.swipe(self.root, 'down')


if __name__ == '__main__':
    unittest.main()
