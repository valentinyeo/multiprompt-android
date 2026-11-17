#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("restart-proof.py")


def run(home, *args):
    env = os.environ.copy()
    env["RESTART_PROOF_HOME"] = str(home)
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True, env=env)


class RestartProofTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_sid_and_owner_files(self):
        sid = run(self.home, "sid")
        self.assertEqual(sid.returncode, 0, sid.stderr)
        self.assertEqual(sid.stdout.strip(), "verify-restart-1")
        prepared = run(self.home, "prepare", "4242")
        self.assertEqual(prepared.returncode, 0, prepared.stderr)
        self.assertIn("RESTART_OWNER_READY verify-restart-1", prepared.stdout)
        root = self.home / ".local/state/multiprompt/agent-host"
        files = sorted(path.name for path in root.iterdir())
        self.assertEqual(len(files), 2)
        config = json.loads((root / files[0]).read_text())
        owner = json.loads((root / files[1]).read_text())
        self.assertEqual(config, {"enabled": True, "provider": "claude", "sid": "verify-restart-1", "tmux": "verify-session"})
        self.assertEqual(owner, {"owner": "host", "pid": 4242})
        self.assertTrue(files[0].endswith(".config.json"))
        self.assertTrue(files[1].endswith(".owner.json"))

    def test_prepare_rejects_a_bad_pid(self):
        for pid in ("0", "-5", "nope"):
            result = run(self.home, "prepare", pid)
            self.assertNotEqual(result.returncode, 0, pid)
            self.assertIn("pid", result.stderr)

    def test_missing_request_is_absent_and_not_written(self):
        absent = run(self.home, "absent")
        self.assertEqual(absent.returncode, 0, absent.stderr)
        self.assertIn("RESTART_ABSENT", absent.stdout)
        written = run(self.home, "written", "1")
        self.assertNotEqual(written.returncode, 0)
        self.assertIn("not written", written.stderr)

    def test_request_time_moves_only_after_a_later_write(self):
        run(self.home, "prepare", "7")
        root = self.home / ".local/state/multiprompt/agent-host"
        restart = next(root.glob("*.restart.json")) if list(root.glob("*.restart.json")) else None
        self.assertIsNone(restart)
        key = next(root.glob("*.config.json")).name[: -len(".config.json")]
        path = root / f"{key}.restart.json"
        path.write_text('{"at":10}\n')
        early = run(self.home, "absent")
        self.assertNotEqual(early.returncode, 0)
        self.assertIn("already existed", early.stderr)
        stale = run(self.home, "written", "11")
        self.assertNotEqual(stale.returncode, 0)
        self.assertIn("older than the tap", stale.stderr)
        ready = run(self.home, "written", "10")
        self.assertEqual(ready.returncode, 0, ready.stderr)
        self.assertIn("RESTART_REQUESTED at=10", ready.stdout)
        stuck = run(self.home, "newer", "10")
        self.assertNotEqual(stuck.returncode, 0)
        self.assertIn("did not move", stuck.stderr)
        path.write_text('{"at":10.5}\n')
        moved = run(self.home, "newer", "10")
        self.assertEqual(moved.returncode, 0, moved.stderr)
        self.assertIn("RESTART_REQUESTED at=10.5", moved.stdout)
        shown = run(self.home, "show")
        self.assertEqual(shown.returncode, 0, shown.stderr)
        self.assertEqual(json.loads(shown.stdout), {"at": 10.5})

    def test_bool_time_is_rejected(self):
        run(self.home, "prepare", "7")
        root = self.home / ".local/state/multiprompt/agent-host"
        key = next(root.glob("*.config.json")).name[: -len(".config.json")]
        (root / f"{key}.restart.json").write_text('{"at":true}\n')
        result = run(self.home, "written", "0")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no time", result.stderr)

    def test_screen_keeps_success_text_and_rejects_failure_text(self):
        xml = self.home / "ui.xml"
        xml.write_text(
            '<node text="Restart on newest version"/>'
            '<node text="Restarted 1 idle chat"/>'
            '<node text="Restart requested. A chat that is answering restarts when its reply ends"/>'
            '<node text="Message verify-session"/>'
        )
        clean = run(self.home, "clear-ui", str(xml), "Message verify-session")
        self.assertEqual(clean.returncode, 0, clean.stderr)
        self.assertIn("RESTART_UI_CLEAR", clean.stdout)
        xml.write_text('<node text="Message verify-session"/><node text="Restart failed: the server did not answer"/>')
        failed = run(self.home, "clear-ui", str(xml), "Message verify-session")
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn("Restart failed", failed.stderr)
        missing = run(self.home, "clear-ui", str(xml), "verify-session row")
        self.assertNotEqual(missing.returncode, 0)
        self.assertIn("missing", missing.stderr)


if __name__ == "__main__":
    unittest.main()
