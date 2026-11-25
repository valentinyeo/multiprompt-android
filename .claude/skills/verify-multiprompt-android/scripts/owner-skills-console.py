#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import re
import sys
import uuid

home = Path.home()
cwd = Path("/home/skillowner/chat-work")
sid = "a1b2c3d4-1009-4000-8000-000000001010"
transcript = home / ".claude/projects" / str(cwd).replace("/", "-") / (sid + ".jsonl")
transcript.parent.mkdir(parents=True, exist_ok=True)
meta = home / ".claude/sessions" / (str(os.getpid()) + ".json")
meta.parent.mkdir(parents=True, exist_ok=True)
meta.write_text(json.dumps(dict(sessionId=sid, cwd=str(cwd), pid=os.getpid())))
receipts = Path(sys.argv[1])


def event(role, text):
    message = dict(role=role, content=text)
    if role == "assistant":
        message.update(id="message-" + uuid.uuid4().hex, stop_reason="end_turn")
    with transcript.open("a", encoding="utf-8") as output:
        output.write(json.dumps(dict(type=role, uuid=str(uuid.uuid4()), sessionId=sid,
                                     timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                     message=message)) + "\n")


def render():
    print("\x1b[2J\x1b[HClaude Code\nVERIFY_READY_528\nReady\n❯ ", flush=True)


def main():
    event("user", "VERIFY_READY_528")
    event("assistant", "Owner account skills are ready.")
    render()
    for line in sys.stdin:
        text = line.rstrip("\r\n")
        with receipts.open("a", encoding="utf-8") as output:
            output.write("VERIFY_RECEIVED:" + text + "\n")
        if re.fullmatch(r"\[mp:[0-9a-f]{16}\]", text):
            continue
        event("user", text)
        event("assistant", f"Received {text}")
        render()


if __name__ == "__main__":
    main()
