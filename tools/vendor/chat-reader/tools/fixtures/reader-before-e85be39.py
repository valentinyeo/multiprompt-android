READER_SOURCE = r'''
"""Read-only Chat View delta reader, sent over SSH stdin (no VPS install).

Offsets bind to a provider session id and inode. Only complete JSONL records
advance the cursor. Paging limits traffic, never the retained conversation.
"""
import base64
from datetime import datetime
import hashlib
import json
import os
import pwd
import re
import subprocess
import sys
import time
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
SETTLE_SUFFIX_MIN = 24
ISSUE_345_STALE_JUMPS_TO_TAIL = True
ISSUE_349_QUEUED_STAMP_FROM_QUEUE = True


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
    if q.get("op") == "settle":
        return f"@mp\t{q['id']}\tsettle\terror\t{reason}\t0\n{END}\n"
    return f"@mp\t{q['id']}\thistory\tmissing\t-\t0\t{reason}\n{END}\n"

def clean_command_markup(text):
    if re.search(r"</?pasted_content\b", text, re.I):
        text = re.sub(r"^[ \t]*</?pasted_content\b[^>]*>[ \t]*\r?\n?", "", text, flags=re.I | re.M)
        text = re.sub(r"</?pasted_content\b[^>]*>", " ", text, flags=re.I).strip()
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
            if argv and (process_basename(argv[0]) == agent or
                         process_basename(argv[0]) in ("node", "bun") and len(argv) > 1 and
                         process_basename(argv[1]) in (agent, agent + ".js")):
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

def link_local_ssh(table, connections):
    """Join a local SSH client to its sshd by reversed TCP endpoints, not user/cwd."""
    for pid, (_ppid, argv) in list(table.items()):
        if not argv or process_basename(argv[0]) != "ssh":
            continue
        for local, remote in connections.get(pid, []):
            for peer, endpoints in connections.items():
                peer_argv = table.get(peer, (0, []))[1]
                if peer_argv and peer_argv[0].startswith("sshd:") and (remote, local) in endpoints:
                    table[peer] = (pid, peer_argv)
    return table

def local_ssh_process_table(root=Path("/proc")):
    table = process_table(root)
    tcp = {}
    for net in ("tcp", "tcp6"):
        try:
            for line in (root / "net" / net).read_text().splitlines()[1:]:
                fields = line.split()
                if len(fields) > 9 and fields[3] == "01":
                    tcp[fields[9]] = (fields[1], fields[2])
        except OSError:
            pass
    connections = {}
    for pid, (_ppid, argv) in table.items():
        if not argv or not (process_basename(argv[0]) == "ssh" or argv[0].startswith("sshd:")):
            continue
        try:
            for fd in (root / str(pid) / "fd").iterdir():
                try:
                    link = os.readlink(fd)
                    endpoint = tcp.get(link[8:-1]) if link.startswith("socket:[") else None
                    if endpoint:
                        connections.setdefault(pid, []).append(endpoint)
                except OSError:
                    pass
        except OSError:
            pass
    return link_local_ssh(table, connections)

def tmux_owner_process(agent, name, source):
    """Only /proc inspection runs as root; transcript reads run as the process owner."""
    try:
        panes = subprocess.run(["tmux", "list-panes", "-t", "=" + name, "-F", "#{pane_pid}"],
                               capture_output=True, text=True, timeout=3, check=False)
        if panes.returncode != 0:
            return None
        pids = [int(line) for line in panes.stdout.split() if line.isdigit()]
        if not pids:
            return None
        encoded = base64.b64encode(json.dumps(dict(agent=agent, panes=pids)).encode()).decode()
        result = subprocess.run(["sudo", "-n", "--", "python3", "-", encoded, "--discover"],
                                input=source, capture_output=True, text=True, timeout=8, check=False)
        if result.returncode == 0:
            data = json.loads(result.stdout)
            if isinstance(data, dict) and isinstance(data.get("pid"), int) and data["pid"] > 0:
                user = pwd.getpwnam(data["user"])
                if user.pw_uid != 0:
                    return data["pid"], (user.pw_name, Path(user.pw_dir))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        pass
    return None

def open_claude_transcript(pid):
    paths = []
    try:
        for fd in Path(f"/proc/{pid}/fd").iterdir():
            try:
                path = Path(os.readlink(fd))
                if (path.suffix == ".jsonl" and SESSION_ID.fullmatch(path.stem) and
                        any(path.resolve().parent.parent == (root / "projects").resolve()
                            for root in claude_config_dirs(HOME))):
                    paths.append(path)
            except OSError:
                pass
    except OSError:
        pass
    return newest_jsonl(paths)

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

def resolve_tmux_agent(agent, name, pid=None):
    """tmux pane pids -> descendant agent pid -> exact transcript (sid, path)."""
    if pid is None:
        try:
            result = subprocess.run(["tmux", "list-panes", "-t", "=" + name, "-F", "#{pane_pid}"],
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
        opened = open_claude_transcript(pid)
        if opened is not None:
            return opened.stem, opened
        sid, mapped_cwd = claude_session_metadata(pid)
        cwd = mapped_cwd or cwd
        if sid:
            path = conversation_path("claude", sid, cwd) if cwd else None
            return (sid, path) if path is not None else None
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
        elif event.get("type") not in ("user", "assistant") or event.get("isMeta") or event.get("isSidechain") or event.get("isCompactSummary") or event.get("isVisibleInTranscriptOnly"):
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



def settlement_event(event, agent):
    if agent != "claude" or not isinstance(event, dict) or event.get("isSidechain"):
        return None
    kind = event.get("type")
    operation = event.get("operation")
    content = event.get("content", "")
    if kind == "queue-operation" and operation in ("enqueue", "remove", "dequeue", "popAll"):
        fact = dict(operation=operation, content=content if isinstance(content, str) else "")
    elif (kind == "system" and event.get("subtype") == "informational" and
          event.get("level") in ("warning", "error") and isinstance(content, str) and
          content.startswith(("Unknown command: /", "Args from unknown skill: "))):
        fact = dict(operation="rejected", content=content)
    else:
        return None
    timestamp = event.get("timestamp", "")
    return dict(role="assistant", text="", timestamp=timestamp if isinstance(timestamp, str) else "", settlement=fact)


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


def resolve_response_path(q, source=None, as_owner=False):
    rid, agent = q["id"], "codex" if q["codex"] else "claude"
    sid, tmux, cwd = q["sid"], q["tmux"], q["cwd"]
    if (not isinstance(rid, int) or rid < 0 or
            sid and not SESSION_ID.fullmatch(sid) or
            tmux and not TMUX_NAME.fullmatch(tmux) or not sid and not tmux or
            cwd and not Path(cwd).is_absolute()):
        raise ValueError("invalid identity")
    denied = False
    delegated = None
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
                delegated = owner_response(q, owner, source)
                if delegated != missing_response(q, "session transcript not found"):
                    return agent, sid, None, delegated

    if path is None and tmux:
        try:
            resolved = (resolve_tmux_agent(agent, tmux, q["discovery_pid"])
                        if as_owner and "discovery_pid" in q else resolve_tmux_agent(agent, tmux))
        except PermissionError:
            resolved, denied = None, True
        sid, path = resolved if resolved else ("", None)
        if path is None and source is not None and not as_owner:
            discovered = tmux_owner_process(agent, tmux, source)
            if discovered:
                pid, owner = discovered
                return agent, sid, None, owner_response(dict(q, sid="", discovery_pid=pid), owner, source)
    if path is None:
        return agent, sid, None, delegated or missing_response(q, "permission denied" if denied else "session transcript not found")
    return agent, sid, path, None


def response(q, source=None, as_owner=False):
    agent, sid, path, delegated = resolve_response_path(q, source, as_owner)
    if delegated is not None:
        return delegated
    rid, tmux = q["id"], q["tmux"]
    rows, budget = [], 0
    queue_stamps = {}
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
        jumped = False
        if before and valid:
            mode = "older"
            base, end, data = read_window(source, before)
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
            if ISSUE_345_STALE_JUMPS_TO_TAIL and not q.get("no_jump") and info.st_size - offset > 2 * SCAN_BYTES:
                base, end, data = read_window(source, info.st_size)
                if data:
                    jumped = True
                    mode = "tail"
                    oldest = base
                    scan_more = False
            if mode == "delta":
                base = offset
                oldest = q.get("start", 0)
                source.seek(base)
                data = source.read(min(SCAN_BYTES, info.st_size - base))
                scan_more = base + len(data) < info.st_size
                end = base + data.rfind(b"\n") + 1
                if end == base and scan_more:
                    mode = "tail"
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
            if ISSUE_349_QUEUED_STAMP_FROM_QUEUE and agent == "claude" and isinstance(event, dict):
                attachment = event.get("attachment") or {}
                queued = event.get("type") == "attachment" and isinstance(attachment, dict) and attachment.get("type") == "queued_command"
                operation = event.get("operation") if event.get("type") == "queue-operation" else None
                content = attachment.get("prompt") if queued else event.get("content")
                if isinstance(content, str) and (queued or operation in ("enqueue", "remove")) and not event.get("isSidechain"):
                    timestamp = event.get("timestamp") or ""
                    try:
                        stamp = datetime.fromisoformat(timestamp.replace("Z", "+00:00")).timestamp()
                    except (ValueError, TypeError, AttributeError):
                        stamp = 0
                    previous = queue_stamps.get(content, (0, ""))
                    if queued and message and previous[0] > stamp:
                        message["timestamp"] = previous[1]
                    elif operation in ("enqueue", "remove") and stamp >= previous[0]:
                        queue_stamps[content] = (stamp, timestamp)
            if message is None and q.get("settlement"):
                message = settlement_event(event, agent)
            if message:
                row = json.dumps(message, ensure_ascii=True, separators=(",", ":"))
                if mode == "delta" and rows and budget + len(row) + 1 > PAGE_BYTES:
                    end = consumed
                    page_more = True
                    break
                rows.append(row)
                budget += len(row) + 1
            consumed += len(raw)
        if jumped and not rows:
            return response(dict(q, no_jump=True), source, as_owner)
        more = scan_more or page_more
        source.seek(max(0, end - 64))
        probe = hashlib.sha256(source.read(min(end, 64))).hexdigest()
    header = f"@mp\t{rid}\thistory\tok\t{sid}\t{base}\t{end}\t{info.st_ino}\t{int(more)}\t{agent}\t{tmux or '-'}\t{info.st_mtime_ns}\t{probe}\t{mode}\t{oldest}"
    return "\n".join((header, *rows, END, ""))


def prompt_box_text(target):
    try:
        shown = subprocess.run(["tmux", "capture-pane", "-p", "-J", "-t", target], capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.SubprocessError, UnicodeError):
        return ""
    if not isinstance(shown, str):
        return ""
    lines = shown.splitlines()
    starts = [i for i, line in enumerate(lines) if line.startswith("\u276f")]
    if not starts:
        return ""
    box = []
    for line in lines[starts[-1]:]:
        if line.startswith("\u2500"):
            break
        box.append(line)
    return " ".join(" ".join(box)[1:].split())


def send_response(q):
    rid = q.get("id", 0)
    name = q.get("tmux")
    text = q.get("text")
    reason = None
    if not isinstance(name, str) or re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", name) is None:
        reason = "invalid_tmux_name"
    elif not isinstance(text, str) or not text or "\x00" in text:
        reason = "invalid_text"
    else:
        try:
            target = "=" + name + ":"
            wanted = " ".join(text.split())
            if wanted and wanted in prompt_box_text(target):
                pass
            elif "\n" in text or "\r" in text:
                buffer = "mp-send-" + str(os.getpid()) + "-" + str(rid)
                subprocess.run(["tmux", "load-buffer", "-b", buffer, "-"], input=text.encode("utf-8"), check=True, capture_output=True, timeout=5)
                try:
                    pasted = subprocess.run(["tmux", "paste-buffer", "-p", "-d", "-b", buffer, "-t", target], capture_output=True, timeout=5)
                    if pasted.returncode:
                        subprocess.run(["tmux", "send-keys", "-t", target, "-l", "--", text], check=True, capture_output=True, timeout=5)
                finally:
                    subprocess.run(["tmux", "delete-buffer", "-b", buffer], capture_output=True, timeout=5)
            else:
                subprocess.run(["tmux", "send-keys", "-t", target, "-l", "--", text], check=True, capture_output=True, timeout=5)
            time.sleep(0.15)
            subprocess.run(["tmux", "send-keys", "-t", target, "Enter"], check=True, capture_output=True, timeout=5)
            time.sleep(0.4)
            if wanted and wanted in prompt_box_text(target):
                subprocess.run(["tmux", "send-keys", "-t", target, "Enter"], check=True, capture_output=True, timeout=5)
        except (OSError, subprocess.SubprocessError, UnicodeError):
            reason = "tmux_send_failed"
    status = "error" if reason else "ok"
    return f"@mp\t{rid}\tsend\t{status}\t{reason or '-'}\t0\n{END}\n"


def settle_unchained(target):
    words = target.split(" ")
    commands = 0
    while commands < len(words) and re.fullmatch(r"/[A-Za-z0-9_:.-]+", words[commands]):
        commands += 1
    if commands < 2 or commands == len(words):
        return None
    return words[0] + " " + " ".join(words[commands:])


def settle_response(q, source=None, as_owner=False):
    rid = q.get("id", 0)

    def error(reason):
        return f"@mp\t{rid}\tsettle\terror\t{reason}\t0\n{END}\n"

    texts = q.get("texts")
    if (not isinstance(texts, list) or not 1 <= len(texts) <= 16 or
            any(not isinstance(text, str) or len(text.encode("utf-8", errors="surrogatepass")) > 65536 for text in texts)):
        return error("invalid_texts")
    if type(q.get("after_ms")) is not int:
        return error("invalid_after_ms")
    try:
        agent, _sid, path, delegated = resolve_response_path(q, source, as_owner)
        if delegated is not None:
            return delegated
        targets = [" ".join(clean_command_markup(text).split()) for text in texts]
        chained = [settle_unchained(target) for target in targets]
        rows = [dict(i=i, delivered_ms=0, line=0) for i in range(len(texts))]
        paths = [path]
        if agent == "claude":
            paths += sorted((p for p in path.parent.glob("*.jsonl")
                             if p != path and p.stat().st_mtime * 1000 >= q["after_ms"] - 5000),
                            key=lambda p: p.stat().st_mtime, reverse=True)
        for source_path in paths:
            if all(row["line"] for row in rows):
                break
            with source_path.open("rb") as transcript:
                for line_number, line in enumerate(transcript, 1):
                    if not line.endswith(b"\n"):
                        continue
                    try:
                        event = json.loads(line)
                    except (ValueError, UnicodeError):
                        continue
                    if not isinstance(event, dict) or event.get("isMeta"):
                        continue
                    if event.get("type") not in ("user", "attachment", "response_item"):
                        continue
                    message = conversation_event(event, agent)
                    if message is None or message["role"] != "user":
                        continue
                    try:
                        stamp = int(datetime.fromisoformat(message["timestamp"].replace("Z", "+00:00")).timestamp() * 1000)
                    except (ValueError, OverflowError, OSError):
                        continue
                    if stamp < q["after_ms"] - 5000:
                        continue
                    text = " ".join(message["text"].split())
                    open_targets = [i for i, target in enumerate(targets) if rows[i]["line"] == 0]
                    hits = [i for i in open_targets if text == targets[i] or text == chained[i]]
                    if not hits:
                        padded = f" {text} "
                        hits = [i for i in open_targets if len(targets[i]) >= SETTLE_SUFFIX_MIN and f" {targets[i]} " in padded]
                    for i in hits:
                        rows[i].update(delivered_ms=stamp, line=line_number)
        return (f"@mp\t{rid}\tsettle\tok\t-\t{len(rows)}\n" +
                "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows) + END + "\n")
    except (OSError, ValueError, KeyError, TypeError, IndexError):
        return error("read_failed")


def serve(source):
    for line in sys.stdin.buffer:
        query = None
        rid = 0
        try:
            query = json.loads(line)
            if not isinstance(query, dict):
                raise ValueError("invalid request")
            rid = query.get("id", 0)
            if type(rid) is not int or rid < 0:
                rid = 0
                raise ValueError("invalid request id")
            if query.get("op") == "send":
                wire = send_response(query)
            elif query.get("op") == "settle":
                wire = settle_response(query, source)
            else:
                wire = response(query, source)
        except (OSError, ValueError, KeyError, TypeError, IndexError):
            wire = f"@mp\t{rid}\thistory\terror\t-\t0\n{END}\n"
        print(json.dumps(dict(id=rid, wire=wire), separators=(",", ":")), flush=True)


if __name__ == "__main__" and sys.argv[1:] == ["--serve"]:
    serve(globals().get("READER_SOURCE"))
elif __name__ == "__main__":
    try:
        query = json.loads(base64.b64decode(sys.argv[1], validate=True))
        # python3 - has consumed stdin by execution time; keep the source for sudo.
        if "--discover" in sys.argv[2:]:
            pid = agent_pid_for_panes(query["panes"], local_ssh_process_table(), query["agent"])
            user = pwd.getpwuid(Path(f"/proc/{pid}").stat().st_uid) if pid is not None else None
            print(json.dumps(dict(pid=pid, user=user.pw_name) if user else None))
        else:
            handler = settle_response if query.get("op") == "settle" else response
            print(handler(query, globals().get("READER_SOURCE"), "--owner" in sys.argv[2:]), end="")
    except (OSError, ValueError, KeyError, TypeError, IndexError):
        print("@mp\t0\thistory\terror\t-\t0\n" + END)

'''
exec(compile(READER_SOURCE, '<chat-history-reader>', 'exec'))
