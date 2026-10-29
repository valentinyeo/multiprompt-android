#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import sys
import time

mode = sys.argv[2]
sid = "verify-" + mode
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


def event(role, text):
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    row = dict(kind=role, text=text, timestamp=stamp) if mode == "hax" else dict(type="response_item", timestamp=stamp, payload=dict(type="message", role=role, content=[dict(type="input_text" if role == "user" else "output_text", text=text)]))
    if mode == "claude":
        row = dict(type=role, timestamp=stamp, message=dict(role=role, content=text, stop_reason="end_turn"))
    output.write(json.dumps(row) + "\n")


event("user", "VERIFY_READY_528")
event("assistant", "CODEX_TRANSCRIPT_ONLY_" + mode)
print("Hax" if mode == "hax" else "Claude Code" if mode == "claude" else "OpenAI Codex", flush=True)
print("Terminal intentionally contains no chat replies.\n" + ("❯ \n────────────────────" if mode == "claude" else "› "), flush=True)
for line in sys.stdin:
    text = line.rstrip("\r\n")
    received = time.time_ns()
    with Path(sys.argv[1]).open("a") as receipt:
        receipt.write("VERIFY_RECEIVED:" + text + "\n")
    event("user", text)
    event("assistant", "CODEX_REPLY_" + mode + "_" + text)
    (home / (mode + "-reply-ns.txt")).write_text(str(time.time_ns()))
    print("Prompt received.\n" + ("❯ \n────────────────────" if mode == "claude" else "› "), flush=True)
