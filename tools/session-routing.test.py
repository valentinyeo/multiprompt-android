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

spec = importlib.util.spec_from_file_location(
    "reader", Path(__file__).resolve().parents[1] / "app/src/main/assets/chat_history_reader.py")
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


class SessionRoutingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.cwd = self.home / "shared-workspace"
        self.cwd.mkdir()
        self.addCleanup(patch.stopall)
        patch.object(reader, "HOME", self.home).start()
        patch.dict(os.environ, {"CLAUDE_CONFIG_DIR": str(self.home / ".claude")}).start()

    def transcript(self, agent, sid, newer=False):
        if agent == "claude":
            folder = self.home / ".claude/projects" / reader.claude_project_folder(self.cwd)
            name = sid + ".jsonl"
        else:
            folder = self.home / ".codex/sessions/2026/10/25"
            name = "rollout-2026-10-25T22-15-00-" + sid + ".jsonl"
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / name
        path.write_text(json.dumps({"type": "session_meta", "payload": {
            "id": sid, "cwd": str(self.cwd)}}) + "\n")
        os.utime(path, (200 if newer else 100, 200 if newer else 100))
        return path

    def process(self, path=None, wrapper=False, duplicate=False, codex=False):
        program = "import sys; f=open(sys.argv[1]) if sys.argv[1] else None; g=open(sys.argv[1]) if sys.argv[2]=='yes' else None; print('ready',flush=True); input()"
        command = ["python3", "-c", program, str(path) if path else "", "yes" if duplicate else "no"]
        if wrapper or codex:
            executable = self.home / "codex"
            executable.symlink_to(sys.executable)
            command[0] = str(executable)
        if wrapper:
            command = ["python3", "-c", "import subprocess,sys; sys.exit(subprocess.call(sys.argv[1:]))", *command]
        process = subprocess.Popen(command,
                                   cwd=self.cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        def cleanup():
            process.stdin.write("\n")
            process.stdin.flush()
            process.wait(timeout=5)
            process.stdin.close()
            process.stdout.close()
        self.addCleanup(cleanup)
        self.assertEqual("ready", process.stdout.readline().strip())
        return process.pid

    def test_large_image_records_never_skip_queued_message_reply_or_second_message(self):
        path = self.transcript("claude", "session-a")
        query = dict(id=1, codex=False, sid="session-a", tmux="", cwd=str(self.cwd),
                     cached_sid="", inode=0, offset=0, start=0, no_jump=True,
                     source_offsets=True, settlement=True)

        def poll():
            lines = reader.response(query).splitlines()
            fields = lines[0].split("\t")
            self.assertEqual("ok", fields[3])
            query.update(id=query["id"] + 1, cached_sid=fields[4], inode=int(fields[7]),
                         offset=int(fields[6]), start=int(fields[14]), mtime=int(fields[11]), probe=fields[12])
            return fields, [json.loads(line) for line in lines[1:-1]]

        poll()
        queued = dict(type="attachment", uuid="queued-one", attachment=dict(
            type="queued_command", prompt="Translation request"))
        image = dict(type="user", isMeta=True, message=dict(role="user", content=[
            dict(type="image", source=dict(type="base64", data="A" * (2 * reader.SCAN_BYTES)))]))
        reply = dict(type="assistant", message=dict(role="assistant", content="Translation answered"))
        second = dict(type="user", message=dict(role="user", content="Make it a PWA"))
        expected = ["Translation request", "Translation answered", "Make it a PWA"]
        with path.open("a") as output:
            for row in (queued, image, reply, second):
                output.write(json.dumps(row) + "\n")
        seen = []
        for _ in range(5):
            fields, rows = poll()
            self.assertEqual("delta", fields[13], "A large image must not discard earlier chat rows")
            seen.extend(row["text"] for row in rows if row.get("text"))
            if fields[8] == "0":
                break
        self.assertEqual(expected, seen)
        self.assertEqual(path.stat().st_size, query["offset"])

    def test_incomplete_large_record_preserves_cursor_until_newline(self):
        path = self.transcript("claude", "session-a")
        offset = path.stat().st_size
        query = dict(id=1, codex=False, sid="session-a", tmux="", cwd=str(self.cwd),
                     cached_sid="session-a", inode=path.stat().st_ino, offset=offset,
                     start=0, no_jump=True, source_offsets=True, settlement=True)
        record = json.dumps(dict(type="user", message=dict(role="user", content="large " + "x" * reader.SCAN_BYTES)))
        path.write_text(path.read_text() + record)
        fields = reader.response(query).splitlines()[0].split("\t")
        self.assertEqual("delta", fields[13])
        self.assertEqual(offset, int(fields[6]))
        self.assertEqual("0", fields[8], "Wait for writer instead of spinning on an unfinished record")
        with path.open("a") as output:
            output.write("\n")
        lines = reader.response(query).splitlines()
        self.assertEqual(path.stat().st_size, int(lines[0].split("\t")[6]))
        self.assertEqual("large " + "x" * reader.SCAN_BYTES, json.loads(lines[1])["text"])

    def test_unidentified_claude_never_shows_another_session_in_same_folder(self):
        self.transcript("claude", "wrong-session", newer=True)
        pid = self.process()
        resolved = reader.resolve_tmux_agent("claude", "session-a", pid)
        print("claude session-a without process identity resolved:", resolved)
        self.assertIsNone(resolved)

    def test_unidentified_codex_never_shows_another_session_in_same_folder(self):
        self.transcript("codex", "wrong-session", newer=True)
        self.assertIsNone(reader.resolve_tmux_agent("codex", "session-a", self.process()))

    def test_claude_open_transcript_stays_bound_despite_newer_neighbour(self):
        own = self.transcript("claude", "session-a")
        self.transcript("claude", "session-b", newer=True)
        self.assertEqual(("session-a", own), reader.resolve_tmux_agent("claude", "session-a", self.process(own)))

    def test_codex_open_transcript_stays_bound_despite_newer_neighbour(self):
        own = self.transcript("codex", "session-a")
        self.transcript("codex", "session-b", newer=True)
        self.assertEqual(("session-a", own), reader.resolve_tmux_agent("codex", "session-a", self.process(own, codex=True)))

    def test_codex_launcher_uses_its_native_child_not_a_workspace_neighbour(self):
        own = self.transcript("codex", "session-a")
        self.transcript("codex", "session-b", newer=True)
        pid = self.process(own, wrapper=True)
        command = ["tmux", "list-panes", "-t", "=session-a", "-F", "#{pane_pid}"]
        with patch.object(reader.subprocess, "run", return_value=subprocess.CompletedProcess(command, 0, str(pid) + "\n", "")) as run:
            self.assertEqual(("session-a", own), reader.resolve_tmux_agent("codex", "session-a"))
        run.assert_called_once_with(command, capture_output=True, text=True, encoding="utf-8", timeout=3, check=False)

    def test_duplicate_descriptors_do_not_make_a_single_codex_session_ambiguous(self):
        own = self.transcript("codex", "session-a")
        self.assertEqual(("session-a", own), reader.resolve_tmux_agent("codex", "session-a", self.process(own, duplicate=True, codex=True)))


if __name__ == "__main__":
    unittest.main()
