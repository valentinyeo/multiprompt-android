#!/usr/bin/env python3
import argparse
import hashlib
import importlib.machinery
import importlib.util
import json
import os
import sys
from pathlib import Path

PROVIDERS = ("claude", "codex", "pi", "cursor")


def load_send(session_api=True):
    here = Path(__file__).resolve()
    source = here.with_name("mp_send.py")
    if not source.is_file():
        source = here.with_name("mp-send")
    loader = importlib.machinery.SourceFileLoader("mp_send", str(source))
    spec = importlib.util.spec_from_loader("mp_send", loader)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if session_api and not callable(getattr(module, "zsb", None)):
        raise ValueError("session commands need a newer mp-send helper; update the desktop app and reconnect")
    return module


def display_name(name):
    name = " ".join(name.split())
    if not name or len(name) > 63 or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in name):
        raise ValueError("chat name must be 1-63 characters without control characters")
    return name


def host_slot(text=None):
    text = (text if text is not None else os.environ.get("MULTIPROMPT_HOST") or "0").strip()
    try:
        slot = int(text)
    except ValueError:
        raise ValueError("host must be 0, 1 or 2")
    if slot not in (0, 1, 2):
        raise ValueError("host must be 0, 1 or 2")
    return slot


def host_name(listing, slot):
    hosts = listing.get("hosts") if listing.get("ok") else None
    if not isinstance(hosts, list) or slot >= len(hosts) or not isinstance(hosts[slot], str):
        raise ValueError("desktop host list is unavailable; open the desktop app and retry")
    return hosts[slot]


def session_identity(slot, name):
    digest = hashlib.sha256(json.dumps([slot, name.casefold()]).encode()).hexdigest()
    raw = bytearray(bytes.fromhex(digest[:32]))
    raw[6] = (raw[6] & 15) | 128
    raw[8] = (raw[8] & 63) | 128
    hexed = raw.hex()
    sid = "-".join((hexed[:8], hexed[8:12], hexed[12:16], hexed[16:20], hexed[20:]))
    return sid, "live-" + hexed


def default_workspace(listing):
    workspaces = listing.get("workspaces") or []
    for workspace in workspaces:
        if workspace.get("active"):
            name = workspace.get("name")
            if isinstance(name, str) and name.strip():
                return name.strip()
    for workspace in workspaces:
        name = workspace.get("name")
        if isinstance(name, str) and name.strip():
            return name.strip()
    raise ValueError("no workspace is visible; open the desktop app")


def workspace_anchor(listing, workspace):
    for name, group, tab in load_send().iter_tabs(listing):
        if name == workspace and tab.get("id"):
            return tab["id"]
    return None


def enable_args(provider, sid, folder, tmux):
    command = ["enable", provider, sid, "--tmux", tmux, "--cwd", folder]
    if provider == "claude":
        command += ["--permission-mode", "bypassPermissions"]
    return command


def session_catalog(send, listing):
    names = send.published_names()
    rows = {}
    if listing.get("ok"):
        for workspace, group, tab in send.iter_tabs(listing):
            tmux = tab.get("tmux")
            if not isinstance(tmux, str):
                continue
            rows[tmux] = dict(
                name=tab.get("title") or names.get(tmux) or tmux,
                tmux=tmux,
                host=tab.get("host"),
                workspace=workspace,
                group=group,
                tab_id=tab.get("id"),
                tab_open=True,
            )
    for path in sorted(send.host_root().glob("*.config.json")):
        config = send.read_json(path)
        if not config or config.get("helper_parent"):
            continue
        key = path.name[: -len(".config.json")]
        state = send.read_json(path.with_name(key + ".state.json")) or {}
        tmux = config.get("tmux")
        if not isinstance(tmux, str):
            continue
        provider = config.get("provider") or state.get("provider") or "claude"
        sid = state.get("sid") or config.get("sid")
        alive = send.pid_alive(state.get("pid"))
        row = rows.get(tmux, dict(tmux=tmux, tab_open=False))
        row.update(
            provider=provider,
            sid=sid,
            folder=config.get("cwd"),
            spawn_name=config.get("spawn_name"),
            host=row.get("host"),
            workspace=row.get("workspace") or config.get("spawn_workspace"),
            group=row.get("group") if "group" in row else config.get("spawn_group"),
        )
        row["running"] = config.get("enabled") is True and alive
        row["busy"] = bool(state.get("inflight")) and alive
        if not row.get("name"):
            row["name"] = names.get(tmux) or config.get("spawn_display") or tmux
        rows[tmux] = row
    for tmux, row in rows.items():
        row.setdefault("tab_open", False)
        row.setdefault("running", False)
        row.setdefault("busy", False)
        row["link"] = send.link_token(row.get("name"), tmux)
    return sorted(rows.values(), key=lambda row: ((row.get("name") or row["tmux"]).casefold(), row["tmux"]))


