#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import re
import time

QUESTION = "Which target should ship?"
PANE = "Pane reply stays."
STEP = "Rewrite the inbox row"
STORED = "Stored reply stays off the row."
QUESTION_SID = "a1b2c3d4-2361-4000-8000-000000002361"
STEP_SID = "a1b2c3d4-2361-4000-8000-000000002362"
STEP_SESSION = "live-rowstep"
CWD = Path("/home/verifyssh/row-work")


def project_folder(cwd):
    return re.sub(r"[^A-Za-z0-9]", "-", str(cwd))


def host_key(provider, sid):
    return hashlib.sha256((provider + ":" + sid).encode()).hexdigest()[:32]


def question_events():
    return [dict(
        type="assistant",
        uuid="row-question-1",
        sessionId=QUESTION_SID,
        message=dict(role="assistant", content=[
            dict(type="text", text=PANE),
            dict(type="tool_use", id="toolu_row_question", name="AskUserQuestion", input=dict(questions=[
                dict(question=QUESTION, header="Target", multiSelect=False, options=[
                    dict(label="Phone"),
                    dict(label="Desktop"),
                ]),
            ])),
        ]),
    )]


def step_events():
    return [dict(
        type="assistant",
        uuid="row-step-1",
        sessionId=STEP_SID,
        message=dict(role="assistant", content=[
            dict(type="text", text=STORED),
            dict(type="tool_use", id="toolu_row_step", name="TodoWrite", input=dict(todos=[
                dict(content=STEP, status="in_progress"),
            ])),
        ]),
    )]


def write_jsonl(path, events):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(event) + "\n" for event in events), encoding="utf-8")


def question_transcript_path(home):
    return Path(home) / ".claude/projects" / project_folder(CWD) / (QUESTION_SID + ".jsonl")


def write_question_transcript(home):
    path = question_transcript_path(home)
    write_jsonl(path, question_events())
    return path


def write_step_history(home):
    root = Path(home) / ".local/state/multiprompt/agent-host"
    root.mkdir(parents=True, exist_ok=True)
    key = host_key("claude", STEP_SID)
    history = root / (key + ".history.jsonl")
    write_jsonl(history, step_events())
    config = dict(enabled=True, provider="claude", sid=STEP_SID, tmux=STEP_SESSION, cwd=str(CWD))
    state = dict(
        provider="claude",
        sid=STEP_SID,
        tmux=STEP_SESSION,
        inflight=True,
        display_history=True,
        result=dict(at=1700000000),
    )
    (root / (key + ".config.json")).write_text(json.dumps(config), encoding="utf-8")
    (root / (key + ".state.json")).write_text(json.dumps(state), encoding="utf-8")
    return history


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("HOME") != "/home/verifyssh" or os.geteuid() == 0:
        raise SystemExit("row console requires the hosted fixture")
    home = Path("/home/verifyssh")
    CWD.mkdir(parents=True, exist_ok=True)
    os.chdir(CWD)
    transcript = write_question_transcript(home)
    history = write_step_history(home)
    meta = home / ".claude/sessions" / (str(os.getpid()) + ".json")
    meta.parent.mkdir(parents=True, exist_ok=True)
    meta.write_text(json.dumps(dict(sessionId=QUESTION_SID, cwd=str(CWD), pid=os.getpid())), encoding="utf-8")
    (home / "row-layout.txt").write_text(
        f"question_transcript={transcript}\nstep_history={history}\n",
        encoding="utf-8",
    )
    print(f"\x1b[2J\x1b[HClaude Code\n\u23fa {PANE}\n\u276f\n", flush=True)
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
