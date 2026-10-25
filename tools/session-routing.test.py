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

    def process(self, path=None):
        program = "import sys; f=open(sys.argv[1]) if sys.argv[1] else None; print('ready',flush=True); input()"
        process = subprocess.Popen(["python3", "-c", program, str(path) if path else ""],
                                   cwd=self.cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        def cleanup():
            process.terminate()
            process.wait(timeout=5)
            process.stdin.close()
            process.stdout.close()
        self.addCleanup(cleanup)
        self.assertEqual("ready", process.stdout.readline().strip())
        return process.pid

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
        self.assertEqual(("session-a", own), reader.resolve_tmux_agent("codex", "session-a", self.process(own)))


if __name__ == "__main__":
    unittest.main()