def pick_target(send, listing, target):
    wanted = target.strip()
    mention = send.MENTION.match(wanted)
    if mention:
        wanted = mention.group(2)
        for row in session_catalog(send, listing):
            if row["tmux"].casefold() == wanted.casefold():
                return row
        raise ValueError("no chat in terminal session " + wanted)
    key = send.squash(wanted.lstrip("@"))
    if not key:
        raise ValueError("empty chat name")
    rows = session_catalog(send, listing)
    exact = [row for row in rows if send.squash(row.get("name") or "") == key]
    if len(exact) == 1:
        return exact[0]
    if len(exact) > 1:
        raise ValueError("more than one chat is named " + repr(target) + "; pass the {session} form")
    prefixed = [row for row in rows if send.squash(row.get("name") or "").startswith(key)]
    if len(prefixed) == 1:
        return prefixed[0]
    if len(prefixed) > 1:
        raise ValueError(repr(target) + " matches more than one chat; type more of the name")
    for row in rows:
        if row["tmux"].casefold() == key:
            return row
    raise ValueError("no chat named " + repr(target) + "; run mp-sessions list")


def find_existing(send, listing, slot, display, folder):
    token = display.casefold()
    for row in session_catalog(send, listing):
        if row.get("spawn_name") == token:
            matched = True
        elif row.get("sid") and send.squash(row.get("name") or "") == send.squash(display):
            matched = True
        else:
            continue
        if row.get("folder") and os.path.realpath(row["folder"]) != folder:
            raise ValueError("a chat named " + repr(display) + " already exists with another folder")
        return row
    return None


def ensure_visible(send, listing, target, display):
    workspace = target.get("workspace")
    if not workspace:
        workspace = default_workspace(listing)
    host = target.get("host") or host_name(listing, host_slot())
    present = send.find_tab(listing, target["tmux"])[2]
    if target.get("tab_open") or present:
        return dict(ok=True, tab=(present or {}).get("id") or target.get("tab_id"), existing=True)
    anchor = None if target.get("group") else workspace_anchor(listing, workspace)
    opened = send.open_visible_tab(listing, target["tmux"], display, workspace, host,
                                   group=target.get("group"), after_tab=anchor)
    return opened


def start_host(send, provider, sid, folder, tmux):
    enabled = send.reader_cli(*enable_args(provider, sid, folder, tmux))
    if enabled.get("status") != "enabled":
        raise ValueError("chat host was not enabled: " + str(enabled.get("reason") or enabled.get("status")))
    started = send.reader_cli("start", provider, sid)
    if started.get("status") not in ("started", "already"):
        raise ValueError("chat host did not start: " + str(started.get("reason") or started.get("status")))
    return started


def row_state(row):
    if row.get("busy"):
        return "busy"
    if row.get("running"):
        return "running"
    return "stopped"


def caller_mark(send, listing, rows):
    tmux = send.own_tmux()
    if not tmux:
        return None, None
    workspace = send.find_tab(listing, tmux)[0]
    if not workspace:
        for row in rows:
            if row["tmux"] == tmux:
                workspace = row.get("workspace")
                break
    return tmux, workspace


def filter_workspace(rows, workspace):
    wanted = workspace.strip()
    if not wanted:
        return rows
    return [row for row in rows if (row.get("workspace") or "") == wanted]


