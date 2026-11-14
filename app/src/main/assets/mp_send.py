#!/usr/bin/env python3
import argparse
import importlib.util
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import uuid
from pathlib import Path

TERMINAL_STATES = ("accepted", "started", "failed", "cancelled")
HANDOFF_STATES = ("sent", "accepted", "started", "failed", "cancelled")
HANDOFF_TIMEOUT = 15.0
PROVIDERS = ("claude", "codex", "pi", "hax", "cursor")
TARGET_PER_MINUTE = 6
SENDER_PER_MINUTE = 12
ANSWER_TAIL_BYTES = 4 * 1024 * 1024
ANSWER_CHARS = 20000
SESSION_ID = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
MESSAGE_ID = re.compile(r"[A-Za-z0-9_-]{16,80}\Z")
TMUX_NAME = re.compile(r"[A-Za-z0-9_.:-]{1,64}\Z")
MENTION = re.compile(r"@?(.*?)\{([A-Za-z0-9_.:-]{1,64})\}\Z", re.S)
_reader = None


class RateLimited(ValueError):
    pass


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
    query = dict(op="message_submit", message_protocol=1, provider=provider, sid=sid, tmux=tmux, message_id=token, text=text, origin="agent")
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


def handoff(reader, provider, sid, tmux, message_id, timeout):
    deadline = time.monotonic() + max(0.0, min(timeout, HANDOFF_TIMEOUT))
    message = status(reader, provider, sid, tmux, message_id)
    while message is None or message.get("state") not in HANDOFF_STATES:
        if time.monotonic() >= deadline:
            return message
        time.sleep(0.2)
        if message is not None and message.get("state") == "queued":
            dispatch(reader, provider, sid, tmux)
        poll(reader, provider, sid, tmux)
        message = status(reader, provider, sid, tmux, message_id) or message
    return message


def state_root():
    return Path.home() / ".local/state/multiprompt"


def read_json(path):
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return value if isinstance(value, dict) else None


def pid_alive(pid):
    if not isinstance(pid, int) or pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def tmux_names():
    try:
        done = subprocess.run(["tmux", "list-sessions", "-F", "#{session_name}"], capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.SubprocessError):
        return None
    if done.returncode != 0:
        return None
    return set(done.stdout.split())


def published_names():
    known = read_json(state_root() / "chat-names.json") or {}
    return {tmux: entry["name"] for tmux, entry in known.items() if isinstance(entry, dict) and isinstance(entry.get("name"), str) and entry["name"].strip()}


def live_chats():
    root = state_root() / "agent-host"
    names = published_names()
    sessions = tmux_names()
    best = {}
    for config_path in sorted(root.glob("*.config.json")):
        config = read_json(config_path)
        state = read_json(config_path.with_name(config_path.name[: -len(".config.json")] + ".state.json"))
        if not config or not state or config.get("enabled") is False:
            continue
        tmux, sid, provider = config.get("tmux"), state.get("sid"), state.get("provider")
        if not isinstance(tmux, str) or TMUX_NAME.match(tmux) is None or not isinstance(sid, str) or SESSION_ID.match(sid) is None:
            continue
        if provider not in PROVIDERS or not pid_alive(state.get("pid")):
            continue
        if sessions is not None and tmux not in sessions:
            continue
        started = state.get("started") if isinstance(state.get("started"), (int, float)) else 0
        row = dict(name=names.get(tmux), provider=provider, sid=sid, tmux=tmux, cwd=config.get("cwd"), state="busy" if state.get("inflight") else "idle", started=started)
        if tmux not in best or started >= best[tmux]["started"]:
            best[tmux] = row
    rows = sorted(best.values(), key=lambda row: ((row["name"] or row["tmux"]).casefold(), row["tmux"]))
    for row in rows:
        del row["started"]
    return rows


def recent_agent_messages(reader, provider=None, sid=None, sender=None, window=60.0):
    locate = getattr(reader, "message_store_path", None)
    if locate is None:
        return 0
    path = locate()
    if not path.is_file():
        return 0
    since = time.time() - window
    sql = "SELECT COUNT(*) FROM shared_messages WHERE origin='agent' AND created>?"
    args = [since]
    if provider is not None:
        sql += " AND provider=? AND sid=?"
        args += [provider, sid]
    if sender is not None:
        escaped = sender.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        sql += " AND text LIKE ? ESCAPE '\\'"
        args.append("From " + escaped + ": %")
    try:
        with sqlite3.connect(path.as_uri() + "?mode=ro", uri=True) as db:
            return db.execute(sql, args).fetchone()[0]
    except sqlite3.Error:
        return 0


