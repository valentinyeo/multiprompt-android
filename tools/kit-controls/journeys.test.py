from pathlib import Path
import json
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / ".claude/skills/verify-multiprompt-android/scripts"


class ControlJourneyTest(unittest.TestCase):
    def test_reaction_fixture_consumes_the_prompt_and_delivery_marker_as_one_turn(self):
        wire = "👍 to: First reply [mp:6d8e353d25c5fe23]"
        marker = "[mp:5e5cdaa77f3dcfe4]"
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            receipts = home / "receipts.txt"
            result = subprocess.run(
                ["python3", str(SCRIPTS / "reaction-console.py"), str(receipts)],
                input=wire + "\n" + marker + "\n", text=True, capture_output=True,
                env={**os.environ, "HOME": directory},
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(receipts.read_text().splitlines(), ["VERIFY_RECEIVED:" + wire])
            transcript = next((home / ".claude/projects").rglob("*.jsonl"))
            messages = [json.loads(line)["message"] for line in transcript.read_text().splitlines()]
            self.assertEqual(messages[-2]["content"], wire + "\n" + marker)
            self.assertEqual(sum(message["content"] == "Reaction received." for message in messages), 1)

    def test_host_rotation_closes_only_the_owned_listener_and_its_connections(self):
        source = (SCRIPTS / "drive.sh").read_text()
        start = source.index("    mapfile -t old_connections")
        end = source.index("    for attempt", start)
        rotation = source[start:end]
        with tempfile.TemporaryDirectory() as directory:
            script = '''set -euo pipefail
old_pid=123
EVIDENCE="$1"
sudo() {
    if [[ "$1" == ps ]]; then printf '111\n222\n';
    elif [[ "$1" == kill ]]; then shift; printf '%s\n' "$@" > "$EVIDENCE/killed.txt";
    else return 1; fi
}
''' + rotation
            result = subprocess.run(["bash", "-c", script, "rotation", directory], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((Path(directory) / "killed.txt").read_text().splitlines(), ["123", "111", "222"])
            self.assertEqual((Path(directory) / "sshd-children-before-rotation.txt").read_text().splitlines(), ["111", "222"])


if __name__ == "__main__":
    unittest.main()
