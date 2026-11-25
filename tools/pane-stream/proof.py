import json
from pathlib import Path
import sys


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
    first = (root / "pane-stream-terminal-first.txt").read_text()
    updated = (root / "pane-stream-terminal-updated.txt").read_text()
    assert "TERMINALALPHA" in first and "TERMINALOMEGA" not in first and "TERMINALOMEGA" in updated, "PANE_STREAM_TERMINAL_FROZEN"
    assert "claude-control" in (root / "pane-stream-clients.txt").read_text(), "PANE_STREAM_TERMINAL_NOT_ATTACHED"
    assert (root / "pane-stream-stage.txt").read_text() == "3", "PANE_STREAM_NOT_STILL_STREAMING"
    assert (root / "pane-stream-receipt.txt").read_text().splitlines() == ["VERIFY_RECEIVED:STREAM_531"], "PANE_STREAM_RECEIPT"
    host = (root / "pane-stream-host.txt").read_text()
    assert "TERMINALALPHA" in host and "TERMINALOMEGA" in host, "PANE_STREAM_HOST"
    result = dict(outcome="reader.pane-budget", native_scope="off", native_snapshots=0, native_history_requests=len(chat_history), pane_scrollback_snapshots=len(scrollback), chat=ui, terminal_first="pane-stream-terminal-first.txt", terminal_updated="pane-stream-terminal-updated.txt", receipt="VERIFY_RECEIVED:STREAM_531", still_streaming=True)
    (root / "pane-stream-result.json").write_text(json.dumps(result) + "\n")
    return result


if __name__ == "__main__":
    print("PANE_STREAM_CHAT_AND_TERMINAL_LIVE " + json.dumps(prove(Path(sys.argv[1]))))
