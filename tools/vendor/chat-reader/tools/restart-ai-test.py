import importlib.util
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("reader", ROOT / "src/chat_history_reader.py")
reader = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reader)

SID = "0b6c1f5e-8d2a-4a37-9f10-5c3e2d4a7b19"


class FakeTmux:
    def __init__(self, pane_command):
        self.pane_command = pane_command
        self.calls = []

    def __call__(self, args, **kwargs):
        self.calls.append(list(args))
        stdout = self.pane_command + "\n" if "display" in args else ""
        return type("Done", (), dict(returncode=0, stdout=stdout, stderr=""))()

    def typed(self):
        return [call[-1] for call in self.calls if "-l" in call]

    def sent_keys(self):
        return [call for call in self.calls if "send-keys" in call]


class RestartAiTest(unittest.TestCase):
    def restart(self, **query):
        fn = getattr(reader, "restart_ai_response", None)
        self.assertTrue(callable(fn), "the reader has no restart_ai operation")
        return fn(dict(op="restart_ai", id=9, **query))

    def test_IGSH_2283_stopped_claude_pane_resumes_the_same_conversation(self):
        tmux = FakeTmux("bash")
        with patch.object(reader.subprocess, "run", tmux):
            wire = self.restart(tmux="multiprompt-android", sid=SID, cwd="/tmp")
        self.assertEqual(wire, "@mp\t9\trestart_ai\tok\t-\t0\n@@MP_END@@\n")
        self.assertEqual(tmux.typed(), ["cd /tmp && claude --resume " + SID])
        self.assertEqual(tmux.sent_keys()[-1], ["tmux", "send-keys", "-t", "=multiprompt-android:", "Enter"])

    def test_IGSH_2283_every_shell_counts_as_stopped_and_every_agent_as_running(self):
        for shell in ("bash", "zsh", "sh", "dash", "fish", "ksh"):
            tmux = FakeTmux(shell)
            with patch.object(reader.subprocess, "run", tmux):
                self.assertIn("\trestart_ai\tok\t", self.restart(tmux="chat-1", sid=SID))
            self.assertEqual(len(tmux.typed()), 1, shell)
        for running in ("claude", "node", "codex", "pi", "hax"):
            tmux = FakeTmux(running)
            with patch.object(reader.subprocess, "run", tmux):
                self.assertIn("\trestart_ai\terror\tpane_busy\t", self.restart(tmux="chat-1", sid=SID))
            self.assertEqual(tmux.typed(), [], running)

    def test_IGSH_2283_a_claude_process_behind_a_shell_wrapper_is_not_started_twice(self):
        tmux = FakeTmux("bash")
        with patch.object(reader.subprocess, "run", tmux), patch.object(reader, "resolve_tmux_agent", return_value=(SID, Path("/tmp/x"))):
            self.assertIn("\trestart_ai\terror\tpane_busy\t", self.restart(tmux="chat-1", sid=SID))
        self.assertEqual(tmux.typed(), [])

    def test_IGSH_2283_missing_session_id_continues_the_last_conversation(self):
        tmux = FakeTmux("bash")
        with patch.object(reader.subprocess, "run", tmux):
            self.assertIn("\trestart_ai\tok\t", self.restart(tmux="chat-1", sid=""))
        self.assertEqual(tmux.typed(), ["claude --continue"])

    def test_IGSH_2283_unsafe_input_never_reaches_the_pane(self):
        for query in (
            dict(tmux="a;b", sid=SID),
            dict(tmux="", sid=SID),
            dict(tmux=None, sid=SID),
            dict(tmux="chat-1", sid="x; rm -rf /"),
            dict(tmux="chat-1", sid=123),
            dict(tmux="chat-1", sid="$(id)aaaa"),
        ):
            tmux = FakeTmux("bash")
            with patch.object(reader.subprocess, "run", tmux):
                self.assertIn("\trestart_ai\terror\t", self.restart(**query), query)
            self.assertEqual(tmux.typed(), [], query)

    def test_IGSH_2283_folder_with_shell_characters_is_quoted_or_dropped(self):
        tmux = FakeTmux("bash")
        with patch.object(reader.subprocess, "run", tmux):
            self.restart(tmux="chat-1", sid=SID, cwd="/tmp; touch /tmp/x")
        self.assertEqual(tmux.typed(), ["claude --resume " + SID])

    def test_IGSH_2283_a_failed_tmux_call_reports_an_error(self):
        with patch.object(reader.subprocess, "run", side_effect=OSError):
            self.assertIn("\trestart_ai\terror\tpane_busy\t", self.restart(tmux="chat-1", sid=SID))

    def test_IGSH_2283_the_serve_loop_routes_restart_ai(self):
        printed = []
        tmux = FakeTmux("bash")
        line = json.dumps(dict(op="restart_ai", id=12, tmux="chat-1", sid=SID))
        with patch.object(reader.subprocess, "run", tmux), patch.object(reader, "serve_print", printed.append):
            reader.serve_request(line, None, {}, reader.threading.Lock())
        self.assertEqual(len(printed), 1)
        self.assertEqual(printed[0]["id"], 12)
        self.assertIn("\trestart_ai\tok\t", printed[0]["wire"])
        self.assertEqual(tmux.typed(), ["claude --resume " + SID])


if __name__ == "__main__":
    unittest.main(verbosity=2)
