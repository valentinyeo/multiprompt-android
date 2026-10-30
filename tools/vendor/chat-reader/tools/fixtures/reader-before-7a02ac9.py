READER_SOURCE = r'''
"""Read-only Chat View delta reader, sent over SSH stdin (no VPS install).

Offsets bind to a provider session id and inode. Only complete JSONL records
advance the cursor. Paging limits traffic, never the retained conversation.
"""
import base64
import hashlib
import json
import os
import pwd
import re
import subprocess
import sys
from pathlib import Path

HOME = Path.home()
SESSION_ID = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
TMUX_NAME = re.compile(r"[A-Za-z0-9_.:-]{1,80}\Z")
_COMMAND_NAME = re.compile(r"<command-name>\s*(.*?)\s*</command-name>", re.S)
_COMMAND_ARGS = re.compile(r"<command-args>(.*?)</command-args>", re.S)
_LOCAL_COMMAND = re.compile(r"<(local-command-stdout|local-command-stderr|local-command-caveat)>.*?</\1>", re.S)
END = "@@MP_END@@"
PAGE_BYTES = 350000
SCAN_BYTES = 512 * 1024
TAIL_BYTES = 512 * 1024


def read_json(path):
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}

def claude_config_dirs(home):
    configured = os.environ.get("CLAUDE_CONFIG_DIR") if home == HOME else None
    return ([Path(configured).expanduser()] if configured else []) + [home / ".claude", home / ".config/claude"]

def conversation_owner(cwd):
    parts = Path(cwd).parts
    if (len(parts) < 4 or parts[:2] != ("/", "home") or ".." in parts or
            not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]{0,31}", parts[2])):
        return None
    try:
        user = pwd.getpwnam(parts[2])
    except KeyError:
        return None
    home = Path(user.pw_dir)
    return (user.pw_name, home) if user.pw_uid != 0 and home == Path("/home") / parts[2] else None

def conversation_path(agent, sid, cwd, home=None):
    if not SESSION_ID.fullmatch(sid):
        return None
    home = HOME if home is None else home
    roots = ([directory / "projects" for directory in claude_config_dirs(home)]
             if agent == "claude" else [home / ".codex/sessions"])
    denied = None
    for root in roots:
        candidates = []
        if agent == "claude":
            folder = claude_project_folder(cwd)
            candidates.append(root / folder / (sid + ".jsonl"))
        else:
            candidates.extend(root.glob("**/rollout-*-" + sid + ".jsonl"))
        # Try the exact path before globbing directories the caller may not read.
        for exact in (True, False):
            if not exact:
                try:
                    candidates = list(root.glob("*/" + sid + ".jsonl")) if agent == "claude" else []
                except PermissionError as error:
                    denied = error
                    continue
            for path in candidates:
                try:
                    if path.is_file() and path.resolve().is_relative_to(root.resolve()):
                        with path.open("rb"):
                            return path
                except PermissionError as error:
                    denied = error
                except OSError:
                    pass
    if denied is not None:
        raise denied
    return None

def owner_response(q, owner, source):
    user, _home = owner
    try:
        probe = subprocess.run(["sudo", "-n", "-u", user, "--", "true"],
                               capture_output=True, timeout=3, check=False)
        if probe.returncode != 0:
            return missing_response(q, "permission denied; noninteractive sudo unavailable for " + user)
        encoded = base64.b64encode(json.dumps(q).encode()).decode()
        result = subprocess.run(["sudo", "-n", "-H", "-u", user, "--", "python3", "-", encoded, "--owner"],
                                input=source, capture_output=True, text=True, timeout=45, check=False)
        if result.returncode == 0 and result.stdout.endswith(END + "\n"):
            return result.stdout
    except (OSError, subprocess.SubprocessError):
        pass
    return missing_response(q, "permission denied; could not read as " + user)

def missing_response(q, reason):
    return f"@mp\t{q['id']}\thistory\tmissing\t-\t0\t{reason}\n{END}\n"

def clean_command_markup(text):
    """Show a slash command the way the user typed it.

    Claude Code stores "/x args" as <command-message>, <command-name> and
    <command-args> tags, and adds local-command output/caveat notices. Return
    "/x args" for the first, and drop the notices (empty string when nothing
    else is left).
    """
    if "<command-name>" in text:
        name = _COMMAND_NAME.search(text)
        if name:
            args = _COMMAND_ARGS.search(text)
            command = name.group(1).strip()
            if command and not command.startswith("/"):
                command = "/" + command
            arguments = args.group(1).strip() if args else ""
            return (command + " " + arguments).strip()
    if "<local-command-" in text:
        text = _LOCAL_COMMAND.sub("", text)
    return text

def process_basename(value):
    return str(value).replace("\\", "/").rsplit("/", 1)[-1]

def agent_pid_for_panes(pane_pids, table, agent):
    """BFS each pane pid's descendants for an argv[0] basename equal to agent.

    `table` maps pid -> (ppid, argv) so the search is a pure function over the
    process snapshot (see process_table). Returns the first matching pid.
    """
    children = {}
    for pid, (ppid, _argv) in table.items():
        children.setdefault(ppid, []).append(pid)
    for pane in pane_pids:
        queue = [pane]
        seen = set()
        while queue:
            pid = queue.pop(0)
            if pid in seen:
                continue
            seen.add(pid)
            argv = table.get(pid, (0, []))[1]
            if any(process_basename(part) == agent for part in argv):
                return pid
            queue.extend(children.get(pid, []))
    return None

def claude_project_folder(cwd):
    return re.sub(r"[^A-Za-z0-9]", "-", str(cwd))

def newest_jsonl(paths):
    best = None
    best_mtime = -1.0
    for path in paths:
        try:
            if not path.is_file():
                continue
            mtime = path.stat().st_mtime
        except OSError:
            continue
        if mtime > best_mtime:
            best, best_mtime = path, mtime
    return best

def process_table(root=Path("/proc")):
    """Snapshot every readable process as pid -> (ppid, argv)."""
    table = {}
    try:
        entries = list(root.iterdir())
    except OSError:
        return table
    for entry in entries:
        if not entry.name.isdigit():
            continue
        try:
            stat = (entry / "stat").read_text(encoding="utf-8", errors="replace")
            raw = (entry / "cmdline").read_bytes()
        except OSError:
            continue
        close = stat.rfind(")")
        tail = stat[close + 2:].split() if close >= 0 else []
        ppid = int(tail[1]) if len(tail) > 1 and tail[1].lstrip("-").isdigit() else 0
        argv = [part.decode("utf-8", "replace") for part in raw.split(b"\0") if part]
        table[int(entry.name)] = (ppid, argv)
    return table

def read_process_cwd(pid):
    try:
        return os.readlink(f"/proc/{pid}/cwd")
    except OSError:
        return None

def claude_session_metadata(pid):
    """Exact sid/cwd from ~/.claude/sessions/<pid>.json when that map exists."""
    data = read_json(HOME / ".claude/sessions" / f"{pid}.json")
    sid = data.get("sessionId")
    cwd = data.get("cwd")
    return (sid if isinstance(sid, str) and SESSION_ID.fullmatch(sid) else None,
            cwd if isinstance(cwd, str) and cwd else None)

def claude_transcript_for_cwd(cwd):
    folder = HOME / ".claude/projects" / claude_project_folder(cwd)
    if not folder.is_dir():
        return None
    return newest_jsonl(folder.glob("*.jsonl"))

def codex_transcript_for_cwd(cwd):
    """Newest rollout whose session_meta cwd matches; returns (sid, path)."""
    root = HOME / ".codex/sessions"
    if not root.is_dir():
        return None
    best = None
    best_mtime = -1.0
    try:
        candidates = list(root.rglob("rollout-*.jsonl"))
    except OSError:
        return None
    for path in candidates:
        try:
            with path.open("r", encoding="utf-8") as source:
                meta = json.loads(source.readline())
            payload = meta.get("payload") if isinstance(meta, dict) else None
            if not isinstance(payload, dict) or payload.get("cwd") != cwd:
                continue
            sid = payload.get("id") or payload.get("session_id")
            if not isinstance(sid, str) or not SESSION_ID.fullmatch(sid):
                continue
            mtime = path.stat().st_mtime
        except (OSError, ValueError):
            continue
        if mtime > best_mtime:
            best, best_mtime = (sid, path), mtime
    return best

def resolve_tmux_agent(agent, name):
    """tmux pane pids -> descendant agent pid -> exact transcript (sid, path)."""
    try:
        result = subprocess.run(["tmux", "list-panes", "-t", name, "-F", "#{pane_pid}"],
                                capture_output=True, text=True, encoding="utf-8", timeout=3, check=False)
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    pane_pids = [int(line) for line in result.stdout.split() if line.isdigit()]
    if not pane_pids:
        return None
    pid = agent_pid_for_panes(pane_pids, process_table(), agent)
    if pid is None:
        return None
    cwd = read_process_cwd(pid)
    if agent == "claude":
        sid, mapped_cwd = claude_session_metadata(pid)
        cwd = mapped_cwd or cwd
        if sid:
            path = conversation_path("claude", sid, cwd) if cwd else None
            if path is not None:
                return sid, path
        fallback = claude_transcript_for_cwd(cwd) if cwd else None
        if fallback is None:
            return None
        return fallback.stem, fallback
    if cwd is None:
        return None
    return codex_transcript_for_cwd(cwd)


def conversation_event(event, agent):
    if not isinstance(event, dict):
        return None
    if agent == "claude":
        attachment = event.get("attachment") if event.get("type") == "attachment" else None
        if isinstance(attachment, dict) and attachment.get("type") == "queued_command" and not event.get("isSidechain"):
            message = {"role": "user", "content": attachment.get("prompt", "")}
        elif event.get("type") not in ("user", "assistant") or event.get("isMeta") or event.get("isSidechain"):
            return None
        else:
            message = event.get("message") or {}
    else:
        if event.get("type") != "response_item":
            return None
        message = event.get("payload") or {}
        if not isinstance(message, dict) or message.get("type") != "message":
            return None
    if not isinstance(message, dict) or message.get("role") not in ("user", "assistant"):
        return None
    role = message["role"]
    content = message.get("content", "")
    if isinstance(content, list):
        text = "\n".join(part.get("text", "") for part in content
                         if isinstance(part, dict) and part.get("type") in ("text", "input_text", "output_text")
                         and isinstance(part.get("text"), str))
    else:
        text = content if isinstance(content, str) else ""
    if role == "user":
        if text.lstrip().startswith("<task-notification>"):
            return None
        text = clean_command_markup(text)
    if not text.strip():
        return None
    identity = message.get("id") or event.get("uuid") or ""
    timestamp = event.get("timestamp") or ""
    return dict(role=role, timestamp=timestamp if isinstance(timestamp, str) else "",
                text=text, id=identity if isinstance(identity, str) else "")


def read_window(source, end, budget=TAIL_BYTES):
    """Read only a bounded byte range and discard its incomplete boundary rows."""
    start = max(0, end - budget)
    source.seek(start)
    data = source.read(end - start)
    if start:
        newline = data.find(b"\n")
        if newline < 0:
            return 0, 0, b""  # Never advance into an unfinished giant record.
        skip = newline + 1
        start += skip
        data = data[skip:]
    complete = data.rfind(b"\n") + 1
    return start, start + complete, data[:complete]


def response(q, source=None, as_owner=False):
    rid, agent = q["id"], "codex" if q["codex"] else "claude"
    sid, tmux, cwd = q["sid"], q["tmux"], q["cwd"]
    if (not isinstance(rid, int) or rid < 0 or
            sid and not SESSION_ID.fullmatch(sid) or
            tmux and not TMUX_NAME.fullmatch(tmux) or not sid and not tmux or
            cwd and not Path(cwd).is_absolute()):
        raise ValueError("invalid identity")
    denied = False
    try:
        path = conversation_path(agent, sid, cwd) if sid else None
    except PermissionError:
        path, denied = None, True
    owner = conversation_owner(cwd) if agent == "claude" and sid else None
    if path is None and owner and owner[1] != HOME:
        try:
            path = conversation_path(agent, sid, cwd, owner[1])
        except PermissionError:
            denied = True
            if source is not None and not as_owner:
                return owner_response(q, owner, source)
    if path is None and tmux:
        try:
            resolved = resolve_tmux_agent(agent, tmux)
        except PermissionError:
            resolved, denied = None, True
        sid, path = resolved if resolved else ("", None)
    if path is None:
        return missing_response(q, "permission denied" if denied else "session transcript not found")
    rows, budget = [], 0
    with path.open("rb") as source:
        info = os.fstat(source.fileno())
        offset = q["offset"]
        valid = q["cached_sid"] == sid and q["inode"] == info.st_ino and 0 <= offset <= info.st_size
        if valid and offset and "probe" in q:
            source.seek(max(0, offset - 64))
            boundary = hashlib.sha256(source.read(min(offset, 64))).hexdigest()
            valid = boundary == q["probe"] and (info.st_size != offset or info.st_mtime_ns == q.get("mtime"))
        before = q.get("before", 0)
        if not isinstance(before, int) or before < 0 or (valid and before > info.st_size):
            raise ValueError("invalid page")
        scan_more = False
        if before and valid:
            mode = "older"
            base, end, data = read_window(source, before)
            # A single oversized record is fetched whole only on explicit scroll,
            # never during startup or background polling.
            window = TAIL_BYTES
            while not data and window < before:
                window = min(before, window * 2)
                base, end, data = read_window(source, before, window)
            oldest = base
        elif not valid:
            mode = "tail"
            base, end, data = read_window(source, info.st_size)
            oldest = base
        else:
            mode = "delta"
            base = offset
            oldest = q.get("start", 0)
            source.seek(base)
            data = source.read(min(SCAN_BYTES, info.st_size - base))
            scan_more = base + len(data) < info.st_size
            end = base + data.rfind(b"\n") + 1
            if end == base and scan_more:
                mode = "tail"  # A giant appended tool record cannot pin the cursor.
                base, end, data = read_window(source, info.st_size)
                oldest = base
                scan_more = False
            else:
                data = data[:end - base]
        page_more = False
        consumed = base
        for raw in data.splitlines(keepends=True):
            event = json.loads(raw)
            message = conversation_event(event, agent)
            if message:
                row = json.dumps(message, ensure_ascii=True, separators=(",", ":"))
                # Tail/older windows are already bounded; include all complete
                # records so the newest message cannot be stranded behind a cap.
                if mode == "delta" and rows and budget + len(row) + 1 > PAGE_BYTES:
                    end = consumed
                    page_more = True
                    break
                rows.append(row)
                budget += len(row) + 1
            consumed += len(raw)
        more = scan_more or page_more
        source.seek(max(0, end - 64))
        probe = hashlib.sha256(source.read(min(end, 64))).hexdigest()
    header = f"@mp\t{rid}\thistory\tok\t{sid}\t{base}\t{end}\t{info.st_ino}\t{int(more)}\t{agent}\t{tmux or '-'}\t{info.st_mtime_ns}\t{probe}\t{mode}\t{oldest}"
    return "\n".join((header, *rows, END, ""))


if __name__ == "__main__":
    try:
        query = json.loads(base64.b64decode(sys.argv[1], validate=True))
        # python3 - has consumed stdin by execution time; keep the source for sudo.
        print(response(query, globals().get("READER_SOURCE"), "--owner" in sys.argv[2:]), end="")
    except (OSError, ValueError, KeyError, TypeError, IndexError):
        print("@mp\t0\thistory\terror\t-\t0\n" + END)

'''
exec(compile(READER_SOURCE, '<chat-history-reader>', 'exec'))
