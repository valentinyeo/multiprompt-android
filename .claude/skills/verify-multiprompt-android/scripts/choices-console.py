#!/usr/bin/env python3
import json
import sys
import termios
import tty
import uuid
from pathlib import Path

receipts = Path(sys.argv[1])
folder = Path.home() / ".claude/projects/verify-choices"
folder.mkdir(parents=True, exist_ok=True)
transcript = (folder / "97400000-0000-4000-8000-000000000001.jsonl").open("a", buffering=1)


def record(role, parts):
    if isinstance(parts, str):
        parts = [dict(type="text", text=parts)]
    transcript.write(json.dumps(dict(type=role, uuid=str(uuid.uuid4()), message=dict(role=role, content=parts))) + "\n")


def request(identity, question):
    record("assistant", [dict(type="tool_use", id=identity, name="AskUserQuestion", input=dict(questions=[question]))])


def settle(identity):
    record("user", [dict(type="tool_result", tool_use_id=identity, content="Answered")])


def receipt(text):
    with receipts.open("a") as stream:
        stream.write(text + "\n")


record("assistant", "Ordinary reply lists:\n\n1. First route\n2. Second route\n\n- Ordinary bullet")
request("single", dict(question="Which route should we use?", header="Account", multiSelect=False, options=[
    dict(label="Create a new account", description="Keep work separate."),
    dict(label="Use the current account", description="Keep the current setup.")]))
settings = termios.tcgetattr(sys.stdin)
try:
    tty.setraw(sys.stdin.fileno())
    print("\x1b[2J\x1b[H────────────────────────────────────────\r\nWhich route should we use?\r\n\r\n❯ 1. Create a new account\r\n  2. Use the current account\r\n  3. Type something.\r\n────────────────────────────────────────\r\nEnter to select · Tab/Arrow keys to navigate · Esc to cancel", end="\r\n", flush=True)
    key = sys.stdin.read(1)
    receipt("VERIFY_QUESTION_KEY:" + key)
    settle("single")
    request("multi", dict(question="What should we keep?", header="Keep", multiSelect=True, options=[
        dict(label="History", description="Keep earlier messages."), dict(label="Settings", description="Keep preferences.")]))
    selected = set()
    cursor = 0
    other = ""
    editing = False

    def screen():
        lines = ["────────────────────────────────────────", "What should we keep?"]
        for index, label in enumerate(["History", "Settings", "Type something."]):
            lines.append(("❯ " if index == cursor else "  ") + str(index + 1) + ". " + ("☒ " if index in selected else "☐ ") + label)
        lines += ["────────────────────────────────────────", "Space to toggle · Enter to submit · Esc to cancel"]
        print("\x1b[2J\x1b[H" + "\r\n".join(lines), end="\r\n", flush=True)

    screen()
    while True:
        key = sys.stdin.read(1)
        if editing:
            if key in "\r\n":
                break
            other += key
        elif key == "\x1b":
            direction = sys.stdin.read(2)
            cursor = min(2, cursor + 1) if direction == "[B" else max(0, cursor - 1)
            screen()
        elif key == " ":
            if cursor == 2:
                editing = True
            elif cursor in selected:
                selected.remove(cursor)
            else:
                selected.add(cursor)
            screen()
        elif key in "\r\n":
            break
    receipt("VERIFY_MULTI:" + json.dumps(dict(selected=sorted(selected), other=other), sort_keys=True))
    settle("multi")
    record("assistant", "Structured questions answered.")
    print("\x1b[2J\x1b[HClaude Code · questions answered", flush=True)
finally:
    termios.tcsetattr(sys.stdin, termios.TCSADRAIN, settings)
for line in sys.stdin:
    receipt("UNEXPECTED_EXTRA_INPUT:" + line.rstrip("\n"))
