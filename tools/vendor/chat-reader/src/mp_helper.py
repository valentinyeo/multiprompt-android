#!/usr/bin/env python3
import argparse
import hashlib
import importlib.machinery
import importlib.util
import json
import os
import re
import sys
import time
from pathlib import Path

PROVIDER = "claude"
MODELS = ("opus", "sonnet", "haiku", "fable")
EFFORTS = ("low", "medium", "high")
FOLDER = re.compile(r"[A-Za-z0-9_./~+ -]{1,220}\Z")
_send = None


def send_module():
    global _send
    if _send is None:
        here = Path(__file__).resolve()
        source = here.with_name("mp_send.py")
        if not source.is_file():
            source = here.with_name("mp-send")
        loader = importlib.machinery.SourceFileLoader("mp_send", str(source))
        spec = importlib.util.spec_from_loader("mp_send", loader)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _send = module
    return _send


def host_key(sid):
    return send_module().host_config_key(PROVIDER, sid)


def identity(parent, name):
    digest = hashlib.sha256(json.dumps(["helper", parent, name.casefold()]).encode()).hexdigest()
    raw = bytearray(bytes.fromhex(digest[:32]))
    raw[6] = (raw[6] & 15) | 128
    raw[8] = (raw[8] & 63) | 128
    hexed = raw.hex()
    sid = "-".join((hexed[:8], hexed[8:12], hexed[12:16], hexed[16:20], hexed[20:]))
    return sid, "live-" + hexed, "mp-helper-" + hexed


def parent_tmux():
    tmux = send_module().own_tmux()
    if not tmux:
        raise ValueError("run this from inside a chat; its helpers sit under it")
    return tmux


def helpers(parent):
    send = send_module()
    rows = []
    for path in sorted(send.host_root().glob("*.config.json")):
        config = send.read_json(path)
        if not config or config.get("helper_parent") != parent or not isinstance(config.get("helper_name"), str):
            continue
        state = send.read_json(path.with_name(path.name[: -len(".config.json")] + ".state.json")) or {}
        alive = send.pid_alive(state.get("pid"))
        rows.append(dict(name=config["helper_name"], sid=config.get("sid"), tmux=config.get("tmux"), cwd=config.get("cwd"),
                         model=config.get("model") or "", effort=config.get("effort") or "",
                         state=("busy" if state.get("inflight") else "idle") if alive else "stopped"))
    return rows


def pick(parent, name):
    rows = helpers(parent)
    key = " ".join(name.split()).casefold()
    for row in rows:
        if row["name"].casefold() == key or row["tmux"] == name:
            return row
    raise ValueError("no helper named " + repr(name) + " under this chat; run mp-helper list")


def show(parent, tmux, name):
    send = send_module()
    listing = send.zsb("workspace-list")
    if listing.get("ok") is not True:
        return dict(ok=False, error=listing.get("error") or "zsb_failed")
    existing = send.find_tab(listing, tmux)[2]
    if existing:
        return dict(ok=True, tab=existing.get("id"), existing=True)
    workspace, group, tab = send.find_tab(listing, parent)
    if not tab or not tab.get("host"):
        return dict(ok=False, error="parent_tab_not_open")
    opened = send.open_visible_tab(listing, tmux, name, workspace, tab["host"], after_tab=tab["id"])
    if opened.get("ok") is not True and opened.get("error") == "bad_args" and group:
        opened = send.open_visible_tab(listing, tmux, name, workspace, tab["host"], group=group)
    return opened


def start(args):
    send = send_module()
    parent = parent_tmux()
    name = " ".join(args.name.split())
    if not name or len(name) > 63 or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in name):
        raise ValueError("helper name must be 1-63 characters without control characters")
    folder = os.path.realpath(os.path.expanduser(args.folder or os.getcwd()))
    if not os.path.isdir(folder) or FOLDER.match(folder) is None:
        raise ValueError("invalid folder: " + folder)
    task = sys.stdin.read() if args.task == "-" else args.task
    if task is not None and (not task.strip() or "\x00" in task):
        raise ValueError("empty task")
    sid, tmux, message_id = identity(parent, name)
    if tmux == parent:
        raise ValueError("a chat cannot be its own helper")
    path = send.host_root() / (host_key(sid) + ".config.json")
    config = send.read_json(path)
    if config and (config.get("helper_parent") != parent or config.get("cwd") != folder):
        raise ValueError("a helper named " + repr(name) + " already runs here with another folder; pick another name")
    config = dict(config or dict(enabled=False), cwd=folder, tmux=tmux, helper_parent=parent, helper_name=name)
    if args.model:
        config["model"] = args.model
    if args.effort:
        config["effort"] = args.effort
    send.write_json(path, config)
    enable = ["enable", PROVIDER, sid, "--tmux", tmux, "--cwd", folder, "--permission-mode", "bypassPermissions"]
    enabled = send.reader_cli(*enable)
    if enabled.get("status") != "enabled":
        raise ValueError("helper host was not enabled: " + str(enabled.get("reason") or enabled.get("status")))
    started = send.reader_cli("start", PROVIDER, sid)
    if started.get("status") not in ("started", "already"):
        raise ValueError("helper host did not start: " + str(started.get("reason") or started.get("status")))
    shown = show(parent, tmux, name)
    first = None
    if task is not None:
        reader = send.reader_module()
        message = send.submit(reader, PROVIDER, sid, tmux, task, message_id)
        send.dispatch(reader, PROVIDER, sid, tmux)
        final = send.handoff(reader, PROVIDER, sid, tmux, message.get("id"), 15.0)
        first = dict(id=message.get("id"), state=(final or {}).get("state"))
    result = dict(ok=True, name=name, tmux=tmux, sid=sid, folder=folder, model=config.get("model") or "", effort=config.get("effort") or "",
                  parent=parent, visible=shown.get("ok") is True, tab=shown.get("tab"), task=first)
    if shown.get("ok") is not True:
        result["visible_error"] = shown.get("error")
    print(json.dumps(result, separators=(",", ":")))
    return 0 if first is None or first["state"] not in ("failed", "cancelled") else 3