def catalog_table(rows):
    headers = ("NAME", "WORKSPACE", "GROUP", "STATE", "TMUX", "SID")
    body = []
    for row in rows:
        mark = "*" if row.get("self") else " "
        body.append((mark + (row.get("link") or row.get("name") or row["tmux"]), row.get("workspace") or "", row.get("group") or "",
                     row_state(row), row["tmux"], row.get("sid") or ""))
    data = [headers] + body
    widths = [max(len(row[i]) for row in data) for i in range(len(headers))]
    return "\n".join("  ".join(row[i].ljust(widths[i]) for i in range(len(headers))) for row in data)


def session_message(send, target, text, sender=None):
    provider = target.get("provider")
    sid = target.get("sid")
    tmux = target["tmux"]
    if not provider or not sid:
        raise ValueError((target.get("name") or tmux) + " has no session id yet; use mp-sessions start")
    if not target.get("running"):
        raise ValueError((target.get("name") or tmux) + " is not running; use mp-sessions restart")
    reader = send.reader_module()
    label = sender
    if label is None:
        try:
            label = send.sender_label([], None)
        except ValueError:
            label = None
    body = text if label is None else ("From " + label + ": " + text)
    send.check_rate(reader, provider, sid, label)
    message = send.submit(reader, provider, sid, tmux, body)
    send.dispatch(reader, provider, sid, tmux)
    final = send.handoff(reader, provider, sid, tmux, message.get("id"), 15.0)
    state = (final or {}).get("state")
    return dict(name=target.get("name"), tmux=tmux, sid=sid, id=message.get("id"), state=state)


def live_catalog_rows(send):
    rows = []
    for chat in send.live_chats():
        rows.append(dict(
            name=chat.get("name"),
            link=chat.get("link"),
            tmux=chat["tmux"],
            sid=chat["sid"],
            provider=chat.get("provider"),
            workspace="",
            group="",
            busy=chat.get("state") == "busy",
            running=chat.get("state") != "stopped",
        ))
    return rows


def cmd_list(args):
    workspace = getattr(args, "workspace", None)
    send = load_send(session_api=bool(workspace))
    if workspace:
        listing = send.zsb("workspace-list")
        rows = session_catalog(send, listing)
        caller, mine = caller_mark(send, listing, rows)
        for row in rows:
            row["self"] = caller is not None and row["tmux"] == caller
        if workspace == "mine":
            if not mine:
                raise ValueError("cannot tell which workspace is yours; run from inside a chat on the desktop")
            workspace = mine
        rows = filter_workspace(rows, workspace)
        if args.json:
            print(json.dumps(rows, separators=(",", ":")))
        else:
            print(catalog_table(rows))
        return 0
    rows = live_catalog_rows(send)
    caller = send.own_tmux()
    for row in rows:
        row["self"] = caller is not None and row["tmux"] == caller
    if args.json:
        print(json.dumps(send.live_chats(), separators=(",", ":")))
    else:
        print(catalog_table(rows))
    return 0


def cmd_whoami(args):
    send = load_send()
    tmux = send.own_tmux()
    if not tmux:
        raise ValueError("cannot identify this chat; run from inside a Multibot chat")
    listing = send.zsb("workspace-list")
    if listing.get("ok") is not True:
        raise ValueError("cannot read this chat's current name; open the desktop app and retry")
    tabs = [tab for workspace, group, tab in send.iter_tabs(listing) if tab.get("tmux") == tmux]
    if len(tabs) != 1 or not tabs[0].get("title"):
        raise ValueError("this chat has no unique named tab on the desktop")
    tab = tabs[0]
    provider = os.environ.get("MULTIBOT_PROVIDER")
    sid = os.environ.get("MULTIBOT_SID")
    root = send.host_root()
    paths = [root / (send.host_config_key(provider, sid) + ".config.json")] if provider and sid else sorted(root.glob("*.config.json"))
    identities = set()
    for path in paths:
        config = send.read_json(path)
        if not config or config.get("tmux") != tmux:
            continue
        key = path.name[: -len(".config.json")]
        state = send.read_json(path.with_name(key + ".state.json")) or {}
        linked_provider = config.get("provider") or state.get("provider")
        linked_sid = state.get("sid") or config.get("sid")
        if not isinstance(linked_provider, str) or not linked_provider or not isinstance(linked_sid, str) or not linked_sid:
            continue
        if provider and sid and (linked_provider, linked_sid) != (provider, sid):
            continue
        identities.add((linked_provider, linked_sid))
    if len(identities) != 1:
        raise ValueError("cannot identify this chat's linked agent session uniquely")
    provider, sid = identities.pop()
    identity = dict(name=tab["title"], sid=sid, provider=provider, tmux=tmux, link=send.link_token(tab["title"], tmux))
    if args.json:
        print(json.dumps(identity, separators=(",", ":")))
    else:
        print("Name: " + identity["name"] + "\nSession ID: " + sid + "\nAgent: " + provider + "\nLink: " + identity["link"])
    return 0


