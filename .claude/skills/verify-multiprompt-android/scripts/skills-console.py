#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import sys
import uuid

home = Path.home()
cwd = home / "native-skills-work"
cwd.mkdir(parents=True, exist_ok=True)
os.chdir(cwd)
sid = "a1b2c3d4-1009-4000-8000-000000001009"
transcript = home / ".claude/projects" / str(cwd).replace("/", "-") / (sid + ".jsonl")
transcript.parent.mkdir(parents=True, exist_ok=True)
meta = home / ".claude/sessions" / (str(os.getpid()) + ".json")
meta.parent.mkdir(parents=True, exist_ok=True)
meta.write_text(json.dumps(dict(sessionId=sid, cwd=str(cwd), pid=os.getpid())))
for root, name, description, extra in (
    (cwd / ".claude/skills", "review-native", "Review this native project", ""),
    (home / ".claude/skills", "personal-note", "A native personal skill", ""),
    (cwd / ".claude/skills", "hidden-native", "Never show this", "user-invocable: false\n"),
    (home / "native-plugin/skills", "verify-native", "Run native plugin checks", ""),
):
    path = root / name / "SKILL.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"---\nname: {name}\ndescription: {description}\n{extra}---\nBODY_MUST_NOT_BE_INJECTED\n")
plugins = home / ".claude/plugins/installed_plugins.json"
plugins.parent.mkdir(parents=True, exist_ok=True)
plugins.write_text(json.dumps(dict(plugins={"qa@fixture": [dict(scope="user", installPath=str(home / "native-plugin"))]})))
(home / ".claude/settings.json").write_text(json.dumps(dict(enabledPlugins={"qa@fixture": True})))
model, effort = "Opus", "medium"
receipts = Path(sys.argv[1])


def event(role, text):
    with transcript.open("a", encoding="utf-8") as output:
        output.write(json.dumps(dict(type=role, uuid=str(uuid.uuid4()), sessionId=sid,
                                    timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                    message=dict(role=role, content=text))) + "\n")


def render():
    print(f"\x1b[2J\x1b[HClaude Code\nVERIFY_READY_528\n{model} 5.5 {effort}\nReady\n❯ ", flush=True)


event("user", "VERIFY_READY_528")
event("assistant", "Native session skills are ready.")
render()
for line in sys.stdin:
    text = line.rstrip("\r\n")
    with receipts.open("a", encoding="utf-8") as output:
        output.write("VERIFY_RECEIVED:" + text + "\n")
    if text.startswith("/model "):
        model = text.split()[1].capitalize()
    elif text.startswith("/effort "):
        effort = text.split()[1]
    event("user", text)
    event("assistant", f"Received {text}. Claude {model} 5.5 {effort}")
    render()
