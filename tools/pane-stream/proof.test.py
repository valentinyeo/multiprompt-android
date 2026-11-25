import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("pane_stream_proof", Path(__file__).with_name("proof.py"))
proof = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proof)

PACKAGE = "dev.multiprompt.companion.debug"
QUESTION = dict(question="Which reply style do you want?", options=[dict(label="Concise")])


class PaneStreamProofTest(unittest.TestCase):
    def node(self, text="", description="", bounds="[40,400][600,460]"):
        return f'<node class="android.widget.TextView" package="{PACKAGE}" text="{text}" content-desc="{description}" clickable="false" enabled="true" bounds="{bounds}" />'

    def group(self, content, clickable=True, bounds="[40,700][900,780]"):
        return (f'<node class="android.view.ViewGroup" package="{PACKAGE}" clickable="{"true" if clickable else "false"}" '
                f'enabled="true" bounds="{bounds}">{content}</node>')

    def screen(self, content):
        return f'<hierarchy rotation="0"><node class="android.widget.FrameLayout" package="{PACKAGE}" bounds="[0,0][1080,1920]">{content}</node></hierarchy>'

    def chat(self, choices=True, dots=True, question=True, option=True, clickable=True):
        awaiting = self.screen("".join([
            self.node(text="Opus", bounds="[40,300][300,340]") if choices else "",
            self.node(description="Working", bounds="[40,900][300,940]") if dots else "",
        ]))
        updated = self.screen("".join([
            self.node(text="Opus", bounds="[40,300][300,340]") if choices else "",
            self.node(text=QUESTION["question"], bounds="[40,600][900,660]") if question else "",
            self.group(self.node(text="Concise", bounds="[70,710][400,770]"), clickable) if option else "",
        ]))
        return awaiting, updated

    def seed(self, root):
        scrollback = dict(kind="snapshot", session="claude-control", scope="scrollback")
        history = dict(kind="history", session="claude-control")
        (root / "pane-scrollback-requests.jsonl").write_text(json.dumps(scrollback) + "\n")
        (root / "pane-native-before.jsonl").write_text(json.dumps(scrollback) + "\n" + json.dumps(history) + "\n")
        (root / "pane-native-after.jsonl").write_text(
            (root / "pane-native-before.jsonl").read_text() + json.dumps(history) + "\n"
        )
        (root / "pane-stream-ui.json").write_text(json.dumps([dict(text="STREAMCHATSTART"), dict(text="STREAMCHATSTART STREAMCHATUPDATE")]))
        awaiting, updated = self.chat()
        (root / "pane-stream-chat-awaiting.xml").write_text(awaiting)
        (root / "pane-stream-chat-updated.xml").write_text(updated)
        (root / "pane-stream-question.jsonl").write_text(json.dumps(dict(message=dict(content=[dict(input=dict(questions=[QUESTION]))]))) + "\n")
        for name, value in {
            "pane-stream-terminal-first.txt": "TERMINALALPHA",
            "pane-stream-terminal-updated.txt": "TERMINALALPHA TERMINALOMEGA",
            "pane-stream-host.txt": "TERMINALALPHA TERMINALOMEGA",
            "pane-stream-clients.txt": "/dev/pts/1 claude-control",
            "pane-stream-stage.txt": "3",
            "pane-stream-receipt.txt": "VERIFY_RECEIVED:STREAM_531\n",
        }.items():
            (root / name).write_text(value)

    def broken(self, root, name, content):
        original = (root / name).read_text()
        (root / name).write_text(content)
        try:
            with self.assertRaises(AssertionError) as caught:
                proof.prove(root)
            return str(caught.exception)
        finally:
            (root / name).write_text(original)

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

    def test_requires_the_structured_chat_controls_with_no_pane_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.seed(root)
            self.assertIn("PANE_STREAM_DOTS_MISSING", self.broken(root, "pane-stream-chat-awaiting.xml", self.chat(dots=False)[0]))
            self.assertIn("PANE_STREAM_MODEL_CHOICES_MISSING", self.broken(root, "pane-stream-chat-awaiting.xml", self.chat(choices=False)[0]))
            self.assertIn("PANE_STREAM_QUESTION_MISSING", self.broken(root, "pane-stream-chat-updated.xml", self.chat(question=False)[1]))
            self.assertIn("PANE_STREAM_QUESTION_DISABLED", self.broken(root, "pane-stream-chat-updated.xml", self.chat(clickable=False)[1]))
            self.assertIn("PANE_STREAM_MODEL_CHOICES_MISSING", self.broken(root, "pane-stream-chat-updated.xml", self.chat(choices=False)[1]))
            self.assertEqual("off", proof.prove(root)["native_scope"])


if __name__ == "__main__":
    unittest.main()
