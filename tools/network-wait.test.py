from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / ".claude/skills/verify-multiprompt-android/scripts"
DRIVER = SCRIPTS / "drive.sh"


def network_wait_source():
    source = DRIVER.read_text()
    start = source.index("    local network_restarted=0")
    blocked = source.index("    printf 'BLOCKED: emulator cannot reach 10.0.2.2", start)
    end = source.index("    return 1", blocked) + len("    return 1")
    return "wait_emulator_network() {\n" + source[start:end] + "\n}\n"


class NetworkWaitTest(unittest.TestCase):
    def run_wait(self, fail_count):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "evidence"
            evidence.mkdir()
            bin_dir = root / "bin"
            bin_dir.mkdir()
            (bin_dir / "ssh-ready.sh").write_text(
                f"""#!/usr/bin/env bash
set -eu
count=$(cat "$EVIDENCE/ssh-attempts" 2>/dev/null || echo 0)
count=$((count + 1))
echo "$count" > "$EVIDENCE/ssh-attempts"
(( count > {fail_count} ))
"""
            )
            (bin_dir / "ssh-ready.sh").chmod(0o700)
            (bin_dir / "adb").write_text(
                """#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$EVIDENCE/adb-calls"
"""
            )
            (bin_dir / "adb").chmod(0o700)
            script = (
                """set -euo pipefail
EVIDENCE="$1"
scripts="$2"
setup_failure=1
sleep() { :; }
"""
                + network_wait_source()
                + "wait_emulator_network\n"
            )
            result = subprocess.run(
                ["bash", "-c", script, "network-wait", str(evidence), str(bin_dir)],
                env={**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}", "EVIDENCE": str(evidence)},
                capture_output=True,
                text=True,
            )
            adb_calls = (evidence / "adb-calls").read_text() if (evidence / "adb-calls").exists() else ""
            wait_log = (evidence / "network-wait.txt").read_text() if (evidence / "network-wait.txt").exists() else ""
            return result, adb_calls, wait_log

    def test_succeeds_without_restart_when_failures_below_thirty(self):
        result, adb_calls, wait_log = self.run_wait(29)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("NETWORK_RESTARTED", wait_log)
        self.assertNotIn("airplane-mode", adb_calls)

    def test_restarts_once_when_failures_reach_thirty_then_succeed(self):
        result, adb_calls, wait_log = self.run_wait(35)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(wait_log.count("NETWORK_RESTARTED"), 1)
        self.assertEqual(adb_calls.count("airplane-mode enable"), 1)
        self.assertEqual(adb_calls.count("airplane-mode disable"), 1)
        self.assertIn("svc wifi enable", adb_calls)
        self.assertIn("svc data enable", adb_calls)

    def test_blocked_when_failures_reach_ninety(self):
        result, adb_calls, wait_log = self.run_wait(90)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("BLOCKED: emulator cannot reach 10.0.2.2", result.stderr)
        self.assertEqual(wait_log.count("NETWORK_RESTARTED"), 1)


if __name__ == "__main__":
    unittest.main()