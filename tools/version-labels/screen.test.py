#!/usr/bin/env python3
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import screen


VERSION = "0.1.2-verify.1-debug"


def ui(*texts):
    label = f'<node text="{VERSION}" content-desc="Version {VERSION}, open release notes"/>'
    body = "".join(f'<node text="{text}"/>' for text in texts)
    return ET.fromstring(f"<hierarchy>{label}{body}</hierarchy>")


class VersionScreenTest(unittest.TestCase):
    def test_one_label_and_the_update_title(self):
        result = screen.check(ui("App updates", f"Installed: {VERSION} (12)"), VERSION, ["App updates"])
        self.assertEqual(1, result["labels"])

    def test_two_labels_fail(self):
        root = ui()
        root.append(ET.fromstring(
            f'<node text="{VERSION}" content-desc="Version {VERSION}, open release notes"/>'
        ))
        with self.assertRaisesRegex(SystemExit, "expected one version label"):
            screen.check(root, VERSION, [])


if __name__ == "__main__":
    unittest.main()
