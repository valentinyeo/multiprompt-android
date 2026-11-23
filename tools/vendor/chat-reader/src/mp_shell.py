#!/usr/bin/env python3
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

FOLDER = re.compile(r"[A-Za-z0-9_./~+ -]{1,220}\Z")
TMUX = re.compile(r"[A-Za-z0-9_.:-]{1,64}\Z")
OFFLINE = frozenset({"tunnel_down", "reply_timeout", "no_window", "connection_closed", "empty_reply", "notify_token_unavailable", "timeout"})

_send = None


def send_module():
    global _send
    if _send is None:
        here = Path(__file__).resolve()
        source = here.with_name("mp_send.py")
        if not source.is_file():
            source = here.with_name("mp-send")
        import importlib.machinery
        import importlib.util
        loader = importlib.machinery.SourceFileLoader("mp_send", str(source))
        spec = importlib.util.spec_from_loader("mp_send", loader)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _send = module
    return _send


def zsb_command():
    candidates = [Path(__file__).resolve().with_name("mp-zsb")]
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


def find_tab(listing, tmux):
    for workspace in listing.get("workspaces") or []:
        tabs = [(None, tab) for tab in workspace.get("tabs") or []]
        for group in workspace.get("groups") or []:
            tabs.extend((group.get("name"), tab) for tab in group.get("tabs") or [])
        for group, tab in tabs:
            if tab.get("tmux") == tmux:
                return workspace.get("name"), group, tab
    return None, None, None


def parent_context():
    parent = send_module().own_tmux()
    if not parent:
        return None
    listing = zsb("workspace-list")
    if listing.get("ok") is not True:
        return dict(error=listing.get("error") or "zsb_failed", message=listing.get("message"))
    workspace, group, tab = find_tab(listing, parent)
    if not tab or not tab.get("host"):
        return dict(error="parent_tab_not_open")
    return dict(workspace=workspace, group=group, tab_id=tab.get("id"), host=tab["host"])


def shell_name(attach, title):
    if title:
        return " ".join(title.split())
    if attach:
        return attach
    return "Server shell"


def remote_run(folder, command, attach):
    parts = []
    if folder:
        parts.append("cd " + repr(folder.rstrip("/")) + " &&")
    if command:
        parts.append(command)
    elif attach:
        parts.append("tmux attach -t " + repr("=" + attach))
    return " ".join(parts) if parts else None


def open_tab(args):
    ctx = None
    group_fallback = None
    if args.workspace:
        workspace = args.workspace.strip()
        host = args.host
        placement = []
        if args.after_tab is not None:
            placement = ["--after-tab", str(args.after_tab)]
        elif args.group:
            placement = ["--group", args.group.strip()]
        else:
            raise ValueError("give --group or run from a live chat so the tab can follow it")
    else:
        ctx = parent_context()
        if not ctx:
            raise ValueError("run this from a live chat or give --workspace, --host and --group")
        if ctx.get("error"):
            code = ctx["error"]
            if code in OFFLINE:
                raise ValueError("the desktop app is offline")
            raise ValueError(code)
        workspace = ctx["workspace"]
        host = args.host or ctx["host"]
        if args.group:
            placement = ["--group", args.group.strip()]
        elif args.after_tab is not None:
            placement = ["--after-tab", str(args.after_tab)]
        else:
            placement = ["--after-tab", str(ctx["tab_id"])]
        group_fallback = ctx.get("group")
    if not host:
        raise ValueError("give --host or run from a live chat")
    attach = args.attach
    if attach and TMUX.fullmatch(attach) is None:
        raise ValueError("invalid attach session name")
    folder = None
    if args.folder:
        folder = os.path.realpath(os.path.expanduser(args.folder))
        if not os.path.isdir(folder) or FOLDER.match(folder) is None:
            raise ValueError("invalid folder: " + folder)
    title = shell_name(attach, args.name)
    command = remote_run(folder, args.run, attach)
    zsb_args = ["shell-open", "--workspace", workspace, *placement, "--host", host, "--name", title]
    if attach:
        zsb_args.extend(["--attach", attach])
    if command:
        zsb_args.extend(["--run", command])
    opened = zsb(*zsb_args)
    if opened.get("ok") is not True and opened.get("error") == "bad_args" and group_fallback:
        retry = ["shell-open", "--workspace", workspace, "--group", group_fallback, "--host", host, "--name", title]
        if attach:
            retry.extend(["--attach", attach])
        if command:
            retry.extend(["--run", command])
        opened = zsb(*retry)
    if opened.get("ok") is not True:
        code = opened.get("error") or "shell_open_failed"
        if code in OFFLINE:
            print("mp-shell: the desktop app is offline", file=sys.stderr)
            return 4
        message = opened.get("message") or code
        print("mp-shell: " + str(message), file=sys.stderr)
        return 3
    print(json.dumps(dict(ok=True, tab=opened.get("tab"), existing=opened.get("existing"), state=opened.get("state")), separators=(",", ":")))
    return 0


def parse(argv):
    parser = argparse.ArgumentParser(prog="mp-shell", description="Ask the desktop app to open a server shell tab. Run from a live chat or name the workspace and host.")
    commands = parser.add_subparsers(dest="command", required=True)
    opening = commands.add_parser("open", help="open a server shell tab on the desktop")
    opening.add_argument("--folder", default=None, help="remote working folder before attach or command")
    opening.add_argument("--attach", default=None, help="tmux session to attach (for example projects-2)")
    opening.add_argument("--name", default=None, help="tab title on the desktop")
    opening.add_argument("--run", default=None, help="remote startup command (default: plain login or tmux attach)")
    opening.add_argument("--workspace", default=None, help="workspace name when not run from a chat")
    opening.add_argument("--group", default=None, help="group name when not placing after the chat tab")
    opening.add_argument("--host", default=None, help="host name from workspace-list (default: this chat's host)")
    opening.add_argument("--after-tab", type=int, default=None, help="place below this tab id")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse(sys.argv[1:] if argv is None else argv)
    try:
        return open_tab(args)
    except ValueError as error:
        print("mp-shell: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())