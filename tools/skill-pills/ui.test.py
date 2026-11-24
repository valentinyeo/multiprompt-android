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

    def check(self, phase='inserted', text='Please /review-native this', pill='/review-native'):
        ET.ElementTree(self.root).write(self.xml)
        self.image.save(self.png)
        return ui.check(self.xml, self.png, phase, text, pill)

    def test_composer_requires_painted_pill_not_just_slash_text_or_field_border(self):
        with self.assertRaisesRegex(AssertionError, 'outline'):
            self.check()
        self.pill()
        self.assertTrue(self.check()['composer_pill'])

    def test_composer_detects_light_outline_in_day_mode(self):
        self.image = Image.new('RGB', (400, 400), (244, 244, 244))
        ImageDraw.Draw(self.image).rounded_rectangle((10, 200, 389, 279), radius=25, outline=(212, 212, 212))
        with self.assertRaisesRegex(AssertionError, 'outline'):
            self.check()
        ImageDraw.Draw(self.image).rounded_rectangle((120, 220, 280, 259), radius=16, outline=(212, 212, 212), fill=(244, 244, 244))
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
        ImageDraw.Draw(self.image).rectangle((20, 40, 379, 119), fill=(233, 233, 233))
        with self.assertRaisesRegex(AssertionError, 'pill'):
            self.check('sent')
        self.pill((120, 50, 280, 89))
        self.assertEqual(self.check('sent')['sent_pill'], '/review-native')
        message.remove(pill)
        self.root.append(pill)
        with self.assertRaisesRegex(AssertionError, 'inline skill pill'):
            self.check('sent')

    def test_real_sent_pill_fill_survives_outline_colors_but_not_unpainted_slash_text(self):
        for name in ('a4470175', '4600e0e1'):
            with self.subTest(commit=name):
                fixture = Path(__file__).with_name('fixtures') / f'{name}-sent.xml'
                result = ui.check(fixture, fixture.with_suffix('.png'), 'sent', 'Please /review-native this')
                self.assertEqual(result['sent_pill'], '/review-native')
                image = Image.open(fixture.with_suffix('.png')).convert('RGB')
                x1, y1, x2, y2 = result['sent_pill_bounds']
                ImageDraw.Draw(image).rectangle((x1, y1, x2 - 1, y2 - 1), fill=tuple(result['sent_bubble_fill']))
                ImageDraw.Draw(image).text((x1 + 20, y1 + 5), '/review-native', fill=(20, 20, 20))
                image.save(self.png)
                with self.assertRaisesRegex(AssertionError, 'pill'):
                    ui.check(fixture, self.png, 'sent', 'Please /review-native this')

    def test_real_draft_with_dictation_button_has_no_composer_pill(self):
        fixture = Path(__file__).with_name('fixtures') / 'soft3030-composer-draft.xml'
        result = ui.check(fixture, fixture.with_suffix('.png'), 'draft', 'Please this')
        self.assertFalse(result['composer_pill'])

    def test_scroll_uses_the_skill_picker_when_the_chat_also_scrolls(self):
        window = self.node(bounds='[0,0][400,400]')
        self.node(parent=window, bounds='[0,0][400,400]', scrollable='true')
        picker = self.node(parent=window, bounds='[10,80][390,180]', scrollable='true',
                           **{'class': 'android.widget.ScrollView'})
        self.node(parent=picker, text='/pill-guide-01', bounds='[20,90][200,110]')
        ET.ElementTree(self.root).write(self.xml)
        self.assertEqual(ui.picker_swipe(self.xml), (200, 160, 200, 100))
        picker.set('enabled', 'false')
        ET.ElementTree(self.root).write(self.xml)
        with self.assertRaises(AssertionError):
            ui.picker_swipe(self.xml)
        picker.set('enabled', 'true')
        picker.set('bounds', '[10,80][410,180]')
        ET.ElementTree(self.root).write(self.xml)
        with self.assertRaisesRegex(AssertionError, 'clipped'):
            ui.picker_swipe(self.xml)

    def test_a_sent_file_path_must_not_render_an_inline_pill(self):
        self.editor.set('text', 'Message verify-session')
        message = self.node(text='Please /home/verifyssh/notes this', bounds='[20,40][380,120]')
        self.assertEqual(self.check('sent-path', 'Please /home/verifyssh/notes this')['sent_pill'], None)
        self.node(parent=message, text='/home/verifyssh/notes', bounds='[120,50][281,90]')
        with self.assertRaisesRegex(AssertionError, 'file path'):
            self.check('sent-path', 'Please /home/verifyssh/notes this')
        message.remove(message.find('node'))
        with self.assertRaisesRegex(AssertionError, 'Exact sent message'):
            self.check('sent-path', 'Please /home/verifyssh')

    def test_a_built_in_command_pill_is_checked_as_a_painted_pill(self):
        self.editor.set('text', 'Message verify-session')
        message = self.node(text='Keep /clear this', bounds='[20,40][380,120]')
        self.node(parent=message, text='/clear', bounds='[120,50][281,90]')
        ImageDraw.Draw(self.image).rectangle((20, 40, 379, 119), fill=(233, 233, 233))
        with self.assertRaisesRegex(AssertionError, 'fill'):
            self.check('sent-command', 'Keep /clear this', '/clear')
        self.pill((120, 50, 280, 89))
        self.assertEqual(self.check('sent-command', 'Keep /clear this', '/clear')['sent_pill'], '/clear')
        with self.assertRaisesRegex(AssertionError, 'file path'):
            self.check('sent-path', 'Keep /clear this', '/clear')

    def test_a_mid_message_built_in_command_keeps_its_composer_outline(self):
        self.editor.set('text', 'Keep /clear this')
        with self.assertRaisesRegex(AssertionError, 'outline'):
            self.check('command', 'Keep /clear this')
        self.pill()
        self.assertTrue(self.check('command', 'Keep /clear this')['composer_pill'])

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
