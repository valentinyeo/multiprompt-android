#!/usr/bin/env python3
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT = Path(__file__).with_name("dictation-cancel.py")
PACKAGE = "dev.multiprompt.companion.debug"


def hierarchy(cancel=False, start=False, stop=False, placeholder=False, field=""):
    root = ET.Element("hierarchy")
    window = ET.SubElement(root, "node", {"package": PACKAGE, "class": "android.widget.FrameLayout"})
    if cancel:
        ET.SubElement(window, "node", {"package": PACKAGE, "content-desc": "Cancel dictation", "clickable": "true"})
    if start:
        ET.SubElement(window, "node", {"package": PACKAGE, "content-desc": "Start dictation", "clickable": "true"})
    if stop:
        ET.SubElement(window, "node", {"package": PACKAGE, "content-desc": "Stop dictation", "clickable": "true"})
    if placeholder:
        ET.SubElement(window, "node", {"package": PACKAGE, "text": "Message verify-session"})
    ET.SubElement(window, "node", {
        "package": PACKAGE,
        "class": "android.widget.EditText",
        "text": field,
        "bounds": "[0,700][900,780]",
    })
    return root


class DictationCancelTest(unittest.TestCase):
    def run_mode(self, mode, root):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ui.xml"
            ET.ElementTree(root).write(path, encoding="utf-8")
            return subprocess.run([sys.executable, str(SCRIPT), mode, str(path)], capture_output=True, text=True)

    def test_bar_shows_cancel_without_composer(self):
        result = self.run_mode("bar", hierarchy(cancel=True, stop=True))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("DICTATION_CANCEL_VISIBLE", result.stdout)

    def test_bar_rejects_visible_placeholder(self):
        result = self.run_mode("bar", hierarchy(cancel=True, stop=True, placeholder=True))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("composer still visible", result.stderr)

    def test_empty_after_cancel(self):
        result = self.run_mode("empty", hierarchy(start=True, placeholder=True))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("DICTATION_COMPOSER_EMPTY", result.stdout)

    def test_empty_rejects_leftover_transcript(self):
        result = self.run_mode("empty", hierarchy(start=True, placeholder=True, field="spoken words"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not empty after cancel", result.stderr)

    def test_empty_rejects_cancel_still_visible(self):
        result = self.run_mode("empty", hierarchy(cancel=True, start=True, placeholder=True))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not empty after cancel", result.stderr)


if __name__ == "__main__":
    unittest.main()