def check_rate(reader, provider, sid, sender=None):
    if recent_agent_messages(reader, provider, sid) >= TARGET_PER_MINUTE:
        raise RateLimited("slow down: this chat already got " + str(TARGET_PER_MINUTE) + " messages from chats in the last minute; wait and try again")
    if sender is not None and recent_agent_messages(reader, sender=sender) >= SENDER_PER_MINUTE:
        raise RateLimited("slow down: you already sent " + str(SENDER_PER_MINUTE) + " messages to chats in the last minute; wait and try again")


def tail_lines(path, limit=ANSWER_TAIL_BYTES):
    with open(path, "rb") as source:
        source.seek(0, 2)
        size = source.tell()
        source.seek(max(0, size - limit))
        data = source.read()
    lines = data.split(b"\n")
    if size > limit:
        lines = lines[1:]
    return lines


def transcript_path(reader, chat):
    provider, sid, cwd = chat["provider"], chat["sid"], chat["cwd"] or ""
    names = [sid]
    try:
        if reader.host_dispatch_switch(provider, sid):
            native = reader.host_session_state(provider, sid).get("native_session")
            if isinstance(native, str) and SESSION_ID.match(native):
                names.insert(0, native)
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        pass
    for name in names:
        try:
            path = reader.conversation_path(provider, name, cwd)
        except PermissionError:
            continue
        if path is not None:
            return path
    return None


def last_answer(reader, chat):
    if chat["provider"] in ("hax", "cursor"):
        raise ValueError("reading answers is not available for " + chat["provider"] + " chats")
    path = transcript_path(reader, chat)
    if path is None:
        return None
    for raw in reversed(tail_lines(path)):
        try:
            event = json.loads(raw)
        except (ValueError, UnicodeError):
            continue
        row = reader.conversation_event(event, chat["provider"])
        if row and row["role"] == "assistant":
            return row["text"]
    return None


def label(chat):
    return chat["name"] or chat["tmux"]


def squash(text):
    return " ".join(text.split()).casefold()


def describe(chats):
    return "; ".join(label(chat) + " {" + chat["tmux"] + "}" for chat in chats)


def resolve_chat(target, chats):
    wanted = target.strip()
    mention = MENTION.match(wanted)
    if mention:
        wanted = mention.group(2)
        exact_session = True
    else:
        exact_session = False
    for chat in chats:
        if chat["tmux"].casefold() == wanted.casefold():
            return chat
    if exact_session:
        raise ValueError("no live chat in terminal session " + wanted)
    key = squash(wanted.lstrip("@"))
    if not key:
        raise ValueError("empty chat name")
    exact = [chat for chat in chats if chat["name"] and squash(chat["name"]) == key]
    if len(exact) == 1:
        return exact[0]
    if len(exact) > 1:
        raise ValueError("more than one chat is named " + repr(target) + ": " + describe(exact) + "; pass the {session} form")
    prefixed = [chat for chat in chats if chat["name"] and squash(chat["name"]).startswith(key)]
    if len(prefixed) == 1:
        return prefixed[0]
    if len(prefixed) > 1:
        raise ValueError(repr(target) + " matches more than one chat: " + describe(prefixed) + "; type more of the name")
    raise ValueError("no live chat named " + repr(target) + "; run mp-sessions to see the names")


def own_tmux(environ=None):
    environ = os.environ if environ is None else environ
    tab = environ.get("MULTIPROMPT_TAB")
    if tab and TMUX_NAME.match(tab):
        return tab
    pane = environ.get("TMUX_PANE")
    if pane:
        try:
            done = subprocess.run(["tmux", "display-message", "-p", "-t", pane, "#{session_name}"], capture_output=True, text=True, timeout=5)
        except (OSError, subprocess.SubprocessError):
            return None
        name = done.stdout.strip()
        if done.returncode == 0 and TMUX_NAME.match(name):
            return name
    return None


def sender_label(chats, explicit=None):
    if explicit and explicit.strip():
        return " ".join(explicit.split())
    tmux = own_tmux()
    if not tmux:
        raise ValueError("cannot tell which chat is sending; run from inside a chat or pass --from NAME")
    names = published_names()
    return names.get(tmux) or tmux


def parse_named(argv):
    parser = argparse.ArgumentParser(prog="mp-send", description="Send one message to a live chat by the name shown in the desktop app. The text starts with who sent it.")
    parser.add_argument("--to", required=True)
    parser.add_argument("text", nargs="?")
    parser.add_argument("--from", dest="sender", default=None)
    parser.add_argument("--id", dest="message_id", default=None)
    parser.add_argument("--wait", action="store_true")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--reader", default=None)
    return parser.parse_args(argv)


