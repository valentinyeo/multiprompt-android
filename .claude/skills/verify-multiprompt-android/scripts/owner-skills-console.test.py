#!/usr/bin/env python3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CONSOLE = Path(__file__).with_name("owner-skills-console.py")


class OwnerSkillsConsoleTest(unittest.TestCase):
    def run_console(self, text):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        receipts = Path(directory.name) / "receipts.txt"
        result = subprocess.run([sys.executable, str(CONSOLE), str(receipts)], input=text, capture_output=True, text=True)
        return result, receipts

    def probe(self, mode, path, needle):
        return subprocess.run([sys.executable, str(CONSOLE), mode, str(path), needle], capture_output=True, text=True)

    def test_receipt_file_stays_missing_until_a_line_arrives(self):
        result, receipts = self.run_console("")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Claude Code", result.stdout)
        self.assertIn("VERIFY_READY_528", result.stdout)
        self.assertFalse(receipts.exists())
        missing = self.probe("absent", receipts, "VERIFY_RECEIVED:/owner-note kept")
        self.assertEqual(missing.returncode, 0, missing.stderr)
        self.assertIn("NOTHING_SENT", missing.stdout)
        unsent = self.probe("line", receipts, "VERIFY_RECEIVED:/owner-note kept")
        self.assertNotEqual(unsent.returncode, 0)

    def test_one_sent_line_is_recorded_and_the_other_account_is_not(self):
        result, receipts = self.run_console("/owner-note kept\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(receipts.read_text(encoding="utf-8"), "VERIFY_RECEIVED:/owner-note kept\n")
        self.assertNotIn("BODY_MUST_NOT_BE_INJECTED", result.stdout)
        sent = self.probe("line", receipts, "VERIFY_RECEIVED:/owner-note kept")
        self.assertEqual(sent.returncode, 0, sent.stderr)
        self.assertIn("SENT", sent.stdout)
        leaked = self.probe("absent", receipts, "/login-note")
        self.assertEqual(leaked.returncode, 0, leaked.stderr)
        early = self.probe("absent", receipts, "VERIFY_RECEIVED:/owner-note")
        self.assertNotEqual(early.returncode, 0)


if __name__ == "__main__":
    unittest.main()
