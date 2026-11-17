import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import threading
import time

LOG = Path.home() / "reader-requests.jsonl"


def record(kind, session):
    line = json.dumps(dict(kind=kind, session=session, at=time.monotonic_ns())) + "\n"
    fd = os.open(LOG, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    try:
        os.write(fd, line.encode())
    finally:
        os.close(fd)


def snapshot_command(command, observer):
    match = re.search(r"has-session -t '=(.*?)'", command)
    if "__MP_TMUX_SNAPSHOT__" not in command or not match:
        return command
    prefix = f"python3 {shlex.quote(observer)} snapshot {shlex.quote(match[1])}; "
    return command.replace("tmux capture-pane", prefix + "tmux capture-pane")


def forward_history(source, destination):
    try:
        for line in source:
            try:
                query = json.loads(line)
                if isinstance(query, dict) and "cached_sid" in query:
                    record("history", query["tmux"])
            except (ValueError, KeyError):
                pass
            destination.write(line)
            destination.flush()
    except BrokenPipeError:
        pass
    finally:
        destination.close()


def main():
    if sys.argv[1:] and sys.argv[1] == "snapshot":
        record("snapshot", sys.argv[2])
        return
    command = os.environ["SSH_ORIGINAL_COMMAND"]
    command = snapshot_command(command, str(Path(__file__).resolve()))
    if "--serve" not in command:
        os.execv("/bin/bash", ["bash", "-c", command])
    child = subprocess.Popen(["bash", "-c", command], stdin=subprocess.PIPE)
    threading.Thread(target=forward_history, args=(sys.stdin.buffer, child.stdin), daemon=True).start()
    raise SystemExit(child.wait())


if __name__ == "__main__":
    main()
