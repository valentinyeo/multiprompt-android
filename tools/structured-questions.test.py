import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("reader", Path(__file__).resolve().parents[1] / "app/src/main/assets/chat_history_reader.py")
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


class StructuredQuestionsTest(unittest.TestCase):
    def setUp(self):
        self.question = dict(question="Exact question?", header="Exact header", multiSelect=True,
                             options=[dict(label="Exact label", description="Exact description"), dict(label="Second", description="")])

    def claude(self, part):
        return dict(type="assistant", message=dict(content=[part]))

    def wire(self, events, agent="claude"):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "session.jsonl"
            path.write_text("".join(json.dumps(event) + "\n" for event in events))
            with patch.object(reader, "resolve_response_path", return_value=(agent, "session", path, None)):
                wire = reader.response(dict(id=1, tmux="", offset=0, cached_sid="", inode=0,
                                            source_offsets=True, questions=True, settlement=True, no_jump=True))
            return [json.loads(line) for line in wire.splitlines() if line.startswith("{")]

    def status_question(self, rows):
        return next((row.get("question") for row in rows if row.get("role") == "status"), None)

    def test_plain_lists_and_ordinary_tools_never_make_questions(self):
        for text in ["Choose:\n1. First\n2. Second", "A. First\nB. Second", "- First\n- Second"]:
            self.assertIsNone(self.status_question(self.wire([self.claude(dict(type="text", text=text))])))
        self.assertIsNone(self.status_question(self.wire([self.claude(dict(type="tool_use", id="call", name="Read", input={"questions": [self.question]}))])))

    def test_claude_current_question_preserves_metadata(self):
        event = self.claude(dict(type="tool_use", id="call", name="AskUserQuestion", input={"questions": [self.question]}))
        self.assertEqual(dict(id="call", questions=[self.question]), self.status_question(self.wire([event])))

    def test_codex_current_question_preserves_metadata(self):
        event = dict(type="response_item", payload=dict(type="function_call", name="request_user_input", call_id="codex-call", arguments=json.dumps({"questions": [self.question]})))
        self.assertEqual(dict(id="codex-call", provider="codex", questions=[self.question]), self.status_question(self.wire([event], "codex")))

    def test_completion_removes_current_question(self):
        question = self.claude(dict(type="tool_use", id="call", name="AskUserQuestion", input={"questions": [self.question]}))
        result = dict(type="user", message=dict(content=[dict(type="tool_result", tool_use_id="call", content="done")]))
        self.assertIsNone(self.status_question(self.wire([question, result])))

    def test_codex_free_text_question_stays_structured(self):
        question = dict(question="Exact question?", header="Header")
        event = dict(type="response_item", payload=dict(type="function_call", name="request_user_input", call_id="call", arguments=json.dumps({"questions": [question]})))
        self.assertEqual(dict(id="call", provider="codex", questions=[question]), self.status_question(self.wire([event], "codex")))


if __name__ == "__main__":
    unittest.main()
