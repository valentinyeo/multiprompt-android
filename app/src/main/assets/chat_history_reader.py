READER_SOURCE = r'''

import base64
import difflib
from datetime import datetime
import hashlib
import json
import mmap
import os
import pwd
import re
import subprocess
import sys
import time
from pathlib import Path

HOME = Path.home()
SESSION_ID = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
SGR = re.compile(r"\x1b\[([0-9;:]*)m")
ANSI = re.compile(r"\x1b(?:\[[0-9;:?]*[ -/]*[@-~]|[@-Z\\-_])")
TMUX_NAME = re.compile(r"[A-Za-z0-9_.:-]{1,80}\Z")
_COMMAND_NAME = re.compile(r"<command-name>\s*(.*?)\s*</command-name>", re.S)
_COMMAND_ARGS = re.compile(r"<command-args>(.*?)</command-args>", re.S)
_LOCAL_COMMAND = re.compile(r"<(local-command-stdout|local-command-stderr|local-command-caveat)>.*?</\1>", re.S)
SEARCH_ASCII_FOLDS = "ßİŉſǰẖẗẘẙẚẞKﬀﬁﬂﬃﬄﬅﬆ"
END = "@@MP_END@@"
PAGE_BYTES = 350000
SCAN_BYTES = 512 * 1024
TAIL_BYTES = 512 * 1024
QUESTION_SCAN_BYTES = 16 * TAIL_BYTES
STATUS_CACHE = {}
SETTLE_SUFFIX_MIN = 24
IGSH_345_STALE_JUMPS_TO_TAIL = True
IGSH_349_QUEUED_STAMP_FROM_QUEUE = True
IGSH_358_TAIL_STARTS_AT_QUESTION = True
IGSH_263_TOOL_STEPS = True
IGSH_268_CHAT_FIND = True
IGSH_270_DIFF_CARDS = True


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

    data = read_json(HOME / ".claude/sessions" / f"{pid}.json")
    sid = data.get("sessionId")
    cwd = data.get("cwd")
    return (sid if isinstance(sid, str) and SESSION_ID.fullmatch(sid) else None,
            cwd if isinstance(cwd, str) and cwd else None)

def open_codex_transcript(pid):
    matches = set()
    owners = [pid] + [child for child, (parent, argv) in process_table().items()
                      if parent == pid and argv and process_basename(argv[0]) == "codex"]
    for owner in owners:
        try:
            descriptors = list(Path(f"/proc/{owner}/fd").iterdir())
        except OSError:
            continue
        for fd in descriptors:
            try:
                path = Path(os.readlink(fd))
                if (path.suffix != ".jsonl" or not path.name.startswith("rollout-") or
                        (HOME / ".codex/sessions").resolve() not in path.resolve().parents):
                    continue
                with path.open(encoding="utf-8") as source:
                    meta = json.loads(source.readline())
                sid = meta.get("payload", {}).get("id")
                if isinstance(sid, str) and SESSION_ID.fullmatch(sid):
                    matches.add((sid, path))
            except (OSError, ValueError, AttributeError):
                pass
    return next(iter(matches)) if len(matches) == 1 else None

def resolve_tmux_agent(agent, name, pid=None):

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
        return None
    return open_codex_transcript(pid)


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
    row = dict(role=role, timestamp=timestamp if isinstance(timestamp, str) else "",
               text=text, id=identity if isinstance(identity, str) else "")
    if agent == "claude" and isinstance(attachment, dict) and attachment.get("type") == "queued_command":
        row["queued"] = True
    return row



def tool_clean(value):
    if isinstance(value, str):
        return re.sub(r"(?:data:image/[^;]+;base64,)?[A-Za-z0-9+/]{256,}={0,2}", "[image omitted]", value)
    if isinstance(value, list):
        return [tool_clean(part) for part in value]
    if isinstance(value, dict):
        if value.get("type") in ("image", "input_image", "output_image", "base64"):
            return "[image omitted]"
        return {k: tool_clean(v) for k, v in value.items() if k != "base64"}
    return value


def tool_text(value):
    value = tool_clean(value)
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(tool_text(part) for part in value)
    if isinstance(value, dict) and isinstance(value.get("text"), str):
        return value["text"]
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")) if value is not None else ""


def tool_cap(text, limit):
    return text if len(text) <= limit else text[:limit - 1] + "…"


def tool_summary(name, value):
    args = value
    if isinstance(args, str):
        try:
            args = json.loads(args)
        except ValueError:
            args = {"input": args}
    if not isinstance(args, dict):
        args = {}
    kind = name.rsplit(".", 1)[-1].lower()
    path = args.get("file_path") or args.get("path") or "file"
    if kind == "apply_patch":
        match = re.search(r"\*\*\* (Update|Add|Delete) File: ([^\n]+)", str(args.get("input", "")))
        if match:
            path = match.group(2)
            kind = "write" if match.group(1) == "Add" else "edit"
    if kind in ("edit", "multiedit", "apply_patch", "write", "read"):
        summary = ("Wrote " if kind == "write" else "Read " if kind == "read" else "Edited ") + process_basename(path)
    elif kind in ("bash", "shell", "exec_command", "local_shell"):
        command = args.get("command") or args.get("cmd") or "command"
        if isinstance(command, list):
            command = " ".join(str(part) for part in command)
        summary = "Ran " + str(args.get("description") or str(command)[:60])
    elif kind in ("grep", "glob"):
        summary = "Searched " + str(args.get("pattern", "files"))
    elif kind == "webfetch":
        summary = "Fetched " + str(args.get("url", "")).split("://")[-1].split("/")[0]
    elif kind == "websearch":
        summary = "Searched web " + str(args.get("query", ""))
    elif kind in ("task", "agent"):
        summary = "Agent: " + str(args.get("description", "task"))
    else:
        summary = name
    return tool_cap(" ".join(tool_text(summary).split()), 120)


def tool_failure_metadata(value):
    flags = []
    if isinstance(value, dict):
        for key, failure in (("is_error", True), ("success", False)):
            if isinstance(value.get(key), bool):
                flags.append(value[key] is failure)
        if value.get("error"):
            flags.append(True)
        if value.get("status") in ("error", "failed", "failure", "ok", "success", "succeeded", "completed"):
            flags.append(value["status"] in ("error", "failed", "failure"))
        if value.get("exit_code") not in (None, ""):
            flags.append(str(value["exit_code"]) != "0")
        flags.extend(tool_failure_metadata(v) for v in value.values())
    elif isinstance(value, list):
        flags.extend(tool_failure_metadata(v) for v in value)
    elif isinstance(value, str):
        try:
            decoded = json.loads(value)
        except ValueError:
            decoded = None
        if isinstance(decoded, (dict, list)):
            return tool_failure_metadata(decoded)
    known = [flag for flag in flags if flag is not None]
    return any(known) if known else None


def tool_failed(value):
    metadata = tool_failure_metadata(value)
    if metadata is not None:
        return metadata
    if isinstance(value, dict):
        return any(tool_failed(v) for v in value.values())
    if isinstance(value, list):
        return any(tool_failed(v) for v in value)
    if isinstance(value, str):
        try:
            decoded = json.loads(value)
        except ValueError:
            decoded = None
        if isinstance(decoded, (dict, list)) and tool_failed(decoded):
            return True
        if re.search(r"(?:^|\n)(?:Error:|Failed to |apply_patch verification failed:)", value, re.I):
            return True
        return any(int(code) != 0 for code in re.findall(r"(?:Exit code:\s*|Process exited with code\s+)(-?\d+)", value, re.I))
    return False


def tool_diff(row, lines):
    if not lines:
        return
    row["diff"] = "\n".join(lines[:40] + ([f" … {len(lines) - 40} more lines"] if len(lines) > 40 else []))
    row["added"] = sum(line.startswith("+") for line in lines)
    row["removed"] = sum(line.startswith("-") for line in lines)
    if row["text"]:
        row["text"] = re.sub(r" \+\d+ -\d+$", "", row["text"]) + f" +{row['added']} -{row['removed']}"


def tool_input_diff(name, value):
    kind = name.rsplit(".", 1)[-1].lower()
    if kind == "apply_patch":
        if isinstance(value, dict):
            value = value.get("input", "")
        if isinstance(value, str):
            try:
                value = json.loads(value).get("input", value)
            except (ValueError, AttributeError):
                pass
        return [line for line in str(value).splitlines() if line.startswith(("+", "-", " "))]
    if kind not in ("edit", "multiedit", "write") or not isinstance(value, dict):
        return []
    if kind == "write":
        return ["+" + line for line in value.get("content", "").splitlines()]
    lines = []
    for edit in value.get("edits", []) if kind == "multiedit" else [value]:
        if not isinstance(edit, dict):
            continue
        patch = list(difflib.unified_diff(edit.get("old_string", "").splitlines(),
                                         edit.get("new_string", "").splitlines(), n=1, lineterm=""))
        lines.extend(line for line in patch[2:] if line.startswith(("+", "-", " ")))
    return lines


def tool_result_diff(event):
    result = event.get("toolUseResult")
    if not isinstance(result, dict):
        return []
    lines = [line for hunk in result.get("structuredPatch") or [] if isinstance(hunk, dict)
             for line in hunk.get("lines") or [] if isinstance(line, str) and line.startswith(("+", "-", " "))]
    if not lines and result.get("type") == "create" and isinstance(result.get("content"), str):
        lines = ["+" + line for line in result["content"].splitlines()]
    return lines


def tool_events(event, agent, search=False):
    if not IGSH_263_TOOL_STEPS or not isinstance(event, dict):
        return []
    timestamp = event.get("timestamp", "")
    timestamp = timestamp if isinstance(timestamp, str) else ""
    if agent == "claude":
        if (event.get("type") not in ("user", "assistant") or any(event.get(key) for key in
                ("isMeta", "isSidechain", "isCompactSummary", "isVisibleInTranscriptOnly"))):
            return []
        parts = (event.get("message") or {}).get("content", [])
        parts = parts if isinstance(parts, list) else []
    else:
        parts = [event.get("payload")] if event.get("type") == "response_item" else []
    rows = []
    for part in parts:
        if not isinstance(part, dict):
            continue
        kind = part.get("type")
        call = kind in ("tool_use", "function_call", "custom_tool_call", "local_shell_call")
        result = kind in ("tool_result", "function_call_output", "custom_tool_call_output", "local_shell_call_output")
        identity = part.get("id") if agent == "claude" and call else part.get("tool_use_id") if agent == "claude" else part.get("call_id") or part.get("id")
        if not (call or result) or not isinstance(identity, str) or not identity:
            continue
        if call:
            name = part.get("name") or ("local_shell" if kind == "local_shell_call" else "Tool")
            value = part.get("input", part.get("arguments", part.get("action", {})))
            rows.append(dict(role="tool", id=identity, timestamp=timestamp, text=tool_summary(name, value),
                             status="running", detail=tool_cap(tool_text(value), 4000)))
            if IGSH_270_DIFF_CARDS:
                tool_diff(rows[-1], tool_input_diff(name, value))
        else:
            output = part.get("content") if agent == "claude" else part.get("output", "")
            failed = not search and (part.get("is_error") is True if agent == "claude" else tool_failed(part))
            rows.append(dict(role="tool_result", id=identity, timestamp=timestamp, text="",
                             status="error" if failed else "ok", detail=tool_cap(tool_text(output), 4000)))
            if IGSH_270_DIFF_CARDS and agent == "claude":
                tool_diff(rows[-1], tool_result_diff(event))
    return rows


def pair_tool_rows(rows):
    calls = {row["id"]: row for row in rows if row["role"] == "tool"}
    inputs = {identity: row["detail"] for identity, row in calls.items()}
    paired = []
    for row in rows:
        if row["role"] == "tool_result" and row["id"] in calls:
            call = calls[row["id"]]
            call["status"] = row["status"]
            if "offset" in row:
                call["result_offset"] = row["offset"]
            if "diff" in row:
                for key in ("diff", "added", "removed"):
                    call[key] = row[key]
                call["text"] = re.sub(r" \+\d+ -\d+$", "", call["text"]) + f" +{row['added']} -{row['removed']}"
            call["detail"] = tool_cap(tool_cap(inputs[row["id"]], 1800) + "\n\n" + row["detail"], 4000)
        else:
            paired.append(row)
    return paired


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


def read_window(source, end, budget=TAIL_BYTES, captured=None):

    start = max(0, end - budget)
    source.seek(start)
    data = source.read(end - start)
    if captured is not None:
        captured.append((start, data))
    if start:
        newline = data.find(b"\n")
        if newline < 0:
            return 0, 0, b""
        skip = newline + 1
        start += skip
        data = data[skip:]
    complete = data.rfind(b"\n") + 1
    return start, start + complete, data[:complete]


def wire_row(message):
    return json.dumps(message, ensure_ascii=True, separators=(",", ":"))


def load_line(raw):
    try:
        return json.loads(raw)
    except (ValueError, UnicodeError):
        return None


def asks_question(raw, agent):
    message = conversation_event(load_line(raw), agent)
    return message is not None and message.get("role") == "user"


def read_tail(source, size, agent, captured=None):
    base, end, data = read_window(source, size, captured=captured)
    if not IGSH_358_TAIL_STARTS_AT_QUESTION or not base or not data or any(asks_question(raw, agent) for raw in data.splitlines()):
        return base, end, data
    start, _, older = read_window(source, base, QUESTION_SCAN_BYTES - TAIL_BYTES, captured)
    shown = 0
    offset = base
    for raw in reversed(older.splitlines(keepends=True)):
        offset -= len(raw)
        event = load_line(raw)
        message = conversation_event(event, agent)
        if message is None:
            shown += sum(len(wire_row(row)) + 1 for row in tool_events(event, agent))
            if shown > TAIL_BYTES:
                break
            continue
        shown += sum(len(wire_row(row)) + 1 for row in [message, *tool_events(event, agent)])
        if shown > TAIL_BYTES:
            break
        if message.get("role") == "user":
            return offset, end, older[offset - start:] + data
    return base, end, data


def status_header(source, size, agent, captured=()):
    key = (str(Path(source.name).resolve()), os.fstat(source.fileno()).st_ino)
    previous = STATUS_CACHE.get(key)
    probe_bytes = 0
    if previous is not None:
        scanned_end = previous[0]
        if scanned_end > size:
            previous = None
        elif size - scanned_end <= QUESTION_SCAN_BYTES:
            probe_bytes = len(previous[5])
            source.seek(scanned_end - probe_bytes)
            if source.read(probe_bytes) != previous[5]:
                previous = None
    model = usage = todos = None
    window = 200000
    end = size
    lower = previous[0] if previous is not None else 0
    scanned_end = lower
    probe = previous[5] if previous is not None else b""
    remaining = min(QUESTION_SCAN_BYTES, size - lower)
    pending = b""
    first = True
    extra_budget = max(0, QUESTION_SCAN_BYTES - probe_bytes - sum(len(data) for _, data in captured))
    while end > lower and remaining and (model is None or usage is None or todos is None):
        count = min(TAIL_BYTES, end - lower, remaining)
        cached = next(((start, data) for start, data in captured if start < end <= start + len(data)), None)
        if cached is not None:
            start, data = cached
            count = min(count, end - start)
            chunk = data[end - count - start:end - start] + pending
        else:
            count = min(count, extra_budget)
            if not count:
                break
            extra_budget -= count
            source.seek(end - count)
            chunk = source.read(count) + pending
        end -= count
        remaining -= count
        parts = chunk.split(b"\n")
        if first:
            if len(parts) == 1:
                pending = chunk
                continue
            complete = chunk.rfind(b"\n") + 1
            scanned_end = end + complete
            probe = chunk[max(0, complete - 64):complete]
            parts.pop()
            first = False
        pending = parts.pop(0) if end > lower else b""
        for raw in reversed(parts):
            markers = (((b'"model"',) if model is None else ()) +
                       ((b'"usage"', b'token_count') if usage is None else ()) +
                       ((b'TodoWrite', b'update_plan') if todos is None else ()))
            if not any(marker in raw for marker in markers):
                continue
            try:
                event = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                continue
            if not isinstance(event, dict) or event.get("isSidechain"):
                continue
            if agent == "claude":
                if event.get("type") != "assistant":
                    continue
                message = event.get("message") or {}
                if not isinstance(message, dict):
                    continue
                if model is None and isinstance(message.get("model"), str):
                    model = message["model"]
                if usage is None and isinstance(message.get("usage"), dict):
                    values = message["usage"]
                    if any(key in values for key in ("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")):
                        usage = sum(value for key in ("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
                                    if isinstance(value := values.get(key), (int, float)) and value >= 0)
                        if re.search(r"\[1m\]|1m\s+context|context[^\n]{0,20}1m", raw.decode("utf-8", "replace"), re.I):
                            window = 1000000
                content = message.get("content") or []
                if todos is None and isinstance(content, list):
                    for item in reversed(content):
                        if isinstance(item, dict) and item.get("type") == "tool_use" and item.get("name") == "TodoWrite":
                            value = item.get("input") or {}
                            if isinstance(value, dict) and isinstance(value.get("todos"), list):
                                todos = value["todos"]
                                break
            else:
                payload = event.get("payload") or {}
                if not isinstance(payload, dict):
                    continue
                if model is None and event.get("type") == "turn_context" and isinstance(payload.get("model"), str):
                    model = payload["model"]
                if usage is None and event.get("type") == "event_msg" and payload.get("type") == "token_count":
                    info = payload.get("info") or {}
                    if isinstance(info, dict):
                        values = info.get("last_token_usage") or info.get("total_token_usage") or {}
                        if isinstance(values, dict):
                            used = values.get("input_tokens", values.get("total_tokens"))
                            capacity = info.get("model_context_window")
                            if isinstance(used, (int, float)) and isinstance(capacity, (int, float)) and capacity > 0:
                                usage, window = used, capacity
                if todos is None and payload.get("type") == "function_call" and payload.get("name") == "update_plan":
                    try:
                        value = json.loads(payload.get("arguments", ""))
                    except (ValueError, TypeError):
                        continue
                    if isinstance(value, dict) and isinstance(value.get("plan"), list):
                        todos = value["plan"]
            if model is not None and usage is not None and todos is not None:
                break
    if previous is not None:
        model = previous[1] if model is None else model
        if usage is None:
            usage, window = previous[2:4]
        todos = previous[4] if todos is None else todos
    STATUS_CACHE[key] = (scanned_end, model, usage, window, todos[:50] if todos is not None else None, probe)
    if len(STATUS_CACHE) > 256:
        del STATUS_CACHE[next(iter(STATUS_CACHE))]
    result = {"role": "status"}
    if model:
        display = model.removeprefix("claude-")
        match = re.match(r"(opus|sonnet|fable|haiku)-(\d+)-(\d+)", display)
        if match:
            display = f"{match[1].title()} {match[2]}.{match[3]}"
        result["model"] = display[:128]
        if "[1m]" in model.lower():
            window = 1000000
    if usage is not None:
        try:
            result["percent_left"] = max(0, min(100, round(100 - usage * 100 / window)))
        except (ValueError, OverflowError):
            pass
    if todos is not None:
        result["todos"] = [{"text": str(item.get("content" if agent == "claude" else "step", ""))[:200],
                            "status": item.get("status") if item.get("status") in ("pending", "in_progress", "completed") else "pending"}
                           for item in todos[:50] if isinstance(item, dict)]
    return result if len(result) > 1 else None


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


def search_positions(data, token, folds):
    positions = set()
    for source, patterns in ((data.lower(), (token,)), (data, (b"\\u", b"\x00", b"<local-command-", *folds))):
        for pattern in patterns:
            at = source.find(pattern)
            while at >= 0:
                positions.add(data.rfind(b"\n", 0, at) + 1)
                end = data.find(b"\n", at)
                if end < 0:
                    break
                at = source.find(pattern, end + 1)
    return positions


def search_response(q, path, agent, sid):
    query = q.get("search")
    if not isinstance(query, str) or not 1 <= len(query) <= 200:
        raise ValueError("invalid search")
    needle = query.casefold()
    started = time.monotonic()
    generated = {"wrote", "read", "edited", "ran", "command", "file", "searched", "files", "web", "fetched",
                 "agent", "task", "tool", "image", "omitted", "true", "false", "none", "e"}
    words = re.findall(r"[a-z0-9_]+", needle) if needle.isascii() and all(32 <= ord(char) < 127 and char not in '"\\/' for char in needle) else []
    candidates = sorted((word for word in set(words) - generated if set(word) - set("0123456789einfan")), reverse=True)
    tokens = []
    for word in candidates:
        folds = []
        for char in SEARCH_ASCII_FOLDS:
            folded = char.casefold()
            pieces = re.findall(r"[a-z]+", folded)
            if (folded.isascii() and (folded in word or word in folded
                    or any(word.startswith(folded[i:]) or word.endswith(folded[:i]) for i in range(1, len(folded))))
                    or not folded.isascii() and any(word.startswith(piece) or word.endswith(piece) for piece in pieces)):
                folds.append(char.encode("utf-8"))
        tokens.append((word.encode("ascii"), tuple(folds)))
    token, folds = min(tokens, key=lambda item: (len(item[1]), -len(item[0])), default=(b"", ()))
    identities = set()
    hits = []
    truncated = False
    calls = {}
    with path.open("rb") as transcript:
        info = os.fstat(transcript.fileno())
        positions = None
        if token:
            selected = set()
            while transcript.tell() < info.st_size:
                if time.monotonic() - started >= 1.5:
                    truncated = True
                    break
                base = transcript.tell()
                data = transcript.read(min(1024 * 1024, info.st_size - base))
                if not data:
                    break
                if not data.endswith(b"\n"):
                    data += transcript.readline()
                for position in search_positions(data, token, folds):
                    offset = base + position
                    end = data.find(b"\n", position)
                    if end < 0 or base + end + 1 > info.st_size:
                        continue
                    raw = data[position:end + 1]
                    if b"\\u" not in raw and b"\x00" not in raw and b"<local-command-" not in raw:
                        lowered = raw.lower()
                        missing = [(word, alternatives) for word, alternatives in tokens if word not in lowered]
                        if missing:
                            if raw.isascii():
                                continue
                            if any(not alternatives or not any(char in raw for char in alternatives) for _, alternatives in missing):
                                continue
                            try:
                                folded = raw.decode("utf-8").casefold()
                            except UnicodeError:
                                continue
                            if any(word not in folded for word in candidates):
                                continue
                    try:
                        event = json.loads(raw)
                    except (ValueError, UnicodeError):
                        continue
                    message = conversation_event(event, agent)
                    if message and needle in message["text"].casefold():
                        selected.add(offset)
                    for row in tool_events(event, agent, search=True):
                        if needle not in (row["text"] + "\n" + row["detail"]).casefold():
                            continue
                        selected.add(offset)
                        identity = row["id"]
                        if not identity.isascii() or any(ord(char) < 32 or char in '"\\/' for char in identity):
                            token = b""
                            break
                        identities.add(identity.encode("ascii"))
                    if not token:
                        break
                if not token:
                    break
            if token:
                if identities and info.st_size:
                    with mmap.mmap(transcript.fileno(), info.st_size, access=mmap.ACCESS_READ) as mapped:
                        for identity in identities:
                            if time.monotonic() - started >= 1.5:
                                truncated = True
                                break
                            at = mapped.find(identity)
                            while at >= 0:
                                if time.monotonic() - started >= 1.5:
                                    truncated = True
                                    break
                                selected.add(mapped.rfind(b"\n", 0, at) + 1)
                                at = mapped.find(identity, at + len(identity))
                positions = iter(sorted(selected))
            transcript.seek(0)
        while not truncated:
            if time.monotonic() - started >= 1.5:
                truncated = True
                break
            if positions is not None:
                position = next(positions, None)
                if position is None:
                    break
                transcript.seek(position)
            offset = transcript.tell()
            raw = transcript.readline()
            if not raw:
                break
            end = offset + len(raw)
            if end > info.st_size or not raw.endswith(b"\n"):
                break
            try:
                event = json.loads(raw)
            except (ValueError, UnicodeError):
                offset = end
                continue
            message = conversation_event(event, agent)
            rows = ([message] if message else []) + tool_events(event, agent, search=True)
            for row in rows:
                text = row.get("text", "")
                if row["role"] == "tool":
                    calls[row["id"]] = dict(size=len(row["detail"]), prefix=len(text) + 1, hit=None, result=None, match_end=0)
                    text += "\n" + row["detail"]
                elif row["role"] == "tool_result":
                    if row["id"] not in calls:
                        continue
                    call = calls[row["id"]]
                    if call["result"] is not None:
                        hits.remove(call["result"])
                        call["result"] = None
                    visible = call["prefix"] + (1799 if call["size"] > 1800 else call["size"])
                    if call["hit"] is not None and call["match_end"] > visible:
                        hits.remove(call["hit"])
                        call["hit"] = None
                    text = tool_cap(row["detail"], 4000 - min(call["size"], 1800) - 2)
                folded = text.casefold()
                if time.monotonic() - started >= 1.5:
                    truncated = True
                    break
                match = folded.find(needle)
                if match < 0:
                    continue
                at = 0
                for i, char in enumerate(text):
                    at += len(char.casefold())
                    if at > match:
                        match = i
                        break
                start = max(0, match - 60)
                hit = dict(offset=offset, end=end, role="tool" if row["role"].startswith("tool") else row["role"],
                           timestamp=row["timestamp"], snippet=text[start:start + 160])
                hits.append(hit)
                if row["role"] == "tool":
                    calls[row["id"]].update(hit=hit, match_end=match + len(query))
                elif row["role"] == "tool_result":
                    calls[row["id"]]["result"] = hit
                if len(hits) == 500:
                    truncated = True
                    break
            if truncated:
                break
            offset = end
    header = f"@mp\t{q['id']}\tsearch\tok\t{sid}\t{info.st_ino}\t{info.st_size}\t{len(hits)}\t{int(truncated)}\t{agent}\t{q['tmux'] or '-'}"
    return "\n".join((header, *(wire_row(row) for row in hits), END, ""))


def response(q, source=None, as_owner=False):
    if IGSH_268_CHAT_FIND and "search" in q:
        query = q["search"]
        if not isinstance(query, str) or not 1 <= len(query) <= 200:
            raise ValueError("invalid search")
    agent, sid, path, delegated = resolve_response_path(q, source, as_owner)
    if delegated is not None:
        return delegated
    if IGSH_268_CHAT_FIND and "search" in q:
        return search_response(q, path, agent, sid)
    rid, tmux = q["id"], q["tmux"]
    if agent == "claude" and tmux:
        drain_held_queue(tmux, sid, path)
    rows, budget = [], 0
    queue_stamps = {}
    with path.open("rb") as source:
        info = os.fstat(source.fileno())
        captured = []
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
            base, end, data = read_tail(source, info.st_size, agent, captured)
            oldest = base
        else:
            mode = "delta"
            if IGSH_345_STALE_JUMPS_TO_TAIL and not q.get("no_jump") and info.st_size - offset > 2 * SCAN_BYTES:
                base, end, data = read_tail(source, info.st_size, agent, captured)
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
                    base, end, data = read_tail(source, info.st_size, agent, captured)
                    oldest = base
                    scan_more = False
                else:
                    data = data[:end - base]
        page_more = False
        groups = []
        consumed = base
        for raw in data.splitlines(keepends=True):
            event = load_line(raw)
            message = conversation_event(event, agent)
            if IGSH_349_QUEUED_STAMP_FROM_QUEUE and agent == "claude" and isinstance(event, dict):
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
            messages = ([message] if message else []) + tool_events(event, agent)
            if q.get("source_offsets"):
                for row in messages:
                    row.update(offset=consumed, end=consumed + len(raw))
            cost = sum(len(wire_row(row)) + 1 for row in messages)
            if mode == "delta" and rows and budget + cost > PAGE_BYTES:
                end = consumed
                page_more = True
                break
            if messages:
                groups.append((consumed, len(rows), cost))
            rows.extend(messages)
            budget += cost
            consumed += len(raw)
        if mode != "delta" and budget > TAIL_BYTES and len(groups) > 1 and any(row["role"] in ("tool", "tool_result") for row in rows):
            first = 0
            while budget > TAIL_BYTES and first + 1 < len(groups):
                budget -= groups[first][2]
                first += 1
            base, row_start, _cost = groups[first]
            oldest = base
            rows = rows[row_start:]
        if jumped and not rows:
            return response(dict(q, no_jump=True), source, as_owner)
        status = status_header(source, info.st_size, agent, captured)
        if q.get("held") is True and agent == "claude" and tmux and held_queue_path(tmux).exists():
            from contextlib import closing
            with closing(held_queue_db(tmux)) as db:
                pending = db.execute("SELECT key,text,queued_at,state,origin,source_inode FROM messages WHERE sid=? AND state IN ('queued','submitted') ORDER BY created", (sid,)).fetchall()
            status = dict(status or dict(role="status"), held=[dict(id=item[0], text=item[1], queued_at=item[2], state=item[3], offset=item[4], inode=item[5], sid=sid) for item in pending])
        if q.get("delivery_ids") and agent == "claude" and tmux:
            ids = q["delivery_ids"]
            if not isinstance(ids, list) or any(not isinstance(token, str) or re.fullmatch(r"[A-Za-z0-9_-]{1,80}", token) is None for token in ids):
                raise ValueError("invalid message identities")
            from contextlib import closing
            with closing(held_queue_db(tmux)) as db:
                deliveries = []
                for token in ids:
                    held = db.execute("SELECT state FROM messages WHERE key=? AND sid=?", (token, sid)).fetchone()
                    if held:
                        deliveries.append(dict(id=token, state=held[0]))
            status = dict(status or dict(role="status"), deliveries=deliveries)
        more = scan_more or page_more
        source.seek(max(0, end - 64))
        probe = hashlib.sha256(source.read(min(end, 64))).hexdigest()
    header = f"@mp\t{rid}\thistory\tok\t{sid}\t{base}\t{end}\t{info.st_ino}\t{int(more)}\t{agent}\t{tmux or '-'}\t{info.st_mtime_ns}\t{probe}\t{mode}\t{oldest}"
    return "\n".join((header, *(wire_row(row) for row in pair_tool_rows(rows)), *((wire_row(status),) if status else ()), END, ""))


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


def stop_response(q):
    rid = q.get("id", 0)
    name = q.get("tmux")
    op = q.get("op", "stop")
    digit = q.get("digit")
    key = chr(digit) if op == "permission" and type(digit) is int and 49 <= digit <= 57 else "Escape"
    reason = None
    if op == "permission" and key == "Escape":
        reason = "invalid_permission_digit"
    elif not isinstance(name, str) or re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", name) is None:
        reason = "invalid_tmux_name"
    else:
        try:
            subprocess.run(["tmux", "send-keys", "-t", "=" + name + ":", key], check=True, capture_output=True, timeout=5)
        except (OSError, subprocess.SubprocessError):
            reason = "tmux_stop_failed"
    status = "error" if reason else "ok"
    return f"@mp\t{rid}\t{op}\t{status}\t{reason or '-'}\t0\n{END}\n"


def grey_code(params):
    values = [int(part) if part.isdigit() else 0 for part in params.replace(":", ";").split(";")] if params else [0]
    index = 0
    dim = inverse = None
    grey = None
    while index < len(values):
        value = values[index]
        if value == 0:
            dim, inverse, grey = False, False, False
        elif value == 2:
            dim = True
        elif value == 22:
            dim = False
        elif value == 7:
            inverse = True
        elif value == 27:
            inverse = False
        elif value == 39 or 30 <= value <= 37 or 91 <= value <= 97:
            grey = False
        elif value == 90:
            grey = True
        elif value == 38 and index + 2 < len(values) and values[index + 1] == 5:
            grey = values[index + 2] == 8 or 238 <= values[index + 2] <= 250
            index += 2
        elif value == 38 and index + 4 < len(values) and values[index + 1] == 2:
            red, green, blue = values[index + 2:index + 5]
            grey = max(red, green, blue) - min(red, green, blue) <= 8 and 60 <= red <= 190
            index += 4
        index += 1
    return dim, inverse, grey


def styled_chars(line):
    dim = inverse = grey = False
    result = []
    position = 0
    for match in ANSI.finditer(line):
        for char in line[position:match.start()]:
            result.append((char, dim or grey, inverse))
        position = match.end()
        sgr = SGR.fullmatch(match.group(0))
        if sgr:
            new_dim, new_inverse, new_grey = grey_code(sgr.group(1))
            dim = dim if new_dim is None else new_dim
            inverse = inverse if new_inverse is None else new_inverse
            grey = grey if new_grey is None else new_grey
    for char in line[position:]:
        result.append((char, dim or grey, inverse))
    return result


def box_draft_text(shown):
    lines = [styled_chars(line) for line in shown.splitlines()]
    plain = ["".join(char for char, _dim, _inverse in line) for line in lines]
    starts = [i for i, line in enumerate(plain) if line.startswith("❯")]
    if not starts:
        return None
    rows = []
    for number, i in enumerate(range(starts[-1], len(lines))):
        if plain[i].startswith("─"):
            break
        rows.append(lines[i][1:] if number == 0 else lines[i])
    kept = []
    dimmed = False
    for number, row in enumerate(rows):
        if number == 0 and row and row[0][0] == " ":
            row = row[1:]
        elif number:
            lead = 0
            while lead < min(2, len(row)) and row[lead][0] == " ":
                lead += 1
            row = row[lead:]
        dimmed = dimmed or any(dim for _char, dim, _inverse in row)
        kept.append([item for item in row if not item[1]])
    visible = [item for row in kept for item in row]
    if dimmed and len("".join(char for char, _dim, _inverse in visible).strip()) <= 1 and all(inverse for _char, _dim, inverse in visible):
        return ""
    return "\n".join("".join(char for char, _dim, _inverse in row).rstrip() for row in kept).rstrip()




QUEUE_SHOWN = 8


def held_queue_path(tmux):
    if not isinstance(tmux, str) or not TMUX_NAME.fullmatch(tmux):
        raise ValueError("invalid tmux name")
    return HOME / ".local/state/multiprompt/held-messages" / (hashlib.sha256(tmux.encode()).hexdigest() + ".sqlite3")


def held_queue_db(tmux):
    import sqlite3
    path = held_queue_path(tmux)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    db = sqlite3.connect(path)
    os.chmod(path, 0o600)
    db.execute("CREATE TABLE IF NOT EXISTS messages (key TEXT PRIMARY KEY, sid TEXT NOT NULL, text TEXT NOT NULL, queued_at INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('queued','submitted','delivered','cancelled')), scan INTEGER NOT NULL DEFAULT 0, origin INTEGER NOT NULL, source_inode INTEGER NOT NULL, receipt TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)")
    db.execute("CREATE INDEX IF NOT EXISTS pending ON messages(sid,state,created)")
    return db


def held_message_key(q):
    text, stamp, token = q.get("text"), q.get("queued_at"), q.get("message_id")
    if (not isinstance(text, str) or not text.strip() or "\x00" in text or type(stamp) is not int or stamp <= 0 or
            not isinstance(token, str) or re.fullmatch(r"[A-Za-z0-9_-]{1,80}", token) is None):
        raise ValueError("invalid held message")
    return token


def enqueue_response(q):
    from contextlib import closing
    key = held_message_key(q)
    name = q["tmux"]
    resolved = resolve_tmux_agent("claude", name)
    if not resolved:
        return f"@mp\t{q['id']}\tsend\terror\tagent_unavailable\t0\n{END}\n"
    sid, transcript = resolved
    info = transcript.stat()
    with closing(held_queue_db(name)) as db, db:
        db.execute("INSERT OR IGNORE INTO messages(key,sid,text,queued_at,state,created,origin,source_inode) VALUES (?,?,?,?,'queued',?,?,?)",
                   (key, sid, q["text"], q["queued_at"], time.time_ns(), info.st_size, info.st_ino))
    drain_held_queue(name, sid, transcript)
    with closing(held_queue_db(name)) as db:
        identity = db.execute("SELECT sid,source_inode,origin,state,receipt FROM messages WHERE key=?", (key,)).fetchone()
    receipt = wire_row(dict(role="delivery", message_id=key, sid=identity[0], inode=identity[1], offset=identity[2], state=identity[3], detail=identity[4]))
    return f"@mp\t{q['id']}\tsend\tok\t-\t0\n{receipt}\n{END}\n"


def cancel_queued_response(q):
    from contextlib import closing
    key = held_message_key(q)
    reason = "not_queued"
    with closing(held_queue_db(q["tmux"])) as db, db:
        changed = db.execute("UPDATE messages SET state='cancelled' WHERE key=? AND state='queued'", (key,)).rowcount
        if changed or db.execute("SELECT 1 FROM messages WHERE key=? AND state='cancelled'", (key,)).fetchone():
            reason = None
    return f"@mp\t{q['id']}\tcancel_queued\t{'error' if reason else 'ok'}\t{reason or '-'}\t0\n{END}\n"


def held_queue_ready(tmux, path):
    shown = subprocess.run(["tmux", "capture-pane", "-p", "-e", "-J", "-t", "=" + tmux + ":"],
                           capture_output=True, text=True, timeout=5, check=True).stdout
    plain = ANSI.sub("", shown)
    draft = box_draft_text(shown)
    if draft is None or draft.strip() or "❯" not in plain:
        return False
    if any(hint in plain.lower() for hint in ("esc to interrupt", "ctrl+c to interrupt", "edit queued messages",
                                               "select a queued message", "enter to select", "esc to cancel")):
        return False
    with path.open("rb") as source:
        size = os.fstat(source.fileno()).st_size
        start = max(0, size - TAIL_BYTES)
        source.seek(start)
        if start:
            source.readline()
        rows = source.read().splitlines()
    for raw in reversed(rows):
        event = load_line(raw)
        if not isinstance(event, dict) or event.get("isSidechain"):
            continue
        if event.get("type") == "system" and event.get("subtype") == "turn_duration":
            return True
        if event.get("type") == "user":
            return False
        if event.get("type") == "assistant":
            return (event.get("message") or {}).get("stop_reason") == "end_turn"
    return not rows


def drain_held_queue(tmux, sid, transcript):
    from contextlib import closing
    if not held_queue_path(tmux).exists():
        return
    with closing(held_queue_db(tmux)) as db:
        submitted = db.execute("SELECT key,text,scan FROM messages WHERE sid=? AND state='submitted' ORDER BY created", (sid,)).fetchall()
        for key, text, scanned in submitted:
            with transcript.open("rb") as source:
                source.seek(scanned)
                data = source.read(TAIL_BYTES)
            complete = data.rfind(b"\n") + 1
            consumed, receipt = scanned, None
            for raw in data[:complete].splitlines(keepends=True):
                event = load_line(raw)
                consumed += len(raw)
                if isinstance(event, dict) and event.get("type") in ("user", "attachment") and not event.get("isSidechain"):
                    row = conversation_event(event, "claude")
                    if row and row["role"] == "user" and row["text"].replace("\r", "") == text.replace("\r", ""):
                        receipt = event.get("timestamp", "")
                        break
            with db:
                db.execute("UPDATE messages SET scan=?,state=?,receipt=? WHERE key=? AND state='submitted'",
                           (consumed, "delivered" if receipt is not None else "submitted", receipt or "", key))
            if receipt is None:
                return
        if not db.execute("SELECT 1 FROM messages WHERE sid=? AND state='queued' LIMIT 1", (sid,)).fetchone():
            return
        if not held_queue_ready(tmux, transcript):
            return
        current = resolve_tmux_agent("claude", tmux)
        if current is None or current[0] != sid or Path(current[1]) != Path(transcript):
            return
        with db:
            db.execute("BEGIN IMMEDIATE")
            if db.execute("SELECT 1 FROM messages WHERE sid=? AND state='submitted' LIMIT 1", (sid,)).fetchone():
                return
            pending = db.execute("SELECT key,text FROM messages WHERE sid=? AND state='queued' ORDER BY created LIMIT 1", (sid,)).fetchone()
            if pending is None:
                return
            key, text = pending
            db.execute("UPDATE messages SET state='submitted',scan=? WHERE key=? AND state='queued'", (transcript.stat().st_size, key))
        try:
            result = send_response(dict(id=0, tmux=tmux, text=text))
            failure = "Terminal handoff failed; receipt not confirmed" if "\tsend\terror\t" in result else ""
        except (OSError, subprocess.SubprocessError, UnicodeError):
            failure = "Terminal handoff interrupted; receipt not confirmed"
        if failure:
            with db:
                db.execute("UPDATE messages SET receipt=? WHERE key=? AND state='submitted'", (failure, key))


def held_queue_fields(tmux, sid):
    from contextlib import closing
    if not held_queue_path(tmux).exists():
        return dict(queue=[], sent=[])
    with closing(held_queue_db(tmux)) as db:
        queued = db.execute("SELECT text FROM messages WHERE sid=? AND state='queued' ORDER BY created LIMIT ?", (sid, QUEUE_SHOWN)).fetchall()
        sent = db.execute("SELECT text FROM messages WHERE sid=? AND state='delivered' ORDER BY created DESC LIMIT ?", (sid, QUEUE_SHOWN)).fetchall()
    return dict(queue=[row[0] for row in queued], sent=[row[0] for row in reversed(sent)])


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
                wire = enqueue_response(query) if "message_id" in query else send_response(query)
            elif query.get("op") == "cancel_queued":
                wire = cancel_queued_response(query)
            elif query.get("op") in ("stop", "permission"):
                wire = stop_response(query)
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