def parse_send(argv):
    workspace = None
    rest = list(argv)
    if "--workspace" in rest:
        index = rest.index("--workspace")
        if index + 1 >= len(rest):
            raise ValueError("send --workspace needs a workspace name or mine")
        workspace = rest[index + 1]
        del rest[index:index + 2]
    if workspace == "mine":
        if not rest:
            raise ValueError("send needs message text")
        return argparse.Namespace(workspace="mine", name=None, text=" ".join(rest))
    if len(rest) < 2:
        raise ValueError("send needs NAME and message text, or send --workspace mine TEXT")
    return argparse.Namespace(workspace=None, name=rest[0], text=" ".join(rest[1:]))


def cmd_send(args):
    text = args.text
    if text is None or not text.strip() or "\x00" in text:
        raise ValueError("empty message")
    send = load_send()
    listing = send.zsb("workspace-list")
    results = []
    if args.workspace == "mine":
        rows = session_catalog(send, listing)
        caller, mine = caller_mark(send, listing, rows)
        if not caller or not mine:
            raise ValueError("cannot tell which workspace is yours; run from inside a chat on the desktop")
        targets = [row for row in filter_workspace(rows, mine) if row["tmux"] != caller and row.get("running")]
        if not targets:
            raise ValueError("no other running chats in workspace " + repr(mine))
        for target in targets:
            results.append(session_message(send, target, text))
    else:
        target = pick_target(send, listing, args.name)
        if send.own_tmux() == target["tmux"]:
            raise ValueError("that is this chat; pick another chat")
        results.append(session_message(send, target, text))
    if args.workspace == "mine":
        print(json.dumps(results, separators=(",", ":")))
    else:
        print(json.dumps(results[0], separators=(",", ":")))
    failed = [row for row in results if row.get("state") in ("failed", "cancelled")]
    return 3 if failed else 0


def cmd_start(args):
    send = load_send()
    display = display_name(args.name)
    folder = os.path.realpath(os.path.expanduser(args.folder or os.getcwd()))
    if not os.path.isdir(folder) or send.FOLDER_PATH.match(folder) is None:
        raise ValueError("invalid folder: " + folder)
    provider = args.provider or "claude"
    if provider not in PROVIDERS:
        raise ValueError("provider must be one of: " + ", ".join(PROVIDERS))
    task = sys.stdin.read() if args.task == "-" else args.task
    if task is not None and (not task.strip() or "\x00" in task):
        raise ValueError("empty task")
    listing = send.zsb("workspace-list")
    slot = host_slot()
    host = host_name(listing, slot)
    workspace = (args.workspace or "").strip() or default_workspace(listing)
    group = None if args.group is None else display_name(args.group)
    existing = find_existing(send, listing, slot, display, folder)
    if existing:
        sid = existing["sid"]
        tmux = existing["tmux"]
        provider = existing.get("provider") or provider
        workspace = existing.get("workspace") or workspace
        group = existing.get("group") if existing.get("group") is not None else group
        status = "already"
    else:
        sid, tmux = session_identity(slot, display)
        path = send.host_root() / (send.host_config_key(provider, sid) + ".config.json")
        prior = send.read_json(path)
        if prior and prior.get("helper_parent"):
            raise ValueError("that name is already a helper chat; pick another name")
        if prior and (prior.get("cwd") != folder or prior.get("tmux") != tmux):
            raise ValueError("host configuration collision for " + repr(display))
        config = dict(enabled=False, cwd=folder, tmux=tmux, sid=sid, provider=provider, spawn_workspace=workspace,
                      spawn_name=display.casefold(), spawn_display=display, spawn_group=group, spawn_host=slot,
                      spawn_host_name=host)
        send.write_json(path, config)
        existing = dict(name=display, tmux=tmux, provider=provider, sid=sid, folder=folder, workspace=workspace,
                        group=group, host=host, running=False, tab_open=False)
        status = "started" if not prior else "already"
    if not existing.get("running", False):
        start_host(send, provider, sid, folder, tmux)
    shown = ensure_visible(send, listing, {**existing, "tab_open": existing.get("tab_open", False)}, display)
    first = None
    if task is not None:
        reader = send.reader_module()
        token = "mp-spawn-" + tmux[5:]
        message = send.submit(reader, provider, sid, tmux, task, token)
        send.dispatch(reader, provider, sid, tmux)
        final = send.handoff(reader, provider, sid, tmux, message.get("id"), 15.0)
        first = dict(id=message.get("id"), state=(final or {}).get("state"))
    result = dict(ok=True, status=status, name=display, tmux=tmux, sid=sid, provider=provider, folder=folder,
                  workspace=workspace, group=group, host=host, visible=shown.get("ok") is True, tab=shown.get("tab"), task=first)
    if shown.get("ok") is not True:
        result["visible_error"] = shown.get("error")
    print(json.dumps(result, separators=(",", ":")))
    return 0 if first is None or first["state"] not in ("failed", "cancelled") else 3


