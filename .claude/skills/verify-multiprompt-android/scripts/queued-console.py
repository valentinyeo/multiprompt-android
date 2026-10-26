#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import sys
import uuid

HOME = Path.home()
CWD = HOME / "queued-work"
SID = "a1b2c3d4-1008-4000-8000-000000001008"
TRANSCRIPT = HOME / ".claude/projects" / str(CWD).replace("/", "-") / (SID + ".jsonl")
RECEIPTS = HOME / "receipts.txt"


def append(row):
    row.update(sessionId=SID, timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"))
    with TRANSCRIPT.open("a", encoding="utf-8") as output:
        output.write(json.dumps(row, ensure_ascii=False) + "\n")


def received():
    prefix = "VERIFY_RECEIVED:"
    return [line[len(prefix):] for line in RECEIPTS.read_text(encoding="utf-8").splitlines() if line.startswith(prefix)]


if sys.argv[1:] == ["take-in"]:
    for text in received():
        append(dict(type="attachment", uuid=str(uuid.uuid4()), attachment=dict(type="queued_command", prompt=text)))
    append(dict(type="queue-operation", operation="remove", content="an unrelated queued prompt"))
    raise SystemExit(0)

if sys.argv[1:] == ["reply"]:
    append(dict(type="assistant", uuid=str(uuid.uuid4()),
                message=dict(role="assistant", id="msg_" + str(uuid.uuid4()), content="Both messages received.")))
    raise SystemExit(0)

CWD.mkdir(parents=True, exist_ok=True)
os.chdir(CWD)
TRANSCRIPT.parent.mkdir(parents=True, exist_ok=True)
TRANSCRIPT.write_text("", encoding="utf-8")
meta = HOME / ".claude/sessions" / (str(os.getpid()) + ".json")
meta.parent.mkdir(parents=True, exist_ok=True)
meta.write_text(json.dumps(dict(sessionId=SID, cwd=str(CWD), pid=os.getpid())), encoding="utf-8")
append(dict(type="user", uuid=str(uuid.uuid4()), message=dict(role="user", content="VERIFY_READY_1008")))
append(dict(type="assistant", uuid=str(uuid.uuid4()), message=dict(role="assistant", id="msg_ready", content="Ready for queued messages.")))
print("Claude Code\nVERIFY_READY_1008\nReady for queued messages.\nReady\n❯ ", flush=True)
for line in sys.stdin:
    text = line.rstrip("\r\n")
    with RECEIPTS.open("a", encoding="utf-8") as output:
        output.write("VERIFY_RECEIVED:" + text + "\n")
    append(dict(type="queue-operation", operation="enqueue", content=text))
