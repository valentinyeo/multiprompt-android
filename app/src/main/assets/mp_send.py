#!/usr/bin/env python3
import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
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
FOLDER_PATH = re.compile(r"[A-Za-z0-9_./~+ -]{1,220}\Z")
VERIFY_TRIES = 4
VERIFY_PAUSE = 1.0
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


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_name("." + path.name + "." + str(os.getpid()) + ".tmp")
    handle = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(handle, "w", encoding="utf-8") as stream:
        stream.write(json.dumps(value, separators=(",", ":"), sort_keys=True))
    os.replace(temporary, path)


def host_root():
    return state_root() / "agent-host"


def host_config_key(provider, sid):
    return hashlib.sha256((provider + ":" + sid).encode()).hexdigest()[:32]


def reader_cli(*args):
    reader = str(reader_path())
    try:
        done = subprocess.run([sys.executable, reader, "host", *args], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError("host command failed: " + str(error))
    try:
        reply = json.loads(done.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        raise ValueError("host command gave no answer")
    return reply if isinstance(reply, dict) else {}


def zsb_command():
    here = Path(__file__).resolve()
    candidates = [here.with_name("mp-zsb")]
    found = shutil.which("zsb")
    if found:
        candidates.append(Path(found))
    candidates.append(Path.home() / ".local/bin/zsb")
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return "zsb"


def zsb(*args):
    try:
        done = subprocess.run([zsb_command(), *args], capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as error:
        return dict(ok=False, error="zsb_unavailable", message=str(error))
    try:
        reply = json.loads(done.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return dict(ok=False, error="zsb_no_answer", message=(done.stderr or "").strip()[:200])
    return reply if isinstance(reply, dict) else dict(ok=False, error="zsb_no_answer")


def iter_tabs(listing):
    for workspace in listing.get("workspaces") or []:
        for tab in workspace.get("tabs") or []:
            yield workspace.get("name"), None, tab
        for group in workspace.get("groups") or []:
            for tab in group.get("tabs") or []:
                yield workspace.get("name"), group.get("name"), tab


def find_tab(listing, tmux):
    for workspace, group, tab in iter_tabs(listing):
        if tab.get("tmux") == tmux:
            return workspace, group, tab
    return None, None, None


def verify_tab(tmux):
    for attempt in range(VERIFY_TRIES):
        if attempt:
            time.sleep(VERIFY_PAUSE)
        listing = zsb("workspace-list")
        tab = find_tab(listing, tmux)[2] if listing.get("ok") is True else None
        if tab:
            return tab
    return None


def open_visible_tab(listing, tmux, title, workspace, host, group=None, after_tab=None):
    if listing.get("ok") is not True:
        return dict(ok=False, error=listing.get("error") or "zsb_failed")
    existing = find_tab(listing, tmux)[2]
    if existing:
        return dict(ok=True, tab=existing.get("id"), existing=True)
    common = ("--host", host, "--tmux", tmux, "--name", title)
    if after_tab is not None:
        opened = zsb("session-open", "--workspace", workspace, "--after-tab", str(after_tab), *common)
    elif group:
        opened = zsb("session-open", "--workspace", workspace, "--group", group, *common)
    else:
        return dict(ok=False, error="no_placement")
    if opened.get("ok") is not True and opened.get("error") == "reply_timeout":
        tab = verify_tab(tmux)
        if tab:
            return dict(ok=True, tab=tab.get("id"), verified=True)
    return opened


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


def layout_path():
    configured = os.environ.get("MULTIPROMPT_LAYOUT_PATH")
    if configured:
        return Path(configured)
    return Path.home() / ".local/share/multiprompt/layout.json"


def published_entries():
    known = read_json(state_root() / "chat-names.json") or {}
    entries = {}
    for tmux, entry in known.items():
        if not isinstance(tmux, str) or not isinstance(entry, dict):
            continue
        name = entry.get("name")
        workspace = entry.get("workspace")
        group = entry.get("group")
        sid = entry.get("sid")
        entries[tmux] = dict(
            name=name.strip() if isinstance(name, str) and name.strip() else None,
            workspace=workspace.strip() if isinstance(workspace, str) and workspace.strip() else None,
            group=group.strip() if isinstance(group, str) and group.strip() else None,
            sid=sid.strip() if isinstance(sid, str) and sid.strip() else None,
        )
    return entries


def published_names():
    return {tmux: entry["name"] for tmux, entry in published_entries().items() if entry.get("name")}


def synced_layout_tabs(host=None):
    data = read_json(layout_path())
    if not data:
        return []
    tabs = data.get("tabs")
    if not isinstance(tabs, list):
        return []
    result = []
    for tab in tabs:
        if not isinstance(tab, dict):
            continue
        tmux = tab.get("tmux_session")
        workspace = tab.get("workspace")
        if not isinstance(tmux, str) or not tmux or not isinstance(workspace, str) or not workspace.strip():
            continue
        tab_host = tab.get("tmux_host")
        if host is not None and tab_host is not None and tab_host != host:
            continue
        name = tab.get("name")
        group = tab.get("group")
        result.append(dict(
            tmux=tmux,
            name=name.strip() if isinstance(name, str) and name.strip() else None,
            workspace=workspace.strip(),
            group=group.strip() if isinstance(group, str) and group.strip() else None,
            tmux_host=tab_host if isinstance(tab_host, int) else None,
        ))
    return result


def host_owner_pid(config_path):
    owner = read_json(config_path.with_name(config_path.name[: -len(".config.json")] + ".owner.json")) or {}
    return owner.get("pid") if owner.get("owner") == "host" else None


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
        if provider not in PROVIDERS:
            continue
        hosted = pid_alive(state.get("pid")) or pid_alive(host_owner_pid(config_path))
        terminal = sessions is None or tmux in sessions
        if not hosted and not terminal:
            continue
        shown = names.get(tmux)
        started = state.get("started") if isinstance(state.get("started"), (int, float)) else 0
        if hosted:
            row_state = "busy" if state.get("inflight") else "idle"
        else:
            row_state = "stopped"
        row = dict(name=shown, provider=provider, sid=sid, tmux=tmux, cwd=config.get("cwd"), state=row_state,
                   started=started, hosted=hosted, link=link_token(shown, tmux))
        if tmux not in best or (row["hosted"], row["started"]) >= (best[tmux]["hosted"], best[tmux]["started"]):
            best[tmux] = row
    rows = sorted(best.values(), key=lambda row: ((row["name"] or row["tmux"]).casefold(), row["tmux"]))
    for row in rows:
        del row["started"], row["hosted"]
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


def recent_same_agent_message(reader, provider, sid, text, window=60.0):
    locate = getattr(reader, "message_store_path", None)
    if locate is None:
        return None
    path = locate()
    if not path.is_file():
        return None
    since = time.time() - window
    sql = "SELECT id, state FROM shared_messages WHERE origin='agent' AND provider=? AND sid=? AND text=? AND state NOT IN ('failed','cancelled') AND created>? ORDER BY position DESC LIMIT 1"
    try:
        with sqlite3.connect(path.as_uri() + "?mode=ro", uri=True) as db:
            row = db.execute(sql, (provider, sid, text, since)).fetchone()
    except sqlite3.Error:
        return None
    return dict(id=row[0], state=row[1]) if row else None


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


def link_token(name, tmux):
    text = name.strip() if isinstance(name, str) else ""
    if not text or "{" in text or "}" in text or "@" in text:
        text = tmux
    token = text + "{" + tmux + "}"
    if " " in text:
        token = "@" + token
    return token


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


def host_tmux_for_env(environ):
    provider = (environ.get("MULTIBOT_PROVIDER") or "").strip()
    sid = (environ.get("MULTIBOT_SID") or "").strip()
    if not sid or SESSION_ID.match(sid) is None:
        return None
    root = host_root()
    if provider in PROVIDERS:
        key = host_config_key(provider, sid)
        for suffix in (".config.json", ".state.json"):
            data = read_json(root / (key + suffix))
            tmux = data.get("tmux") if data else None
            if isinstance(tmux, str) and TMUX_NAME.match(tmux):
                return tmux
    else:
        found = set()
        for path in sorted(root.glob("*.config.json")):
            config = read_json(path)
            if not config:
                continue
            key = path.name[: -len(".config.json")]
            state = read_json(path.with_name(key + ".state.json")) or {}
            row_sid = state.get("sid") or config.get("sid")
            if row_sid != sid:
                continue
            tmux = config.get("tmux") or state.get("tmux")
            if isinstance(tmux, str) and TMUX_NAME.match(tmux):
                found.add(tmux)
        if len(found) == 1:
            return found.pop()
    published = [tmux for tmux, entry in published_entries().items() if entry.get("sid") == sid and TMUX_NAME.match(tmux)]
    if len(published) == 1:
        return published[0]
    return None


def own_tmux(environ=None):
    environ = os.environ if environ is None else environ
    for var in ("MULTIPROMPT_TAB", "MULTIBOT_SESSION", "ZSB_SESSION"):
        tab = environ.get(var)
        if var == "ZSB_SESSION" and tab == "workspace":
            continue
        if tab and TMUX_NAME.match(tab):
            return tab
    pane = environ.get("TMUX_PANE")
    if pane:
        try:
            done = subprocess.run(["tmux", "display-message", "-p", "-t", pane, "#{session_name}"], capture_output=True, text=True, timeout=5)
        except (OSError, subprocess.SubprocessError):
            done = None
        if done is not None:
            name = done.stdout.strip()
            if done.returncode == 0 and TMUX_NAME.match(name):
                return name
    return host_tmux_for_env(environ)


def sender_label(chats, explicit=None):
    if explicit and explicit.strip():
        return " ".join(explicit.split())
    tmux = own_tmux()
    if not tmux:
        raise ValueError("cannot tell which chat is sending; run from inside a chat or pass --from NAME")
    names = published_names()
    if names.get(tmux):
        return names[tmux]
    layout_names = [tab["name"] for tab in synced_layout_tabs() if tab["tmux"] == tmux and tab.get("name")]
    if len(set(layout_names)) == 1:
        return layout_names[0]
    return tmux


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


def resolve_destination(reader, provider, sid, tmux):
    tmux = tmux or reader.host_chat_name(provider, sid) or locator_tmux(reader, provider, sid) or sid
    if TMUX_NAME.fullmatch(tmux) is None:
        raise ValueError("invalid terminal session: " + str(tmux))
    bound = None
    if sid == tmux:
        hosted = reader.host_sid_for_tmux(provider, tmux)
        if hosted:
            sid = hosted
        else:
            bound = reader.resolve_tmux_agent(provider, tmux, require_bound=True)
            if bound:
                sid = bound[0]
    if SESSION_ID.fullmatch(sid) is None:
        raise ValueError("invalid session id: " + str(sid))
    hosting = reader.host_dispatch_switch(provider, sid)
    if hosting and reader.host_chat_name(provider, sid) == tmux and reader.host_owned(provider, sid):
        return sid, tmux
    bound = bound or reader.resolve_tmux_agent(provider, tmux, require_bound=True)
    if bound and bound[0] == sid and (not hosting or reader.host_chat_name(provider, sid) == tmux):
        return sid, tmux
    raise ValueError("no live host or pane agent for " + provider + " session " + sid + " in " + tmux + "; run mp-sessions to find a live chat")


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
        print("mp-send: message " + str(state) + ": " + str((final or {}).get("receipt") or "delivery failed"), file=sys.stderr)
        return 3
    if state not in (TERMINAL_STATES if args.wait else HANDOFF_STATES):
        print("mp-send: timed out waiting for the message" + ("" if args.wait else " handoff; delivery is unconfirmed"), file=sys.stderr)
        return 4
    return 0


def run(args):
    text = read_text(args.text)
    sid, tmux = resolve_destination(reader_module(args.reader), args.provider, args.sid, args.tmux)
    return deliver(args, args.provider, sid, tmux, text)


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
        rows.append((chat.get("link") or chat["name"] or "(no name yet)", chat["provider"], chat["state"], chat["tmux"], chat["sid"], chat["cwd"] or ""))
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
