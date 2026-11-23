import ast
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

spec = importlib.util.spec_from_file_location('search_ui', Path(__file__).with_name('ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


class SearchUiTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.xml = Path(self.temporary.name) / 'screen.xml'
        self.png = self.xml.with_suffix('.png')
        self.root = ET.Element('hierarchy')
        self.node(text='orchard', **{'class': 'android.widget.EditText'})
        self.node(text='2 of 2')
        for label in ('Close search', 'Previous match', 'Next match'):
            self.node(**{'content-desc': label})
        self.target = self.node(text='orchard south', bounds='[20,100][180,130]')
        self.image = Image.new('RGB', (240, 400), (16, 16, 16))
        ImageDraw.Draw(self.image).text((20, 100), 'orchard south', fill=(242, 242, 242))

    def node(self, **attrs):
        return ET.SubElement(self.root, 'node', {'package': 'dev.multiprompt.companion.debug', **attrs})

    def check(self, query='orchard', count='2 of 2', target='orchard south'):
        ET.ElementTree(self.root).write(self.xml)
        self.image.save(self.png)
        return ui.check(self.xml, self.png, query, count, target)

    def highlight(self):
        ImageDraw.Draw(self.image).rectangle((20, 100, 109, 125), fill=(242, 242, 242))
        ImageDraw.Draw(self.image).text((20, 100), 'orchard', fill=(16, 16, 16))

    def test_highlighted_destination_passes(self):
        self.highlight()
        result = self.check()
        self.assertEqual(result['target'], 'orchard south')
        self.assertGreater(result['highlight_fraction'], 0.22)

    def test_light_mode_highlighted_destination_passes_and_plain_text_fails(self):
        self.image = Image.new('RGB', (240, 400), (247, 247, 247))
        ImageDraw.Draw(self.image).text((20, 100), 'orchard south', fill=(28, 28, 28))
        with self.assertRaisesRegex(AssertionError, 'highlight is missing'):
            self.check()
        ImageDraw.Draw(self.image).rectangle((20, 100, 109, 125), fill=(28, 28, 28))
        ImageDraw.Draw(self.image).text((20, 100), 'orchard', fill=(255, 255, 255))
        result = self.check()
        self.assertEqual(result['target'], 'orchard south')
        self.assertGreater(result['highlight_fraction'], 0.22)

    def test_plain_text_is_not_a_highlight(self):
        with self.assertRaisesRegex(AssertionError, 'highlight is missing'):
            self.check()

    def test_count_query_controls_and_destination_are_required(self):
        self.highlight()
        for node, attr, wrong in ((self.root[1], 'text', '1 of 2'),
                                  (self.root[0], 'text', 'wrong'),
                                  (self.root[2], 'content-desc', 'wrong'),
                                  (self.target, 'text', 'wrong'),
                                  (self.target, 'bounds', '[20,390][180,420]')):
            with self.subTest(attr=attr, wrong=wrong):
                old = node.get(attr)
                node.set(attr, wrong)
                with self.assertRaises(AssertionError):
                    self.check()
                node.set(attr, old)

    def test_zero_matches_needs_the_typed_query(self):
        self.root[0].set('text', 'NO_MATCH_2540')
        self.root[1].set('text', '0 of 0')
        self.check('NO_MATCH_2540', '0 of 0', '')
        with self.assertRaises(AssertionError):
            self.check('orchard', '0 of 0', '')

    def test_closed_requires_restored_chat_and_no_search_controls(self):
        self.node(**{'content-desc': 'Session actions'})
        self.node(text='Message verify-session')
        with self.assertRaises(AssertionError):
            self.check('', 'closed', '')
        self.root.remove(self.root[2])
        self.check('', 'closed', '')

    def test_fixture_is_valid_python_without_running_it(self):
        ast.parse(Path(__file__).with_name('console.py').read_text())


if __name__ == '__main__':
    unittest.main()
