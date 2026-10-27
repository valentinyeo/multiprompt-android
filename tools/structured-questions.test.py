import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("reader", Path(__file__).resolve().parents[1] / "app/src/main/assets/chat_history_reader.py")
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)


class StructuredQuestionsTest(unittest.TestCase):
    def setUp(self):
        self.question = dict(question="Exact question?", header="Exact header", multiSelect=True,
                             options=[dict(label="Exact label", description="Exact description"), dict(label="Second", description="")])

    def claude(self, part):
        return dict(type="assistant", message=dict(content=[part]))

    def test_plain_lists_and_ordinary_tools_never_make_questions(self):
        for text in ["Choose:\n1. First\n2. Second", "A. First\nB. Second", "- First\n- Second"]:
            self.assertEqual([], reader.question_events(self.claude(dict(type="text", text=text)), "claude"))
        self.assertEqual([], reader.question_events(self.claude(dict(type="tool_use", id="call", name="Read", input={"questions": [self.question]})), "claude"))

    def test_claude_preserves_question_metadata_and_result_identity(self):
        event = self.claude(dict(type="tool_use", id="call", name="AskUserQuestion", input={"questions": [self.question]}))
        rows = reader.question_events(event, "claude")
        self.assertEqual(self.question, rows[0]["question"]["questions"][0])
        self.assertEqual("call", rows[0]["id"])
        result = reader.question_events(self.claude(dict(type="tool_result", tool_use_id="call", content="done")), "claude")
        self.assertEqual("call", result[0]["questionResult"])
        event["isSidechain"] = True
        self.assertEqual([], reader.question_events(event, "claude"))

    def test_codex_reads_only_structured_request_user_input(self):
        event = dict(type="response_item", payload=dict(type="function_call", name="request_user_input", call_id="codex-call", arguments=json.dumps({"questions": [self.question]})))
        self.assertEqual(self.question, reader.question_events(event, "codex")[0]["question"]["questions"][0])
        event["payload"]["arguments"] = "broken JSON"
        self.assertEqual([], reader.question_events(event, "codex"))

    def test_malformed_question_cannot_become_a_card(self):
        for questions in [[], [dict(question="?", options=["not an option"])], [dict(question="", options=[])]]:
            self.assertEqual([], reader.question_events(self.claude(dict(type="tool_use", id="call", name="AskUserQuestion", input={"questions": questions})), "claude"))


if __name__ == "__main__":
    unittest.main()
