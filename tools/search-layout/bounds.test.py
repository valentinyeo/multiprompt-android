#!/usr/bin/env python3
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bounds


IME = "mInputShown=true\nInputMethod\n  mFrame=[0,1600][1080,2400]\n"
WINDOW = ""


def screen(row_top, row_bottom):
    return ET.fromstring(f"""
        <hierarchy>
          <node class="android.widget.EditText" text="pin-01" bounds="[40,200][1000,280]">
            <node text="Search all sessions" bounds="[48,208][420,248]"/>
          </node>
          <node content-desc="Robot pin-01" text="pin-01" bounds="[40,{row_top}][1000,{row_bottom}]"/>
        </hierarchy>
    """)


class SearchBoundsTest(unittest.TestCase):
    def test_row_sits_between_the_search_box_and_the_keyboard(self):
        result = bounds.check(screen(300, 420), IME, WINDOW, "pin-01", "Robot pin-01")
        self.assertEqual(True, result["aboveKeyboard"])
        self.assertLessEqual(result["fieldBottom"], result["rowTop"])
        self.assertLessEqual(result["rowBottom"], result["keyboardTop"])

    def test_row_under_the_keyboard_fails(self):
        with self.assertRaisesRegex(SystemExit, "above the keyboard"):
            bounds.check(screen(1700, 1820), IME, WINDOW, "pin-01", "Robot pin-01")

    def test_hidden_keyboard_fails(self):
        with self.assertRaisesRegex(SystemExit, "keyboard is hidden"):
            bounds.check(screen(300, 420), "mInputShown=false\n", WINDOW, "pin-01", "Robot pin-01")


if __name__ == "__main__":
    unittest.main()
