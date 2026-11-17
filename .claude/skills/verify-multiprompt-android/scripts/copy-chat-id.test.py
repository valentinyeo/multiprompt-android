#!/usr/bin/env python3
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT = Path(__file__).with_name("copy-chat-id.py")
PACKAGE = "dev.multiprompt.companion.debug"
HOSTED = "copy-chat-id-2371"


def hierarchy(editor="", extra=""):
    root = ET.Element("hierarchy")
    window = ET.SubElement(root, "node", {"package": PACKAGE, "class": "android.widget.FrameLayout"})
    ET.SubElement(window, "node", {
        "package": PACKAGE,
        "text": "verify-session",
        "bounds": "[80,40][400,80]",
    })
    if editor is not None:
        ET.SubElement(window, "node", {
            "package": PACKAGE,
            "class": "android.widget.EditText",
            "text": editor,
            "focused": "true",
            "bounds": "[40,1600][1000,1700]",
        })
    if extra:
        ET.SubElement(window, "node", {"package": PACKAGE, "text": extra, "bounds": "[40,200][400,260]"})
    return root


class CopyChatIdTest(unittest.TestCase):
    def run_mode(self, mode, root, chat_id=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ui.xml"
            ET.ElementTree(root).write(path, encoding="utf-8")
            command = [sys.executable, str(SCRIPT), mode, str(path)]
            if chat_id is not None:
                command.append(chat_id)
            return subprocess.run(command, capture_output=True, text=True)

    def test_hosted_id_is_hidden_before_paste(self):
        result = self.run_mode("hidden", hierarchy(), HOSTED)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("COPY_CHAT_ID_HIDDEN", result.stdout)

    def test_visible_id_fails_hidden(self):
        result = self.run_mode("hidden", hierarchy(extra=HOSTED), HOSTED)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("already on screen", result.stderr)

    def test_empty_composer(self):
        result = self.run_mode("empty", hierarchy())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("COPY_CHAT_ID_EMPTY", result.stdout)

    def test_filled_composer_fails_empty(self):
        result = self.run_mode("empty", hierarchy(editor=HOSTED))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("composer is not empty", result.stderr)

    def test_pasted_hosted_id(self):
        result = self.run_mode("pasted", hierarchy(editor=HOSTED), HOSTED)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("COPY_CHAT_ID_PASTED", result.stdout)

    def test_title_is_not_a_pasted_id(self):
        result = self.run_mode("pasted", hierarchy(editor="verify-session"), HOSTED)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected", result.stderr)

    def test_title_text_does_not_count_as_the_composer(self):
        result = self.run_mode("pasted", hierarchy(editor="", extra=HOSTED), HOSTED)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected", result.stderr)

    def test_missing_app_ui(self):
        root = ET.Element("hierarchy")
        ET.SubElement(root, "node", {"package": "com.android.systemui", "text": HOSTED})
        result = self.run_mode("hidden", root, HOSTED)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("App UI is missing", result.stderr)

    def test_two_composers_fail(self):
        root = hierarchy(editor=HOSTED)
        window = next(root.iter("node"))
        ET.SubElement(window, "node", {
            "package": PACKAGE,
            "class": "android.widget.EditText",
            "text": "other",
        })
        result = self.run_mode("pasted", root, HOSTED)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected one composer", result.stderr)


if __name__ == "__main__":
    unittest.main()
