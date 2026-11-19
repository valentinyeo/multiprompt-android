import json
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

MPA = Path(__file__).resolve().parents[2] / '.claude/skills/verify-multiprompt-android/scripts/mpa'


def run(*arguments):
    result = subprocess.run([sys.executable, '-B', str(MPA), 'skill-pills', *arguments], capture_output=True, text=True)
    return result, json.loads(result.stdout)


def pills_check(xml, png, phase, text):
    result, payload = run('--xml', str(xml), '--png', str(png), '--phase', phase, '--text', text)
    if result.returncode != 0:
        raise AssertionError(payload['message'])
    return payload['result']


def pills_scroll(xml):
    result, payload = run('--xml', str(xml), '--scroll')
    if result.returncode != 0:
        raise AssertionError(payload['message'])
    return tuple(payload['from'] + payload['to'])


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
        return pills_check(self.xml, self.png, phase, text)

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

    def test_real_sent_pill_extends_beyond_its_accessibility_text_bounds(self):
        fixture = Path(__file__).with_name('fixtures') / 'a4470175-sent.xml'
        result = pills_check(fixture, fixture.with_suffix('.png'), 'sent', 'Please /review-native this')
        self.assertEqual(result['sent_pill'], '/review-native')
        image = Image.open(fixture.with_suffix('.png')).convert('RGB')
        x1, y1, x2, y2 = result['sent_pill_bounds']
        for y in range(y1, y2):
            for x in range(x1, x2):
                if all(abs(channel - 46) <= 2 for channel in image.getpixel((x, y))):
                    image.putpixel((x, y), (31, 31, 31))
        image.save(self.png)
        with self.assertRaisesRegex(AssertionError, 'outline'):
            pills_check(fixture, self.png, 'sent', 'Please /review-native this')

    def test_scroll_uses_the_skill_picker_when_the_chat_also_scrolls(self):
        window = self.node(bounds='[0,0][400,400]')
        self.node(parent=window, bounds='[0,0][400,400]', scrollable='true')
        picker = self.node(parent=window, bounds='[10,80][390,180]', scrollable='true',
                           **{'class': 'android.widget.ScrollView'})
        self.node(parent=picker, text='/pill-guide-01', bounds='[20,90][200,110]')
        ET.ElementTree(self.root).write(self.xml)
        self.assertEqual(pills_scroll(self.xml), (200, 160, 200, 100))
        picker.set('enabled', 'false')
        ET.ElementTree(self.root).write(self.xml)
        with self.assertRaises(AssertionError):
            pills_scroll(self.xml)
        picker.set('enabled', 'true')
        picker.set('bounds', '[10,80][410,180]')
        ET.ElementTree(self.root).write(self.xml)
        with self.assertRaisesRegex(AssertionError, 'clipped'):
            pills_scroll(self.xml)

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
