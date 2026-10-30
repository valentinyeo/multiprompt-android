import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
source = Path(os.environ.get("CODEX_READER", Path(__file__).resolve().parents[1] / "app/src/main/assets/chat_history_reader.py"))
spec = importlib.util.spec_from_file_location("reader", source)
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


class CodexSyncTest(unittest.TestCase):
    def test_large_compacted_claude_queue_confirms_without_resending_or_scanning_prefix(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(reader, "HOME", Path(temporary)):
            path = Path(temporary) / "large.jsonl"
            origin = 452 * 1024 * 1024
            with path.open("wb") as output:
                output.seek(origin - 1)
                output.write(b"\n")
                oversized = dict(type="user", isMeta=True, message=dict(role="user", content=[dict(type="image", data="x" * (reader.TAIL_BYTES + 1024))]))
                output.write((json.dumps(oversized) + "\n").encode())
                for text in ("submitted phone message", "queued but already received"):
                    output.write((json.dumps(dict(type="user", timestamp="2026-10-29T23:00:00Z", message=dict(role="user", content=text))) + "\n").encode())
            import sqlite3
            database = reader.held_queue_path("large-session")
            database.parent.mkdir(parents=True)
            with sqlite3.connect(database) as db:
                db.execute("CREATE TABLE messages (key TEXT PRIMARY KEY, sid TEXT, text TEXT, queued_at INTEGER, state TEXT, scan INTEGER DEFAULT 0, origin INTEGER, source_inode INTEGER, receipt TEXT DEFAULT '', created INTEGER)")
                for index, (state, text) in enumerate((("submitted", "submitted phone message"), ("queued", "queued but already received"))):
                    db.execute("INSERT INTO messages(key,sid,text,queued_at,state,origin,source_inode,created) VALUES (?,?,?,?,?,?,?,?)", (str(index), "large-sid", text, 1793314800000, state, origin, path.stat().st_ino, index))
            with patch.object(reader, "resolve_tmux_agent", return_value=("large-sid", path)), patch.object(reader, "held_queue_ready", return_value=False), patch.object(reader, "send_response") as send:
                for _ in range(5):
                    reader.drain_held_queue("large-session", "large-sid", path)
                with sqlite3.connect(database) as db:
                    rows = db.execute("SELECT state,scan,receipt FROM messages ORDER BY created").fetchall()
                    self.assertEqual(["delivered", "delivered"], [row[0] for row in rows])
                    self.assertTrue(all(row[1] > origin and row[2] for row in rows))
                send.assert_not_called()
                print("LARGE_CLAUDE_QUEUED_RECEIPTS_RECONCILED")

    def test_hax_live_descriptor_and_incremental_reply_ignore_workspace_neighbour(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            root = home / ".local/state/hax/sessions/project"
            root.mkdir(parents=True)
            path = root / "today_session-a.jsonl"
            rows = [dict(type="session", version=1, id="session-a", cwd=temporary),
                    dict(kind="user", text="Question from desktop"),
                    dict(kind="assistant", text="Reply from Codex")]
            path.write_text("".join(json.dumps(row) + "\n" for row in rows))
            (root / "newer_session-b.jsonl").write_text(json.dumps(dict(type="session", version=1, id="session-b")) + "\n")
            executable = home / "hax"
            executable.symlink_to(sys.executable)
            process = subprocess.Popen([str(executable), "-c", "import sys; f=open(sys.argv[1]); print('ready',flush=True); input()", str(path)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
            try:
                self.assertEqual("ready", process.stdout.readline().strip())
                with patch.object(reader, "HOME", home), patch.dict(os.environ, {"XDG_STATE_HOME": str(home / ".local/state")}):
                    table = reader.process_table()
                    self.assertEqual(process.pid, reader.agent_pid_for_panes([process.pid], table, "codex"))
                    self.assertEqual(("session-a", path), reader.resolve_tmux_agent("codex", "codex-session", process.pid))
                    query = dict(id=1, codex=True, sid="session-a", tmux="", cwd=temporary,
                                 cached_sid="", inode=0, offset=0, start=0, no_jump=True, source_offsets=True)
                    lines = reader.response(query).splitlines()
                    header = lines[0].split("\t")
                    self.assertEqual("ok", header[3])
                    self.assertEqual(["Question from desktop", "Reply from Codex"], [json.loads(row)["text"] for row in lines[1:-1]])
                    query.update(id=2, cached_sid=header[4], inode=int(header[7]), offset=int(header[6]), start=int(header[14]), mtime=int(header[11]), probe=header[12])
                    with path.open("a") as output:
                        output.write(json.dumps(dict(kind="user", text="Phone message")) + "\n")
                        output.write(json.dumps(dict(kind="assistant", text="Phone reply")) + "\n")
                    delta = reader.response(query).splitlines()
                    self.assertEqual("delta", delta[0].split("\t")[13])
                    self.assertEqual(["Phone message", "Phone reply"], [json.loads(row)["text"] for row in delta[1:-1]])
                    self.assertIsNone(reader.conversation_event(dict(kind="user", origin="compact_seed", text="Hidden seed"), "codex"))
            finally:
                process.communicate("\n", timeout=5)


if __name__ == "__main__":
    unittest.main()
