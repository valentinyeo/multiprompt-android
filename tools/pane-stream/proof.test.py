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
        before = dict(kind="snapshot", session="claude-control", scope="scrollback")
        (root / "pane-scrollback-requests.jsonl").write_text(json.dumps(before) + "\n")
        (root / "pane-native-before.jsonl").write_text(json.dumps(before) + "\n")
        (root / "pane-native-after.jsonl").write_text(json.dumps(before) + "\n" + json.dumps(dict(before, scope="visible")) + "\n")
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

    def test_requires_updated_phone_pixels_and_actual_visible_only_captures(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.seed(root)
            self.assertEqual("visible", proof.prove(root)["native_scope"])
            for filename, broken in {
                "pane-native-after.jsonl": (root / "pane-native-after.jsonl").read_text().replace('"visible"', '"scrollback"'),
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


if __name__ == "__main__":
    unittest.main()
