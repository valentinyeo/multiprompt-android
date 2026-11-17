import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import runpy
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / ".claude/skills/verify-multiprompt-android/scripts"
spec = importlib.util.spec_from_file_location("fixture_reader", ROOT / "app/src/main/assets/chat_history_reader.py")
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


class CodexFixtureTest(unittest.TestCase):
    def test_native_codex_fixture_uses_the_real_queue_attachment_and_cli(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            binary = home / "codex"
            shutil.copy2(Path(sys.executable).resolve(), binary)
            shutil.copy2(SCRIPTS / "codex-queue.py", home / "queue")
            env = os.environ.copy()
            for key in ("XDG_STATE_HOME", "CODEX_STATE_HOME", "CODEX_PROFILE", "CODEX_CONFIG_PROFILE", "CODEX_HOME"):
                env.pop(key, None)
            env["HOME"] = str(home)
            process = subprocess.Popen([str(binary), "-u", str(SCRIPTS / "codex-console.py"), str(home / "codex-receipts.txt"), "codex"],
                                       cwd=home, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                self.assertEqual("OpenAI Codex", process.stdout.readline().strip())
                path = next((home / ".codex/sessions").rglob("*.jsonl"))
                sid = json.loads(path.read_text().splitlines()[0])["payload"]["id"]
                self.assertEqual(sid, str(uuid.UUID(sid)))
                run = subprocess.run

                def discover(command, *args, **kwargs):
                    if command[:2] == ["tmux", "list-panes"]:
                        return subprocess.CompletedProcess(command, 0, str(process.pid) + "\n", "")
                    return run(command, *args, **kwargs)

                with patch.object(reader, "HOME", home), patch.dict(os.environ, env, clear=True), patch.object(reader.subprocess, "run", side_effect=discover):
                    context = reader.codex_queue_attachment("codex-native", sid, path)
                self.assertIsNotNone(context, "The native fixture must satisfy Codex attachment validation, not bypass it")
                connector_spec = importlib.util.spec_from_file_location("fixture_codex", ROOT / "app/src/main/assets/chat_connectors/codex.py")
                connector = importlib.util.module_from_spec(connector_spec)
                connector_spec.loader.exec_module(connector)
                finished = []
                context.update(sid=sid)
                intent = dict(id="fixture-message-2534", attempt="attempt-2534", text="PHONE_CODEX", epoch=0)
                previous = Path.cwd()
                try:
                    os.chdir(home)
                    with patch.dict(os.environ, env, clear=True):
                        result = connector.submit(context, intent, {}, dict(codex_attempt_claim=lambda key: dict(status="claimed"),
                                                                            codex_attempt_finish=lambda key, status: finished.append(status)))
                finally:
                    os.chdir(previous)
                self.assertEqual("sent", result["handoff"])
                self.assertEqual(["accepted"], finished)
                self.assertEqual("VERIFY_RECEIVED:PHONE_CODEX\n", (home / "codex-receipts.txt").read_text())
                rows = [json.loads(line) for line in path.read_text().splitlines()]
                self.assertEqual("CODEX_REPLY_codex_PHONE_CODEX", rows[-1]["payload"]["content"][0]["text"])
                run([sys.executable, str(SCRIPTS / "codex-console.py"), str(home / "receipts.txt"), "hax"],
                    input="PHONE_CODEX\n", env=env, text=True, capture_output=True, check=True)
                parity = run([sys.executable, str(SCRIPTS / "codex-parity.py"), str(ROOT / "app/src/main/assets/chat_history_reader.py"),
                              str(ROOT / "app/src/main/assets/chat_history_reader.py")], env=env, text=True, capture_output=True, check=True)
                self.assertIn("DESKTOP_ANDROID_POSTS_IDENTICAL", parity.stdout)
                rejected = run([str(binary), "queue", "--thread", "25340000-0000-4000-8000-000000000099", "--message", "WRONG_THREAD"], cwd=home, env=env, capture_output=True)
                self.assertNotEqual(0, rejected.returncode)
                self.assertNotIn("WRONG_THREAD", (home / "codex-receipts.txt").read_text())
            finally:
                try:
                    process.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate()
                    raise

    def test_shared_codex_evidence_reads_the_native_fixture_thread(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            subprocess.run([sys.executable, str(SCRIPTS / "codex-console.py"), str(home / "receipts.txt"), "codex"],
                           input="", env=dict(os.environ, HOME=temporary), text=True, capture_output=True, check=True, timeout=5)
            transcript = next((home / ".codex/sessions").rglob("*.jsonl"))
            sid = json.loads(transcript.read_text().splitlines()[0])["payload"]["id"]
            database = home / "store.sqlite3"
            with sqlite3.connect(database) as db:
                db.execute("CREATE TABLE message_chats(provider, sid, epoch, seq)")
                db.execute("CREATE TABLE shared_messages(provider, sid, id, attempt, state, epoch, text, position)")
                db.execute("INSERT INTO message_chats VALUES('codex', ?, 0, 1)", (sid,))
                db.execute("INSERT INTO shared_messages VALUES('codex', ?, 'phone-id', 'attempt', 'queued', 0, 'SHARED_CODEX_PHONE', 1)", (sid,))
            connect = sqlite3.connect
            output = io.StringIO()
            with patch.dict(os.environ, GITHUB_ACTIONS="true", HOME="/home/verifyssh"), patch.object(os, "getuid", return_value=1000), \
                    patch.object(sys, "argv", ["shared-evidence.py", "read", "codex"]), \
                    patch.object(sqlite3, "connect", side_effect=lambda *args, **kwargs: connect(database)), contextlib.redirect_stdout(output):
                runpy.run_path(str(SCRIPTS / "shared-evidence.py"), run_name="__main__")
            evidence = json.loads(output.getvalue())
            self.assertEqual(sid, evidence["sid"])
            self.assertEqual(["SHARED_CODEX_PHONE"], [row["body"] for row in evidence["rows"]])
            for name in ("fixture.sh", "shared-lock.py", "drive.sh"):
                self.assertIn(sid, (SCRIPTS / name).read_text(), f"{name} must use the native fixture thread")

    def test_claude_fixture_keeps_the_phone_correlation_tag_in_one_native_occurrence(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            env = dict(os.environ, HOME=temporary)
            payload = "CLAUDE_CONTROL\n[mp:0123456789abcdef]"
            result = subprocess.run([sys.executable, str(SCRIPTS / "codex-console.py"), str(home / "claude-receipts.txt"), "claude"],
                                    input="\x1b[200~" + payload + "\x1b[201~\rHOST_WHILE_HIDDEN_claude\r", env=env, text=True, capture_output=True, check=True, timeout=5)
            self.assertNotIn("CODEX_REPLY_", result.stdout)
            self.assertEqual("VERIFY_RECEIVED:CLAUDE_CONTROL\nVERIFY_RECEIVED:HOST_WHILE_HIDDEN_claude\n", (home / "claude-receipts.txt").read_text())
            path = next((home / ".claude/projects").rglob("*.jsonl"))
            with path.open("rb") as source:
                source.seek(-min(path.stat().st_size, 16 * 1024), 2)
                rows = [json.loads(line) for line in source.read().splitlines() if line.startswith(b"{")]
            messages = [row["message"]["content"] for row in rows if row.get("type") in ("user", "assistant")]
            self.assertIn(payload, messages)
            self.assertIn("CODEX_REPLY_claude_CLAUDE_CONTROL", messages)
            self.assertNotIn("[mp:0123456789abcdef]", messages)


if __name__ == "__main__":
    unittest.main()
