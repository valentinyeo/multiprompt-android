import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

MODEL_LABELS = ("Opus", "Sonnet", "Haiku", "Fable", "Sol", "Terra", "Luna", "Astra")
APPLICATION = "dev.multiprompt.companion"


def capture(root, name):
    tree = ET.parse(root / name)
    parents = {child: parent for parent in tree.iter("node") for child in parent}
    nodes = [node for node in tree.iter("node") if node.get("package", "").startswith(APPLICATION)]
    return nodes, parents


def shown(node):
    bounds = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds") or "")
    return bool(bounds) and int(bounds[3]) > int(bounds[1]) and int(bounds[4]) > int(bounds[2])


def with_text(nodes, value):
    return [node for node in nodes if value in (node.get("text") or "") and shown(node)]


def model_choices(nodes):
    return [node for node in nodes if (node.get("text") or "").split(" ·")[0] in MODEL_LABELS and shown(node)]


def clickable_above(parents, node):
    parent = parents.get(node)
    while parent is not None:
        if parent.get("clickable") == "true" and parent.get("enabled") != "false" and shown(parent):
            return parent
        parent = parents.get(parent)
    return None


def prove(root):
    def rows(name):
        return [json.loads(line) for line in (root / name).read_text().splitlines()]

    scrollback = [row for row in rows("pane-scrollback-requests.jsonl") if row["session"] == "claude-control" and row["kind"] == "snapshot" and row.get("scope") == "scrollback"]
    assert scrollback, "PANE_STREAM_SCOPE: pane reader never requested scrollback"
    before = rows("pane-native-before.jsonl")
    after = rows("pane-native-after.jsonl")
    assert after[:len(before)] == before, "PANE_STREAM_SCOPE: request log changed identity"
    delta = after[len(before):]
    chat_pane = [row for row in delta if row["session"] == "claude-control" and row["kind"] == "snapshot"]
    assert not chat_pane, "PANE_STREAM_CHAT_READS_THE_PANE: native chat still captured the pane"
    chat_history = [row for row in delta if row["session"] == "claude-control" and row["kind"] == "history"]
    assert chat_history, "PANE_STREAM_CHAT_FROZEN: native chat polled no history while the reply streamed"
    ui = json.loads((root / "pane-stream-ui.json").read_text())
    assert len(ui) == 2 and "STREAMCHATSTART" in ui[0]["text"] and "STREAMCHATUPDATE" in ui[1]["text"], "PANE_STREAM_CHAT_FROZEN"
    awaiting, _ = capture(root, "pane-stream-chat-awaiting.xml")
    assert [node for node in awaiting if "Working" in (node.get("content-desc") or "") and shown(node)], "PANE_STREAM_DOTS_MISSING: chat view showed no working dots while his message had no reply"
    choices = model_choices(awaiting)
    assert choices, "PANE_STREAM_MODEL_CHOICES_MISSING: chat view offered no model choice from structured data"
    updated, updated_parents = capture(root, "pane-stream-chat-updated.xml")
    asked = json.loads((root / "pane-stream-question.jsonl").read_text())["message"]["content"][0]["input"]["questions"][0]
    assert with_text(updated, asked["question"]), "PANE_STREAM_QUESTION_MISSING: chat view showed no question card from structured data"
    options = with_text(updated, asked["options"][0]["label"])
    assert options, "PANE_STREAM_QUESTION_MISSING: chat view showed no answer option for the structured question"
    assert any(clickable_above(updated_parents, node) is not None for node in options), "PANE_STREAM_QUESTION_DISABLED: the structured question offered no enabled answer"
    assert model_choices(updated), "PANE_STREAM_MODEL_CHOICES_MISSING: chat view dropped its model choices during the reply"
    first = (root / "pane-stream-terminal-first.txt").read_text()
    updated_terminal = (root / "pane-stream-terminal-updated.txt").read_text()
    assert "TERMINALALPHA" in first and "TERMINALOMEGA" not in first and "TERMINALOMEGA" in updated_terminal, "PANE_STREAM_TERMINAL_FROZEN"
    assert "claude-control" in (root / "pane-stream-clients.txt").read_text(), "PANE_STREAM_TERMINAL_NOT_ATTACHED"
    assert (root / "pane-stream-stage.txt").read_text() == "3", "PANE_STREAM_NOT_STILL_STREAMING"
    assert (root / "pane-stream-receipt.txt").read_text().splitlines() == ["VERIFY_RECEIVED:STREAM_531"], "PANE_STREAM_RECEIPT"
    host = (root / "pane-stream-host.txt").read_text()
    assert "TERMINALALPHA" in host and "TERMINALOMEGA" in host, "PANE_STREAM_HOST"
    result = dict(outcome="reader.pane-budget", native_scope="off", native_snapshots=0, native_history_requests=len(chat_history), pane_scrollback_snapshots=len(scrollback), chat=ui, chat_dots=True, model_choices=[node.get("text") for node in choices], question=asked["question"], terminal_first="pane-stream-terminal-first.txt", terminal_updated="pane-stream-terminal-updated.txt", receipt="VERIFY_RECEIVED:STREAM_531", still_streaming=True)
    (root / "pane-stream-result.json").write_text(json.dumps(result) + "\n")
    return result


if __name__ == "__main__":
    print("PANE_STREAM_CHAT_AND_TERMINAL_LIVE " + json.dumps(prove(Path(sys.argv[1]))))