def cmd_restart(args):
    send = load_send()
    listing = send.zsb("workspace-list")
    target = pick_target(send, listing, args.name)
    if not target.get("sid"):
        raise ValueError("chat has no session id yet; use mp-sessions start")
    provider = target["provider"]
    sid = target["sid"]
    tmux = target["tmux"]
    folder = target.get("folder") or os.getcwd()
    if target.get("running") and target.get("tab_open"):
        print(json.dumps(dict(ok=True, status="already", name=target.get("name"), tmux=tmux, sid=sid), separators=(",", ":")))
        return 0
    if target.get("busy"):
        raise ValueError(target.get("name") or tmux + " is busy; wait for its turn to finish")
    start_host(send, provider, sid, folder, tmux)
    display = target.get("name") or tmux
    shown = ensure_visible(send, listing, target, display)
    print(json.dumps(dict(ok=True, status="restarted", name=display, tmux=tmux, sid=sid,
                          visible=shown.get("ok") is True, tab=shown.get("tab")), separators=(",", ":")))
    return 0


def cmd_rename(args):
    send = load_send()
    listing = send.zsb("workspace-list")
    target = pick_target(send, listing, args.name)
    new_name = display_name(args.new_name)
    if not target.get("tab_id"):
        raise ValueError("chat tab is not open on the desktop; restart it first")
    renamed = send.zsb("session-rename", "--tab", str(target["tab_id"]), "--name", new_name)
    if renamed.get("ok") is not True:
        raise ValueError("rename failed: " + str(renamed.get("error") or renamed.get("message")))
    print(json.dumps(dict(ok=True, name=new_name, tmux=target["tmux"], tab=target["tab_id"]), separators=(",", ":")))
    return 0


def cmd_move(args):
    send = load_send()
    listing = send.zsb("workspace-list")
    target = pick_target(send, listing, args.name)
    workspace = display_name(args.workspace)
    if not target.get("tab_id"):
        raise ValueError("chat tab is not open on the desktop; restart it first")
    command = ["session-move", "--tab", str(target["tab_id"]), "--workspace", workspace]
    if args.group is not None:
        command += ["--group", display_name(args.group)]
    else:
        command += ["--ungrouped"]
    moved = send.zsb(*command)
    if moved.get("ok") is not True:
        raise ValueError("move failed: " + str(moved.get("error") or moved.get("message")))
    path = send.host_root() / (send.host_config_key(target["provider"], target["sid"]) + ".config.json")
    config = send.read_json(path)
    if config:
        config["spawn_workspace"] = workspace
        if args.group is not None:
            config["spawn_group"] = display_name(args.group)
        elif "spawn_group" in config:
            del config["spawn_group"]
        send.write_json(path, config)
    group = display_name(args.group) if args.group is not None else None
    print(json.dumps(dict(ok=True, name=target.get("name"), tmux=target["tmux"], workspace=workspace, group=group), separators=(",", ":")))
    return 0


