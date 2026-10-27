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


def record(role, text):
    transcript.write(json.dumps({"type": role, "uuid": str(uuid.uuid4()), "message": {
        "role": role, "content": [{"type": "text", "text": text}],
    }}) + "\n")


def receipt(text):
    with receipts.open("a") as stream:
        stream.write(text + "\n")


record("assistant", "Older choices:\nA. Old first option\nB. Old second option")
record("user", "Choose a route")
record("assistant", "Choose a route:\n\nA. **Create a new account.** Keep work separate.\n\nB. **Use your own account.** Keep the current setup.")
print("Claude Code · choice protocol fixture", flush=True)
line = input()
receipt("VERIFY_RECEIVED:" + line)
record("user", line)
record("assistant", "Now answer the live question.")
settings = termios.tcgetattr(sys.stdin)
try:
    tty.setraw(sys.stdin.fileno())
    print("\x1b[2J\x1b[H────────────────────────────────────────\r\nWhich route should we use?\r\n\r\n❯ 1. Create a new account\r\n  2. Use the current account\r\n  3. Type something.\r\n────────────────────────────────────────\r\nEnter to select · Tab/Arrow keys to navigate · Esc to cancel", end="\r\n", flush=True)
    key = sys.stdin.read(1)
finally:
    termios.tcsetattr(sys.stdin, termios.TCSADRAIN, settings)
receipt("VERIFY_QUESTION_KEY:" + key)
record("assistant", "Question answered: " + ("Use the current account" if key == "2" else "WRONG KEY " + repr(key)))
print("\x1b[2J\x1b[HClaude Code · question answered", flush=True)
line = input()
receipt("VERIFY_RECEIVED:" + line)
record("user", line)
record("assistant", "1. **Current setup**\n2. **Work remaining**\n\nWould you like me to continue?")
for line in sys.stdin:
    receipt("UNEXPECTED_EXTRA_INPUT:" + line.rstrip("\n"))
