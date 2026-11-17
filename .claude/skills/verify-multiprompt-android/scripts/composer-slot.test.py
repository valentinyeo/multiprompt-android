#!/usr/bin/env python3
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT = Path(__file__).with_name("composer-slot.py")
PACKAGE = "dev.multiprompt.companion.debug"


def hierarchy(send=None, stop=None, stop_dictation=False):
    root = ET.Element("hierarchy")
    window = ET.SubElement(root, "node", {"package": PACKAGE, "class": "android.widget.FrameLayout"})
    if stop_dictation:
        ET.SubElement(window, "node", {
            "package": PACKAGE,
            "content-desc": "Stop dictation",
            "clickable": "true",
            "enabled": "true",
            "bounds": "[900,1700][980,1780]",
        })
    if send is not None:
        ET.SubElement(window, "node", {
            "package": PACKAGE,
            "content-desc": "Send prompt",
            "clickable": "true",
            "enabled": send,
            "bounds": "[980,1700][1060,1780]",
        })
    if stop is not None:
        parent = ET.SubElement(window, "node", {
            "package": PACKAGE,
            "clickable": "true",
            "enabled": stop,
            "bounds": "[980,1700][1060,1780]",
        })
        ET.SubElement(parent, "node", {
            "package": PACKAGE,
            "content-desc": "Stop",
            "bounds": "[990,1710][1050,1770]",
        })
    ET.SubElement(window, "node", {
        "package": "com.android.systemui",
        "content-desc": "Stop",
        "clickable": "true",
        "enabled": "true",
    })
    return root


class ComposerSlotTest(unittest.TestCase):
    def run_mode(self, mode, root=None, text=None, missing=False):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence"
            if not missing:
                if root is not None:
                    ET.ElementTree(root).write(path, encoding="utf-8")
                else:
                    path.write_text(text or "", encoding="utf-8")
            return subprocess.run([sys.executable, str(SCRIPT), mode, str(path)], capture_output=True, text=True)

    def test_stop_ignores_dictation_and_system_stop(self):
        result = self.run_mode("stop", hierarchy(stop="true", stop_dictation=True))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("COMPOSER_STOP", result.stdout)

    def test_stop_fails_while_send_remains(self):
        result = self.run_mode("stop", hierarchy(send="false", stop="true"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Send prompt is still visible", result.stderr)

    def test_stop_fails_when_disabled(self):
        result = self.run_mode("stop", hierarchy(stop="false"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Stop is not enabled", result.stderr)

    def test_idle_send_may_be_disabled(self):
        result = self.run_mode("send", hierarchy(send="false", stop_dictation=True))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("COMPOSER_SEND", result.stdout)

    def test_send_ready_requires_enabled_send(self):
        disabled = self.run_mode("send-ready", hierarchy(send="false"))
        self.assertNotEqual(disabled.returncode, 0)
        self.assertIn("Send prompt is not enabled", disabled.stderr)
        ready = self.run_mode("send-ready", hierarchy(send="true"))
        self.assertEqual(ready.returncode, 0, ready.stderr)
        self.assertIn("COMPOSER_SEND_READY", ready.stdout)

    def test_send_fails_while_stop_remains(self):
        result = self.run_mode("send", hierarchy(send="true", stop="true"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Stop is still visible", result.stderr)

    def test_missing_receipt_is_quiet(self):
        result = self.run_mode("quiet", missing=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("COMPOSER_HOST_QUIET", result.stdout)

    def test_empty_receipt_is_quiet(self):
        result = self.run_mode("quiet", text="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("COMPOSER_HOST_QUIET", result.stdout)

    def test_received_line_is_not_quiet(self):
        result = self.run_mode("quiet", text="VERIFY_RECEIVED:KEEP_DRAFT\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("host already received input", result.stderr)

    def test_interrupt_receipt(self):
        result = self.run_mode("interrupted", text="VERIFY_INTERRUPTED_528\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("COMPOSER_HOST_INTERRUPTED", result.stdout)

    def test_missing_interrupt_fails(self):
        result = self.run_mode("interrupted", missing=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("interrupt receipt missing", result.stderr)

    def test_interrupt_plus_prompt_fails(self):
        result = self.run_mode("interrupted", text="VERIFY_INTERRUPTED_528\nVERIFY_RECEIVED:KEEP_DRAFT\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("host received a prompt", result.stderr)


if __name__ == "__main__":
    unittest.main()