def cmd_close(args):
    send = load_send()
    listing = send.zsb("workspace-list")
    target = pick_target(send, listing, args.name)
    if not target.get("sid"):
        raise ValueError("chat has no session id")
    if not target.get("tab_open") and not target.get("running"):
        print(json.dumps(dict(ok=True, status="closed", name=target.get("name"), tmux=target["tmux"]), separators=(",", ":")))
        return 0
    if target.get("busy") and not args.force:
        raise ValueError((target.get("name") or target["tmux"]) + " is busy; pass --force to close anyway")
    tab_closed = None
    if target.get("tab_id"):
        closed = send.zsb("session-close", "--tab", str(target["tab_id"]))
        tab_closed = closed.get("ok") is True
        if closed.get("ok") is not True and closed.get("error") != "unknown_tab":
            raise ValueError("tab close failed: " + str(closed.get("error") or closed.get("message")))
    stopped = send.reader_cli("stop", target["provider"], target["sid"])
    print(json.dumps(dict(ok=stopped.get("status") == "stopped", status="closed", name=target.get("name"),
                          tmux=target["tmux"], tab_closed=tab_closed), separators=(",", ":")))
    return 0 if stopped.get("status") == "stopped" else 3


def parse(argv):
    parser = argparse.ArgumentParser(prog="mp-sessions", description="List, start, restart and manage Multibot chats on this server without an account key.")
    commands = parser.add_subparsers(dest="command")
    listing = commands.add_parser("list", help="list live chats (default)")
    listing.add_argument("--json", action="store_true")
    listing.add_argument("--workspace", default=None)
    identity = commands.add_parser("whoami", help="show this chat's current name, session id and linked agent")
    identity.add_argument("--json", action="store_true")
    sending = commands.add_parser("send", help="send one message to a chat by app name")
    sending.add_argument("--workspace", default=None)
    sending.add_argument("name", nargs="?")
    sending.add_argument("text", nargs=argparse.REMAINDER)
    begin = commands.add_parser("start", help="start or reuse a top-level chat")
    begin.add_argument("--name", required=True)
    begin.add_argument("--folder")
    begin.add_argument("--workspace")
    begin.add_argument("--group")
    begin.add_argument("--provider", choices=PROVIDERS)
    begin.add_argument("--task", help="first task; - reads stdin")
    again = commands.add_parser("restart", help="start a stopped chat and reopen its tab")
    again.add_argument("name")
    renamed = commands.add_parser("rename", help="rename a chat tab on the desktop")
    renamed.add_argument("name")
    renamed.add_argument("new_name")
    moved = commands.add_parser("move", help="move a chat tab to another workspace or group")
    moved.add_argument("name")
    moved.add_argument("--workspace", required=True)
    moved.add_argument("--group")
    ending = commands.add_parser("close", help="stop a chat and close its tab")
    ending.add_argument("name")
    ending.add_argument("--force", action="store_true")
    if not argv:
        return argparse.Namespace(command="list", json=False, workspace=None)
    if argv[0] not in {"list", "whoami", "start", "restart", "rename", "move", "close", "send", "-h", "--help"}:
        if argv[0] == "--json":
            return parser.parse_args(["list", *argv])
        raise ValueError("unknown command; use mp-sessions --help")
    return parser.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv == ["--help"] or argv == ["-h"]:
        parse(["--help"])
        return 0
    try:
        args = parse(argv)
    except (argparse.ArgumentError, SystemExit, ValueError) as error:
        if isinstance(error, SystemExit):
            return error.code if isinstance(error.code, int) else 2
        print("mp-sessions: " + str(error), file=sys.stderr)
        return 2
    command = args.command or "list"
    send = load_send(session_api=False)
    handlers = dict(list=cmd_list, whoami=cmd_whoami, start=cmd_start, restart=cmd_restart, rename=cmd_rename, move=cmd_move,
                    close=cmd_close, send=cmd_send)
    try:
        if command == "send":
            tail = argv[1:] if argv and argv[0] == "send" else argv
            return handlers[command](parse_send(tail))
        return handlers[command](args)
    except send.RateLimited as error:
        print("mp-sessions: " + str(error), file=sys.stderr)
        return 5
    except (OSError, ValueError, KeyError, TypeError) as error:
        print("mp-sessions: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())