import datetime
import json
import os
from pathlib import Path
import sys
import uuid

home = Path.home()
cwd = home / "notification-work"
sid = "11801180-1180-4000-8000-000000001180"
transcript = home / ".claude/projects" / str(cwd).replace("/", "-") / (sid + ".jsonl")
cwd.mkdir(parents=True, exist_ok=True)
os.chdir(cwd)
transcript.parent.mkdir(parents=True, exist_ok=True)
transcript.write_text("")
metadata = home / ".claude/sessions" / (str(os.getpid()) + ".json")
metadata.parent.mkdir(parents=True, exist_ok=True)
metadata.write_text(json.dumps(dict(sessionId=sid, cwd=str(cwd), pid=os.getpid())))

def event(role, text):
    row = dict(type=role, uuid=str(uuid.uuid4()), sessionId=sid,
               timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
               message=dict(role=role, content=text, id=str(uuid.uuid4())))
    with transcript.open("a") as output:
        output.write(json.dumps(row) + "\n")

print("Claude Code\nNotification fixture ready\n❯ ", flush=True)
for line in sys.stdin:
    text = line.strip()
    event("user", text)
    if text == "/rc":
        print("❯ /rc\n❯ ", flush=True)
    else:
        event("assistant", "Checkout fixed. Tests passed.")
        print("● Checkout fixed. Tests passed.\n❯ ", flush=True)
    (home / "notification-receipt").write_text(text)
