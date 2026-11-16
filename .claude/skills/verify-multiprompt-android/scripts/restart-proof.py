#!/usr/bin/env python3
import hashlib
import json
import os
import sys
from pathlib import Path

PROVIDER = "claude"
SID = "verify-restart-1"
FAILURES = (
    "Restart failed",
    "no live host",
    "Restart is unavailable",
    "No live chat to restart",
    "No idle chat to restart",
    "did not answer",
    "could not restart",
)


def home():
    override = os.environ.get("RESTART_PROOF_HOME")
    return Path(override) if override else Path.home()


def host_key(provider, sid):
    return hashlib.sha256(f"{provider}:{sid}".encode()).hexdigest()[:32]


def directory():
    return home() / ".local/state/multiprompt/agent-host"


def named(suffix):
    return directory() / f"{host_key(PROVIDER, SID)}.{suffix}.json"


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    temporary.write_text(json.dumps(value, separators=(",", ":"), sort_keys=True), encoding="utf-8")
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


def load_restart():
    path = named("restart")
    if not path.is_file():
        raise SystemExit("restart request was not written")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise SystemExit("restart request is not json")
    if not isinstance(value, dict):
        raise SystemExit("restart request is not json")
    stamp = value.get("at")
    if isinstance(stamp, bool) or not isinstance(stamp, (int, float)):
        raise SystemExit("restart request has no time")
    return path, stamp


def prepare(pid_text):
    try:
        pid = int(pid_text)
    except ValueError:
        raise SystemExit("restart owner pid is not an integer")
    if pid <= 0:
        raise SystemExit("restart owner pid is not a live process id")
    write_json(named("config"), {"enabled": True, "provider": PROVIDER, "sid": SID, "tmux": "verify-session"})
    write_json(named("owner"), {"owner": "host", "pid": pid})
    print(f"RESTART_OWNER_READY {SID}")


def absent():
    if named("restart").exists():
        raise SystemExit("restart request already existed")
    print("RESTART_ABSENT")


def written(minimum):
    _, stamp = load_restart()
    if stamp < float(minimum):
        raise SystemExit(f"restart request is older than the tap: {stamp}")
    print(f"RESTART_REQUESTED at={stamp}")


def newer(previous):
    _, stamp = load_restart()
    if not stamp > float(previous):
        raise SystemExit(f"restart request did not move: {stamp}")
    print(f"RESTART_REQUESTED at={stamp}")


def show():
    path, _ = load_restart()
    text = path.read_text(encoding="utf-8")
    sys.stdout.write(text if text.endswith("\n") else text + "\n")


def clear_ui(path, marker):
    text = Path(path).read_text(encoding="utf-8")
    if marker not in text:
        raise SystemExit(f"restart screen missing {marker}")
    for phrase in FAILURES:
        if phrase in text:
            raise SystemExit(f"restart failed on screen: {phrase}")
    print("RESTART_UI_CLEAR")


def main():
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "sid" and len(sys.argv) == 2:
        print(SID)
    elif command == "prepare" and len(sys.argv) == 3:
        prepare(sys.argv[2])
    elif command == "absent" and len(sys.argv) == 2:
        absent()
    elif command == "written" and len(sys.argv) == 3:
        written(sys.argv[2])
    elif command == "newer" and len(sys.argv) == 3:
        newer(sys.argv[2])
    elif command == "show" and len(sys.argv) == 2:
        show()
    elif command == "clear-ui" and len(sys.argv) == 4:
        clear_ui(sys.argv[2], sys.argv[3])
    else:
        raise SystemExit("usage: restart-proof.py sid|prepare PID|absent|written STAMP|newer PREVIOUS|show|clear-ui XML MARKER")


if __name__ == "__main__":
    main()