def entries(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as source:
            for line in source:
                try:
                    entry = json.loads(line)
                except ValueError:
                    continue
                if isinstance(entry, dict):
                    yield entry
    except OSError:
        return


def texts(content):
    if isinstance(content, str):
        return [content]
    if not isinstance(content, list):
        return []
    return [part["text"] for part in content if isinstance(part, dict) and part.get("type") == "text" and isinstance(part.get("text"), str)]


def last_reply(path):
    reply = []
    for entry in entries(path):
        message = entry.get("message") if isinstance(entry.get("message"), dict) else {}
        if entry.get("type") == "user" and not entry.get("isMeta") and texts(message.get("content")):
            reply = []
        elif entry.get("type") == "assistant":
            reply.extend(text for text in texts(message.get("content")) if text.strip())
    return "\n\n".join(reply)


def transcript(row):
    reader = send_module().reader_module()
    try:
        return reader.conversation_path(PROVIDER, row["sid"], row["cwd"])
    except OSError:
        return None


def read(args):
    parent = parent_tmux()
    row = pick(parent, args.name)
    deadline = time.monotonic() + max(0.0, args.timeout)
    while True:
        path = transcript(row)
        text = last_reply(path) if path else ""
        if not args.wait or (row["state"] != "busy" and text) or row["state"] == "stopped":
            break
        if time.monotonic() >= deadline:
            print("mp-helper: timed out waiting for " + row["name"], file=sys.stderr)
            if args.json:
                print(json.dumps(dict(name=row["name"], state=row["state"], reply=text), separators=(",", ":")))
            return 4
        time.sleep(1.0)
        row = pick(parent, args.name)
    if args.json:
        print(json.dumps(dict(name=row["name"], state=row["state"], reply=text), separators=(",", ":")))
    else:
        print(text)
    return 0 if text else 5


def listing(args):
    rows = helpers(parent_tmux())
    if args.json:
        print(json.dumps(rows, separators=(",", ":")))
        return 0
    if not rows:
        print("no helpers under this chat")
        return 0
    for row in rows:
        print("  ".join((row["name"], row["state"], row["model"] or "default", row["effort"] or "default", row["tmux"])))
    return 0


def message(args):
    parent = parent_tmux()
    row = pick(parent, args.name)
    if row["state"] == "stopped":
        raise ValueError(row["name"] + " is not running")
    send = send_module()
    text = send.read_text(args.text)
    names = send.published_names()
    sender = names.get(parent) or parent
    forwarded = argparse.Namespace(reader=None, tmux=None, message_id=None, wait=args.wait, timeout=args.timeout, json=args.json)
    return send.deliver(forwarded, PROVIDER, row["sid"], row["tmux"], "From " + sender + ": " + text)


def close(args):
    send = send_module()
    parent = parent_tmux()
    row = pick(parent, args.name)
    listing_reply = send.zsb("workspace-list")
    tab = send.find_tab(listing_reply, row["tmux"])[2] if listing_reply.get("ok") is True else None
    closed = send.zsb("session-close", "--tab", str(tab["id"])) if tab else dict(ok=True)
    stopped = send.reader_cli("disable", PROVIDER, row["sid"])
    print(json.dumps(dict(ok=stopped.get("status") == "disabled", name=row["name"], tab_closed=closed.get("ok") is True,
                          tab_error=closed.get("error")), separators=(",", ":")))
    return 0 if stopped.get("status") == "disabled" else 3


def parse(argv):
    parser = argparse.ArgumentParser(prog="mp-helper", description="Start helper chats from this chat. Each helper shows in the sidebar right below this chat.")
    commands = parser.add_subparsers(dest="command", required=True)
    begin = commands.add_parser("start", help="start a helper chat and give it a first task")
    begin.add_argument("--name", required=True)
    begin.add_argument("--task", default=None, help="first task; - reads it from stdin")
    begin.add_argument("--model", choices=MODELS, default=None)
    begin.add_argument("--effort", choices=EFFORTS, default=None)
    begin.add_argument("--folder", default=None, help="working folder (default: this folder)")
    reading = commands.add_parser("read", help="print the helper's latest reply")
    reading.add_argument("name")
    reading.add_argument("--wait", action="store_true", help="wait until the helper finishes its turn")
    reading.add_argument("--timeout", type=float, default=600.0)
    reading.add_argument("--json", action="store_true")
    sending = commands.add_parser("send", help="send the helper a message")
    sending.add_argument("name")
    sending.add_argument("text", nargs="?")
    sending.add_argument("--wait", action="store_true")
    sending.add_argument("--timeout", type=float, default=60.0)
    sending.add_argument("--json", action="store_true")
    showing = commands.add_parser("list", help="list this chat's helpers")
    showing.add_argument("--json", action="store_true")
    ending = commands.add_parser("close", help="stop a helper and close its tab")
    ending.add_argument("name")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse(sys.argv[1:] if argv is None else argv)
    handlers = dict(start=start, read=read, send=message, list=listing, close=close)
    try:
        return handlers[args.command](args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print("mp-helper: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())