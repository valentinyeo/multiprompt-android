#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import queue
import re
import sys
import termios
import threading
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
    return ACK


def record(home, line):
    with (home / "receipts.txt").open("a", encoding="utf-8") as output:
        output.write(line)


def redraw(shown):
    print("\x1b[2J\x1b[HClaude Code\n" + "\n".join(shown) + "\n\n❯ \n" + "─" * 50, flush=True)


def pump(home, transcript, arriving, quiet=1.0):
    shown = ["REPLY_READY_418", "Ready"]
    redraw(shown)
    pending = []
    while True:
        try:
            line = arriving.get(timeout=quiet)
        except queue.Empty:
            if "".join(pending).strip():
                shown.append(submit(transcript, pending))
                redraw(shown)
            pending = []
            continue
        if line is None:
            return
        record(home, line)
        pending.append(line.rstrip("\r\n"))
        if MARKER.fullmatch(pending[-1]):
            shown.append(submit(transcript, pending))
            redraw(shown)
            pending = []


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or Path.home() != Path("/home/verifyssh") or os.geteuid() == 0:
        raise SystemExit("message reply requires the hosted SSH fixture")
    home = Path.home()
    transcript = write_transcript(home)
    attributes = termios.tcgetattr(sys.stdin)
    attributes[3] &= ~termios.ECHO
    termios.tcsetattr(sys.stdin, termios.TCSANOW, attributes)
    arriving = queue.Queue()

    def console_reader():
        for line in sys.stdin:
            arriving.put(line)
        arriving.put(None)

    threading.Thread(target=console_reader, daemon=True).start()
    pump(home, transcript, arriving)


if __name__ == "__main__":
    main()
