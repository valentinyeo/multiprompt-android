#!/usr/bin/env python3
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT = Path(__file__).with_name("draft-label.py")
PACKAGE = "dev.multiprompt.companion.debug"


def hierarchy(draft=None, preview_bounds="[150,150][700,180]"):
    root = ET.Element("hierarchy")
    window = ET.SubElement(root, "node", {"package": PACKAGE, "class": "android.widget.FrameLayout"})
    row = ET.SubElement(window, "node", {
        "package": PACKAGE,
        "clickable": "true",
        "bounds": "[0,100][1080,220]",
    })
    ET.SubElement(row, "node", {
        "package": PACKAGE,
        "text": "verify-session",
        "bounds": "[80,110][400,140]",
    })
    if draft is not None:
        ET.SubElement(row, "node", {"package": PACKAGE, "text": "Draft", "bounds": draft})
    ET.SubElement(row, "node", {
        "package": PACKAGE,
        "text": "Inbox preview stays.",
        "bounds": preview_bounds,
    })
    ET.SubElement(row, "node", {"package": PACKAGE, "text": "Now", "bounds": "[900,140][980,170]"})
    return root


class DraftLabelTest(unittest.TestCase):
    def run_mode(self, mode, root):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ui.xml"
            ET.ElementTree(root).write(path, encoding="utf-8")
            return subprocess.run([sys.executable, str(SCRIPT), mode, str(path)], capture_output=True, text=True)

    def test_draft_before_preview(self):
        result = self.run_mode("present", hierarchy("[80,150][140,180]"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("DRAFT_BEFORE_PREVIEW", result.stdout)

    def test_draft_after_preview_fails(self):
        result = self.run_mode("present", hierarchy("[720,150][800,180]"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not before the preview", result.stderr)

    def test_missing_draft_fails_present(self):
        result = self.run_mode("present", hierarchy(None))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected one Draft label", result.stderr)

    def test_absent_keeps_preview(self):
        result = self.run_mode("absent", hierarchy(None))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("DRAFT_LABEL_GONE", result.stdout)

    def test_visible_draft_fails_absent(self):
        result = self.run_mode("absent", hierarchy("[80,150][140,180]"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Draft label still visible", result.stderr)


if __name__ == "__main__":
    unittest.main()
