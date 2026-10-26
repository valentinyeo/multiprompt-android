#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import sys
import uuid

HOME = Path.home()
CWD = HOME / "resync-work"
SID = "a1b2c3d4-1011-4000-8000-000000001011"
TRANSCRIPT = HOME / ".claude/projects" / str(CWD).replace("/", "-") / (SID + ".jsonl")


def event(role, text, identity):
    row = dict(type=role, uuid=str(uuid.uuid4()), sessionId=SID,
               timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
               message=dict(role=role, content=text, **({"id": identity} if role == "assistant" else {})))
    with TRANSCRIPT.open("a", encoding="utf-8") as output:
        output.write(json.dumps(row, ensure_ascii=False) + "\n")


receipts = Path(sys.argv[1])
CWD.mkdir(parents=True, exist_ok=True)
os.chdir(CWD)
TRANSCRIPT.parent.mkdir(parents=True, exist_ok=True)
TRANSCRIPT.write_text("", encoding="utf-8")
meta = HOME / ".claude/sessions" / (str(os.getpid()) + ".json")
meta.parent.mkdir(parents=True, exist_ok=True)
meta.write_text(json.dumps(dict(sessionId=SID, cwd=str(CWD), pid=os.getpid())), encoding="utf-8")
event("user", "VERIFY_READY_528", "initial")
event("assistant", "FIRST REPLY", "msg_first")
print("Claude Code\nVERIFY_READY_528\nFIRST REPLY\nReady\n❯ ", flush=True)
for line in sys.stdin:
    text = line.rstrip("\r\n")
    with receipts.open("a", encoding="utf-8") as output:
        output.write("VERIFY_RECEIVED:" + text + "\n")
    event("user", "[phone] " + text, "resync")
    event("assistant", "Reply to " + text, "msg_reply_" + str(uuid.uuid4()))
    print("Reply to " + text + "\n❯ ", flush=True)
