import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(os.environ.get("HISTORY_READER", ROOT / "app/src/main/assets/chat_history_reader.py"))
spec = importlib.util.spec_from_file_location("history_reader", SCRIPT)
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


def post(index, text=None):
    role = "user" if index % 2 == 0 else "assistant"
    return json.dumps(dict(type=role, uuid=f"event-{index}", timestamp="2026-10-26T16:49:00Z",
                          message=dict(id=f"message-{index}", role=role,
                                       content=text or f"Synthetic post {index}"))) + "\n"


class HistoryContinuityTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / ".claude/projects/-synthetic/synthetic.jsonl"
        self.path.parent.mkdir(parents=True)
        self.path.write_text(post(0))
        self.home = reader.HOME
        reader.HOME = Path(self.tmp.name)
        self.addCleanup(setattr, reader, "HOME", self.home)
        self.query = dict(id=1, codex=False, sid="synthetic", tmux="", cwd="/synthetic",
                          cached_sid="", inode=0, offset=0, start=0, before=0,
                          source_offsets=True, no_jump=True, settlement=True)
        self.rows = []
        self.poll()

    def poll(self):
        wire = reader.response(self.query)
        lines = wire.splitlines()
        fields = lines[0].split("\t")
        self.assertEqual("ok", fields[3])
        base, end = map(int, fields[5:7])
        mode = fields[13]
        if self.query["cached_sid"]:
            self.assertEqual("delta", mode, "Reconnect must not discard cached history or jump past posts")
            self.assertEqual(self.query["offset"], base)
            self.assertEqual(self.query["start"], int(fields[14]))
        rows = [json.loads(line) for line in lines[1:-1]]
        self.rows.extend(row for row in rows if row.get("role") in ("user", "assistant") and not row.get("settlement"))
        self.query.update(cached_sid=fields[4], inode=int(fields[7]), offset=end,
                          start=int(fields[14]), mtime=int(fields[11]), probe=fields[12])
        return fields[8] == "1"

    def append(self, text):
        with self.path.open("a") as output:
            output.write(text)

    def catch_up(self):
        for _ in range(100):
            if not self.poll():
                return
        self.fail("Reader did not finish reconnecting")

    def test_long_reconnect_retains_all_204_gap_posts(self):
        self.append("".join(post(index, f"Synthetic post {index} " + "body " * 1800) for index in range(1, 205)))
        self.catch_up()
        self.assertEqual([f"message-{index}" for index in range(205)], [row["id"] for row in self.rows])
        self.assertEqual(self.path.stat().st_size, self.query["offset"])

    def test_595494_byte_tool_result_never_skips_the_next_post(self):
        event = dict(type="user", message=dict(role="user", content=[
            dict(type="tool_result", tool_use_id="synthetic-tool", content="")]))
        empty = json.dumps(event) + "\n"
        event["message"]["content"][0]["content"] = "x" * (595494 - len(empty))
        record = json.dumps(event) + "\n"
        self.assertEqual(595494, len(record.encode()))
        self.append(record + "".join(post(index) for index in range(1, 205)))
        self.catch_up()
        self.assertEqual([f"message-{index}" for index in range(205)], [row["id"] for row in self.rows])
        self.assertEqual(self.path.stat().st_size, self.query["offset"])

    def test_large_visible_post_survives_the_scan_boundary(self):
        text = "Visible oversized post " + "x" * 595494
        self.append(post(1, text) + post(2))
        self.catch_up()
        self.assertEqual(["Synthetic post 0", text, "Synthetic post 2"], [row["text"] for row in self.rows])

    def test_unfinished_large_record_waits_without_skipping_or_spinning(self):
        record = post(1, "x" * 595494)
        before = self.query["offset"]
        self.append(record[:-1])
        self.assertFalse(self.poll())
        self.assertEqual(before, self.query["offset"])
        self.append("\n" + post(2))
        self.catch_up()
        self.assertEqual(["message-0", "message-1", "message-2"], [row["id"] for row in self.rows])

    def test_unfinished_small_record_is_not_committed(self):
        self.append(post(1)[:-1])
        self.assertFalse(self.poll())
        self.assertEqual(["message-0"], [row["id"] for row in self.rows])
        self.append("\n")
        self.catch_up()
        self.assertEqual(["message-0", "message-1"], [row["id"] for row in self.rows])


if __name__ == "__main__":
    unittest.main(verbosity=2)
