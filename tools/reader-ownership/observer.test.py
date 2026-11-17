import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


observer = load("observer", "ssh-observer.py")
receipts = load("receipts", "receipts.py")


class ObserverTest(unittest.TestCase):
    def test_snapshot_probe_counts_work_not_channel_opens(self):
        command = "while tmux has-session -t '=verify-session'; do tmux capture-pane -p; sleep 1; done; printf '__MP_TMUX_SNAPSHOT__'"
        measured = observer.snapshot_command(command, "/home/verifyssh/ssh-observer.py")
        self.assertIn("do python3 /home/verifyssh/ssh-observer.py snapshot verify-session; tmux capture-pane", measured)
        self.assertEqual("tmux list-sessions", observer.snapshot_command("tmux list-sessions", "/probe"))

    def test_history_probe_preserves_input_bytes_and_records_real_queries(self):
        class Destination(io.BytesIO):
            def close(self):
                self.saved = self.getvalue()
                super().close()
        with tempfile.TemporaryDirectory() as temporary, patch.object(observer, "LOG", Path(temporary) / "requests.jsonl"):
            payload = b'Ym9vdC1wYXlsb2FkCg==\n{"id":1,"cached_sid":"","tmux":"verify-session"}\n{"op":"engine_info"}\n'
            output = Destination()
            observer.forward_history(io.BytesIO(payload), output)
            self.assertEqual(payload, output.saved)
            self.assertEqual(dict(history=1, snapshot=0), receipts.counts(observer.LOG, "verify-session"))
            observer.record("snapshot", "verify-session")
            observer.record("snapshot", "other")
            self.assertEqual(dict(history=1, snapshot=1), receipts.counts(observer.LOG, "verify-session"))

    def test_deadline_observation_can_skip_idle_sleep_without_skipping_the_ui_check(self):
        source = (ROOT.parents[1] / ".claude/skills/verify-multiprompt-android/scripts/drive.sh").read_text()
        helper = source[source.index("wait_for_text() {"):source.index("\nfill() {")]
        for interval, expected in [("", "1"), ("0", "0")]:
            script = helper + f'''
attempts=0
select_node() {{ attempts=$((attempts + 1)); [[ "$attempts" == 2 ]]; }}
dismiss_system_anr() {{ :; }}
sleep() {{ printf 'wait=%s\\n' "$1"; }}
wait_for_text reply {interval} || exit 1
printf 'attempts=%s\\n' "$attempts"
'''
            result = subprocess.run(["bash", "-c", script], text=True, capture_output=True, check=True)
            self.assertEqual(f"wait={expected}\nattempts=2\n", result.stdout)

    def test_ui_witness_retains_real_rendered_text_and_rejects_missing_or_duplicate_nodes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            saved = root / "reader-ui.json"
            for index in (1, 2):
                path = root / f"{index}-reader.xml"
                node = f'<node package="dev.multiprompt.companion.debug" text="OBSERVED_REPLY_{index}" bounds="[1,2][30,40]"/>'
                path.write_text("<hierarchy>" + node + "</hierarchy>")
                subprocess.run(["python3", "-B", str(ROOT / "receipts.py"), "ui", str(path), "OBSERVED_REPLY", "--append", str(saved)], check=True, capture_output=True)
                with self.assertRaises(ValueError):
                    receipts.ui_result(path, "MISSING_REPLY")
                path.write_text("<hierarchy>" + node + node + "</hierarchy>")
                with self.assertRaises(ValueError):
                    receipts.ui_result(path, "OBSERVED_REPLY")
            self.assertEqual(["OBSERVED_REPLY_1", "OBSERVED_REPLY_2"], [row["text"] for row in json.loads(saved.read_text())])
            self.assertEqual(["1-reader.xml", "2-reader.xml"], [row["source"] for row in json.loads(saved.read_text())])

    def test_hidden_gate_requires_positive_controls_and_zero_more_requests(self):
        for before, after in [(dict(history=0, snapshot=0), dict(history=0, snapshot=0)),
                              (dict(history=2, snapshot=3), dict(history=3, snapshot=3)),
                              (dict(history=2, snapshot=3), dict(history=2, snapshot=4))]:
            with self.assertRaises(ValueError):
                receipts.compare(before, after, "hidden")
        receipts.compare(dict(history=2, snapshot=3), dict(history=2, snapshot=3), "hidden")
        receipts.compare(dict(history=2, snapshot=3), dict(history=3, snapshot=4), "resumed")
        with self.assertRaises(ValueError):
            receipts.compare(dict(history=2, snapshot=3), dict(history=2, snapshot=4), "resumed")


if __name__ == "__main__":
    unittest.main()
