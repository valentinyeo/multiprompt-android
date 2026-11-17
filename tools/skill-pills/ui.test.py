import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

spec = importlib.util.spec_from_file_location('pills_ui', Path(__file__).with_name('ui.py'))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


class SkillPillsUiTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.xml = Path(self.temporary.name) / 'screen.xml'
        self.png = self.xml.with_suffix('.png')
        self.root = ET.Element('hierarchy')
        self.editor = self.node(text='Please /review-native this', bounds='[10,200][390,280]',
                                **{'class': 'android.widget.EditText'})
        self.image = Image.new('RGB', (400, 400), (31, 31, 31))
        ImageDraw.Draw(self.image).rounded_rectangle((10, 200, 389, 279), radius=25, outline=(46, 46, 46))

    def node(self, parent=None, **attrs):
        return ET.SubElement(self.root if parent is None else parent, 'node',
                             {'package': 'dev.multiprompt.companion.debug', **attrs})

    def pill(self, box=(120, 220, 280, 259)):
        ImageDraw.Draw(self.image).rounded_rectangle(box, radius=16, outline=(46, 46, 46), fill=(31, 31, 31))

    def check(self, phase='inserted', text='Please /review-native this'):
        ET.ElementTree(self.root).write(self.xml)
        self.image.save(self.png)
        return ui.check(self.xml, self.png, phase, text)

    def test_composer_requires_painted_pill_not_just_slash_text_or_field_border(self):
        with self.assertRaisesRegex(AssertionError, 'outline'):
            self.check()
        self.pill()
        self.assertTrue(self.check()['composer_pill'])

    def test_removal_requires_exact_remaining_text_and_no_outline(self):
        self.editor.set('text', 'Keep this')
        self.assertFalse(self.check('removed', 'Keep this')['composer_pill'])
        self.pill()
        with self.assertRaisesRegex(AssertionError, 'outline'):
            self.check('removed', 'Keep this')
        with self.assertRaisesRegex(AssertionError, 'Wrong composer'):
            self.check('removed', 'Keep /review-native this')

    def test_sent_pill_must_belong_to_the_exact_message_and_be_painted(self):
        self.editor.set('text', 'Message verify-session')
        message = self.node(text='Please /review-native this', bounds='[20,40][380,120]')
        pill = self.node(parent=message, text='/review-native', bounds='[120,50][281,90]')
        with self.assertRaisesRegex(AssertionError, 'outline'):
            self.check('sent')
        self.pill((120, 50, 280, 89))
        self.assertEqual(self.check('sent')['sent_pill'], '/review-native')
        message.remove(pill)
        self.root.append(pill)
        with self.assertRaisesRegex(AssertionError, 'inline skill pill'):
            self.check('sent')

    def test_wrong_draft_missing_picker_and_uncleared_send_fail(self):
        self.pill()
        with self.assertRaisesRegex(AssertionError, 'Wrong composer'):
            self.check('draft', 'Please this')
        with self.assertRaisesRegex(AssertionError, 'picker'):
            self.check('picker')
        with self.assertRaisesRegex(AssertionError, 'clear'):
            self.check('sent')


if __name__ == '__main__':
    unittest.main()
