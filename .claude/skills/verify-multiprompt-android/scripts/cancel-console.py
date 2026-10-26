#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import select
import shutil
import sys
import termios
import tty
import uuid

HOME = Path.home()
CWD = HOME / "cancel-work"
SID = "a1b2c3d4-1010-4000-8000-000000001010"
TRANSCRIPT = HOME / ".claude/projects" / str(CWD).replace("/", "-") / (SID + ".jsonl")
RECEIPTS = HOME / "receipts.txt"
SUBMITTED = HOME / "submitted.txt"
CONTROL = HOME / "cancel-control.txt"


def append(row):
    row.update(sessionId=SID, timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"))
    with TRANSCRIPT.open("a", encoding="utf-8") as output:
        output.write(json.dumps(row, ensure_ascii=False) + "\n")


def note(path, line):
    with path.open("a", encoding="utf-8") as output:
        output.write(line + "\n")


if sys.argv[1:] in (["take-in"], ["reply"]):
    note(CONTROL, sys.argv[1])
    raise SystemExit(0)

CWD.mkdir(parents=True, exist_ok=True)
os.chdir(CWD)
TRANSCRIPT.parent.mkdir(parents=True, exist_ok=True)
TRANSCRIPT.write_text("", encoding="utf-8")
CONTROL.write_text("", encoding="utf-8")
meta = HOME / ".claude/sessions" / (str(os.getpid()) + ".json")
meta.parent.mkdir(parents=True, exist_ok=True)
meta.write_text(json.dumps(dict(sessionId=SID, cwd=str(CWD), pid=os.getpid())), encoding="utf-8")
append(dict(type="user", uuid=str(uuid.uuid4()), message=dict(role="user", content="VERIFY_READY_1010")))
append(dict(type="assistant", uuid=str(uuid.uuid4()), message=dict(role="assistant", id="msg_ready", content="Ready for cancel tests.")))

queue = []
history = []
box = ""
pasting = False


def render():
    rows = box.split("\n") if box else [""]
    first = rows[0]
    if queue and not box:
        first = "\x1b[2mPress up to edit queued messages\x1b[0m"
    top = ["Claude Code", "VERIFY_READY_1010", "Ready for cancel tests."]
    top += ["❯ " + " ".join(text.split()) for text in queue]
    top += ["✻ Working…"] if queue else []
    bottom = ["─" * 60, "❯ " + first, *("  " + row for row in rows[1:]), "─" * 60, "  ? for shortcuts"]
    height = shutil.get_terminal_size().lines
    lines = top + [""] * max(0, height - len(top) - len(bottom)) + bottom
    sys.stdout.write("\x1b[H" + "\x1b[K\r\n".join(lines) + "\x1b[K")
    sys.stdout.flush()


def enter():
    global box
    text = box
    box = ""
    if not text.strip():
        return
    queue.append(text)
    history.append(text)
    note(RECEIPTS, "VERIFY_RECEIVED:" + " ".join(text.split()))
    append(dict(type="queue-operation", operation="enqueue", content=text))


def up():
    global box
    if queue:
        append(dict(type="queue-operation", operation="popAll", content="\n".join(queue)))
        box = "\n".join(queue)
        queue.clear()
    elif history and not box:
        box = history[-1]


def control():
    lines = [line for line in CONTROL.read_text(encoding="utf-8").splitlines() if line]
    if not lines:
        return False
    CONTROL.write_text("", encoding="utf-8")
    for command in lines:
        if command == "take-in":
            for text in queue:
                note(SUBMITTED, "VERIFY_SUBMITTED:" + " ".join(text.split()))
                append(dict(type="attachment", uuid=str(uuid.uuid4()), attachment=dict(type="queued_command", prompt=text)))
            queue.clear()
        elif command == "reply":
            append(dict(type="assistant", uuid=str(uuid.uuid4()),
                        message=dict(role="assistant", id="msg_" + str(uuid.uuid4()), content="Everything that was queued is received.")))
    return True


def feed(data):
    global box, pasting
    index = 0
    while index < len(data):
        if data.startswith("\x1b[200~", index):
            pasting = True
            index += 6
        elif data.startswith("\x1b[201~", index):
            pasting = False
            index += 6
        elif data.startswith("\x1b[A", index):
            up()
            index += 3
        elif data[index] == "\x15":
            box = ""
            index += 1
        elif data[index] in "\r\n" and not pasting:
            enter()
            index += 1
        else:
            box += "\n" if data[index] == "\r" else data[index]
            index += 1


tty.setraw(sys.stdin.fileno(), termios.TCSANOW)
sys.stdout.write("\x1b[?2004h")
render()
while True:
    ready, _, _ = select.select([sys.stdin], [], [], 0.2)
    if ready:
        chunk = os.read(sys.stdin.fileno(), 65536)
        if not chunk:
            break
        feed(chunk.decode("utf-8", "replace"))
        render()
    elif control():
        render()
