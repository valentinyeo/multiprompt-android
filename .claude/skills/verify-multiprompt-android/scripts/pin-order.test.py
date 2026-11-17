#!/usr/bin/env python3
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT = Path(__file__).with_name("pin-order.py")
PACKAGE = "dev.multiprompt.companion.debug"


def hierarchy(session_bounds, other_bounds):
    root = ET.Element("hierarchy")
    window = ET.SubElement(root, "node", {"package": PACKAGE, "class": "android.widget.FrameLayout"})
    for name, rect in (("verify-session", session_bounds), ("verify-other", other_bounds)):
        ET.SubElement(window, "node", {"package": PACKAGE, "text": name, "bounds": rect})
    return root


class PinOrderTest(unittest.TestCase):
    def run_script(self, root, mode, *rest):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ui.xml"
            ET.ElementTree(root).write(path, encoding="utf-8")
            return subprocess.run([sys.executable, str(SCRIPT), mode, str(path), *rest], capture_output=True, text=True)

    def test_order_reads_visual_top_then_bottom(self):
        result = self.run_script(
            hierarchy("[40,400][600,460]", "[40,200][600,260]"),
            "order",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "top verify-other bottom verify-session")

    def test_above_requires_the_named_chat_on_top(self):
        above = self.run_script(
            hierarchy("[40,200][600,260]", "[40,400][600,460]"),
            "above",
            "verify-session",
        )
        self.assertEqual(above.returncode, 0, above.stderr)
        self.assertIn("PINNED_ABOVE verify-session", above.stdout)
        below = self.run_script(
            hierarchy("[40,400][600,460]", "[40,200][600,260]"),
            "above",
            "verify-session",
        )
        self.assertNotEqual(below.returncode, 0)
        self.assertIn("is not above the other chat", below.stderr)

    def test_overlapping_rows_fail(self):
        result = self.run_script(
            hierarchy("[40,200][600,320]", "[40,280][600,400]"),
            "order",
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("inbox rows overlap", result.stderr)

    def test_same_checks_both_positions(self):
        root = hierarchy("[40,400][600,460]", "[40,200][600,260]")
        restored = self.run_script(root, "same", "verify-other", "verify-session")
        self.assertEqual(restored.returncode, 0, restored.stderr)
        self.assertIn("PIN_ORDER_RESTORED", restored.stdout)
        swapped = self.run_script(root, "same", "verify-session", "verify-other")
        self.assertNotEqual(swapped.returncode, 0)
        self.assertIn("expected verify-session then verify-other", swapped.stderr)

    def test_missing_row_fails(self):
        root = ET.Element("hierarchy")
        window = ET.SubElement(root, "node", {"package": PACKAGE})
        ET.SubElement(window, "node", {"package": PACKAGE, "text": "verify-session", "bounds": "[40,200][600,260]"})
        result = self.run_script(root, "order")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected one verify-other row, found 0", result.stderr)


if __name__ == "__main__":
    unittest.main()
