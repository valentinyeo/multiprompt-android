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
    def transcript(self, home):
        previous = Path.cwd()
        try:
            return console.write_transcript(home)
        finally:
            os.chdir(previous)

    def test_transcript_is_one_assistant_bubble_and_receipts_keep_each_line(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            transcript = self.transcript(home)
            row = json.loads(transcript.read_text().splitlines()[0])
            self.assertEqual("assistant", row["type"])
            self.assertEqual(console.BUBBLE, row["message"]["content"])
            console.record(home, "> REPLY_BUBBLE_418\n")
            console.record(home, "REPLY_BODY_418\n")
            self.assertEqual("> REPLY_BUBBLE_418\nREPLY_BODY_418\n", (home / "receipts.txt").read_text())

    def test_a_marked_prompt_lands_in_the_family_as_a_user_turn_and_a_reply(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            transcript = self.transcript(home)
            lines = ["> REPLY_BUBBLE_418", "", "REPLY_BODY_418", "[mp:0123456789abcdef]"]
            console.submit(transcript, lines)
            rows = [json.loads(line) for line in transcript.read_text().splitlines()]
            self.assertEqual(["assistant", "user", "assistant"], [row["type"] for row in rows])
            self.assertEqual("\n".join(lines), rows[1]["message"]["content"])
            self.assertEqual(console.SID, rows[1]["sessionId"])
            self.assertEqual(console.ACK, rows[2]["message"]["content"])
            self.assertEqual(len({row["uuid"] for row in rows}), 3)

    def test_the_marker_line_closes_a_prompt(self):
        self.assertTrue(console.MARKER.fullmatch("[mp:0123456789abcdef]"))
        self.assertFalse(console.MARKER.fullmatch("REPLY_BODY_418"))


if __name__ == "__main__":
    unittest.main()
