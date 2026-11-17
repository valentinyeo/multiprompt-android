import unittest
from pathlib import Path
from PIL import Image, ImageDraw, ImageChops
from scroll import displacement


class ScrollTest(unittest.TestCase):
    def setUp(self):
        self.strip = Image.new('RGB', (1800, 60), 'white')
        draw = ImageDraw.Draw(self.strip)
        for i in range(100):
            x = i * 17
            draw.rectangle((x, 10 + i % 13, x + 5 + i % 7, 50 - i % 11), fill='black')

    def frame(self, offset):
        return self.strip.crop((offset, 0, offset + 850, 60))

    def test_both_directions_with_residual_offset(self):
        self.assertEqual(-600, displacement(self.frame(0), self.frame(600)))
        self.assertEqual(560, displacement(self.frame(600), self.frame(40)))

    def test_stationary_and_wrong_direction_are_not_return_scroll(self):
        self.assertEqual(0, displacement(self.frame(600), self.frame(600)))
        self.assertLess(displacement(self.frame(600), self.frame(700)), 0)


    def test_real_reverse_scroll_that_pixel_restoration_rejected(self):
        fixtures = Path(__file__).parent / 'fixtures'
        initial, left, right = [Image.open(fixtures / (name + '.png')).convert('RGB')
                                for name in ('gestures-before', 'code-scrolled-left', 'code-scrolled-right')]
        changed = sum(max(p) > 32 for p in ImageChops.difference(initial, left).getdata())
        restored = sum(max(p) > 32 for p in ImageChops.difference(initial, right).getdata())
        self.assertGreaterEqual(restored, changed / 2)
        self.assertLess(displacement(initial, left), -20)
        self.assertGreater(displacement(left, right), 20)
        self.assertEqual(0, displacement(left, left))
        self.assertLess(displacement(right, left), -20)


if __name__ == '__main__':
    unittest.main()
