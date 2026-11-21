import unittest
import xml.etree.ElementTree as ET

from reveal import list_bounds, reveal


def screen(*rows, scrollable=True, extra=''):
    body = ''.join(f'<node text="{row}" bounds="[0,{100 + index * 120}][1080,{200 + index * 120}]" />'
                   for index, row in enumerate(rows))
    attribute = 'true' if scrollable else 'false'
    return ET.fromstring(f'<hierarchy><node scrollable="{attribute}" bounds="[0,0][1080,1920]">{body}{extra}</node></hierarchy>')


class RevealTest(unittest.TestCase):
    def dumps(self, *frames):
        frames = list(frames)

        def dump():
            return frames.pop(0) if len(frames) > 1 else frames[0]
        return dump

    def test_visible_row_needs_no_scroll(self):
        swipes = []
        root = reveal(self.dumps(screen('verify-session', 'verify-other')), swipes.append, 'verify-other')
        self.assertTrue(any(node.get('text') == 'verify-other' for node in root.iter('node')))
        self.assertEqual([], swipes)

    def test_offscreen_row_scrolls_into_view(self):
        swipes = []
        dump = self.dumps(screen('verify-session'), screen('pin-00', 'verify-other'))
        root = reveal(dump, swipes.append, 'verify-other')
        self.assertEqual([(0, 0, 1080, 1920)], swipes)
        self.assertTrue(any(node.get('text') == 'verify-other' for node in root.iter('node')))

    def test_missing_row_names_the_target_and_the_scroll_count(self):
        swipes = []
        with self.assertRaisesRegex(AssertionError, 'Missing verify-other after 2 scrolls'):
            reveal(self.dumps(screen('verify-session')), swipes.append, 'verify-other', limit=2)
        self.assertEqual(2, len(swipes))

    def test_prefix_does_not_count_as_the_row(self):
        with self.assertRaisesRegex(AssertionError, 'Missing verify-other'):
            reveal(self.dumps(screen('verify-session', 'verify-other-2')), lambda bounds: None, 'verify-other', limit=0)

    def test_ambiguous_lists_are_refused(self):
        with self.assertRaisesRegex(AssertionError, 'Expected one scrollable list, found 2'):
            list_bounds(ET.fromstring('<hierarchy><node scrollable="true" bounds="[0,0][10,10]" />'
                                      '<node scrollable="true" bounds="[0,20][10,30]" /></hierarchy>'))

    def test_page_without_a_list_is_refused(self):
        with self.assertRaisesRegex(AssertionError, 'Expected one scrollable list, found 0'):
            list_bounds(screen('verify-session', scrollable=False))


if __name__ == '__main__':
    unittest.main()
