#!/usr/bin/env python3
import codecs
import datetime
import json
import os
from pathlib import Path
import re
import sys
import time
import tty
import uuid


def prompts():
    decoder = codecs.getincrementaldecoder("utf-8")()
    buffer = ""
    text = ""
    pasting = False
    while chunk := os.read(sys.stdin.fileno(), 4096):
        buffer += decoder.decode(chunk)
        while buffer:
            if buffer.startswith("\x15") and not pasting:
                text = ""
                buffer = buffer[1:]
            elif buffer.startswith("\x1b[200~") and not pasting:
                buffer = buffer[6:]
                pasting = True
            elif pasting:
                end = buffer.find("\x1b[201~")
                if end < 0:
                    break
                text += buffer[:end]
                buffer = buffer[end + 6:]
                pasting = False
            else:
                end = next((i for i, char in enumerate(buffer) if char in "\r\n"), -1)
                if end < 0:
                    break
                text += buffer[:end]
                buffer = buffer[end + 1:]
                if text:
                    yield text
                text = ""


mode = sys.argv[2]
sid = {"codex": "25340000-0000-4000-8000-000000000001", "claude": "25340000-0000-4000-8000-000000000002"}.get(mode, "verify-" + mode)
home = Path.home()
if mode == "hax":
    transcript = home / ".local/state/hax/sessions/verify" / ("today_" + sid + ".jsonl")
elif mode == "claude":
    transcript = home / ".claude/projects" / str(home).replace("/", "-") / (sid + ".jsonl")
else:
    transcript = home / ".codex/sessions/2026/10/29" / ("rollout-today-" + sid + ".jsonl")
transcript.parent.mkdir(parents=True, exist_ok=True)
output = transcript.open("w", buffering=1)
if mode == "claude":
    output.seek(452 * 1024 * 1024 - 1)
    output.write("\n")
    metadata = home / ".claude/sessions" / (str(os.getpid()) + ".json")
    metadata.parent.mkdir(parents=True, exist_ok=True)
    metadata.write_text(json.dumps(dict(sessionId=sid, cwd=str(home))))
header = dict(type="session", version=1, id=sid, cwd=str(home)) if mode == "hax" else dict(type="session_meta", payload=dict(id=sid, cwd=str(home)))
output.write(json.dumps(header) + "\n")


def event(role, text, identity=None):
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    row = dict(kind=role, text=text, timestamp=stamp) if mode == "hax" else dict(type="response_item", timestamp=stamp, payload=dict(type="message", role=role, content=[dict(type="input_text" if role == "user" else "output_text", text=text)]))
    if mode == "claude":
        identity = identity or str(uuid.uuid4())
        row = dict(type=role, uuid=identity, sessionId=sid, timestamp=stamp,
                   message=dict(role=role, content=text, stop_reason="end_turn", **({"id": identity} if role == "assistant" else {})))
    output.write(json.dumps(row) + "\n")


event("user", "VERIFY_READY_528")
event("assistant", "CODEX_TRANSCRIPT_ONLY_" + mode)
output.close()
output = transcript.open("a", buffering=1)
if mode == "claude" and sys.stdin.isatty():
    tty.setcbreak(sys.stdin)
    print("\x1b[?2004h", end="", flush=True)
print("Hax" if mode == "hax" else "Claude Code" if mode == "claude" else "OpenAI Codex", flush=True)
print("Terminal intentionally contains no chat replies.\n" + ("❯ \n────────────────────" if mode == "claude" else "› "), flush=True)
for text in prompts():
    visible = re.sub(r"\n\[mp:[0-9a-f]{16}\]\s*\Z", "", text)
    with Path(sys.argv[1]).open("a") as receipt:
        receipt.write("VERIFY_RECEIVED:" + visible + "\n")
    event("user", text)
    if mode == "claude" and visible == "STREAM_531":
        identity = str(uuid.uuid4())
        content = ""
        for index, marker in enumerate(("STREAMCHATSTART", "STREAMCHATUPDATE", "TERMINALALPHA", "TERMINALOMEGA")):
            content += marker + " "
            event("assistant", content.strip(), identity)
            print(marker, flush=True)
            (home / "pane-stream-stage.txt").write_text(str(index))
            deadline = time.monotonic() + 120
            release = home / ("pane-stream-release-" + str(index))
            while not release.exists() and time.monotonic() < deadline:
                time.sleep(0.1)
            if not release.exists():
                raise RuntimeError("PANE_STREAM_531: phone never released stage " + str(index))
        (home / "pane-stream-complete.txt").write_text(content.strip())
    else:
        event("assistant", "CODEX_REPLY_" + mode + "_" + visible)
    (home / (mode + "-reply-ns.txt")).write_text(str(time.time_ns()))
    print("Prompt received.\n" + ("❯ \n────────────────────" if mode == "claude" else "› "), flush=True)
