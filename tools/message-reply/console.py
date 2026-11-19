#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import re
import select
import sys
import uuid

BUBBLE = "REPLY_BUBBLE_418"
ACK = "REPLY_DELIVERED_418"
SID = "a1b2c3d4-0418-4000-8000-000000000418"
MARKER = re.compile(r"\[mp:[0-9a-f]{16}\]\Z")


def transcript_path(home):
    cwd = home / "message-reply-work"
    return home / ".claude/projects" / str(cwd).replace("/", "-") / (SID + ".jsonl")


def append(transcript, kind, message):
    with transcript.open("a", encoding="utf-8") as output:
        output.write(json.dumps(dict(
            type=kind, uuid=str(uuid.uuid4()), sessionId=SID,
            timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
            message=message,
        )) + "\n")


def write_transcript(home):
    cwd = home / "message-reply-work"
    cwd.mkdir(parents=True, exist_ok=True)
    os.chdir(cwd)
    transcript = transcript_path(home)
    transcript.parent.mkdir(parents=True, exist_ok=True)
    meta = home / ".claude/sessions" / (str(os.getpid()) + ".json")
    meta.parent.mkdir(parents=True, exist_ok=True)
    meta.write_text(json.dumps(dict(sessionId=SID, cwd=str(cwd), pid=os.getpid())))
    append(transcript, "assistant", dict(role="assistant", content=BUBBLE, id="message-" + uuid.uuid4().hex, stop_reason="end_turn"))
    return transcript


def submit(transcript, lines):
    append(transcript, "user", dict(role="user", content="\n".join(lines)))
    append(transcript, "assistant", dict(role="assistant", content=ACK, id="message-" + uuid.uuid4().hex, stop_reason="end_turn"))


def record(home, line):
    with (home / "receipts.txt").open("a", encoding="utf-8") as output:
        output.write(line)


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or Path.home() != Path("/home/verifyssh") or os.geteuid() == 0:
        raise SystemExit("message reply requires the hosted SSH fixture")
    home = Path.home()
    transcript = write_transcript(home)
    print("Claude Code\nREPLY_READY_418\nReady\n❯ ", flush=True)
    pending = []
    while True:
        ready, _, _ = select.select([sys.stdin], [], [], 1.0)
        if not ready:
            if "".join(pending).strip():
                submit(transcript, pending)
            pending = []
            continue
        line = sys.stdin.readline()
        if not line:
            break
        record(home, line)
        pending.append(line.rstrip("\n"))
        if MARKER.fullmatch(pending[-1]):
            submit(transcript, pending)
            pending = []


if __name__ == "__main__":
    main()
