#!/usr/bin/env python3
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT = Path(__file__).with_name("row-preview.py")
PACKAGE = "dev.multiprompt.companion.debug"


def hierarchy(question=None, step=None, pane=None, stored=None, extra=None):
    root = ET.Element("hierarchy")
    window = ET.SubElement(root, "node", {"package": PACKAGE, "class": "android.widget.FrameLayout"})

    def row(name, preview):
        node = ET.SubElement(window, "node", {
            "package": PACKAGE,
            "clickable": "true",
            "bounds": "[0,100][1080,220]",
        })
        ET.SubElement(node, "node", {"package": PACKAGE, "text": name, "bounds": "[80,110][400,140]"})
        if preview is not None:
            ET.SubElement(node, "node", {"package": PACKAGE, "text": preview, "bounds": "[80,150][700,180]"})
        for text in extra or ():
            if text[0] == name:
                ET.SubElement(node, "node", {"package": PACKAGE, "text": text[1], "bounds": "[80,180][700,200]"})
        return node

    row("verify-session", question if question is not None else pane)
    row("live-rowstep", step if step is not None else stored)
    return root


class RowPreviewTest(unittest.TestCase):
    def run_mode(self, mode, root):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ui.xml"
            ET.ElementTree(root).write(path, encoding="utf-8")
            return subprocess.run([sys.executable, str(SCRIPT), mode, str(path)], capture_output=True, text=True)

    def test_question_replaces_pane_reply(self):
        result = self.run_mode("question", hierarchy(question="Which target should ship?", step="Rewrite the inbox row"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("ROW_QUESTION_SHOWN", result.stdout)

    def test_pane_reply_fails_question(self):
        result = self.run_mode("question", hierarchy(pane="Pane reply stays.", step="Rewrite the inbox row"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected one 'Which target should ship?'", result.stderr)

    def test_pane_reply_beside_question_fails(self):
        result = self.run_mode("question", hierarchy(
            question="Which target should ship?",
            step="Rewrite the inbox row",
            extra=(("verify-session", "Pane reply stays."),),
        ))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Pane reply stays.", result.stderr)

    def test_step_replaces_stored_reply(self):
        result = self.run_mode("step", hierarchy(question="Which target should ship?", step="Rewrite the inbox row"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("ROW_STEP_SHOWN", result.stdout)

    def test_stored_reply_fails_step(self):
        result = self.run_mode("step", hierarchy(question="Which target should ship?", stored="Stored reply stays off the row."))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Rewrite the inbox row", result.stderr)

    def test_question_on_the_step_row_fails(self):
        result = self.run_mode("step", hierarchy(
            question="Which target should ship?",
            step="Rewrite the inbox row",
            extra=(("live-rowstep", "Which target should ship?"),),
        ))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("still on the live-rowstep row", result.stderr)


if __name__ == "__main__":
    unittest.main()
