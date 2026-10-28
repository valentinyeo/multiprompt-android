#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

home = Path.home()
cwd = home / "working-proof"
cwd.mkdir(exist_ok=True)
os.chdir(cwd)
sid = "11170000-0000-4000-8000-000000001117"
transcript = home / ".claude/projects" / str(cwd).replace("/", "-") / (sid + ".jsonl")
transcript.parent.mkdir(parents=True, exist_ok=True)
meta = home / ".claude/sessions" / (str(os.getpid()) + ".json")
meta.parent.mkdir(parents=True, exist_ok=True)
meta.write_text(json.dumps(dict(sessionId=sid, cwd=str(cwd), pid=os.getpid())))
state = home / ".local/state/multiprompt/tab-state.json"
state.parent.mkdir(parents=True, exist_ok=True)
events = home / "working-events.jsonl"
events.touch()


def record(kind, **fields):
    with events.open("a") as output:
        output.write(json.dumps(dict(event=kind, at=time.time_ns() // 1000000, **fields)) + "\n")


def turn(active):
    temp = state.with_suffix(".tmp")
    temp.write_text(json.dumps({"verify-session": {"state": "active" if active else "done", "at": int(time.time())}}))
    temp.replace(state)
    record("start" if active else "end")


with transcript.open("w") as output:
    for role, text in [("user", "Run quiet local tools."), ("assistant", "WORKING_DOTS_READY")]:
        output.write(json.dumps(dict(type=role, uuid=str(uuid.uuid4()), sessionId=sid,
            message=dict(role=role, content=[dict(type="text", text=text)]))) + "\n")
state.write_text(json.dumps({"verify-session": {"state": "done", "at": int(time.time())}}))
print("Claude Code protocol fixture\nWORKING_DOTS_READY\n❯ ", flush=True)
while not (home / "start-working-proof").exists():
    time.sleep(0.1)
turn(True)
started = time.monotonic()
index = 0
while time.monotonic() - started < 135:
    result = subprocess.run(["sh", "-c", "printf quiet-tool-result"], capture_output=True, text=True, check=True)
    index += 1
    record("tool", index=index, output=result.stdout)
    time.sleep(1)
turn(False)
record("complete", tools=index, transcript_bytes=transcript.stat().st_size)
while True:
    time.sleep(60)
