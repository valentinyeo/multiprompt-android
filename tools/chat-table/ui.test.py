import json
import os
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

MPA = Path(__file__).resolve().parents[2] / '.claude/skills/verify-multiprompt-android/scripts/mpa'
PACKAGE = 'dev.multiprompt.companion.debug'


def run(*arguments):
    result = subprocess.run([sys.executable, '-B', str(MPA), 'chat-table', *arguments], capture_output=True, text=True)
    return result, json.loads(result.stdout)


def table_check(xml, png, state, target, hidden=''):
    result, payload = run('--xml', str(xml), '--png', str(png), '--state', state, '--target', target, '--hidden', hidden)
    if result.returncode != 0:
        raise AssertionError(payload['message'])
    return payload['result']


def table_scroll(xml, axis):
    result, payload = run('--xml', str(xml), '--scroll', axis)
    if result.returncode != 0:
        raise AssertionError(payload['message'])
    return tuple(payload['from'] + payload['to'])


class TableUiTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.xml = Path(self.temporary.name) / 'screen.xml'
        self.png = self.xml.with_suffix('.png')
        self.root = ET.Element('hierarchy')
        self.window = self.node(self.root, bounds='[0,0][400,800]')
        self.sheet = self.node(self.window, bounds='[10,100][390,750]')
        self.node(self.sheet, text='Garden harvest table')
        self.close = self.node(self.sheet, **{'content-desc': 'Close'})
        self.viewport = self.node(self.sheet, **{'bounds': '[20,180][380,720]', 'scrollable': 'true'})
        self.table = self.node(self.viewport, **{'class': 'android.widget.HorizontalScrollView', 'bounds': '[20,180][380,720]'})
        self.cell = self.node(self.table, text='R24-C06 harvest', bounds='[40,600][210,630]')
        self.hidden = self.node(self.table, text='R01-C06 harvest', bounds='[40,-400][210,-370]')
        self.image = Image.new('RGB', (400, 800), (16, 16, 16))
        ImageDraw.Draw(self.image).text((40, 605), 'R24-C06 harvest', fill=(242, 242, 242))

    def node(self, parent, **attrs):
        return ET.SubElement(parent, 'node', {'package': PACKAGE, **attrs})

    def check(self, state='down', target='R24-C06 harvest', hidden='R01-C06 harvest'):
        ET.ElementTree(self.root).write(self.xml)
        self.image.save(self.png)
        return table_check(self.xml, self.png, state, target, hidden)

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
        with self.assertRaisesRegex(AssertionError, 'outside the table'):
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
                                  (self.viewport, 'bounds', '[20,180][200,300]')]:
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
        table = self.node(self.window, **{'class': 'android.widget.HorizontalScrollView', 'bounds': '[20,180][380,720]'})
        self.node(table, text='R24-C06 harvest', bounds='[40,600][210,630]')
        self.assertEqual(self.check('closed', hidden='')['state'], 'closed')
        self.window.remove(self.window[0])
        with self.assertRaisesRegex(AssertionError, 'Chat did not return'):
            self.check('closed', hidden='')

    def test_swipes_stay_inside_the_table_and_use_the_requested_axis(self):
        self.image.save(self.png)
        ET.ElementTree(self.root).write(self.xml)
        x1, y1, x2, y2 = table_scroll(self.xml, 'sideways')
        self.assertTrue(20 < x2 < x1 < 380)
        self.assertEqual(y1, y2)
        x1, y1, x2, y2 = table_scroll(self.xml, 'down')
        self.assertEqual(x1, x2)
        self.assertTrue(180 < y2 < y1 < 720)
        self.viewport.set('scrollable', 'false')
        ET.ElementTree(self.root).write(self.xml)
        with self.assertRaisesRegex(AssertionError, 'no vertical scroll container'):
            table_scroll(self.xml, 'down')


class RetainedPhoneTest(unittest.TestCase):
    fixtures = Path(__file__).with_name('fixtures')
    root = Path(__file__).resolve().parents[2]

    def test_open_screen_has_description_only_close_and_no_exported_tags(self):
        xml = self.fixtures / 'e4386ee1-open.xml'
        root = ET.parse(xml).getroot()
        self.assertFalse(any(node.get('text') == 'Close' for node in root.iter('node')))
        self.assertFalse(any(node.get('resource-id', '').startswith('table-') for node in root.iter('node')))
        result = table_check(xml, xml.with_suffix('.png'), 'open', 'R01-C01 orchard', 'R01-C06 harvest')
        self.assertEqual(result['state'], 'open')

    def run_expand(self, xml):
        script = self.root / '.claude/skills/verify-multiprompt-android/scripts/chat-table-drive.sh'
        definitions, journey = script.read_text().split('tap_text verify-session\n', 1)
        expand = journey[journey.index("tap_desc 'Expand table'"):journey.index("table_reveal 'R01-C06 harvest'")]
        harness = """
set -euo pipefail
""" + definitions + """
tap_desc() { :; }
table_state() { printf 'TABLE_OPEN_CAPTURED\\n'; }
wait_for_text() { python3 "$SHARED_UI" "$REPLAY_XML" contains "$1" >/dev/null; }
select_node() { python3 "$SHARED_UI" "$REPLAY_XML" "$@" >/dev/null; }
sleep() { :; }
""" + expand
        return subprocess.run(['bash', '-c', harness], text=True, capture_output=True,
                              env={**os.environ, 'REPLAY_XML': str(xml),
                                   'SHARED_UI': str(self.root / '.claude/skills/verify-multiprompt-android/scripts/ui.py')})

    def test_expand_handshake_accepts_the_real_close_description(self):
        result = self.run_expand(self.fixtures / 'e4386ee1-open.xml')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('TABLE_OPEN_CAPTURED', result.stdout)

    def test_expand_handshake_rejects_close_text_without_the_control(self):
        root = ET.parse(self.fixtures / 'e4386ee1-open.xml').getroot()
        for node in root.iter('node'):
            if node.get('content-desc') == 'Close':
                node.set('content-desc', '')
                node.set('text', 'Close')
        with tempfile.TemporaryDirectory() as directory:
            xml = Path(directory) / 'missing-close.xml'
            ET.ElementTree(root).write(xml)
            result = self.run_expand(xml)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('TABLE_OPEN_CAPTURED', result.stdout)


if __name__ == '__main__':
    unittest.main()