def named_mode(argv):
    return any(arg == "--to" or arg.startswith("--to=") for arg in argv)


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


def read_text(text):
    text = sys.stdin.read() if text in (None, "-") else text
    if not text.strip() or "\x00" in text:
        raise ValueError("empty message")
    return text


def deliver(args, provider, sid, tmux, text, sender=None):
    reader = reader_module(args.reader)
    check_rate(reader, provider, sid, sender)
    if not tmux:
        tmux = args.tmux or reader.host_chat_name(provider, sid) or locator_tmux(reader, provider, sid)
        if not tmux:
            raise ValueError("no terminal binding for this session; pass --tmux")
    message = submit(reader, provider, sid, tmux, text, args.message_id)
    message_id = message.get("id")
    dispatch(reader, provider, sid, tmux)
    final = wait(reader, provider, sid, tmux, message_id, args.timeout) if args.wait else handoff(reader, provider, sid, tmux, message_id, args.timeout)
    if args.json:
        print(json.dumps(dict(id=message_id, state=(final or {}).get("state"), receipt=(final or {}).get("receipt")), separators=(",", ":")))
    else:
        print(message_id)
        if args.wait and (final or {}).get("state") is not None:
            line = "state=" + str((final or {}).get("state"))
            if (final or {}).get("receipt"):
                line += " receipt=" + str(final["receipt"])
            print(line)
    state = (final or {}).get("state")
    if state == "failed" or state == "cancelled":
        return 3
    if not args.wait:
        return 0
    if state not in TERMINAL_STATES:
        print("timed out waiting for the message", file=sys.stderr)
        return 4
    return 0


def run(args):
    if SESSION_ID.match(args.sid) is None:
        raise ValueError("invalid session id: " + str(args.sid))
    text = read_text(args.text)
    reader = reader_module(args.reader)
    tmux = args.tmux or locator_tmux(reader, args.provider, args.sid)
    if not tmux:
        raise ValueError("no terminal binding for this session; pass --tmux")
    return deliver(args, args.provider, args.sid, tmux, text)


def run_named(args):
    text = read_text(args.text)
    chats = live_chats()
    target = resolve_chat(args.to, chats)
    sender = sender_label(chats, args.sender)
    if own_tmux() == target["tmux"] and not args.sender:
        raise ValueError("that is this chat; pick another chat")
    return deliver(args, target["provider"], target["sid"], target["tmux"], "From " + sender + ": " + text, sender)


def run_list(argv):
    parser = argparse.ArgumentParser(prog="mp-send list", description="List the live chats, every AI, by the name shown in the desktop app.")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    chats = live_chats()
    if args.json:
        print(json.dumps(chats, separators=(",", ":")))
    else:
        print(table(chats))
    return 0


def run_read(argv):
    parser = argparse.ArgumentParser(prog="mp-send read", description="Print the last answer of a live chat.")
    parser.add_argument("target")
    parser.add_argument("--reader", default=None)
    parser.add_argument("--chars", type=int, default=ANSWER_CHARS)
    args = parser.parse_args(argv)
    chat = resolve_chat(args.target, live_chats())
    answer = last_answer(reader_module(args.reader), chat)
    if answer is None:
        print("mp-send: " + label(chat) + " has no answer yet", file=sys.stderr)
        return 3
    print(answer if len(answer) <= args.chars else answer[-args.chars:])
    return 0


def table(chats):
    rows = [("NAME", "AI", "STATE", "TMUX", "SID", "FOLDER")]
    for chat in chats:
        rows.append((chat["name"] or "(no name yet)", chat["provider"], chat["state"], chat["tmux"], chat["sid"], chat["cwd"] or ""))
    widths = [max(len(row[i]) for row in rows) for i in range(5)]
    return "\n".join("  ".join(row[i].ljust(widths[i]) for i in range(5)) + "  " + row[5] for row in rows)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv and argv[0] in ("list", "read"):
        try:
            return run_list(argv[1:]) if argv[0] == "list" else run_read(argv[1:])
        except (OSError, ValueError, KeyError, TypeError, sqlite3.Error) as error:
            print("mp-send: " + str(error), file=sys.stderr)
            return 2
    named = named_mode(argv)
    args = parse_named(argv) if named else parse(argv)
    try:
        return run_named(args) if named else run(args)
    except RateLimited as error:
        print("mp-send: " + str(error), file=sys.stderr)
        return 5
    except (OSError, ValueError, KeyError, TypeError, sqlite3.Error) as error:
        print("mp-send: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
