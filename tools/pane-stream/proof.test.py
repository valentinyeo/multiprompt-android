import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("pane_stream_proof", Path(__file__).with_name("proof.py"))
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)


class PaneStreamProofTest(unittest.TestCase):
    def seed(self, root):
        scrollback = dict(kind="snapshot", session="claude-control", scope="scrollback")
        history = dict(kind="history", session="claude-control")
        (root / "pane-scrollback-requests.jsonl").write_text(json.dumps(scrollback) + "\n")
        (root / "pane-native-before.jsonl").write_text(json.dumps(scrollback) + "\n" + json.dumps(history) + "\n")
        (root / "pane-native-after.jsonl").write_text(
            (root / "pane-native-before.jsonl").read_text() + json.dumps(history) + "\n"
        )
        (root / "pane-stream-ui.json").write_text(json.dumps([dict(text="STREAMCHATSTART"), dict(text="STREAMCHATSTART STREAMCHATUPDATE")]))
        for name, value in {
            "pane-stream-terminal-first.txt": "TERMINALALPHA",
            "pane-stream-terminal-updated.txt": "TERMINALALPHA TERMINALOMEGA",
            "pane-stream-host.txt": "TERMINALALPHA TERMINALOMEGA",
            "pane-stream-clients.txt": "/dev/pts/1 claude-control",
            "pane-stream-stage.txt": "3",
            "pane-stream-receipt.txt": "VERIFY_RECEIVED:STREAM_531\n",
        }.items():
            (root / name).write_text(value)

    def test_requires_updated_phone_pixels_and_a_pane_free_chat_window(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.seed(root)
            self.assertEqual("off", proof.prove(root)["native_scope"])
            padded = (root / "pane-native-before.jsonl").read_text()
            for filename, broken in {
                "pane-native-after.jsonl": padded + json.dumps(dict(kind="snapshot", session="claude-control", scope="visible")) + "\n",
                "pane-stream-terminal-updated.txt": "TERMINALALPHA",
                "pane-stream-stage.txt": "2",
                "pane-stream-receipt.txt": "VERIFY_RECEIVED:OTHER\n",
                "pane-stream-ui.json": json.dumps([dict(text="STREAMCHATSTART"), dict(text="STREAMCHATSTART")]),
            }.items():
                original = (root / filename).read_text()
                (root / filename).write_text(broken)
                with self.assertRaises(AssertionError, msg=filename):
                    proof.prove(root)
                (root / filename).write_text(original)
            (root / "pane-native-after.jsonl").write_text(padded)
            with self.assertRaises(AssertionError, msg="history"):
                proof.prove(root)


if __name__ == "__main__":
    unittest.main()
