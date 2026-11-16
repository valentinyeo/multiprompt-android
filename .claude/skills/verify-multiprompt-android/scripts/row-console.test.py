#!/usr/bin/env python3
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[4]
CONSOLE_PATH = Path(__file__).with_name("row-console.py")
READER_PATH = ROOT / "app/src/main/assets/chat_history_reader.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


console = load("row_console", CONSOLE_PATH)
reader = load("chat_history_reader", READER_PATH)


def query(**extra):
    base = dict(
        id=1,
        codex=False,
        sid="",
        tmux="",
        cwd=str(console.CWD),
        follow=False,
        cached_sid="",
        inode=0,
        offset=0,
        start=0,
        before=0,
        mtime=0,
        probe="",
        questions=True,
        held=False,
        delivery_ids=[],
        source_offsets=True,
        no_jump=True,
        settlement=True,
    )
    base.update(extra)
    return base


def rows(wire):
    lines = wire.splitlines()
    if lines[-1] != reader.END or lines[0].split("\t")[3] != "ok":
        raise AssertionError(wire[:500])
    return [json.loads(line) for line in lines[1:-1]]


class RowConsoleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.addCleanup(patch.stopall)
        patch.object(reader, "HOME", self.home).start()

    def test_question_transcript_is_unanswered(self):
        console.write_question_transcript(self.home)
        found = rows(reader.response(query(sid=console.QUESTION_SID, provider="claude")))
        status = next(row for row in found if row["role"] == "status")
        self.assertEqual(status["question"]["questions"][0]["question"], console.QUESTION)
        self.assertNotIn(console.STEP, json.dumps(found))

    def test_step_history_is_the_in_progress_todo(self):
        console.write_step_history(self.home)
        found = rows(reader.response(query(
            provider="claude",
            sid=console.STEP_SID,
            tmux=console.STEP_SESSION,
            follow=True,
        )))
        status = next(row for row in found if row["role"] == "status")
        self.assertNotIn("question", status)
        self.assertEqual(
            next(item["text"] for item in status["todos"] if item["status"] == "in_progress"),
            console.STEP,
        )

    def test_host_key_matches_the_reader(self):
        self.assertEqual(console.host_key("claude", console.STEP_SID), reader.host_key("claude", console.STEP_SID))


if __name__ == "__main__":
    unittest.main()
