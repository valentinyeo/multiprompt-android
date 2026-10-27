import importlib.util
import json
from pathlib import Path
import unittest
import tempfile
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

    def test_question_metadata_survives_the_actual_wire_encoder(self):
        event = self.claude(dict(type="tool_use", id="call", name="AskUserQuestion", input={"questions": [self.question]}))
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "session.jsonl"
            path.write_text(json.dumps(event) + "\n")
            with patch.object(reader, "resolve_response_path", return_value=("claude", "session", path, None)):
                wire = reader.response(dict(id=1, tmux="", offset=0, cached_sid="", inode=0,
                                            source_offsets=True, questions=True, settlement=True, no_jump=True))
            rows = [json.loads(line) for line in wire.splitlines() if line.startswith("{")]
            question = next(row for row in rows if "question" in row)
            self.assertEqual(self.question, question["question"]["questions"][0])
            self.assertEqual(0, question["offset"])
            self.assertEqual(path.stat().st_size, question["end"])

    def test_codex_free_text_question_stays_structured(self):
        event = dict(type="response_item", payload=dict(type="function_call", name="request_user_input", call_id="call",
                                                       arguments=json.dumps({"questions": [dict(question="Exact question?", header="Header")]})))
        self.assertEqual([], reader.question_events(event, "codex")[0]["question"]["questions"][0]["options"])


if __name__ == "__main__":
    unittest.main()
