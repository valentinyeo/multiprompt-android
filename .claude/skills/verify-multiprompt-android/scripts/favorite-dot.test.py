#!/usr/bin/env python3
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT = Path(__file__).with_name("favorite-dot.py")
PACKAGE = "dev.multiprompt.companion.debug"


def hierarchy(star_dot=False, keep=True, keep_dot=False, loose_dot=False, query_field=False, one_result=False):
    root = ET.Element("hierarchy")
    window = ET.SubElement(root, "node", {"package": PACKAGE, "class": "android.widget.FrameLayout"})
    if query_field:
        ET.SubElement(window, "node", {
            "package": PACKAGE,
            "class": "android.widget.EditText",
            "clickable": "true",
            "focused": "true",
            "text": "verify-session",
            "bounds": "[0,40][1080,96]",
        })
    if one_result:
        ET.SubElement(window, "node", {
            "package": PACKAGE,
            "class": "android.widget.TextView",
            "text": "1 result across all splits",
            "bounds": "[16,100][480,132]",
        })

    def row(name, dot):
        item = ET.SubElement(window, "node", {
            "package": PACKAGE,
            "clickable": "true",
            "bounds": "[0,100][1080,220]",
        })
        ET.SubElement(item, "node", {
            "package": PACKAGE,
            "text": name,
            "bounds": "[80,110][400,140]",
        })
        if dot:
            ET.SubElement(item, "node", {
                "package": PACKAGE,
                "content-desc": "Session favorite dot",
                "bounds": "[1000,120][1024,144]",
            })
        return item

    row("verify-session", star_dot)
    if keep:
        row("verify-keep", keep_dot)
    if loose_dot:
        ET.SubElement(window, "node", {
            "package": PACKAGE,
            "content-desc": "Session favorite dot",
            "bounds": "[0,0][10,10]",
        })
    return root


class FavoriteDotTest(unittest.TestCase):
    def run_mode(self, mode, root):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ui.xml"
            ET.ElementTree(root).write(path, encoding="utf-8")
            return subprocess.run([sys.executable, str(SCRIPT), mode, str(path)], capture_output=True, text=True)

    def test_starred_row_only(self):
        result = self.run_mode("starred", hierarchy(star_dot=True))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("FAVORITE_DOT_ON_STARRED", result.stdout)
        self.assertIn("Session favorite dot", result.stdout)

    def test_dot_on_plain_row_fails(self):
        result = self.run_mode("starred", hierarchy(star_dot=True, keep_dot=True))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unstarring chat still shows a yellow dot", result.stderr)

    def test_missing_dot_fails_starred(self):
        result = self.run_mode("starred", hierarchy(star_dot=False))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("starred chat has no yellow dot", result.stderr)

    def test_dot_outside_the_row_fails(self):
        result = self.run_mode("starred", hierarchy(star_dot=True, loose_dot=True))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("outside the starred chat", result.stderr)

    def test_search_hides_the_other_chat(self):
        result = self.run_mode("search", hierarchy(star_dot=True, keep=False, query_field=True, one_result=True))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("FAVORITE_DOT_IN_SEARCH", result.stdout)

    def test_search_requires_the_one_result_label(self):
        result = self.run_mode("search", hierarchy(star_dot=True, keep=False, query_field=True))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("search did not report one result", result.stderr)

    def test_search_with_both_chats_fails(self):
        result = self.run_mode("search", hierarchy(star_dot=True, keep=True))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("search still shows the unstarred chat", result.stderr)

    def test_plain_has_neither_dot(self):
        result = self.run_mode("plain", hierarchy(star_dot=False))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("FAVORITE_DOT_GONE", result.stdout)
        self.assertNotIn("Session favorite dot", result.stdout)

    def test_plain_rejects_a_dot(self):
        result = self.run_mode("plain", hierarchy(star_dot=True))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("yellow dot still visible", result.stderr)

    def test_favorites_hides_the_dot(self):
        result = self.run_mode("favorites", hierarchy(star_dot=False, keep=False))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("FAVORITE_DOT_HIDDEN_IN_FAVORITES", result.stdout)

    def test_favorites_rejects_a_dot(self):
        result = self.run_mode("favorites", hierarchy(star_dot=True, keep=False))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("yellow dot still visible", result.stderr)


if __name__ == "__main__":
    unittest.main()
