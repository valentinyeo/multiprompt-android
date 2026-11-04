#!/usr/bin/env python3
import argparse
import importlib.util
import json
import os
import re
import sqlite3
import sys
import time
import uuid
from pathlib import Path

TERMINAL_STATES = ("accepted", "started", "failed", "cancelled")
PROVIDERS = ("claude", "codex")
SESSION_ID = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
MESSAGE_ID = re.compile(r"[A-Za-z0-9_-]{16,80}\Z")
_reader = None


def runtime_dir():
    return Path(os.environ.get("MULTIPROMPT_RUNTIME_DIR") or (Path.home() / ".multiprompt-runtime"))


def _runtime_reader():
    try:
        active = json.loads((runtime_dir() / "active.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    digest = active.get("sha256") if isinstance(active, dict) else None
    if not isinstance(digest, str) or len(digest) != 64:
        return None
    path = runtime_dir() / (digest + ".py")
    return path if path.is_file() else None


def reader_path():
    configured = os.environ.get("MULTIPROMPT_READER_SOURCE")
    if configured:
        return Path(configured)
    installed = _runtime_reader()
    if installed is not None:
        return installed
    return Path(__file__).resolve().parent / "chat_history_reader.py"


def reader_module(path=None):
    global _reader
    if _reader is not None:
        return _reader
    source = Path(path or reader_path())
    spec = importlib.util.spec_from_file_location("mp_reader", source)
    if spec is None or spec.loader is None:
        raise ValueError("reader not loadable: " + str(source))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    _reader = module
    return module


def locator_tmux(reader, provider, sid):
    path = reader.message_store_path()
    if not path.is_file():
        return None
    try:
        with sqlite3.connect(path.as_uri() + "?mode=ro", uri=True) as db:
            row = db.execute("SELECT tmux FROM message_locators WHERE provider=? AND sid=?", (provider, sid)).fetchone()
    except sqlite3.Error:
        return None
    return row[0] if row else None


def submit(reader, provider, sid, tmux, text, message_id=None):
    token = message_id or ("mp-" + uuid.uuid4().hex)
    if MESSAGE_ID.match(token) is None:
        raise ValueError("invalid message id: " + token)
    query = dict(op="message_submit", message_protocol=1, provider=provider, sid=sid, tmux=tmux, message_id=token, text=text)
    result = reader.message_store_request(query)
    return result.get("message") or {"id": token, "state": "queued"}


def dispatch(reader, provider, sid, tmux):
    query = dict(op="message_dispatch", message_protocol=1, provider=provider, sid=sid, tmux=tmux)
    try:
        return reader.message_store_request(query)
    except (OSError, ValueError, KeyError, TypeError):
        return None


def status(reader, provider, sid, tmux, message_id):
    query = dict(op="message_status", message_protocol=1, provider=provider, sid=sid, tmux=tmux, message_id=message_id)
    return (reader.message_store_request(query) or {}).get("message")


def poll(reader, provider, sid, tmux):
    try:
        reader.message_poll(tmux, provider, sid)
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        pass


def wait(reader, provider, sid, tmux, message_id, timeout):
    deadline = time.monotonic() + max(0.0, timeout)
    message = status(reader, provider, sid, tmux, message_id)
    while message is None or message.get("state") not in TERMINAL_STATES:
        if time.monotonic() >= deadline:
            return message
        time.sleep(0.2)
        if message is not None and message.get("state") == "queued":
            dispatch(reader, provider, sid, tmux)
        poll(reader, provider, sid, tmux)
        message = status(reader, provider, sid, tmux, message_id) or message
    return message


def parse(argv):
    parser = argparse.ArgumentParser(prog="mp-send", description="Put one message into an agent session they own, without typing into a terminal.")
    parser.add_argument("provider", choices=PROVIDERS)
    parser.add_argument("sid")
    parser.add_argument("text", nargs="?")
    parser.add_argument("--tmux", default=None)
    parser.add_argument("--id", dest="message_id", default=None)
    parser.add_argument("--wait", action="store_true")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--reader", default=None)
    return parser.parse_args(argv)


def run(args):
    if SESSION_ID.match(args.sid) is None:
        raise ValueError("invalid session id: " + str(args.sid))
    text = sys.stdin.read() if args.text in (None, "-") else args.text
    if not text.strip() or "\x00" in text:
        raise ValueError("empty message")
    reader = reader_module(args.reader)
    tmux = args.tmux or locator_tmux(reader, args.provider, args.sid)
    if not tmux:
        raise ValueError("no terminal binding for this session; pass --tmux")
    message = submit(reader, args.provider, args.sid, tmux, text, args.message_id)
    message_id = message.get("id")
    dispatch(reader, args.provider, args.sid, tmux)
    final = wait(reader, args.provider, args.sid, tmux, message_id, args.timeout) if args.wait else message
    if args.json:
        print(json.dumps(dict(id=message_id, state=(final or {}).get("state"), receipt=(final or {}).get("receipt")), separators=(",", ":")))
    else:
        print(message_id)
        if args.wait:
            line = "state=" + str((final or {}).get("state"))
            if (final or {}).get("receipt"):
                line += " receipt=" + str(final["receipt"])
            print(line)
    state = (final or {}).get("state")
    if not args.wait:
        return 0
    if state == "failed" or state == "cancelled":
        return 3
    if state not in TERMINAL_STATES:
        print("timed out waiting for the message", file=sys.stderr)
        return 4
    return 0


def main(argv=None):
    args = parse(sys.argv[1:] if argv is None else argv)
    try:
        return run(args)
    except (OSError, ValueError, KeyError, TypeError, sqlite3.Error) as error:
        print("mp-send: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
