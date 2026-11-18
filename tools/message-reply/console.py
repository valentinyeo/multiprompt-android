#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import sys
import uuid

BUBBLE = "REPLY_BUBBLE_418"


def write_transcript(home):
    cwd = home / "message-reply-work"
    cwd.mkdir(parents=True, exist_ok=True)
    os.chdir(cwd)
    sid = "a1b2c3d4-0418-4000-8000-000000000418"
    transcript = home / ".claude/projects" / str(cwd).replace("/", "-") / (sid + ".jsonl")
    transcript.parent.mkdir(parents=True, exist_ok=True)
    meta = home / ".claude/sessions" / (str(os.getpid()) + ".json")
    meta.parent.mkdir(parents=True, exist_ok=True)
    meta.write_text(json.dumps(dict(sessionId=sid, cwd=str(cwd), pid=os.getpid())))
    message = dict(role="assistant", content=BUBBLE, id="message-" + uuid.uuid4().hex, stop_reason="end_turn")
    with transcript.open("a", encoding="utf-8") as output:
        output.write(json.dumps(dict(
            type="assistant", uuid=str(uuid.uuid4()), sessionId=sid,
            timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
            message=message,
        )) + "\n")
    return transcript


def record(home, line):
    with (home / "receipts.txt").open("a", encoding="utf-8") as output:
        output.write(line)


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or Path.home() != Path("/home/verifyssh") or os.geteuid() == 0:
        raise SystemExit("message reply requires the hosted SSH fixture")
    home = Path.home()
    write_transcript(home)
    print("Claude Code\nREPLY_READY_418\nReady\n❯ ", flush=True)
    for line in sys.stdin:
        record(home, line)


if __name__ == "__main__":
    main()
