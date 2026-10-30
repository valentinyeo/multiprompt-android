import base64
import hashlib
import json
import os
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
SCAN_BYTES = 8 * 1024 * 1024


def read_json(path):
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}

def conversation_path(agent, sid, cwd):
    if not SESSION_ID.fullmatch(sid):
        return None
    root = HOME / (".claude/projects" if agent == "claude" else ".codex/sessions")
    candidates = []
    if agent == "claude":
        folder = re.sub(r"[^A-Za-z0-9]", "-", str(cwd))
        candidates.append(root / folder / (sid + ".jsonl"))
        candidates.extend(root.glob("*/" + sid + ".jsonl"))
    else:
        candidates.extend(root.glob("**/rollout-*-" + sid + ".jsonl"))
    for path in candidates:
        try:
            if path.is_file() and path.resolve().is_relative_to(root.resolve()):
                return path
        except OSError:
            pass
    return None

def clean_command_markup(text):
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


def response(q):
    rid, agent = q["id"], "codex" if q["codex"] else "claude"
    sid, tmux, cwd = q["sid"], q["tmux"], q["cwd"]
    if (not isinstance(rid, int) or rid < 0 or
            sid and not SESSION_ID.fullmatch(sid) or
            tmux and not TMUX_NAME.fullmatch(tmux) or not sid and not tmux or
            cwd and not Path(cwd).is_absolute()):
        raise ValueError("invalid identity")
    path = conversation_path(agent, sid, cwd) if sid else None
    if path is None and tmux:
        resolved = resolve_tmux_agent(agent, tmux)
        sid, path = resolved if resolved else ("", None)
    if path is None:
        return f"@mp\t{rid}\thistory\tmissing\t-\t0\n{END}\n"
    rows, budget = [], 0
    with path.open("rb") as source:
        info = os.fstat(source.fileno())
        offset = q["offset"]
        base = offset if (q["cached_sid"] == sid and q["inode"] == info.st_ino and 0 <= offset <= info.st_size) else 0
        if base and "probe" in q:
            source.seek(max(0, base - 64))
            boundary = hashlib.sha256(source.read(min(base, 64))).hexdigest()
            if boundary != q["probe"] or (info.st_size == base and info.st_mtime_ns != q.get("mtime")):
                base = 0
        source.seek(base)
        end, more = base, False
        while end < info.st_size:
            raw = source.readline()
            if not raw.endswith(b"\n"):
                break
            event = json.loads(raw)
            message = conversation_event(event, agent)
            if message:
                row = json.dumps(message, ensure_ascii=True, separators=(",", ":"))
                if rows and budget + len(row) + 1 > PAGE_BYTES:
                    more = True
                    break
                rows.append(row)
                budget += len(row) + 1
            end = source.tell()
            if budget >= PAGE_BYTES or end - base >= SCAN_BYTES:
                more = end < info.st_size
                break
        source.seek(max(0, end - 64))
        probe = hashlib.sha256(source.read(min(end, 64))).hexdigest()
    header = f"@mp\t{rid}\thistory\tok\t{sid}\t{base}\t{end}\t{info.st_ino}\t{int(more)}\t{agent}\t{tmux or '-'}\t{info.st_mtime_ns}\t{probe}"
    return "\n".join((header, *rows, END, ""))


if __name__ == "__main__":
    try:
        query = json.loads(base64.b64decode(sys.argv[1], validate=True))
        print(response(query), end="")
    except (OSError, ValueError, KeyError, TypeError, IndexError):
        print("@mp\t0\thistory\terror\t-\t0\n" + END)
