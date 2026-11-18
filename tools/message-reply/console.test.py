#!/usr/bin/env python3
import json
import os
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import console


class ReplyConsoleTest(unittest.TestCase):
    def test_transcript_is_one_assistant_bubble_and_receipts_keep_each_line(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            previous = Path.cwd()
            try:
                transcript = console.write_transcript(home)
            finally:
                os.chdir(previous)
            row = json.loads(transcript.read_text().splitlines()[0])
            self.assertEqual("assistant", row["type"])
            self.assertEqual(console.BUBBLE, row["message"]["content"])
            console.record(home, "> REPLY_BUBBLE_418\n")
            console.record(home, "REPLY_BODY_418\n")
            self.assertEqual("> REPLY_BUBBLE_418\nREPLY_BODY_418\n", (home / "receipts.txt").read_text())


if __name__ == "__main__":
    unittest.main()
