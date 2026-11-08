READER_SOURCE = r'''

import base64
import difflib
from contextlib import contextmanager
from datetime import datetime
import hashlib
import html
import json
import mmap
import os
try:
    import pwd
except ImportError:
    pwd = None
import re
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
from pathlib import Path

HOME = Path.home()
SESSION_ID = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
TMUX_NAME = re.compile(r"[A-Za-z0-9_.:-]{1,80}\Z")
_COMMAND_NAME = re.compile(r"<command-name>\s*(.*?)\s*</command-name>", re.S)
_COMMAND_ARGS = re.compile(r"<command-args>(.*?)</command-args>", re.S)
_INTERRUPT_MARKERS = ("[Request interrupted by user]", "[Request interrupted by user for tool use]")
_LOCAL_COMMAND = re.compile(r"<(local-command-stdout|local-command-stderr|local-command-caveat)>.*?</\1>", re.S)
_SYSTEM_REMINDER = re.compile(r"^[ \t]*<system-reminder>.*?</system-reminder>[ \t]*(?:\r?\n|$)", re.S | re.M)
SEARCH_ASCII_FOLDS = "ßİŉſǰẖẗẘẙẚẞKﬀﬁﬂﬃﬄﬅﬆ"
END = "@@MP_END@@"
PAGE_BYTES = 350000
SCAN_BYTES = 512 * 1024
TAIL_BYTES = 512 * 1024
QUESTION_SCAN_BYTES = 16 * TAIL_BYTES
STATUS_CACHE = {}
QUEUE_CACHE = {}
TAIL_CACHE = {}
QUEUE_SCAN_BYTES = 4 * 1024 * 1024
QUEUE_SHOWN = 8
QUEUE_TEXT = 500
_TRACKED_ID = re.compile(r"\n\[mp:([0-9a-f]{16})\]\s*\Z")
DRAFT_TEXT = 4000
SGR = re.compile(r"\x1b\[([0-9;:]*)m")
ANSI = re.compile(r"\x1b(?:\[[0-9;:?]*[ -/]*[@-~]|[@-Z\\-_])")
SETTLE_SUFFIX_MIN = 24
SETTLE_SIBLINGS_MAX = 4
ISSUE_345_STALE_JUMPS_TO_TAIL = True
ISSUE_349_QUEUED_STAMP_FROM_QUEUE = True
ISSUE_358_TAIL_STARTS_AT_QUESTION = True
ISSUE_631_TAIL_FILLS_SHOWN_BUDGET = True
ISSUE_263_TOOL_STEPS = True
ISSUE_268_CHAT_FIND = True
ISSUE_270_DIFF_CARDS = True


def read_json(path):
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}

def claude_config_dirs(home):
    configured = os.environ.get("CLAUDE_CONFIG_DIR") if home == HOME else None
    return ([Path(configured).expanduser()] if configured else []) + [home / ".claude", home / ".config/claude"]

def conversation_owner(cwd, include_home=False):
    if pwd is None:
        return None
    parts = Path(cwd).parts
    if (len(parts) < (3 if include_home else 4) or parts[:2] != ("/", "home") or ".." in parts or
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
    if agent == "pi":
        configured = os.environ.get("PI_CODING_AGENT_SESSION_DIR") if home == HOME else None
        root = Path(configured).expanduser() if configured else home / ".pi/agent/sessions"
        if configured and not root.is_absolute():
            root = Path(cwd) / root
        folder = root if configured else root / ("--" + re.sub(r"[/\\\\:]", "-", cwd.lstrip("/\\\\")) + "--")
        for path in folder.glob("*_" + sid + ".jsonl"):
            if path.resolve().is_relative_to(root.resolve()) and path.is_file():
                with path.open("r", encoding="utf-8") as source:
                    try:
                        header = json.loads(source.readline())
                    except (ValueError, UnicodeError):
                        continue
                if isinstance(header, dict) and header.get("type") == "session" and header.get("id") == sid and header.get("cwd") == cwd:
                    return path
        return None
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
                    if path.is_file() and transcript_inside(path, root):
                        with path.open("rb"):
                            return path
                except PermissionError as error:
                    denied = error
                except OSError:
                    pass
    state_home = Path(os.environ.get("XDG_STATE_HOME", str(home / ".local/state"))) if home == HOME else home / ".local/state"
    hax_root = state_home / "hax/sessions"
    try:
        for path in hax_root.glob("*/*_" + sid + ".jsonl"):
            if path.resolve().is_relative_to(hax_root.resolve()):
                header = hax_session_header(path)
                if header and header["id"] == sid:
                    return path
    except PermissionError as error:
        denied = error
    if denied is not None:
        raise denied
    return None

def transcript_inside(path, root):
    if path.resolve().is_relative_to(root.resolve()):
        return True
    target = path.resolve()
    return (path.is_symlink() and path.parent.resolve().is_relative_to(root.resolve()) and
            target.name == path.name and target.is_file() and target.stat().st_uid == os.getuid())

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

def visible_prompt(text):
    return _TRACKED_ID.sub("", text) if isinstance(text, str) else text

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
    matches = []
    for pane in pane_pids:
        queue = [pane]
        seen = set()
        while queue:
            pid = queue.pop(0)
            if pid in seen:
                continue
            seen.add(pid)
            argv = table.get(pid, (0, []))[1]
            if argv and (process_basename(argv[0]) in (agent, "hax") or
                         process_basename(argv[0]) in ("node", "bun") and len(argv) > 1 and
                         process_basename(argv[1]) in (agent, agent + ".js")):
                if agent != "codex" or process_basename(argv[0]) == "hax":
                    return pid
                matches.append(pid)
                continue
            if argv and process_basename(argv[0]) == "tmux" and pwd is not None:
                try:
                    user = pwd.getpwuid(Path(f"/proc/{pid}").stat().st_uid)
                    if user.pw_uid != 0:
                        clients = subprocess.run(["sudo", "-n", "-H", "-u", user.pw_name, "--", "tmux", "list-clients", "-F", "#{client_pid}\t#{pane_pid}"],
                                                 capture_output=True, text=True, timeout=3, check=False)
                        if clients.returncode == 0:
                            for line in clients.stdout.splitlines():
                                fields = line.split()
                                if len(fields) == 2 and fields[0] == str(pid) and fields[1].isdigit():
                                    queue.append(int(fields[1]))
                except (OSError, KeyError, subprocess.SubprocessError):
                    pass
            queue.extend(children.get(pid, []))
    return matches[0] if len(matches) == 1 else None

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

def process_table(root=Path("/proc"), pids=None):

    table = {}
    try:
        entries = list(root.iterdir()) if pids is None else [root / str(pid) for pid in pids]
    except OSError:
        return table
    seen = set()
    for entry in entries:
        if not entry.name.isdigit() or entry.name in seen:
            continue
        seen.add(entry.name)
        if pids is not None:
            try:
                tasks = list((entry / "task").iterdir())
            except OSError:
                tasks = []
            for task in tasks:
                try:
                    children = (task / "children").read_text().split()
                    entries.extend(root / child for child in children if child.isdigit())
                except OSError:
                    pass
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
    if pwd is None:
        return None
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

def hax_session_header(path):
    try:
        with path.open("rb") as source:
            header = json.loads(source.readline())
        if (isinstance(header, dict) and header.get("type") == "session" and header.get("version") == 1 and
                isinstance(header.get("id"), str) and SESSION_ID.fullmatch(header["id"]) and
                path.name.endswith("_" + header["id"] + ".jsonl")):
            return header
    except (OSError, ValueError):
        pass
    return None


def open_hax_transcript(pid):
    try:
        for fd in Path(f"/proc/{pid}/fd").iterdir():
            try:
                path = Path(os.readlink(fd))
                if path.suffix == ".jsonl":
                    header = hax_session_header(path)
                    if header:
                        return header["id"], path
            except OSError:
                pass
    except OSError:
        pass
    return None


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

def open_codex_transcript(pid, table, codex_home=None):
    children = {}
    for child, (parent, _argv) in table.items():
        children.setdefault(parent, []).append(child)
    candidates = set()
    queue = [pid]
    seen = set()
    root = ((Path(codex_home) if codex_home is not None else HOME / '.codex') / 'sessions').resolve()
    scanned = False
    uncertain = False
    while queue:
        current = queue.pop(0)
        if current in seen:
            continue
        seen.add(current)
        queue.extend(children.get(current, []))
        argv = table.get(current, (0, []))[1]
        if not argv or not (process_basename(argv[0]) == "codex" or
                            process_basename(argv[0]) in ("node", "bun") and len(argv) > 1 and
                            process_basename(argv[1]) in ("codex", "codex.js")):
            continue
        try:
            descriptors = list(Path(f"/proc/{current}/fd").iterdir())
            if current == pid:
                scanned = True
            for fd in descriptors:
                try:
                    path = Path(os.readlink(fd))
                except FileNotFoundError:
                    continue
                except OSError:
                    uncertain = True
                    continue
                try:
                    if not path.resolve().is_relative_to(root):
                        continue
                    with path.open("rb") as source:
                        if not os.path.samestat(fd.stat(), os.fstat(source.fileno())):
                            uncertain = True
                            continue
                        meta = json.loads(source.readline())
                    payload = meta.get("payload") if isinstance(meta, dict) and meta.get("type") == "session_meta" else None
                    sid = payload.get("id") if isinstance(payload, dict) else None
                    if (isinstance(sid, str) and SESSION_ID.fullmatch(sid) and
                            payload.get("session_id", sid) == sid and path.name.startswith("rollout-") and path.name.endswith("-" + sid + ".jsonl")):
                        candidates.add((sid, path.resolve()))
                    else:
                        uncertain = True
                except (OSError, ValueError):
                    uncertain = True
        except OSError:
            uncertain = True
    return candidates if scanned and not uncertain else None


def codex_process_start(pid):
    try:
        return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[19]
    except (OSError, IndexError):
        return None


def codex_socket_fds(pid):
    try:
        sockets = set()
        for fd in Path(f"/proc/{pid}/fd").iterdir():
            try:
                link = os.readlink(fd)
            except FileNotFoundError:
                continue
            match = re.fullmatch(r"socket:\[(\d+)\]", link)
            if match:
                sockets.add(match.group(1))
        return sockets
    except OSError:
        return None


def codex_unix_connections():
    try:
        result = subprocess.run(["ss", "-x", "-a", "-n", "-e"], capture_output=True, text=True, timeout=3, check=True)
        connections = {}
        for line in result.stdout.splitlines():
            fields = line.split()
            if len(fields) >= 5 and fields[0] == "u_str" and fields[1] == "ESTAB":
                if len(fields) < 8 or not fields[5].isdigit() or not fields[7].isdigit():
                    continue
                inode, peer = fields[5], fields[7]
                if inode in connections and connections[inode] != (peer, fields[4]):
                    previous = connections[inode][1]
                    daemon_prefix = '/tmp/codex-daemon-' + str(os.getuid()) + '/'
                    path = previous if isinstance(previous, str) and previous.startswith(daemon_prefix) else fields[4]
                    connections[inode] = (None, path)
                else:
                    connections[inode] = (peer, fields[4])
        return connections
    except (OSError, subprocess.SubprocessError):
        return None


def codex_daemon_transcript(pid, include_endpoint=False):
    try:
        if Path(f"/proc/{pid}").stat().st_uid != os.getuid() or Path(os.readlink(f"/proc/{pid}/exe")).name != "codex":
            return None
        start = codex_process_start(pid)
        tui_sockets = codex_socket_fds(pid)
        connections = codex_unix_connections()
        if not start or tui_sockets is None or connections is None:
            return None
        table = process_table()
        endpoint = re.compile(r"/tmp/codex-daemon-" + str(os.getuid()) + r"/[0-9a-f]{64}\Z")
        backends = []
        for backend, (_, argv) in table.items():
            if backend == pid or not argv or process_basename(argv[0]) != "codex" or "app-server" not in argv or "--listen" not in argv:
                continue
            try:
                if Path(f"/proc/{backend}").stat().st_uid != os.getuid() or Path(os.readlink(f"/proc/{backend}/exe")).name != "codex":
                    continue
                backend_start = codex_process_start(backend)
                backend_sockets = codex_socket_fds(backend)
                if not backend_start or backend_sockets is None:
                    continue
                links = {s for s in backend_sockets if s in connections and isinstance(connections[s][1], str) and endpoint.fullmatch(connections[s][1])}
                if not any(connections[s][0] in tui_sockets for s in links):
                    continue
                if any(connections.get(connections[s][0], (None,))[0] != s for s in links):
                    return None
                peers = {connections[s][0] for s in links}
                backends.append((backend, backend_start, links, peers, backend_sockets))
            except OSError:
                continue
        if len(backends) != 1:
            return None
        backend, backend_start, links, peers, backend_sockets = backends[0]
        if len(links) != 1 or len(peers) != 1 or not peers <= tui_sockets or peers & backend_sockets:
            return None
        candidates = open_codex_transcript(backend, {backend: table[backend]})
        if candidates is None or len(candidates) != 1:
            return None
        latest = codex_unix_connections()
        tui_after, backend_after = codex_socket_fds(pid), codex_socket_fds(backend)
        if latest is None or tui_after is None or backend_after is None:
            return None
        latest_links = {s for s in backend_after if s in latest and isinstance(latest[s][1], str) and endpoint.fullmatch(latest[s][1])}
        if (codex_process_start(pid) != start or codex_process_start(backend) != backend_start or
                not peers <= tui_after or peers & backend_after or latest_links != links or
                open_codex_transcript(backend, {backend: table[backend]}) != candidates or
                any(latest.get(s) != connections.get(s) for s in links | peers)):
            return None
        resolved = next(iter(candidates))
        return (resolved, 'unix://' + connections[next(iter(links))][1]) if include_endpoint else resolved
    except (OSError, KeyError):
        return None


def codex_daemon_endpoint(tmux, sid, transcript):
    try:
        panes = subprocess.run(['tmux', 'list-panes', '-t', '=' + tmux, '-F', '#{pane_pid}'], capture_output=True, text=True, timeout=3, check=True)
        pids = [int(line) for line in panes.stdout.split() if line.isdigit()]
        table = process_table(pids=pids)
        pid = agent_pid_for_panes(pids, table, 'codex')
        result = codex_daemon_transcript(pid, include_endpoint=True) if pid else None
        if result and result[0][0] == sid and Path(result[0][1]) == Path(transcript):
            return result[1]
    except (OSError, subprocess.SubprocessError):
        pass
    return None


def codex_queue_attachment(tmux, sid, transcript):
    try:
        panes = subprocess.run(['tmux', 'list-panes', '-t', '=' + tmux, '-F', '#{pane_pid}'], capture_output=True, text=True, timeout=3, check=True)
        pids = [int(line) for line in panes.stdout.split() if line.isdigit()]
        table = process_table(pids=pids)
        pid = agent_pid_for_panes(pids, table, 'codex')
        if not pid or pid not in table:
            return None
        daemon = codex_daemon_transcript(pid, include_endpoint=True)
        if daemon:
            if daemon[0][0] == sid and Path(daemon[0][1]).resolve() == Path(transcript).resolve():
                return dict(attachment='daemon', daemon_endpoint=daemon[1])
            return None
        start = codex_process_start(pid)
        proc = Path('/proc') / str(pid)
        if not start or proc.stat().st_uid != os.getuid():
            return None
        binary = Path(os.readlink(proc / 'exe'))
        if not binary.is_absolute() or not binary.name.startswith('codex') or not os.path.samestat((proc / 'exe').stat(), binary.stat()) or not os.access(binary, os.X_OK):
            return None
        argv = table[pid][1]
        if any(arg in ('--remote', '--profile', '-p', '--listen') or arg.startswith(('--remote=', '--profile=', '--listen=')) for arg in argv[1:]):
            return None
        for index, arg in enumerate(argv[1:], 1):
            if arg in ('-c', '--config'):
                if index + 1 >= len(argv) or 'state_home' in argv[index + 1].lower():
                    return None
            elif (arg.startswith('--config=') or arg.startswith('-c')) and 'state_home' in arg.lower():
                return None
        def namespace():
            allowed = {'HOME', 'CODEX_HOME', 'XDG_STATE_HOME', 'CODEX_STATE_HOME', 'CODEX_PROFILE', 'CODEX_CONFIG_PROFILE'}
            entries = {}
            for item in (proc / 'environ').read_bytes().split(b'\0'):
                key, sep, value = item.partition(b'=')
                if sep and key.decode('ascii', 'ignore') in allowed:
                    name = key.decode('ascii')
                    if name in entries:
                        return None
                    entries[name] = os.fsdecode(value)
            return entries
        env = namespace()
        if not env or any(env.get(key) or os.environ.get(key) for key in ('XDG_STATE_HOME', 'CODEX_STATE_HOME', 'CODEX_PROFILE', 'CODEX_CONFIG_PROFILE')):
            return None
        home = Path(env.get('HOME', ''))
        codex_home = Path(env.get('CODEX_HOME') or home / '.codex')
        if not home.is_absolute() or not codex_home.is_absolute() or home.resolve() != HOME.resolve():
            return None
        if not home.is_dir() or not codex_home.is_dir() or home.stat().st_uid != os.getuid() or codex_home.stat().st_uid != os.getuid():
            return None
        config = codex_home / 'config.toml'
        if config.exists():
            import tomllib
            with config.open('rb') as stream:
                settings = tomllib.load(stream)
            def has_state_override(value):
                if isinstance(value, dict):
                    return any(key == 'state_home' or has_state_override(child) for key, child in value.items())
                return isinstance(value, list) and any(has_state_override(child) for child in value)
            if has_state_override(settings):
                return None
        sockets = codex_socket_fds(pid)
        connections = codex_unix_connections()
        if sockets is None or connections is None:
            return None
        prefix = '/tmp/codex-daemon-' + str(os.getuid()) + '/'
        observed = {sock: connections[sock] for sock in sockets if sock in connections}
        for peer, path in observed.values():
            if peer is None or (isinstance(path, str) and path.startswith(prefix)):
                return None
            peer_path = connections.get(peer, (None, None))[1]
            if isinstance(peer_path, str) and peer_path.startswith(prefix):
                return None
        peers = {peer for peer, _ in observed.values()}
        for backend, (_, args) in process_table().items():
            if backend == pid or not args or process_basename(args[0]) != 'codex' or 'app-server' not in args:
                continue
            try:
                if Path(f'/proc/{backend}').stat().st_uid != os.getuid():
                    continue
            except FileNotFoundError:
                return None
            backend_sockets = codex_socket_fds(backend)
            if backend_sockets is None or peers & backend_sockets:
                return None
        own_table = {pid: table[pid]}
        candidates = open_codex_transcript(pid, own_table, codex_home)
        expected = (sid, Path(transcript).resolve())
        if candidates != {expected}:
            return None
        latest = codex_unix_connections()
        if (latest is None or codex_process_start(pid) != start or proc.stat().st_uid != os.getuid() or namespace() != env or
                not os.path.samestat((proc / 'exe').stat(), binary.stat()) or
                open_codex_transcript(pid, own_table, codex_home) != {expected} or codex_socket_fds(pid) != sockets or
                {sock: latest[sock] for sock in sockets if sock in latest} != observed or
                any(latest.get(item[0]) != connections.get(item[0]) for item in observed.values())):
            return None
        return dict(attachment='direct', codex_binary=str(binary), codex_home=str(codex_home.resolve()), native_user_home=str(home.resolve()))
    except (OSError, ValueError, KeyError, TypeError, ImportError, subprocess.SubprocessError):
        return None


def resolve_tmux_agent(agent, name, pid=None, require_bound=False):

    table = None
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
        table = process_table(pids=pane_pids)
        pid = agent_pid_for_panes(pane_pids, table, agent)
        if pid is None:
            return None
    try:
        argv = table[pid][1] if table is not None else Path(f"/proc/{pid}/cmdline").read_bytes().decode("utf-8", "replace").split("\0")
    except (OSError, KeyError):
        argv = []
    hax = open_hax_transcript(pid)
    if hax is not None:
        return hax
    if argv and process_basename(argv[0]) == "hax":
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
            if path is not None:
                return sid, path
        for index, argument in enumerate(argv[:-1]):
            if argument == "--resume" and SESSION_ID.fullmatch(argv[index + 1]):
                resumed = argv[index + 1]
                path = conversation_path("claude", resumed, cwd or "")
                return (resumed, path) if path is not None else None
        return None
    if table is None:
        table = process_table(pids=[pid])
    candidates = open_codex_transcript(pid, table)
    if candidates is None or len(candidates) > 1:
        return None
    if candidates:
        return next(iter(candidates))
    return codex_daemon_transcript(pid)


def conversation_event(event, agent):
    if not isinstance(event, dict):
        return None
    if event.get("kind") in ("user", "assistant"):
        if event.get("origin") == "compact_seed":
            return None
        message = {"role": event["kind"], "content": event.get("text", "")}
    elif agent == "pi":
        if event.get("type") != "message":
            return None
        message = event.get("message") or {}
    elif agent == "claude":
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
    text = _SYSTEM_REMINDER.sub("", text)
    if role == "user":
        if text.lstrip().startswith("<task-notification>") or text.strip() in _INTERRUPT_MARKERS:
            return None
        text = visible_prompt(clean_command_markup(text))
    if not text.strip():
        return None
    identity = (event.get("id") if agent == "pi" else message.get("id") or event.get("uuid")) or ""
    timestamp = event.get("timestamp") or ""
    result = dict(role=role, timestamp=timestamp if isinstance(timestamp, str) else "",
                  text=text, id=identity if isinstance(identity, str) else "")
    if not result["id"] and result["timestamp"]:
        result["read_id"] = "event:" + hashlib.sha256(json.dumps(event, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()
    return result



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
    if not ISSUE_263_TOOL_STEPS or not isinstance(event, dict):
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
            if ISSUE_270_DIFF_CARDS:
                tool_diff(rows[-1], tool_input_diff(name, value))
        else:
            output = part.get("content") if agent == "claude" else part.get("output", "")
            failed = not search and (part.get("is_error") is True if agent == "claude" else tool_failed(part))
            rows.append(dict(role="tool_result", id=identity, timestamp=timestamp, text="",
                             status="error" if failed else "ok", detail=tool_cap(tool_text(output), 4000)))
            if ISSUE_270_DIFF_CARDS and agent == "claude":
                tool_diff(rows[-1], tool_result_diff(event))
    return rows


def conversation_rows(event, agent, offset=0, search=False):
    message = conversation_event(event, agent)
    if message and event.get("kind") in ("user", "assistant"):
        message["id"] = "hax:" + str(offset) + ":" + hashlib.sha256(wire_row(event).encode("utf-8")).hexdigest()[:16]
    tools = tool_events(event, agent, search=search)
    if not tools or agent != "claude" or event.get("type") != "assistant":
        return ([message] if message else []) + tools
    content = event["message"]["content"]
    rows = []
    start = 0
    for index in range(len(content) + 1):
        part_event = dict(event, message=dict(event["message"], content=content[index:index + 1]))
        tools = tool_events(part_event, agent, search=search) if index < len(content) else []
        if index < len(content) and not tools:
            continue
        text_event = dict(event, message=dict(event["message"], content=content[start:index]))
        text = conversation_event(text_event, agent)
        if text:
            text["segment"] = start
            if rows or not text["id"]:
                stable_id = event.get("uuid") or text.get("read_id") or text["id"]
                text["read_id"] = f"{stable_id}:text:{start}" if stable_id else ""
                text["id"] = f"{text['id']}:text:{offset}:{start}"
            rows.append(text)
        rows.extend(tools)
        start = index + 1
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


def read_tail(source, size, agent, captured=None, fill=True):
    fill = fill and ISSUE_631_TAIL_FILLS_SHOWN_BUDGET
    base, end, data = read_window(source, size, captured=captured)
    if not ISSUE_358_TAIL_STARTS_AT_QUESTION or not base or (not data and not fill) or any(asks_question(raw, agent) for raw in data.splitlines()):
        return base, end, data
    start, _, older = read_window(source, base, QUESTION_SCAN_BYTES - TAIL_BYTES, captured)
    shown = 0
    offset = base
    if fill:
        held = sum(len(wire_row(row)) + 1 for raw in data.splitlines() for row in conversation_rows(load_line(raw), agent, base))
        fill = fill and held < TAIL_BYTES // 2
        fill_at = base
    for raw in reversed(older.splitlines(keepends=True)):
        offset -= len(raw)
        messages = conversation_rows(load_line(raw), agent, offset)
        shown += sum(len(wire_row(row)) + 1 for row in messages)
        if shown > TAIL_BYTES:
            break
        if any(row["role"] == "user" for row in messages):
            return offset, end, older[offset - start:] + data
        if fill and held + shown <= TAIL_BYTES:
            fill_at = offset
    if fill:
        return fill_at, end, older[fill_at - start:] + data
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
    question = None
    question_known = False
    completed_questions = set()
    window = 200000
    end = size
    lower = previous[0] if previous is not None else 0
    scanned_end = lower
    probe = previous[5] if previous is not None else b""
    remaining = min(QUESTION_SCAN_BYTES, size - lower)
    pending = b""
    first = True
    extra_budget = max(0, QUESTION_SCAN_BYTES - probe_bytes - sum(len(data) for _, data in captured))
    while end > lower and remaining and (model is None or usage is None or todos is None or not question_known):
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
                       ((b'TodoWrite', b'update_plan') if todos is None else ()) +
                       ((b'AskUserQuestion', b'request_user_input', b'tool_result', b'function_call_output') if not question_known else ()))
            if not any(marker in raw for marker in markers):
                continue
            try:
                event = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                continue
            if not isinstance(event, dict) or event.get("isSidechain"):
                continue
            if not question_known:
                if agent == "claude":
                    message = event.get("message")
                    parts = message.get("content", []) if isinstance(message, dict) and event.get("type") in ("assistant", "user") and not any(event.get(key) for key in ("isMeta", "isCompactSummary", "isVisibleInTranscriptOnly")) else []
                    parts = parts if isinstance(parts, list) else []
                else:
                    parts = [event.get("payload")] if event.get("type") == "response_item" else []
                for part in reversed(parts):
                    if not isinstance(part, dict):
                        continue
                    kind = part.get("type")
                    if kind in ("tool_result", "function_call_output"):
                        identity = part.get("tool_use_id") if agent == "claude" else part.get("call_id")
                        if isinstance(identity, str):
                            completed_questions.add(identity)
                    elif ((agent == "claude" and kind == "tool_use" and part.get("name") == "AskUserQuestion") or
                          (agent != "claude" and kind == "function_call" and part.get("name") == "request_user_input")):
                        identity = part.get("id") if agent == "claude" else part.get("call_id")
                        value = part.get("input") if agent == "claude" else part.get("arguments")
                        if isinstance(value, str):
                            try:
                                value = json.loads(value)
                            except ValueError:
                                value = None
                        questions = value.get("questions") if isinstance(value, dict) else None
                        if isinstance(identity, str) and isinstance(questions, list) and questions:
                            question = dict(id=identity, questions=questions) if identity not in completed_questions else None
                            if question is not None and agent != "claude":
                                question["provider"] = "codex"
                            question_known = True
                            break
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
            if model is not None and usage is not None and todos is not None and question_known:
                break
    if previous is not None:
        model = previous[1] if model is None else model
        if usage is None:
            usage, window = previous[2:4]
        todos = previous[4] if todos is None else todos
        if not question_known:
            question = previous[6]
            if question is not None and question["id"] in completed_questions:
                question = None
    STATUS_CACHE[key] = (scanned_end, model, usage, window, todos[:50] if todos is not None else None, probe, question)
    if len(STATUS_CACHE) > 256:
        del STATUS_CACHE[next(iter(STATUS_CACHE))]
    result = {"role": "status"}
    if question is not None or previous is not None and previous[6] is not None:
        result["question"] = question
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


def queue_apply(state, event):
    queue, sent = state
    operation = event.get("operation")
    content = event.get("content")
    if isinstance(content, str):
        content = visible_prompt(content)
    if operation == "enqueue" and isinstance(content, str):
        if content.lstrip().startswith("<task-notification>"):
            return
        if not queue:
            sent.clear()
        queue.append(content)
    elif operation in ("dequeue", "remove") and queue:
        if isinstance(content, str) and content:
            key = queue_key(content)
            index = next((i for i, item in enumerate(queue) if queue_key(item) == key), None)
            if index is None:
                return
        else:
            index = 0
        sent.append(queue.pop(index))
        del sent[:-QUEUE_SHOWN]
        return index
    elif operation == "popAll":
        queue.clear()
        sent.clear()


def queue_replay(source, size, with_receipts=False):
    key = (str(Path(source.name).resolve()), os.fstat(source.fileno()).st_ino)
    previous = QUEUE_CACHE.get(key)
    start, state = 0, ([], [])
    queued, receipts, sent_occurrences, consumed = [], [], [], {}
    resumed = False
    if previous is not None and len(previous) == 7 and previous[0] <= size:
        source.seek(max(0, previous[0] - 64))
        if hashlib.sha256(source.read(min(previous[0], 64))).hexdigest() == previous[2]:
            start, state, resumed = previous[0], (list(previous[1][0]), list(previous[1][1])), True
            queued, receipts = list(previous[3]), list(previous[4])
            sent_occurrences, consumed = list(previous[5]), dict(previous[6])
    trim = not resumed and size > QUEUE_SCAN_BYTES
    if trim:
        start = size - QUEUE_SCAN_BYTES
    source.seek(start)
    data = source.read(size - start)
    if trim:
        data = data[data.find(b"\n") + 1:] if b"\n" in data else b""
        start = size - len(data)
    end = data.rfind(b"\n") + 1
    position = start
    for raw in data[:end].splitlines(keepends=True):
        position += len(raw)
        try:
            event = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            continue
        if not isinstance(event, dict) or event.get("isSidechain"):
            continue
        try:
            stamp = datetime.fromisoformat(event.get("timestamp", "").replace("Z", "+00:00")).timestamp()
        except (ValueError, TypeError, AttributeError, OverflowError):
            stamp = None
        if event.get("type") == "queue-operation":
            count = len(state[0])
            previous_sent = list(state[1])
            removed = queue_apply(state, event)
            if len(state[0]) > count:
                if count == 0:
                    sent_occurrences.clear()
                receipt = (state[0][-1], stamp, position)
                queued.append(receipt)
                receipts.append(receipt)
            elif removed is not None:
                receipt = queued.pop(removed)
                if receipt not in receipts:
                    state[1][:] = previous_sent
                else:
                    sent_occurrences.append(receipt)
                    del sent_occurrences[:-QUEUE_SHOWN]
            elif event.get("operation") == "popAll":
                queued.clear()
                sent_occurrences.clear()
            consumed = {item: consumed[item] for item in sent_occurrences + queued if item in consumed}
            continue
        message = conversation_event(event, "claude")
        if message is None or message["role"] != "user" or stamp is None:
            continue
        for i, receipt in enumerate(receipts):
            text, queued_stamp, _ = receipt
            if queued_stamp is None or stamp < queued_stamp or message["text"] != text:
                continue
            matches = [candidate for candidate in receipts if candidate[1] is not None and stamp >= candidate[1] and message["text"] == candidate[0]]
            receipts.pop(i)
            if (len(matches) == 1 and receipt not in consumed and
                    event.get("type") in ("user", "attachment")):
                consumed[receipt] = (position - len(raw), hashlib.sha256(raw).hexdigest())
            if receipt in queued:
                state[1].append(text)
                sent_occurrences.append(receipt)
                del state[1][:-QUEUE_SHOWN]
                del sent_occurrences[:-QUEUE_SHOWN]
            consumed = {item: consumed[item] for item in sent_occurrences + queued if item in consumed}
            break
    scanned = start + end
    source.seek(max(0, scanned - 64))
    probe = hashlib.sha256(source.read(min(scanned, 64))).hexdigest()
    consumed = {occurrence: consumed[occurrence] for occurrence in sent_occurrences if occurrence in consumed}
    QUEUE_CACHE[key] = (scanned, state, probe, queued, receipts, sent_occurrences, consumed)
    if len(QUEUE_CACHE) > 256:
        del QUEUE_CACHE[next(iter(QUEUE_CACHE))]
    sent = list(zip(state[1], (consumed.get(item) for item in sent_occurrences))) if with_receipts else state[1][-QUEUE_SHOWN:]
    return [receipt[0] for receipt in queued if receipt in receipts], sent


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


def box_draft(target):
    try:
        shown = subprocess.run(["tmux", "capture-pane", "-p", "-e", "-J", "-t", target], capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.SubprocessError, UnicodeError):
        return None
    return box_draft_text(shown) if isinstance(shown, str) else None


def queue_envelope(text):
    envelope = re.fullmatch(r"""\s*<(cross-session-message|task-notification)(?=[\s>])((?:[^>"']|"[^"]*"|'[^']*')*)>(.*?)</\1>\s*""", text, re.S)
    if envelope is None:
        return None
    kind, attrs, body = envelope.groups()
    if '</' + kind + '>' in body:
        return None
    sender = ''
    while attrs.strip():
        attr = re.match(r"""\s*([^=\s]+)\s*=\s*(["'])(.*?)\2(?=\s|$)""", attrs, re.S)
        if attr is None:
            return None
        name, _quote, value = attr.groups()
        if name == 'from-name' and value.strip():
            sender = value
        attrs = attrs[attr.end():]
    return kind, sender, body


def queue_key(text):
    envelope = queue_envelope(text)
    if envelope is None:
        return text
    kind, sender, body = envelope
    return kind, html.unescape(sender), html.unescape(body).strip()


def queue_item(text):
    envelope = queue_envelope(text)
    if envelope is None:
        return text[:QUEUE_TEXT]
    kind, sender, body = envelope
    body = re.sub(r"""</?[A-Za-z](?:[^>"']|"[^"]*"|'[^']*')*>""", '', body.strip())
    return dict(text=html.unescape(body)[:QUEUE_TEXT], sender='Task' if kind == 'task-notification' else html.unescape(sender or 'another session'), injected=True)


def _consumed_identity(source, sid, inode, offset, digest, text):
    try:
        info = os.fstat(source.fileno())
        path_info = os.stat(source.name)
        if ((info.st_dev, info.st_ino) != (path_info.st_dev, path_info.st_ino) or
                info.st_ino != inode or offset < 0 or offset >= info.st_size):
            return None
        if offset:
            source.seek(offset - 1)
            if source.read(1) != b"\n":
                return None
        source.seek(offset)
        raw = source.readline()
        if not raw.endswith(b"\n") or hashlib.sha256(raw).hexdigest() != digest:
            return None
        event = load_line(raw)
        if (not isinstance(event, dict) or event.get("sessionId", sid) != sid or
                event.get("type") not in ("user", "attachment") or event.get("isSidechain")):
            return None
        if not any(row["role"] == "user" and row["text"].replace("\r", "").rstrip(" ") == text.replace("\r", "").rstrip(" ")
                   for row in conversation_rows(event, "claude", offset)):
            return None
        return sid, inode, offset, digest
    except (OSError, ValueError, TypeError):
        return None


def live_fields(source, size, tmux, shown=None, sid=None):
    queue, sent = queue_replay(source, size, with_receipts=True)
    fields = dict(queue=[queue_item(text) for text in queue[:QUEUE_SHOWN]])
    if sid is not None:
        inode = os.fstat(source.fileno()).st_ino
        identities = {identity for text, receipt in sent if receipt is not None
                      if (identity := _consumed_identity(source, sid, inode, *receipt, text)) is not None}
        for text, receipt in held_queue_fields(tmux, sid, with_receipts=True)["sent"]:
            identity = _consumed_identity(source, sid, *receipt, text) if receipt is not None else None
            if identity is None or identity not in identities:
                sent.append((text, None))
        from contextlib import closing
        with closing(message_store_db(tmux)) as db:
            fields['received'] = [row[0] for row in db.execute("SELECT b.token FROM legacy_bindings b JOIN shared_messages m ON m.id=b.id WHERE b.sid=? AND m.state IN ('accepted','started') AND b.token LIKE 'desktop-%' ORDER BY m.position DESC", (sid,))]
    fields["sent"] = [queue_item(text) for text, _ in sent[-QUEUE_SHOWN:]]
    draft = box_draft_text(shown) if shown is not None else box_draft("=" + tmux + ":")
    if draft is not None:
        fields["draft"] = draft[:DRAFT_TEXT]
    return fields


def pin_hosted_claude(q, agent, as_owner=False):
    if agent != "claude" or not q.get("tmux"):
        return q
    if not q.get("sid") and not as_owner:
        q["sid"] = host_sid_for_tmux(agent, q["tmux"]) or ""
    sid = q.get("sid")
    if isinstance(sid, str) and SESSION_ID.fullmatch(sid) and host_dispatch_switch(agent, sid):
        q["follow"] = False
        q["host_pinned"] = True
    return q


def resolve_response_path(q, source=None, as_owner=False):
    rid = q["id"]
    agent = q.get("provider") if q.get("provider") in HOST_PROVIDERS else "codex" if q["codex"] else "claude"
    sid, tmux, cwd = q["sid"], q["tmux"], q["cwd"]
    hosted = host_config(agent, sid) if isinstance(sid, str) and SESSION_ID.fullmatch(sid) else None
    if isinstance(hosted, dict) and isinstance(hosted.get("cwd"), str) and Path(hosted["cwd"]).is_absolute():
        cwd = q["cwd"] = hosted["cwd"]
    if (not isinstance(rid, int) or rid < 0 or
            sid and not SESSION_ID.fullmatch(sid) or
            tmux and not TMUX_NAME.fullmatch(tmux) or not sid and not tmux or
            cwd and not Path(cwd).is_absolute()):
        raise ValueError("invalid identity")
    if agent == "hax":
        return agent, sid, None, empty_history_response(q, sid, tmux, agent)
    if agent == "pi":
        try:
            path = conversation_path(agent, sid, cwd or host_cwd(agent, sid)) if sid else None
        except PermissionError:
            return agent, sid, None, missing_response(q, "permission denied")
        return (agent, sid, path, None) if path else (agent, sid, None, empty_history_response(q, sid, tmux, agent))
    pin_hosted_claude(q, agent, as_owner)
    sid = q["sid"]
    follow = bool(tmux and (q.get("follow") or agent == "codex"))
    denied = False
    delegated = None
    try:
        path = conversation_path(agent, sid, cwd) if sid and not follow else None
    except PermissionError:
        path, denied = None, True
    if agent == "codex" and sid and host_dispatch_switch(agent, sid):
        native = host_session_state(agent, sid).get("native_session")
        try:
            hosted = conversation_path(agent, native, cwd) if isinstance(native, str) else None
        except PermissionError:
            hosted = None
        return (agent, sid, hosted, None) if hosted is not None else (agent, sid, None, empty_history_response(q, sid, tmux, agent))
    owner = conversation_owner(cwd) if agent == "claude" else None
    if follow and owner and owner[1] != HOME and source is not None and not as_owner:
        delegated = owner_response(dict(q, sid=""), owner, source)
        if delegated != missing_response(q, "session transcript not found"):
            return agent, sid, None, delegated
    if path is None and sid and not follow and owner and owner[1] != HOME:
        try:
            path = conversation_path(agent, sid, cwd, owner[1])
        except PermissionError:
            denied = True
            if source is not None and not as_owner:
                delegated = owner_response(q, owner, source)
                if delegated != missing_response(q, "session transcript not found"):
                    return agent, sid, None, delegated

    if path is None and not denied and agent == "claude" and sid and host_dispatch_switch(agent, sid):
        try:
            existing = conversation_path(agent, sid, cwd)
        except PermissionError:
            existing = None
        if existing is None:
            return agent, sid, None, empty_history_response(q, sid, tmux)
    if path is None and tmux:
        try:
            resolved = (resolve_tmux_agent(agent, tmux, q["discovery_pid"])
                        if as_owner and "discovery_pid" in q else resolve_tmux_agent(agent, tmux))
        except PermissionError:
            resolved, denied = None, True
        sid, path = resolved if resolved else ("", None)
        if path is None and follow and q["sid"]:
            try:
                pinned = conversation_path(agent, q["sid"], cwd)
                if pinned is not None and (hax_session_header(pinned) or host_dispatch_switch(agent, q["sid"])):
                    sid, path = q["sid"], pinned
            except PermissionError:
                denied = True
        if path is None and source is not None and not as_owner:
            discovered = tmux_owner_process(agent, tmux, source)
            if discovered:
                pid, owner = discovered
                return agent, sid, None, owner_response(dict(q, sid="", discovery_pid=pid), owner, source)
    if path is None:
        return agent, sid, None, delegated or missing_response(q, "permission denied" if denied else "session transcript not found")
    return agent, sid, path, None


def empty_history_response(q, sid, tmux, agent="claude"):
    rid = q["id"]
    probe = hashlib.sha256(("empty:" + sid).encode()).hexdigest()
    header = f"@mp\t{rid}\thistory\tok\t{sid}\t0\t0\t0\t0\t{agent}\t{tmux or '-'}\t0\t{probe}\tdelta\t0"
    status = wire_row(dict(role="status", no_transcript=True))
    return "\n".join((header, status, END, ""))


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
                    for row in conversation_rows(event, agent, offset, search=True):
                        if needle not in (row["text"] + "\n" + row.get("detail", "")).casefold():
                            continue
                        selected.add(offset)
                        if not row["role"].startswith("tool"):
                            continue
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
            rows = conversation_rows(event, agent, offset, search=True)
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
                if "segment" in row:
                    hit["segment"] = row["segment"]
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


def tail_view(q, agent, fill):
    return (agent, bool(fill), bool(q.get("settlement")), q.get("message_protocol"), bool(q.get("source_offsets")),
            bool(ISSUE_263_TOOL_STEPS), bool(ISSUE_349_QUEUED_STAMP_FROM_QUEUE),
            bool(ISSUE_358_TAIL_STARTS_AT_QUESTION), bool(ISSUE_631_TAIL_FILLS_SHOWN_BUDGET))


def recall_tail(path, source, info, q, agent, fill):
    hit = TAIL_CACHE.get((str(Path(path).resolve()), info.st_ino, tail_view(q, agent, fill)))
    if not hit or hit["end"] > info.st_size or info.st_size - hit["end"] > TAIL_BYTES:
        return None
    source.seek(max(0, hit["end"] - 64))
    if hashlib.sha256(source.read(min(hit["end"], 64))).hexdigest() != hit["probe"]:
        return None
    rows = [json.loads(line) for line in hit["wires"]]
    if hit["size"] == info.st_size and hit["mtime"] == info.st_mtime_ns:
        return hit["base"], hit["end"], b"", hit["oldest"], rows, "exact", hit["end"]
    source.seek(hit["end"])
    extra = source.read(info.st_size - hit["end"])
    complete = extra.rfind(b"\n") + 1
    extra = extra[:complete]
    return hit["base"], hit["end"] + len(extra), extra, hit["oldest"], rows, "append", hit["end"]


def remember_tail(path, info, q, agent, fill, base, end, oldest, rows, probe):
    if end <= 0:
        return
    TAIL_CACHE[(str(Path(path).resolve()), info.st_ino, tail_view(q, agent, fill))] = dict(
        size=info.st_size, mtime=info.st_mtime_ns, base=base, end=end,
        oldest=oldest, probe=probe, wires=[wire_row(row) for row in rows])
    while len(TAIL_CACHE) > 8:
        del TAIL_CACHE[next(iter(TAIL_CACHE))]


def fit_tail(rows, oldest):
    budget = sum(len(wire_row(row)) + 1 for row in rows)
    if budget <= TAIL_BYTES or len(rows) < 2:
        return rows, oldest
    start = 0
    while budget > TAIL_BYTES and start + 1 < len(rows):
        budget -= len(wire_row(rows[start])) + 1
        start += 1
    kept = rows[start:]
    if kept and isinstance(kept[0].get("offset"), int):
        oldest = kept[0]["offset"]
    return kept, oldest


def response(q, source=None, as_owner=False, resolved=None):
    q["_held_queue_progress"] = False
    if ISSUE_268_CHAT_FIND and "search" in q:
        query = q["search"]
        if not isinstance(query, str) or not 1 <= len(query) <= 200:
            raise ValueError("invalid search")
    agent, sid, path, delegated = resolved or resolve_response_path(q, source, as_owner)
    if delegated is not None:
        return delegated
    if ISSUE_268_CHAT_FIND and "search" in q:
        return search_response(q, path, agent, sid)
    rid, tmux = q["id"], q["tmux"]
    legacy_status = None
    if agent == 'claude' and tmux and (q.get('held') is True or q.get('delivery_ids')):
        ids = q.get('delivery_ids') or []
        if not isinstance(ids, list) or any(not isinstance(token, str) or re.fullmatch(r'[A-Za-z0-9_-]{1,80}', token) is None for token in ids):
            raise ValueError('invalid message identities')
        legacy_status = legacy_projection(tmux, sid, path, ids)
        if legacy_status[0]:
            start_message_worker(dict(tmux=tmux, provider='claude', sid=sid), source)
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
        tail_fill = None
        tail_reuse = None
        append_at = None
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
            tail_fill = True
            recalled = recall_tail(path, source, info, q, agent, True)
            if recalled:
                base, end, data, oldest, rows, tail_reuse, append_at = recalled
                budget = sum(len(wire_row(row)) + 1 for row in rows)
            else:
                base, end, data = read_tail(source, info.st_size, agent, captured)
                oldest = base
        else:
            mode = "delta"
            if ISSUE_345_STALE_JUMPS_TO_TAIL and not q.get("no_jump") and info.st_size - offset > 2 * SCAN_BYTES:
                tail_fill = False
                recalled = recall_tail(path, source, info, q, agent, False)
                if recalled and (recalled[2] or recalled[4]):
                    base, end, data, oldest, rows, tail_reuse, append_at = recalled
                    budget = sum(len(wire_row(row)) + 1 for row in rows)
                    jumped = True
                    mode = "tail"
                    scan_more = False
                else:
                    base, end, data = read_tail(source, info.st_size, agent, captured, fill=False)
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
                    source.seek(base)
                    data = source.readline(info.st_size - base)
                    if data.endswith(b"\n"):
                        end = base + len(data)
                        scan_more = end < info.st_size
                    else:
                        data = b""
                        scan_more = False
                else:
                    data = data[:end - base]
        page_more = False
        groups = []
        consumed = append_at if tail_reuse == "append" else base
        for raw in data.splitlines(keepends=True):
            event = load_line(raw)
            messages = conversation_rows(event, agent, consumed)
            message = next((row for row in messages if row["role"] in ("user", "assistant")), None)
            if ISSUE_349_QUEUED_STAMP_FROM_QUEUE and q.get('message_protocol') != 1 and agent == "claude" and isinstance(event, dict):
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
                fact = settlement_event(event, agent)
                if fact:
                    messages.insert(0, fact)
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
        if tail_reuse == "append":
            rows, oldest = fit_tail(rows, oldest)
        elif tail_reuse != "exact" and mode != "delta" and budget > TAIL_BYTES and len(groups) > 1 and any(row["role"] in ("tool", "tool_result") for row in rows):
            first = 0
            while budget > TAIL_BYTES and first + 1 < len(groups):
                budget -= groups[first][2]
                first += 1
            base, row_start, _cost = groups[first]
            oldest = base
            rows = rows[row_start:]
        if jumped and not rows:
            return response(dict(q, no_jump=True), source, as_owner, resolved)
        if mode == "delta":
            captured.append((base, data))
        status = status_header(source, info.st_size, agent, captured)
        if q.get("live") is True and agent == "claude" and tmux and mode != "older":
            status = dict(status or dict(role="status"), **live_fields(source, info.st_size, tmux, q.get("watch_screen"), sid))
        if legacy_status is not None:
            if q.get('held') is True:
                status = dict(status or dict(role='status'), held=legacy_status[0])
            if q.get('delivery_ids'):
                status = dict(status or dict(role='status'), deliveries=legacy_status[1])
        more = scan_more or page_more
        source.seek(max(0, end - 64))
        probe = hashlib.sha256(source.read(min(end, 64))).hexdigest()
        paired = pair_tool_rows(rows)
        if mode == "tail" and tail_fill is not None and tail_reuse != "exact":
            remember_tail(path, info, q, agent, tail_fill, base, end, oldest, paired, probe)
    header = f"@mp\t{rid}\thistory\tok\t{sid}\t{base}\t{end}\t{info.st_ino}\t{int(more)}\t{agent}\t{tmux or '-'}\t{info.st_mtime_ns}\t{probe}\t{mode}\t{oldest}"
    return "\n".join((header, *(wire_row(row) for row in paired), *((wire_row(status),) if status else ()), END, ""))


def prompt_box_text(target):
    try:
        shown = subprocess.run(["tmux", "capture-pane", "-p", "-e", "-J", "-t", target], capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.SubprocessError, UnicodeError):
        return ""
    draft = box_draft_text(shown) if isinstance(shown, str) else None
    if not draft:
        return ""
    return "\n".join(" ".join(line.split()) for line in draft.split("\n")).strip()


def stop_response(q):
    rid = q.get("id", 0)
    name = q.get("tmux")
    op = q.get("op", "stop")
    digit = q.get("digit")
    key = chr(digit) if op == "permission" and type(digit) is int and 49 <= digit <= 57 else "Escape"
    keys = None
    reason = None
    if op == "question":
        values = q.get("keys")
        if not isinstance(values, list) or not values or any(type(value) is not int or value not in (9, 13) and not 49 <= value <= 57 for value in values):
            reason = "invalid_question_keys"
        else:
            keys = [key for value in values for key in (["Up", "Tab"] if value == 9 else ["Enter"] if value == 13 else [chr(value)])]
    elif op == "permission" and key == "Escape":
        reason = "invalid_permission_digit"
    if not isinstance(name, str) or re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", name) is None:
        reason = "invalid_tmux_name"
    if reason is None:
        try:
            if keys is None:
                keys = ["C-x", "C-s"] if op == "send_queued" else ["Up"] if op == "edit_queued" else [key]
            subprocess.run(["tmux", "send-keys", "-t", "=" + name + ":", *keys], check=True, capture_output=True, timeout=5)
        except (OSError, subprocess.SubprocessError):
            reason = "tmux_stop_failed"
    status = "error" if reason else "ok"
    return f"@mp\t{rid}\t{op}\t{status}\t{reason or '-'}\t0\n{END}\n"


def box_is_empty(box):
    return not box


def clear_prompt_box(target, box):
    presses = 0
    stalled = 0
    stuck = False
    limit = len(box) + 2
    while not box_is_empty(box) and presses < limit:
        subprocess.run(["tmux", "send-keys", "-t", target, "C-u"], check=True, capture_output=True, timeout=5)
        presses += 1
        after = prompt_box_text(target)
        stalled = stalled + 1 if after == box else 0
        box = after
        if stalled == 2:
            subprocess.run(["tmux", "send-keys", "-t", target, "C-k"], check=True, capture_output=True, timeout=5)
            box = prompt_box_text(target)
            stuck = True
            break
    return box, stuck


def held_queue_path(tmux):
    if not isinstance(tmux, str) or not TMUX_NAME.fullmatch(tmux):
        raise ValueError("invalid tmux name")
    return HOME / ".local/state/multiprompt/held-messages" / hashlib.sha256(tmux.encode()).hexdigest() / "queue.sqlite3"


def held_queue_db(tmux):
    import sqlite3
    path = held_queue_path(tmux)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    db = sqlite3.connect(path)
    os.chmod(path, 0o600)
    db.execute("CREATE TABLE IF NOT EXISTS messages (key TEXT PRIMARY KEY, sid TEXT NOT NULL, text TEXT NOT NULL, queued_at INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('queued','submitted','delivered','cancelled')), scan INTEGER NOT NULL DEFAULT 0, origin INTEGER NOT NULL, source_inode INTEGER NOT NULL, receipt TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)")
    db.execute("CREATE INDEX IF NOT EXISTS pending ON messages(sid,state,created)")
    db.execute("CREATE INDEX IF NOT EXISTS recent ON messages(sid,created DESC)")
    with db:
        db.execute("BEGIN IMMEDIATE")
        if db.execute("PRAGMA user_version").fetchone()[0] < 1:
            db.execute("UPDATE messages SET scan=origin WHERE state IN ('queued','submitted')")
            db.execute("PRAGMA user_version=1")
        if "recovery_scan" not in {row[1] for row in db.execute("PRAGMA table_info(messages)")}:
            db.execute("ALTER TABLE messages ADD COLUMN recovery_scan INTEGER")
        db.execute("UPDATE messages SET recovery_scan=origin WHERE recovery_scan IS NULL")
        if db.execute("PRAGMA user_version").fetchone()[0] < 2:
            db.execute("PRAGMA user_version=2")
        db.execute("CREATE TABLE IF NOT EXISTS held_receipts (sid TEXT NOT NULL, inode INTEGER NOT NULL, offset INTEGER NOT NULL, key TEXT NOT NULL, digest TEXT NOT NULL, PRIMARY KEY(sid,inode,offset))")
        db.execute("CREATE TABLE IF NOT EXISTS v1_admission (sid TEXT PRIMARY KEY)")
        trigger = db.execute("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='v1_no_legacy_insert'").fetchone()
        if trigger and '@mp-shared-v1' not in trigger[0]:
            db.execute('DROP TRIGGER IF EXISTS v1_no_legacy_insert')
            db.execute('DROP TRIGGER IF EXISTS v1_no_legacy_requeue')
        db.execute("CREATE TRIGGER IF NOT EXISTS v1_no_legacy_insert BEFORE INSERT ON messages WHEN EXISTS (SELECT 1 FROM v1_admission WHERE sid=NEW.sid) AND (NEW.state='queued' OR (NEW.state='submitted' AND NEW.receipt NOT LIKE '@mp-shared-v1:%')) BEGIN SELECT RAISE(ABORT,'shared queue active'); END")
        db.execute("CREATE TRIGGER IF NOT EXISTS v1_no_legacy_requeue BEFORE UPDATE OF state ON messages WHEN EXISTS (SELECT 1 FROM v1_admission WHERE sid=NEW.sid) AND (NEW.state='queued' OR (NEW.state='submitted' AND NEW.receipt NOT LIKE '@mp-shared-v1:%')) BEGIN SELECT RAISE(ABORT,'shared queue active'); END")
        db.execute("CREATE TRIGGER IF NOT EXISTS v1_preserve_mirror BEFORE UPDATE ON messages WHEN OLD.receipt LIKE '@mp-shared-v1:%' AND (NEW.receipt=OLD.receipt OR NEW.receipt NOT LIKE '@mp-shared-v1:%') BEGIN SELECT RAISE(IGNORE); END")
        db.execute("CREATE TRIGGER IF NOT EXISTS v1_no_mirror_claim BEFORE INSERT ON held_receipts WHEN EXISTS (SELECT 1 FROM messages WHERE key=NEW.key AND sid=NEW.sid AND receipt LIKE '@mp-shared-v1:%') BEGIN SELECT RAISE(IGNORE); END")
        db.execute("CREATE INDEX IF NOT EXISTS held_receipts_key ON held_receipts(key,sid,inode,offset)")
    return db


ROOM_MESSAGE_PROTOCOL = 1


def message_room_scope(value):
    import uuid
    if not isinstance(value, dict) or set(value) != {'host_uuid', 'room_id', 'parent_event_id', 'member_id'}:
        raise ValueError('invalid room scope')
    for field in value.values():
        if not isinstance(field, str) or str(uuid.UUID(field)) != field:
            raise ValueError('invalid room scope')
    return dict(value)


def message_store_path():
    return HOME / '.local/state/multiprompt/shared-messages/store.sqlite3'


def message_retry_generation(provider, sid):
    import sqlite3
    from contextlib import closing
    path = message_store_path()
    if not path.exists():
        return 0
    with closing(sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)) as db:
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('message_retries','shared_messages')")}
        if len(tables) != 2:
            return 0
        return db.execute('SELECT COALESCE(MAX(r.rowid),0) FROM message_retries r JOIN shared_messages m ON m.id=r.id WHERE m.provider=? AND m.sid=?', (provider, sid)).fetchone()[0]


def host_failure_current(provider, sid, failure, generation):
    return (isinstance(failure, dict) and failure.get('config') == host_config(provider, sid)
            and failure.get('retry_generation', generation) >= generation)


def message_store_db(tmux):
    import sqlite3
    from contextlib import closing
    with closing(held_queue_db(tmux)):
        pass
    path = message_store_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    db = sqlite3.connect(path)
    os.chmod(path, 0o600)
    db.execute('ATTACH DATABASE ? AS legacy', (str(held_queue_path(tmux)),))
    with db:
        db.execute("CREATE TABLE IF NOT EXISTS message_chats (provider TEXT NOT NULL, sid TEXT NOT NULL, epoch INTEGER NOT NULL DEFAULT 0, seq INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(provider,sid))")
        db.execute("CREATE TABLE IF NOT EXISTS shared_messages (id TEXT PRIMARY KEY, provider TEXT NOT NULL, sid TEXT NOT NULL, text TEXT NOT NULL, position INTEGER NOT NULL, attempt TEXT NOT NULL, state TEXT NOT NULL, deadline REAL, receipt TEXT, epoch INTEGER NOT NULL, created REAL NOT NULL)")
        db.execute("CREATE INDEX IF NOT EXISTS shared_messages_chat ON shared_messages(provider,sid,position)")
        db.execute("CREATE TABLE IF NOT EXISTS message_events (provider TEXT NOT NULL, sid TEXT NOT NULL, seq INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(provider,sid,seq))")
        db.execute("CREATE TABLE IF NOT EXISTS message_attempts (provider TEXT NOT NULL, sid TEXT NOT NULL, id TEXT NOT NULL, attempt TEXT NOT NULL, epoch INTEGER NOT NULL, text_sha256 TEXT NOT NULL, outcome TEXT NOT NULL, binding TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(provider,sid,id,attempt))")
        if 'binding' not in {row[1] for row in db.execute('PRAGMA table_info(message_attempts)')}:
            db.execute("ALTER TABLE message_attempts ADD COLUMN binding TEXT NOT NULL DEFAULT '{}'")
        db.execute("CREATE TABLE IF NOT EXISTS connector_states (provider TEXT NOT NULL, sid TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY(provider,sid))")
        db.execute("CREATE TABLE IF NOT EXISTS connector_facts (provider TEXT NOT NULL, sid TEXT NOT NULL, event_id TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(provider,sid,event_id))")
        db.execute("CREATE TABLE IF NOT EXISTS message_retries (id TEXT NOT NULL, request TEXT NOT NULL, attempt TEXT NOT NULL, PRIMARY KEY(id,request))")
        db.execute("CREATE TABLE IF NOT EXISTS message_attempt_history (id TEXT NOT NULL, attempt TEXT NOT NULL, epoch INTEGER NOT NULL, PRIMARY KEY(id,attempt))")
        db.execute("CREATE TABLE IF NOT EXISTS message_resets (provider TEXT NOT NULL, sid TEXT NOT NULL, request TEXT NOT NULL, epoch INTEGER NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(provider,sid,request))")
        db.execute("CREATE TABLE IF NOT EXISTS message_snapshots (id TEXT PRIMARY KEY, provider TEXT NOT NULL, sid TEXT NOT NULL, epoch INTEGER NOT NULL, seq INTEGER NOT NULL, created REAL NOT NULL, body TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS legacy_bindings (token TEXT PRIMARY KEY, id TEXT NOT NULL UNIQUE, sid TEXT NOT NULL, queued_at INTEGER NOT NULL, origin INTEGER NOT NULL, inode INTEGER NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS legacy_locations (token TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY(token,path))")
        db.execute("CREATE TABLE IF NOT EXISTS message_locators (provider TEXT NOT NULL, sid TEXT NOT NULL, tmux TEXT NOT NULL, PRIMARY KEY(provider,sid))")
        db.execute("CREATE TABLE IF NOT EXISTS message_room_scopes (id TEXT PRIMARY KEY REFERENCES shared_messages(id), scope TEXT NOT NULL)")
    return db


def message_event(db, provider, sid, message, emit=True):
    if provider == 'claude' and isinstance(message, dict) and message.get('id') and message.get('state'):
        binding = db.execute('SELECT token,queued_at,origin,inode FROM legacy_bindings WHERE id=? AND sid=?', (message['id'], sid)).fetchone()
        if binding:
            token, stamp, origin, inode = binding
            state = {'queued':'submitted', 'sent':'submitted', 'accepted':'delivered', 'started':'delivered', 'failed':'cancelled', 'cancelled':'cancelled'}[message['state']]
            marker = '@mp-shared-v1:' + message['attempt'] + ':' + message['state'] + ':' + str(db.execute('SELECT seq+1 FROM message_chats WHERE provider=? AND sid=?', (provider, sid)).fetchone()[0])
            databases = {path: name for _, name, path in db.execute('PRAGMA database_list')}
            for (path,) in db.execute('SELECT path FROM legacy_locations WHERE token=?', (token,)).fetchall():
                alias = databases.get(path)
                if alias is None:
                    alias = 'mirror' + str(len(databases))
                    db.execute('ATTACH DATABASE ? AS ' + alias, (path,))
                    databases[path] = alias
                db.execute('INSERT OR IGNORE INTO ' + alias + '.v1_admission VALUES (?)', (sid,))
                db.execute("INSERT INTO " + alias + ".messages(key,sid,text,queued_at,state,scan,recovery_scan,origin,source_inode,receipt,created) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(key) DO UPDATE SET state=excluded.state,receipt=excluded.receipt,recovery_scan=COALESCE(recovery_scan,excluded.recovery_scan) WHERE sid=excluded.sid", (token, sid, message['text'], stamp, state, origin, origin, origin, inode, marker, int(message['created'] * 1000000000)))
    if not emit:
        return db.execute('SELECT seq FROM message_chats WHERE provider=? AND sid=?', (provider, sid)).fetchone()[0]
    db.execute("UPDATE message_chats SET seq=seq+1 WHERE provider=? AND sid=?", (provider, sid))
    seq = db.execute("SELECT seq FROM message_chats WHERE provider=? AND sid=?", (provider, sid)).fetchone()[0]
    db.execute("INSERT INTO message_events VALUES (?,?,?,?)", (provider, sid, seq, json.dumps(message, separators=(',', ':'))))
    return seq


def message_events_rows(db, provider, sid, after):
    events, size = [], 0
    for seq, body in db.execute('SELECT seq,body FROM message_events WHERE provider=? AND sid=? AND seq>? ORDER BY seq LIMIT ?',
                                (provider, sid, after, HOST_EVENTS_PAGE)):
        if events and size + len(body.encode()) > HOST_EVENTS_BYTES:
            break
        events.append(dict(seq=seq, event=json.loads(body)))
        size += len(body.encode())
    return dict(events=events, next_after=events[-1]['seq'] if events else after)


def message_events_page(provider, sid, after):
    from contextlib import closing
    path = message_store_path()
    if not path.is_file():
        return dict(events=[], next_after=after)
    try:
        with closing(sqlite3.connect(path, timeout=2)) as db:
            return message_events_rows(db, provider, sid, after)
    except sqlite3.Error:
        return dict(events=[], next_after=after)


def message_row(row):
    return dict(zip(('id', 'provider', 'sid', 'text', 'position', 'attempt', 'state', 'deadline', 'receipt', 'epoch', 'created'), row))


def message_expire(db, provider, sid):
    def idle(rows):
        if rows and provider in HOST_PROVIDERS and host_owned(provider, sid):
            state = host_session_state(provider, sid)
            inflight = state.get('inflight')
            current = {token for token in state.get('sends') or [] if isinstance(token, str)}
            if isinstance(inflight, dict):
                current.add(inflight.get('id'))
            return [row for row in rows if row[0] not in current]
        return rows
    now = time.time()
    for row in idle(db.execute("SELECT * FROM shared_messages WHERE provider=? AND sid=? AND state IN ('queued','sent') AND deadline IS NOT NULL AND deadline<=? ORDER BY position", (provider, sid, now)).fetchall()):
        db.execute("UPDATE shared_messages SET state='failed',deadline=NULL WHERE id=? AND state IN ('queued','sent')", (row[0],))
        message_event(db, provider, sid, message_row(db.execute("SELECT * FROM shared_messages WHERE id=?", (row[0],)).fetchone()))
    for row in idle(db.execute("SELECT * FROM shared_messages WHERE provider=? AND sid=? AND state IN ('queued','sent') AND created<=? ORDER BY position", (provider, sid, now - 600)).fetchall()):
        db.execute("UPDATE shared_messages SET state='failed',deadline=NULL,receipt='waiting_too_long' WHERE id=? AND state IN ('queued','sent')", (row[0],))
        message_event(db, provider, sid, message_row(db.execute("SELECT * FROM shared_messages WHERE id=?", (row[0],)).fetchone()))


def codex_attempt_claim(tmux, key):
    from contextlib import closing
    fields = (key.get('provider'), key.get('sid'), key.get('id'), key.get('attempt'), key.get('epoch'), key.get('text_sha256'))
    provider, sid, token, attempt, epoch, digest = fields
    if provider != 'codex' or not all(isinstance(v, str) and v for v in (sid, token, attempt, digest)) or type(epoch) is not int:
        raise ValueError('invalid attempt')
    with closing(message_store_db(tmux)) as db, db:
        db.execute('BEGIN IMMEDIATE')
        existing = db.execute('SELECT epoch,text_sha256,outcome FROM message_attempts WHERE provider=? AND sid=? AND id=? AND attempt=?', fields[:4]).fetchone()
        if existing:
            return dict(status=existing[2] if existing[:2] == (epoch, digest) and existing[2] == 'accepted' else 'unavailable')
        row = db.execute('SELECT text,state,epoch,attempt FROM shared_messages WHERE provider=? AND sid=? AND id=?', (provider, sid, token)).fetchone()
        current = db.execute('SELECT epoch FROM message_chats WHERE provider=? AND sid=?', (provider, sid)).fetchone()
        if not row or not current or current[0] != epoch or row[1:] != ('sent', epoch, attempt) or hashlib.sha256(row[0].encode()).hexdigest() != digest:
            return dict(status='unavailable')
        db.execute("INSERT INTO message_attempts(provider,sid,id,attempt,epoch,text_sha256,outcome) VALUES (?,?,?,?,?,?,'claimed')", fields)
        return dict(status='claimed')


def codex_attempt_finish(tmux, key, outcome):
    from contextlib import closing
    if outcome not in ('accepted', 'uncertain', 'not_sent'):
        raise ValueError('invalid outcome')
    fields = (key.get('provider'), key.get('sid'), key.get('id'), key.get('attempt'), key.get('epoch'), key.get('text_sha256'))
    with closing(message_store_db(tmux)) as db, db:
        db.execute('BEGIN IMMEDIATE')
        db.execute("UPDATE message_attempts SET outcome=? WHERE provider=? AND sid=? AND id=? AND attempt=? AND epoch=? AND text_sha256=? AND outcome='claimed'", (outcome, *fields))


def attempt_prepare(tmux, provider, sid, key, binding):
    from contextlib import closing
    fields = tuple(key.get(k) for k in ('provider', 'sid', 'id', 'attempt', 'epoch', 'text_sha256'))
    if fields[:2] != (provider, sid) or not all(isinstance(v, str) and v for v in (fields[2], fields[3], fields[5])) or type(fields[4]) is not int or not isinstance(binding, dict):
        raise ValueError('invalid attempt')
    encoded = json.dumps(binding, separators=(',', ':'), sort_keys=True)
    with closing(message_store_db(tmux)) as db, db:
        db.execute('BEGIN IMMEDIATE')
        previous = db.execute('SELECT epoch,text_sha256,binding,outcome FROM message_attempts WHERE provider=? AND sid=? AND id=? AND attempt=?', fields[:4]).fetchone()
        if previous:
            if previous[:2] != (fields[4], fields[5]):
                return dict(status='unavailable')
            return dict(status='existing', binding=json.loads(previous[2]), outcome=previous[3])
        row = db.execute('SELECT text,state,epoch,attempt FROM shared_messages WHERE provider=? AND sid=? AND id=?', fields[:3]).fetchone()
        chat = db.execute('SELECT epoch FROM message_chats WHERE provider=? AND sid=?', fields[:2]).fetchone()
        if not row or not chat or chat[0] != fields[4] or row[1:] != ('sent', fields[4], fields[3]) or hashlib.sha256(row[0].encode()).hexdigest() != fields[5]:
            return dict(status='unavailable')
        db.execute("INSERT INTO message_attempts VALUES (?,?,?,?,?,?,'claimed',?)", (*fields, encoded))
        return dict(status='claimed', binding=binding, outcome='claimed')


def attempt_finish(tmux, provider, sid, key, outcome):
    from contextlib import closing
    if outcome not in ('sent', 'uncertain', 'not_sent', 'accepted') or (key.get('provider'), key.get('sid')) != (provider, sid):
        raise ValueError('invalid attempt outcome')
    fields = tuple(key.get(k) for k in ('provider', 'sid', 'id', 'attempt', 'epoch', 'text_sha256'))
    with closing(message_store_db(tmux)) as db, db:
        db.execute('BEGIN IMMEDIATE')
        db.execute("UPDATE message_attempts SET outcome=? WHERE provider=? AND sid=? AND id=? AND attempt=? AND epoch=? AND text_sha256=? AND outcome='claimed'", (outcome, *fields))


def attempt_bindings(tmux, provider, sid):
    from contextlib import closing
    with closing(message_store_db(tmux)) as db:
        rows = db.execute("SELECT a.provider,a.sid,a.id,a.attempt,a.epoch,a.text_sha256,a.binding,a.outcome FROM message_attempts a JOIN message_chats c ON c.provider=a.provider AND c.sid=a.sid LEFT JOIN shared_messages m ON m.id=a.id AND m.provider=a.provider AND m.sid=a.sid WHERE a.provider=? AND a.sid=? AND (a.epoch=c.epoch OR (m.state='accepted' AND m.attempt=a.attempt)) ORDER BY a.rowid", (provider, sid)).fetchall()
        return [dict(key=dict(zip(('provider','sid','id','attempt','epoch','text_sha256'), row[:6])), binding=json.loads(row[6]), outcome=row[7]) for row in rows]


def message_services(tmux, provider, sid):
    return dict(resolve_tmux_agent=resolve_tmux_agent, send_response=send_response_preserve_draft,
                send_response_preserve_draft=send_response_preserve_draft, box_draft=box_draft,
                load_line=load_line, conversation_rows=conversation_rows,
                attempt_prepare=lambda key, binding: attempt_prepare(tmux, provider, sid, key, binding),
                attempt_finish=lambda key, outcome: attempt_finish(tmux, provider, sid, key, outcome),
                attempt_bindings=lambda: attempt_bindings(tmux, provider, sid),
                codex_attempt_claim=lambda key: codex_attempt_claim(tmux, key),
                codex_attempt_finish=lambda key, outcome: codex_attempt_finish(tmux, key, outcome))


def message_connector(provider):
    sources = globals().get('CONNECTOR_SOURCES', {})
    encoded = sources.get(provider) if isinstance(sources, dict) else None
    if not isinstance(encoded, str):
        return None
    namespace = {'__name__': 'multiprompt_connector_' + provider}
    exec(compile(base64.b64decode(encoded, validate=True), '<chat-connector>', 'exec'), namespace)
    return namespace


def message_apply_connector(db, provider, sid, epoch, result):
    if not isinstance(result, dict) or not isinstance(result.get('state'), dict) or not isinstance(result.get('facts'), list):
        raise ValueError('invalid connector result')
    db.execute('INSERT OR REPLACE INTO connector_states VALUES (?,?,?)', (provider, sid, json.dumps(result['state'], separators=(',', ':'))))
    for fact in result['facts']:
        if not isinstance(fact, dict) or fact.get('sid') != sid or not isinstance(fact.get('event_id'), str) or fact.get('kind') not in ('accepted', 'started', 'message', 'not_sent'):
            continue
        if fact['kind'] == 'message' and isinstance(fact.get('row'), dict):
            fact = dict(fact, row=dict(fact['row'], offset=fact.get('offset'), end=fact.get('end')))
        body = json.dumps(fact, separators=(',', ':'))
        if not db.execute('INSERT OR IGNORE INTO connector_facts VALUES (?,?,?,?)', (provider, sid, fact['event_id'], body)).rowcount:
            continue
        kind, token, attempt = fact['kind'], fact.get('id'), fact.get('attempt')
        row = db.execute('SELECT * FROM shared_messages WHERE id=? AND provider=? AND sid=?', (token, provider, sid)).fetchone()
        if kind == 'message':
            message_event(db, provider, sid, dict(native_message=fact))
        elif row and row[9] == epoch and kind == 'accepted' and row[6] == 'queued' and row[5] != attempt and db.execute('SELECT 1 FROM message_attempt_history WHERE id=? AND attempt=? AND epoch=?', (token, attempt, epoch)).fetchone():
            db.execute("UPDATE shared_messages SET attempt=?,state='accepted',deadline=NULL,receipt=? WHERE id=? AND epoch=? AND state='queued'", (attempt, body, token, epoch))
            message_event(db, provider, sid, message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (token,)).fetchone()))
        elif row and row[9] == epoch and kind == 'accepted' and row[5] != attempt and db.execute('SELECT 1 FROM message_attempt_history WHERE id=? AND attempt=? AND epoch=?', (token, attempt, epoch)).fetchone():
            message_event(db, provider, sid, dict(attempt_receipt=fact))
        elif row and row[9] == epoch and row[5] == attempt:
            state = row[6]
            target = ('accepted' if kind == 'accepted' and state in ('sent', 'failed') else
                      'started' if kind == 'started' and state == 'accepted' else
                      'failed' if kind == 'not_sent' and state == 'sent' else None)
            if target:
                db.execute('UPDATE shared_messages SET state=?,deadline=NULL,receipt=? WHERE id=? AND epoch=? AND attempt=?', (target, body, token, epoch, attempt))
                message_event(db, provider, sid, message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (token,)).fetchone()))


def legacy_wire_state(state):
    return {'queued': 'queued', 'sent': 'submitted', 'accepted': 'delivered', 'started': 'delivered', 'failed': 'cancelled', 'cancelled': 'cancelled'}[state]


def legacy_message_id(token):
    return 'legacy-' + hashlib.sha256(token.encode('utf-8')).hexdigest()


def import_legacy_messages(db, sid, transcript, epoch):
    import uuid
    rows = db.execute("SELECT key,text,queued_at,state,origin,source_inode,created,receipt FROM legacy.messages WHERE sid=? AND receipt NOT LIKE '@mp-shared-v1:%' ORDER BY created", (sid,)).fetchall()
    legacy_path = next(path for _, name, path in db.execute('PRAGMA database_list') if name == 'legacy')
    for token, text, queued_at, state, origin, inode, created, receipt in rows:
        identity = legacy_message_id(token)
        existing = db.execute('SELECT sid,id FROM legacy_bindings WHERE token=?', (token,)).fetchone()
        if existing and existing != (sid, identity):
            continue
        if not existing:
            db.execute('INSERT INTO legacy_bindings VALUES (?,?,?,?,?,?)', (token, identity, sid, queued_at, origin, inode))
        db.execute('INSERT OR IGNORE INTO legacy_locations VALUES (?,?)', (token, legacy_path))
        if db.execute('SELECT 1 FROM shared_messages WHERE id=?', (identity,)).fetchone():
            continue
        verified = None
        if state == 'delivered':
            claims = db.execute('SELECT inode,offset,digest FROM legacy.held_receipts WHERE sid=? AND key=? LIMIT 2', (sid, token)).fetchall()
            if len(claims) == 1:
                try:
                    with Path(transcript).open('rb') as source:
                        verified = _consumed_identity(source, sid, *claims[0], text)
                except OSError:
                    pass
        imported_state = 'accepted' if verified else 'cancelled' if state == 'cancelled' else 'failed'
        detail = json.dumps(dict(kind='legacy_verified_claim', inode=verified[1], offset=verified[2], digest=verified[3])) if verified else 'legacy_delivery_unconfirmed'
        position = db.execute('SELECT COALESCE(MAX(position),0)+1 FROM shared_messages WHERE provider=? AND sid=?', ('claude', sid)).fetchone()[0]
        db.execute('INSERT INTO shared_messages VALUES (?,?,?,?,?,?,?,?,?,?,?)', (identity, 'claude', sid, text, position, uuid.uuid4().hex, imported_state, None, detail, epoch, created / 1000000000))
        message_event(db, 'claude', sid, message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (identity,)).fetchone()))
    db.execute("UPDATE legacy.messages SET state='cancelled' WHERE sid=? AND state IN ('queued','submitted') AND receipt NOT LIKE '@mp-shared-v1:%'", (sid,))
    db.execute('INSERT OR IGNORE INTO legacy.v1_admission VALUES (?)', (sid,))


HOST_PROVIDERS = ('claude', 'codex', 'pi', 'hax')
MESSAGE_WORKER_IDLE_SECONDS = 120
MESSAGE_WORKER_STALL_SECONDS = 90
HOST_BINARY_NAMES = {'claude': 'claude', 'codex': 'codex', 'pi': 'pi', 'hax': 'hax'}
HOST_BINARY_FOLDERS = ('~/.local/bin', '~/.claude/local', '~/.npm-global/bin', '~/.bun/bin')
HOST_FOLDER = re.compile(r"[A-Za-z0-9_./~+-]{1,220}\Z")
HOST_USER_HOMES = Path("/home")
HOST_PERMISSION_MODES = ('acceptEdits', 'auto', 'bypassPermissions', 'manual', 'dontAsk', 'plan')
HOST_IDLE_SECONDS = 300
HOST_DELIVERY_TIMEOUT = 15
HOST_STOP_GRACE = 5.0
HOST_TERMINAL_TIMEOUT = 20
HOST_LINE_LIMIT = 4 * 1024 * 1024
HOST_LOG_TAIL = 6
HOST_EVENT_VERSION = 1
HOST_COALESCE_SECONDS = 0.05
HOST_EVENT_TEXT_LIMIT = 8192
HOST_EVENTS_PAGE = 256
HOST_EVENTS_BYTES = 512 * 1024
HOST_PLAN_STOP_MESSAGE = ("The client captured your proposed plan. Stop here and wait for the user's feedback "
                          "or implementation request in a later turn.")
HOST_APPEND_PROMPT = ("You run inside Multibot live chat, not a plain terminal.\n"
                      "Browser pane: use `zsb --help`.\n"
                      "Computer view is view-only unless the user grants control. The browser does not operate Computer.\n"
                      "When a missing capability blocks you, run\n"
                      "`multibot-feedback \"<what you want>\" --why \"<what it blocks>\"` once, then continue.\n"
                      "Saved locally; an authorized agent token also submits or votes on the board.")
HOST_RUNTIME_TOOLS = '.multiprompt-runtime'
HOST_PI_MODEL = ('--provider', 'opencode-go', '--model', 'deepseek-v4.1-flash')
HOST_CODEX_SECRETS = ('OPENAI_API_KEY', 'CODEX_API_KEY', 'OPENAI_BASE_URL', 'OPENAI_ORG_ID', 'OPENAI_PROJECT_ID')
HOST_CODEX_POLICIES = {'bypassPermissions': ('never', 'danger-full-access'), 'dontAsk': ('never', 'danger-full-access'),
                       'acceptEdits': ('on-request', 'workspace-write'), 'auto': ('on-request', 'workspace-write'),
                       'default': ('on-request', 'workspace-write'), 'manual': ('untrusted', 'workspace-write'),
                       'plan': ('untrusted', 'read-only')}
HOST_CODEX_APPROVALS = ('item/commandExecution/requestApproval', 'item/fileChange/requestApproval',
                        'execCommandApproval', 'applyPatchApproval')
HOST_CODEX_LEGACY = {'accept': 'approved', 'acceptForSession': 'approved_for_session', 'decline': 'denied', 'cancel': 'abort'}
HOST_PI_DIALOGS = ('select', 'confirm', 'input', 'editor')
HOST_HAX_MODEL = ('--provider=codex', '--model=gpt-6.1-sol', '--effort=medium')
HOST_HAX_BRIDGE = (
    "import json,os,signal,subprocess,sys,threading\n"
    "hax=sys.argv[1:]\n"
    "resume=None\n"
    "if '--hax-resume' in hax:\n"
    "    i=hax.index('--hax-resume');resume=hax[i+1];del hax[i:i+2]\n"
    "child=None\n"
    "active=False\n"
    "out=threading.Lock()\n"
    "def emit(o):\n"
    "    global active\n"
    "    with out:\n"
    "        if o.get('type')=='bridge_end':active=False\n"
    "        sys.stdout.write(json.dumps(o,separators=(',',':'))+'\\n');sys.stdout.flush()\n"
    "def run(token,text):\n"
    "    global child,resume\n"
    "    outcome,code='error',None\n"
    "    try:\n"
    "        p=subprocess.Popen(hax+['--json']+(['--resume='+resume] if resume else []),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,start_new_session=True)\n"
    "    except OSError:\n"
    "        emit(dict(type='bridge_end',id=token,outcome='spawn_failed',session=resume));return\n"
    "    child=p\n"
    "    emit(dict(type='bridge_start',id=token))\n"
    "    try:\n"
    "        p.stdin.write(text.encode('utf-8'));p.stdin.close()\n"
    "    except OSError:\n"
    "        pass\n"
    "    for line in p.stdout:\n"
    "        try:\n"
    "            record=json.loads(line)\n"
    "        except ValueError:\n"
    "            continue\n"
    "        if not isinstance(record,dict):\n"
    "            continue\n"
    "        if record.get('type')=='session' and isinstance(record.get('id'),str):\n"
    "            resume=record['id']\n"
    "        if record.get('type')=='result':\n"
    "            outcome=record.get('outcome') or 'complete'\n"
    "            resume=record.get('session_id') or resume\n"
    "        emit(dict(type='hax',id=token,record=record))\n"
    "    code=p.wait();child=None\n"
    "    emit(dict(type='bridge_end',id=token,outcome=outcome,exit=code,session=resume))\n"
    "worker=None\n"
    "for line in sys.stdin:\n"
    "    try:\n"
    "        message=json.loads(line)\n"
    "    except ValueError:\n"
    "        continue\n"
    "    if not isinstance(message,dict):\n"
    "        continue\n"
    "    if message.get('type')=='prompt' and isinstance(message.get('id'),str) and isinstance(message.get('message'),str):\n"
    "        if active:\n"
    "            emit(dict(type='bridge_busy',id=message['id']));continue\n"
    "        active=True\n"
    "        worker=threading.Thread(target=run,args=(message['id'],message['message']),daemon=True);worker.start()\n"
    "    elif message.get('type')=='abort' and child is not None:\n"
    "        try:\n"
    "            os.killpg(child.pid,signal.SIGINT)\n"
    "        except OSError:\n"
    "            pass\n"
    "if worker is not None:\n"
    "    worker.join()\n"
)
HOST_CODEX_SANDBOXES = {'danger-full-access': {'type': 'dangerFullAccess'},
                        'read-only': {'type': 'readOnly', 'networkAccess': False},
                        'workspace-write': {'type': 'workspaceWrite', 'writableRoots': [], 'networkAccess': False,
                                            'excludeTmpdirEnvVar': False, 'excludeSlashTmp': False}}
HOST_BACKOFF_STEPS = (0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 30.0)
HOST_EXIT_REASONS = {1: "owner_busy", 2: "unsupported_provider", 3: "host_disabled", 4: "host_error", 5: "agent_not_found"}
HOST_CHILDREN = []


def host_key(provider, sid):
    return hashlib.sha256((provider + ":" + sid).encode()).hexdigest()[:32]


def host_socket_root():
    import tempfile
    root = Path(tempfile.gettempdir()) / ("mp-agent-host-" + str(getattr(os, "getuid", lambda: 0)()))
    if root.is_dir():
        root.chmod(0o700)
    else:
        root.mkdir(mode=0o700)
    return root


def host_paths(provider, sid):
    root = HOME / ".local/state/multiprompt/agent-host"
    if root.is_dir():
        root.chmod(0o700)
    else:
        root.mkdir(mode=0o700, parents=True)
    key = host_key(provider, sid)
    return dict(root=root, key=key, config=root / (key + ".config.json"), lock=root / (key + ".owner.lock"),
                owner=root / (key + ".owner.json"), socket=host_socket_root() / (key + ".sock"),
                state=root / (key + ".state.json"), backoff=root / (key + ".backoff"),
                log=root / (key + ".events.log"), errors=root / (key + ".stderr.log"))


def host_identity(provider, sid):
    return (isinstance(provider, str) and provider in HOST_PROVIDERS and isinstance(sid, str)
            and SESSION_ID.fullmatch(sid) is not None)


def host_read(path):
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else None
    except (OSError, ValueError):
        return None


def host_write(path, value):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_name("." + path.name + "." + str(os.getpid()) + ".tmp")
    handle = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            stream.write(json.dumps(value, separators=(",", ":"), sort_keys=True))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    finally:
        try:
            temporary.unlink()
        except OSError:
            pass


def host_config(provider, sid):
    if not host_identity(provider, sid):
        return None
    config = host_read(host_paths(provider, sid)["config"])
    return config if isinstance(config, dict) and config.get("enabled") is True else None


def host_enabled(provider, sid):
    return host_config(provider, sid) is not None


def host_binary_name(provider):
    return HOST_BINARY_NAMES.get(provider, provider if isinstance(provider, str) else "")


def host_agent_binary(name):
    if not name:
        return None
    found = shutil.which(name)
    if found:
        return found
    for folder in HOST_BINARY_FOLDERS:
        try:
            candidate = Path(os.path.expanduser(folder)) / name
            if candidate.is_file() and os.access(str(candidate), os.X_OK):
                return str(candidate)
        except OSError:
            continue
    return None


def host_command(provider, sid):
    config = host_config(provider, sid) or {}
    command = config.get("command")
    parts = command if isinstance(command, list) and command and all(isinstance(part, str) and part for part in command) else None
    if parts is None:
        resolved = host_agent_binary(host_binary_name(provider))
        if resolved is None:
            return None
        parts = [resolved]
    extra = config.get("args")
    return parts + (extra if isinstance(extra, list) and all(isinstance(part, str) for part in extra) else [])


def host_folder(value):
    if not isinstance(value, str) or not value or len(value) > 220 or HOST_FOLDER.fullmatch(value) is None:
        return None
    expanded = os.path.expanduser(value)
    return host_owner_folder(expanded) if os.path.isabs(expanded) else None


def host_owner_folder(folder):
    if os.path.isdir(folder):
        return folder
    try:
        relative = Path(folder).relative_to(HOME)
    except ValueError:
        return folder
    if not relative.parts or ".." in relative.parts:
        return folder
    try:
        names = sorted(os.listdir(HOST_USER_HOMES))
    except OSError:
        return folder
    found = []
    for name in names:
        if name == HOME.name:
            continue
        owner = conversation_owner(str(HOST_USER_HOMES / name / relative), include_home=True)
        if owner is None:
            continue
        candidate = str(owner[1] / relative)
        try:
            probe = subprocess.run(["sudo", "-n", "-u", owner[0], "--", "test", "-d", candidate],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
        except (OSError, subprocess.SubprocessError):
            continue
        if probe.returncode == 0:
            found.append(candidate)
    if len(found) == 1:
        return found[0]
    used = []
    for path in (HOME / ".local/state/multiprompt/agent-host").glob("*.config.json") if found else ():
        config = host_read(path)
        if isinstance(config, dict) and config.get("cwd") in found:
            try:
                used.append((path.stat().st_mtime, config["cwd"]))
            except OSError:
                pass
    return max(used)[1] if used else folder


def host_cwd(provider, sid):
    config = host_config(provider, sid) or {}
    return host_folder(config.get("cwd")) or str(HOME)


def host_located_chat(provider, sid):
    import sqlite3
    from contextlib import closing
    path = message_store_path()
    if not path.is_file():
        return None
    try:
        with closing(sqlite3.connect(path)) as db:
            row = db.execute("SELECT tmux FROM message_locators WHERE provider=? AND sid=?", (provider, sid)).fetchone()
    except sqlite3.Error:
        return None
    return row[0] if row else None


def host_chat_name(provider, sid):
    config = host_config(provider, sid) or {}
    tmux = config.get("tmux")
    if isinstance(tmux, str) and TMUX_NAME.fullmatch(tmux):
        return tmux
    located = host_located_chat(provider, sid)
    return located if isinstance(located, str) and TMUX_NAME.fullmatch(located) else None


def host_pane_option(tmux, value):
    if not isinstance(tmux, str) or TMUX_NAME.fullmatch(tmux) is None:
        return False
    command = ['tmux', 'set-option', '-t', '=' + tmux + ':']
    command += ['-u', '@mp_host_sid'] if value is None else ['@mp_host_sid', value]
    try:
        subprocess.run(command, capture_output=True, timeout=5, check=True)
    except (OSError, subprocess.SubprocessError):
        return False
    return True


def host_mark_pane(provider, sid):
    return host_pane_option(host_chat_name(provider, sid), sid)


def host_clear_pane(provider, sid):
    return host_pane_option(host_chat_name(provider, sid), None)


def host_lock(provider, sid, wait=False):
    import fcntl
    handle = host_paths(provider, sid)["lock"].open("a")
    try:
        fcntl.flock(handle, fcntl.LOCK_EX if wait else fcntl.LOCK_EX | fcntl.LOCK_NB)
        return handle
    except BlockingIOError:
        handle.close()
        return None
    except OSError:
        handle.close()
        raise


def host_release(handle):
    if handle is None:
        return
    import fcntl
    try:
        fcntl.flock(handle, fcntl.LOCK_UN)
    finally:
        handle.close()


def host_owner(provider, sid):
    owner = host_read(host_paths(provider, sid)["owner"])
    if not isinstance(owner, dict) or owner.get("owner") not in ("host", "terminal"):
        return None
    if owner.get("owner") == "host" and (type(owner.get("pid")) is not int or owner["pid"] <= 0):
        return None
    return owner


def host_alive(provider, sid):
    owner = host_owner(provider, sid)
    if not owner:
        return False
    if owner["owner"] != "host":
        return True
    try:
        os.kill(owner["pid"], 0)
        return True
    except OSError:
        return False


def host_owned(provider, sid):
    owner = host_owner(provider, sid)
    return bool(owner) and owner["owner"] == "host" and host_alive(provider, sid)


def host_pane_claimed(tmux):
    try:
        configs = list((HOME / ".local/state/multiprompt/agent-host").glob("*.config.json"))
    except OSError:
        return False
    for path in configs:
        config = host_read(path)
        if isinstance(config, dict) and config.get("enabled") is True and config.get("tmux") == tmux:
            return True
    return False


def host_sid_for_tmux(provider, tmux):
    try:
        configs = list((HOME / ".local/state/multiprompt/agent-host").glob("*.config.json"))
    except OSError:
        return None
    found = set()
    for path in configs:
        config = host_read(path)
        if not isinstance(config, dict) or config.get("enabled") is not True or config.get("tmux") != tmux:
            continue
        state = host_read(path.with_name(path.name[:-len(".config.json")] + ".state.json")) or {}
        sid = state.get("sid")
        if state.get("provider") == provider and host_identity(provider, sid) and host_key(provider, sid) + ".config.json" == path.name:
            found.add(sid)
    return found.pop() if len(found) == 1 else None


def host_handed_over(provider, sid):
    owner = host_owner(provider, sid)
    return bool(owner) and owner["owner"] == "terminal"


def host_clear_stale(provider, sid):
    if host_alive(provider, sid):
        return
    paths = host_paths(provider, sid)
    for name in ("socket", "owner"):
        try:
            paths[name].unlink()
        except OSError:
            pass


def host_log(provider, sid, event, detail=None):
    line = json.dumps(dict(at=round(time.time(), 3), event=event, detail=detail), separators=(",", ":")) + "\n"
    try:
        with host_paths(provider, sid)["log"].open("a", encoding="utf-8") as stream:
            stream.write(line)
    except OSError:
        pass


def host_log_tail(provider, sid, limit=HOST_LOG_TAIL):
    try:
        lines = host_paths(provider, sid)["log"].read_text(encoding="utf-8").strip().splitlines()[-limit:]
    except (OSError, UnicodeError):
        return []
    events = []
    for line in lines:
        try:
            value = json.loads(line)
        except ValueError:
            continue
        if isinstance(value, dict):
            events.append(value)
    return events


def host_backoff(provider, sid):
    saved = host_read(host_paths(provider, sid)["backoff"])
    if not isinstance(saved, dict) or type(saved.get("until")) not in (int, float):
        return 0.0
    return max(0.0, saved["until"] - time.time())


def host_backoff_fail(provider, sid):
    saved = host_read(host_paths(provider, sid)["backoff"]) or {}
    previous = saved.get("delay") if type(saved.get("delay")) in (int, float) else 0.0
    delay = next((step for step in HOST_BACKOFF_STEPS if step > previous), HOST_BACKOFF_STEPS[-1])
    host_write(host_paths(provider, sid)["backoff"], dict(delay=delay, until=time.time() + delay))
    return delay


def host_backoff_clear(provider, sid):
    try:
        host_paths(provider, sid)["backoff"].unlink()
    except OSError:
        pass


def host_request(provider, sid, request, timeout=HOST_DELIVERY_TIMEOUT):
    import socket as module
    client = module.socket(module.AF_UNIX, module.SOCK_STREAM)
    client.settimeout(timeout)
    try:
        client.connect(str(host_paths(provider, sid)["socket"]))
        client.sendall((json.dumps(request, separators=(",", ":")) + "\n").encode("utf-8"))
        buffer = b""
        while b"\n" not in buffer and len(buffer) <= 65536:
            chunk = client.recv(65536)
            if not chunk:
                break
            buffer += chunk
        if not buffer.strip():
            return dict(status="unavailable", reason="host_closed")
        reply = json.loads(buffer.split(b"\n", 1)[0].decode("utf-8"))
        return reply if isinstance(reply, dict) else dict(status="error", reason="invalid_reply")
    except (OSError, ValueError, UnicodeError):
        return dict(status="unavailable", reason="host_unreachable")
    finally:
        client.close()


def host_reply(query):
    provider, sid = query.get("provider"), query.get("sid")
    if not host_identity(provider, sid):
        return dict(status="refused", reason="unsupported_provider")
    if query.get("op") in ("set_model", "set_effort") and provider != "claude":
        return dict(status="refused", reason="unsupported_provider")
    if not host_owned(provider, sid):
        operation = query.get("op")
        if operation not in ("set_model", "set_effort"):
            return dict(status="unavailable", reason="host_not_running")
        key = "model" if operation == "set_model" else "effort"
        allowed = ("opus", "sonnet", "haiku") if key == "model" else ("low", "medium", "high")
        value = query.get(key)
        if value not in allowed:
            return dict(status="refused", reason="invalid_" + key)
        lock = host_lock(provider, sid)
        if lock is None:
            return dict(status="unavailable", reason="host_starting")
        try:
            if host_handed_over(provider, sid):
                return dict(status="refused", reason="host_handed_over")
            config = host_config(provider, sid)
            if config is None:
                return dict(status="refused", reason="host_disabled")
            config[key] = value
            host_write(host_paths(provider, sid)["config"], config)
            settings = host_settings(provider, sid)
            host_event(provider, sid, host_chat_name(provider, sid), dict(state={}),
                       "host.settings", dict(settings, applied=False, mode=host_permission_mode(provider, sid)))
            return dict(status="ok")
        finally:
            host_release(lock)
    request = {key: query[key] for key in ("op", "request_id", "decision", "answers", "dismissed",
                                           "action", "feedback", "input", "mode", "model", "effort") if key in query}
    reply = host_request(provider, sid, request)
    return reply if isinstance(reply, dict) else dict(status="error", reason="invalid_reply")


def host_runtime_file():
    name = globals().get("__file__") or (sys.argv[0] if sys.argv else "")
    if not name or name.startswith("-"):
        return None
    try:
        path = Path(name).resolve()
    except (OSError, ValueError):
        return None
    return str(path) if path.is_file() else None


def host_reader_source():
    source = globals().get("READER_SOURCE")
    if isinstance(source, str) and source:
        return source
    standalone = host_runtime_file()
    if standalone is None:
        return None
    try:
        return Path(standalone).read_text(encoding="utf-8") or None
    except (OSError, UnicodeError):
        return None


def host_spawn(provider, sid, source=None):
    bootstrap = ("import base64,sys;s=base64.b64decode(sys.stdin.buffer.readline()).decode();"
                 "exec(compile(s,'<agent-host>','exec'),dict(__name__='__main__',READER_SOURCE=s))")
    source = source if isinstance(source, str) and source else globals().get("READER_SOURCE")
    standalone = host_runtime_file() if not isinstance(source, str) or not source else None
    if standalone is None and not isinstance(source, str):
        return None
    errors = host_paths(provider, sid)["errors"].open("ab")
    try:
        if standalone is not None:
            child = subprocess.Popen([sys.executable, "-u", standalone, "--agent-host", provider, sid],
                                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=errors,
                                     start_new_session=True)
        else:
            child = subprocess.Popen([sys.executable, "-u", "-c", bootstrap, "--agent-host", provider, sid],
                                     stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=errors,
                                     start_new_session=True)
    finally:
        errors.close()
    if standalone is None:
        try:
            child.stdin.write(base64.b64encode(source.encode("utf-8")) + b"\n")
            child.stdin.flush()
        finally:
            child.stdin.close()
    HOST_CHILDREN[:] = [running for running in HOST_CHILDREN if running.poll() is None][-7:] + [child]
    return child


def host_start(provider, sid, source=None):
    if not host_identity(provider, sid):
        return dict(status="refused", reason="unsupported_provider")
    if not host_enabled(provider, sid):
        return dict(status="refused", reason="host_disabled")
    failure = host_session_state(provider, sid).get("startup_failure")
    if host_failure_current(provider, sid, failure, message_retry_generation(provider, sid)):
        return dict(status="error", reason=failure.get("reason"), permanent=True)
    if host_alive(provider, sid) and host_request(provider, sid, dict(op="ping"), timeout=2).get("status") == "ok":
        return dict(status="already", reason="host_running", pid=host_owner(provider, sid)["pid"])
    host_clear_stale(provider, sid)
    child = host_spawn(provider, sid, source)
    if child is None:
        return dict(status="error", reason="host_launch_unavailable")
    deadline = time.time() + 20
    while time.time() < deadline:
        reply = host_request(provider, sid, dict(op="ping"), timeout=2)
        if reply.get("status") == "ok":
            host_log(provider, sid, "externally_started")
            return dict(status="started", pid=reply.get("pid"))
        exited = child.poll()
        if exited is not None:
            host_clear_stale(provider, sid)
            failure = host_session_state(provider, sid).get("startup_failure")
            if host_failure_current(provider, sid, failure, message_retry_generation(provider, sid)):
                return dict(status="error", reason=failure.get("reason"), permanent=True)
            return dict(status="error", reason=HOST_EXIT_REASONS.get(exited, "host_exited"), code=exited)
        time.sleep(0.2)
    return dict(status="error", reason="host_timeout")


def host_stop(provider, sid, force=False):
    if not host_identity(provider, sid):
        return dict(status="refused", reason="unsupported_provider")
    owner = host_owner(provider, sid)
    reply = host_request(provider, sid, dict(op="stop"), timeout=HOST_STOP_GRACE + 2)
    pid = owner["pid"] if isinstance(owner, dict) and owner.get("owner") == "host" else None
    if type(pid) is int and pid > 0 and (reply.get("status") != "ok" or host_alive(provider, sid)):
        for _ in range(int(HOST_STOP_GRACE * 8)):
            if not host_alive(provider, sid):
                break
            time.sleep(0.125)
    if type(pid) is int and pid > 0 and host_alive(provider, sid):
        try:
            os.kill(pid, 9 if force else 15)
        except OSError:
            pid = None
        for _ in range(int(HOST_STOP_GRACE * 8)):
            if not host_alive(provider, sid):
                break
            time.sleep(0.125)
    host_clear_stale(provider, sid)
    host_clear_pane(provider, sid)
    return dict(status="stopped")


def host_handover(provider, sid):
    if provider != "claude":
        return dict(status="refused", reason="terminal_unsupported")
    result = host_stop(provider, sid)
    host_write(host_paths(provider, sid)["owner"], dict(owner="terminal", at=time.time()))
    host_log(provider, sid, "handover", dict(previous=result.get("status")))
    return dict(status="handover", previous=result.get("status"))


def host_pane_agent(tmux, sid):
    try:
        bound = resolve_tmux_agent('claude', tmux, require_bound=True)
    except (OSError, ValueError):
        return False
    return bool(bound) and bound[0] == sid


def host_terminal_command(provider, sid):
    config = host_config(provider, sid) or {}
    parts = [host_binary_name(provider), '--resume', sid]
    mode = config.get('permission_mode')
    if mode in HOST_PERMISSION_MODES:
        parts += ['--permission-mode', mode]
    return ' '.join(parts)


def host_terminal_send(tmux, command):
    try:
        subprocess.run(['tmux', 'send-keys', '-t', '=' + tmux + ':', '-l', '--', command],
                       capture_output=True, timeout=5, check=True)
        subprocess.run(['tmux', 'send-keys', '-t', '=' + tmux + ':', 'Enter'],
                       capture_output=True, timeout=5, check=True)
    except (OSError, subprocess.SubprocessError):
        return False
    return True


def host_undo_typed_line(tmux, command):
    try:
        shown = subprocess.run(['tmux', 'capture-pane', '-p', '-t', '=' + tmux + ':'],
                               capture_output=True, text=True, timeout=5, check=True).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    lines = [line.rstrip() for line in ANSI.sub('', shown).splitlines() if line.strip()]
    if not lines or command not in lines[-1]:
        return True
    try:
        subprocess.run(['tmux', 'send-keys', '-t', '=' + tmux + ':', 'C-u'],
                       capture_output=True, timeout=5, check=True)
    except (OSError, subprocess.SubprocessError):
        return False
    return True


def host_terminal_restore(provider, sid, tmux, command, reason):
    cleared = host_undo_typed_line(tmux, command)
    started = host_start(provider, sid)
    host_log(provider, sid, 'terminal_move_failed', dict(reason=reason, cleared=cleared, start=started.get('status')))
    if started.get('status') not in ('started', 'already'):
        try:
            host_paths(provider, sid)['owner'].unlink()
        except OSError:
            pass
    return dict(status='error', reason=reason, start=started.get('status'))


def host_terminal(provider, sid, tmux=None):
    if not host_identity(provider, sid):
        return dict(status='refused', reason='unsupported_provider')
    if provider != 'claude':
        return dict(status='refused', reason='terminal_unsupported')
    if not host_enabled(provider, sid):
        return dict(status='already', reason='not_hosted')
    if tmux is None:
        tmux = host_chat_name(provider, sid)
    if not isinstance(tmux, str) or TMUX_NAME.fullmatch(tmux) is None:
        return dict(status='refused', reason='no_chat')
    if host_pane_agent(tmux, sid):
        host_stop(provider, sid)
        host_write(host_paths(provider, sid)['owner'], dict(owner='terminal', at=time.time()))
        return dict(status='already', reason='pane_agent')
    if not host_pane_is_shell(tmux) and host_pane_prompt(tmux) is None:
        return dict(status='refused', reason='pane_busy')
    host_stop(provider, sid)
    host_reap_agent(provider, sid)
    if host_alive(provider, sid):
        return dict(status='error', reason='host_still_running')
    host_write(host_paths(provider, sid)['owner'], dict(owner='terminal', at=time.time()))
    host_clear_pane(provider, sid)
    host_log(provider, sid, 'terminal_move')
    command = host_terminal_command(provider, sid)
    if not host_terminal_send(tmux, command):
        return host_terminal_restore(provider, sid, tmux, command, 'pane_input_failed')
    deadline = time.time() + HOST_TERMINAL_TIMEOUT
    while time.time() < deadline:
        if host_pane_agent(tmux, sid):
            return dict(status='moved', command=command)
        time.sleep(0.2)
    return dict(status='error', reason='pane_agent_timeout')


def host_resume(provider, sid, tmux=None, cwd=None, mode=None):
    if not host_identity(provider, sid):
        return dict(status='refused', reason='unsupported_provider')
    if not host_enabled(provider, sid):
        return dict(status='already', reason='not_hosted')
    if host_owned(provider, sid):
        return dict(status='already', reason='host_running')
    if tmux is None:
        tmux = host_chat_name(provider, sid)
    adopted = host_adopt(provider, sid, tmux, cwd, mode)
    started = adopted.get('start')
    if started in ('started', 'already') or adopted.get('status') == 'already':
        return dict(status='moved', start=started or adopted.get('reason'))
    return dict(status='error', reason=adopted.get('reason') or 'host_start_failed')


def host_send(provider, sid, text, tmux=None):
    import uuid
    if not host_identity(provider, sid):
        return dict(status='refused', reason='unsupported_provider')
    if not isinstance(text, str) or not text.strip() or '\x00' in text:
        return dict(status='refused', reason='invalid_text')
    if not host_enabled(provider, sid):
        return dict(status='refused', reason='host_disabled')
    if tmux is None:
        tmux = host_chat_name(provider, sid)
    if not isinstance(tmux, str) or TMUX_NAME.fullmatch(tmux) is None:
        return dict(status='refused', reason='no_chat')
    token = 'mp-' + uuid.uuid4().hex
    store = dict(message_protocol=1, provider=provider, sid=sid, tmux=tmux)
    try:
        submitted = message_store_request(dict(store, op='message_submit', message_id=token, text=text))
        message_store_request(dict(store, op='message_dispatch'))
        message = message_store_request(dict(store, op='message_status', message_id=token)).get('message')
    except (OSError, ValueError, KeyError, TypeError):
        return dict(status='error', reason='message_store_failed', id=token)
    state = message.get('state') if isinstance(message, dict) else (submitted.get('message') or {}).get('state')
    return dict(status='queued' if state in (None, 'queued') else 'sent', id=token, state=state)


def host_deliver(provider, sid, token, attempt, epoch, text, room_scope=None):
    request = dict(op="deliver", id=token, attempt=attempt, epoch=epoch, text=text)
    if room_scope is not None:
        request["room_scope"] = room_scope
    def send():
        if room_scope is not None:
            ping = host_request(provider, sid, dict(op="ping"))
            protocol = ping.get("room_message_protocol")
            if ping.get("status") != "ok" or ping.get("sid") != sid or type(protocol) is not int or protocol != ROOM_MESSAGE_PROTOCOL:
                return dict(status="busy", reason="room_host_upgrade_required")
        return host_request(provider, sid, request)
    reply = send() if host_owned(provider, sid) else dict(status="unavailable")
    if reply.get("status") in ("unavailable", "busy") or reply.get("reason") == "host_unreachable":
        if host_enabled(provider, sid) and host_start(provider, sid).get("status") in ("started", "already"):
            reply = send()
    return reply if isinstance(reply, dict) else dict(status="error", reason="invalid_reply")


def host_apply(provider, sid, tmux, facts):
    from contextlib import closing
    with closing(message_store_db(tmux)) as db, db:
        db.execute("BEGIN IMMEDIATE")
        chat = db.execute("SELECT epoch FROM message_chats WHERE provider=? AND sid=?", (provider, sid)).fetchone()
        if not chat:
            return False
        saved = db.execute("SELECT state FROM connector_states WHERE provider=? AND sid=?", (provider, sid)).fetchone()
        state = json.loads(saved[0]) if saved else {}
        message_apply_connector(db, provider, sid, chat[0], dict(state=state, facts=facts))
    return True


def host_attempt(provider, sid, tmux, token, attempt, epoch, text, binding, outcome=None):
    key = dict(provider=provider, sid=sid, id=token, attempt=attempt, epoch=epoch,
               text_sha256=hashlib.sha256(text.encode("utf-8", "surrogatepass")).hexdigest())
    if outcome is None:
        return attempt_prepare(tmux, provider, sid, key, binding)
    attempt_finish(tmux, provider, sid, key, outcome)
    return None


def host_state_write(provider, sid, state):
    host_write(host_paths(provider, sid)["state"], state)


def host_transcript_exists(provider, sid):
    try:
        return conversation_path(provider, sid, host_cwd(provider, sid)) is not None
    except (OSError, ValueError):
        return False


def host_agent_env(provider, sid):
    config = host_config(provider, sid) or {}
    env = dict(os.environ)
    tools = str(HOME / HOST_RUNTIME_TOOLS)
    path = env.get('PATH', '')
    env['PATH'] = tools + (':' + path if path else '')
    env['MULTIBOT_PROVIDER'] = provider
    env['MULTIBOT_APP_VERSION'] = globals().get('MULTIBOT_APP_VERSION', '')
    if provider != 'claude':
        env['MULTIBOT_HOST'] = host_key(provider, sid)
    if provider == 'codex':
        for key in HOST_CODEX_SECRETS:
            env.pop(key, None)
    if provider == 'claude' and config.get('model') in ('opus', 'sonnet', 'haiku'):
        for key in ('ANTHROPIC_DEFAULT_OPUS_MODEL', 'ANTHROPIC_DEFAULT_SONNET_MODEL', 'ANTHROPIC_DEFAULT_HAIKU_MODEL'):
            env.pop(key, None)
    tmux = config.get('tmux')
    if isinstance(tmux, str) and tmux:
        env['MULTIBOT_SESSION'] = tmux
    name = host_chat_name(provider, sid)
    if name:
        for key in ("TMUX", "TMUX_PANE"):
            env.pop(key, None)
        env.update(ZSB_SESSION=name, MULTIPROMPT_TAB=name)
    return env


def host_claude_argv(provider, sid, base, mode=None):
    mode = mode if mode in HOST_PERMISSION_MODES else (host_config(provider, sid) or {}).get("permission_mode")
    command = base + (["--permission-mode", mode] if mode in HOST_PERMISSION_MODES else [])
    command += ["--print", "--input-format", "stream-json",
                "--output-format", "stream-json", "--verbose",
                "--include-partial-messages", "--permission-prompt-tool", "stdio"]
    command += ["--append-system-prompt", HOST_APPEND_PROMPT]
    return command + (["--resume", sid] if host_transcript_exists(provider, sid) else ["--session-id", sid])


def host_codex_argv(provider, sid, base):
    return base + ["app-server"]


def host_pi_argv(provider, sid, base):
    return base + ["--mode", "rpc", *HOST_PI_MODEL, "--session-id", sid, "--append-system-prompt", HOST_APPEND_PROMPT]


def host_hax_argv(provider, sid, base):
    chosen = any(part.startswith(("--provider", "--model", "--preset")) for part in base[1:])
    native = host_session_state(provider, sid).get("native_session")
    resume = ["--hax-resume", native] if isinstance(native, str) and SESSION_ID.fullmatch(native) else []
    return ["python3", "-u", "-c", HOST_HAX_BRIDGE] + base + ([] if chosen else list(HOST_HAX_MODEL)) + resume


def host_model_name(value):
    if not isinstance(value, str):
        return ""
    match = re.fullmatch(r"(?:claude-)?(opus|sonnet|haiku)(?:-[a-zA-Z0-9._-]+)?(?:\[[a-zA-Z0-9]+\])?", value)
    return match.group(1) if match else ""


HOST_MODE_NAMES = dict(default="manual", manual="manual", acceptEdits="acceptEdits", plan="plan", bypassPermissions="bypassPermissions")


def host_permission_mode(provider, sid, state=None):
    mode = state.get("mode") if isinstance(state, dict) else None
    if mode is None:
        stored = host_read(host_paths(provider, sid)["state"])
        mode = stored.get("mode") if stored else None
    if mode is None:
        mode = (host_config(provider, sid) or {}).get("permission_mode")
    return HOST_MODE_NAMES.get(mode if mode is not None else "default", "")


def host_settings(provider, sid):
    config = host_config(provider, sid) or {}
    args = config.get("args") if isinstance(config.get("args"), list) else []
    command = config.get("command") if isinstance(config.get("command"), list) else []
    parts = command + args
    values = {}
    for index, part in enumerate(parts):
        if part in ("--model", "--effort") and index + 1 < len(parts):
            values[part[2:]] = parts[index + 1]
        elif isinstance(part, str) and (part.startswith("--model=") or part.startswith("--effort=")):
            key, value = part[2:].split("=", 1)
            values[key] = value
    model = config.get("model", values.get("model"))
    effort = config.get("effort", values.get("effort"))
    return dict(model=host_model_name(model),
                effort=effort if effort in ("low", "medium", "high") else "")


def host_settings_event(provider, sid, tmux, session):
    settings = session["settings"]
    applied = session.get("ready", False) and session.get("applied_settings") == settings
    shown = dict(settings)
    if not shown["model"] and applied:
        shown["model"] = session.get("init_model", "")
    host_event(provider, sid, tmux, session, "host.settings", dict(shown, applied=applied, mode=host_permission_mode(provider, sid, session.get("state"))))


def host_agent_start(provider, sid, mode=None):
    cwd = host_cwd(provider, sid)
    owner = conversation_owner(cwd, include_home=True)
    cross_owner = owner is not None and owner[1] != HOME
    if cross_owner:
        config = host_config(provider, sid) or {}
        command = config.get("command")
        base = command if isinstance(command, list) and command and all(isinstance(part, str) and part for part in command) else [host_binary_name(provider)]
        extra = config.get("args")
        base = base + (extra if isinstance(extra, list) and all(isinstance(part, str) for part in extra) else [])
    else:
        base = host_command(provider, sid)
    if base is None:
        return None
    if provider == "claude":
        config = host_config(provider, sid) or {}
        overrides = {key: config[key] for key, values in
                     (("model", ("opus", "sonnet", "haiku")), ("effort", ("low", "medium", "high")))
                     if config.get(key) in values}
        if overrides:
            filtered = base[:1]
            index = 1
            while index < len(base):
                part = base[index]
                key = next((key for key in overrides if part == "--" + key or part.startswith("--" + key + "=")), None)
                if key is None:
                    filtered.append(part)
                elif part == "--" + key and index + 1 < len(base) and not base[index + 1].startswith("--"):
                    index += 1
                index += 1
            base = filtered
        for key, value in overrides.items():
            base += ["--" + key, value]
        command = host_claude_argv(provider, sid, base, mode)
    else:
        command = HOST_ADAPTERS[provider]["argv"](provider, sid, base)
    if cross_owner:
        user, home = owner
        env = {"PATH": "/usr/bin:/bin"}
        prefix = ["sudo", "-n", "-H", "-u", user, "--", "/usr/bin/env", "-i", "HOME=" + str(home),
                  "USER=" + user, "LOGNAME=" + user, "MULTIBOT_PROVIDER=" + provider,
                  "MULTIBOT_APP_VERSION=" + globals().get('MULTIBOT_APP_VERSION', '')]
        tmux = host_chat_name(provider, sid)
        if tmux:
            prefix += ["MULTIBOT_SESSION=" + tmux, "ZSB_SESSION=" + tmux, "MULTIPROMPT_TAB=" + tmux]
        shell = ["/bin/sh", "-l", "-c"]
        folders = ':'.join('$HOME/' + folder[2:] for folder in HOST_BINARY_FOLDERS)
        setup = 'PATH="$HOME/.multiprompt-runtime:' + folders + ':$PATH"; export PATH; '
        if provider == "claude" and config.get("model") in ("opus", "sonnet", "haiku"):
            setup += 'unset ANTHROPIC_DEFAULT_OPUS_MODEL ANTHROPIC_DEFAULT_SONNET_MODEL ANTHROPIC_DEFAULT_HAIKU_MODEL; '
        source = host_reader_source()
        if not source:
            raise RuntimeError("owner_unavailable")
        check = ('import sys;n={"__name__":"host_owner_probe"};'
                 'exec(compile(sys.stdin.read(),"<host-owner>","exec"),n);'
                 'sys.exit(74 if n["conversation_path"](*sys.argv[1:]) is not None else 0)')
        try:
            probe = subprocess.run(prefix + shell + [setup + 'cd "$1" || exit 72; command -v "$2" >/dev/null || exit 73; exec python3 -c "$3" "$4" "$5" "$1"',
                                                     "agent-preflight", cwd, command[0], check, provider, sid],
                                   input=source, text=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   env=env, timeout=10)
        except (OSError, subprocess.SubprocessError):
            raise RuntimeError("owner_unavailable")
        if probe.returncode not in (0, 74):
            raise RuntimeError({72: "cwd_unavailable", 73: "agent_not_found"}.get(probe.returncode, "owner_unavailable"))
        if provider == "claude":
            command[-2:] = ["--resume" if probe.returncode == 74 else "--session-id", sid]
        command = prefix + shell + [setup + 'cd "$1" || exit 72; shift; exec "$@"', "agent-start", cwd] + command
        try:
            return subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                    env=env, start_new_session=True)
        except OSError:
            raise RuntimeError("owner_unavailable")
    return subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            cwd=cwd, env=host_agent_env(provider, sid), start_new_session=True)


def host_agent_recorded_pid(provider, sid):
    state = host_session_state(provider, sid)
    pid = state.get("agent_pid")
    return pid if type(pid) is int and pid > 0 else None


def host_agent_orphan(provider, sid, pid):
    if type(pid) is not int or pid <= 0 or pid == os.getpid():
        return False
    try:
        with open("/proc/" + str(pid) + "/cmdline", "rb") as handle:
            command = handle.read(8192).replace(b"\x00", b" ").decode("utf-8", "replace")
    except OSError:
        return False
    if provider == "claude":
        return sid in command and "--print" in command
    try:
        with open("/proc/" + str(pid) + "/environ", "rb") as handle:
            environ = handle.read(262144).split(b"\x00")
    except OSError:
        return False
    return ("MULTIBOT_HOST=" + host_key(provider, sid)).encode() in environ


def host_reap_agent(provider, sid):
    pid = host_agent_recorded_pid(provider, sid)
    if pid is None or not host_agent_orphan(provider, sid, pid):
        return False
    if host_owned(provider, sid):
        return False
    try:
        os.kill(pid, 9)
    except OSError:
        pass
    host_log(provider, sid, "agent_reaped", dict(pid=pid))
    return True


def host_agent_stop(agent):
    if agent is None:
        return
    try:
        agent.stdin.close()
    except (OSError, ValueError):
        pass
    try:
        agent.wait(timeout=HOST_STOP_GRACE)
    except subprocess.TimeoutExpired:
        try:
            agent.kill()
            agent.wait(timeout=HOST_STOP_GRACE)
        except (OSError, subprocess.TimeoutExpired):
            pass


def host_written(state):
    written = state.get("written")
    if not isinstance(written, dict):
        written = {}
        state["written"] = written
    return written


def host_session_state(provider, sid):
    state = host_read(host_paths(provider, sid)["state"])
    return state if isinstance(state, dict) else {}


def host_session_reset(session, agent=None):
    session.update(agent=agent, inflight=None, buffer=b"", stop=False, idle=time.time(), pending={}, parked={},
                   messages={}, current={}, sends=[], turn=None, interrupted=False, ready=False, rpc={}, seq=0,
                   thread=None, native_turn=None, codex_tokens={}, pi_run=False, pi_message=0, pi_last=None)


def host_db(provider, sid, tmux, session):
    db = session.get("db")
    if db is None:
        db = message_store_db(tmux)
        with db:
            db.execute('INSERT OR IGNORE INTO message_chats(provider,sid) VALUES (?,?)', (provider, sid))
        session["db"] = db
    return db


def host_event_text(value, limit=HOST_EVENT_TEXT_LIMIT):
    text = value if isinstance(value, str) else ""
    return text if len(text) <= limit else text[:limit]


def host_event(provider, sid, tmux, session, kind, payload=None, item=None, turn=None):
    if tmux is None:
        return
    message = dict(v=HOST_EVENT_VERSION, sid=sid, turn=session.get("turn") if turn is None else turn, kind=kind,
                   at=int(time.time() * 1000), item=item, payload=payload if isinstance(payload, dict) else {})
    inflight = session.get("inflight")
    if isinstance(inflight, dict) and inflight.get("room_scope") is not None:
        message["room_scope"] = inflight["room_scope"]
        message["message_id"] = inflight["id"]
    try:
        with host_db(provider, sid, tmux, session) as db:
            message_event(db, provider, sid, message)
    except (OSError, ValueError, sqlite3.Error):
        host_log(provider, sid, "event_failed", dict(kind=kind))


def host_stream_message(session, message_id):
    messages = session["messages"]
    entry = messages.get(message_id)
    if entry is None:
        entry = messages[message_id] = dict(text=0, thinking=0, blocks=[], index={})
    return entry


def host_stream_block(session, message_id, kind, index=None):
    entry = host_stream_message(session, message_id)
    if isinstance(index, int) and index in entry["index"]:
        return entry["index"][index]
    count = entry[kind]
    entry[kind] = count + 1
    block = dict(kind=kind, item=message_id + ":" + kind + ":" + str(count), text="", done=False)
    entry["blocks"].append(block)
    if isinstance(index, int):
        entry["index"][index] = block
    return block


def host_stream_open_block(entry, kind):
    return next((block for block in entry["blocks"] if block["kind"] == kind and not block["done"]), None)


def host_delta(session, block, text):
    block["text"] += text
    pending = session["pending"]
    if block["item"] not in pending:
        pending[block["item"]] = dict(block=block, due=time.time() + HOST_COALESCE_SECONDS)


def host_flush(provider, sid, tmux, session, now=None):
    now = time.time() if now is None else now
    pending = session["pending"]
    for item, entry in list(pending.items()):
        if entry["due"] > now:
            continue
        del pending[item]
        block = entry["block"]
        if block["done"]:
            continue
        host_event(provider, sid, tmux, session, block["kind"] + ".delta",
                   dict(text=block["text"], done=False), item)
    return bool(pending)


def host_flush_timeout(session):
    pending = session["pending"]
    if not pending:
        return 1.0
    return max(0.0, min(1.0, min(entry["due"] for entry in pending.values()) - time.time()))


def host_complete(provider, sid, tmux, session, block, text=None):
    block["done"] = True
    session["pending"].pop(block["item"], None)
    final = block["text"] if not isinstance(text, str) else text
    block["text"] = final
    host_event(provider, sid, tmux, session, block["kind"] + ".done", dict(text=final), block["item"])


def host_stream_event(provider, sid, tmux, session, event):
    body = event.get("event") if isinstance(event.get("event"), dict) else {}
    subtype = body.get("type")
    parent = event.get("parent_tool_use_id")
    thread = str(parent)
    if subtype == "message_start":
        message = body.get("message") if isinstance(body.get("message"), dict) else {}
        if isinstance(message.get("id"), str):
            session["current"][thread] = message["id"]
            host_stream_message(session, message["id"])
        return
    if subtype == "message_stop":
        for message_id in list(session["messages"]):
            entry = session["messages"][message_id]
            if all(block["done"] for block in entry["blocks"]):
                session["messages"].pop(message_id)
        return
    if not isinstance(body.get("index"), int):
        return
    message_id = session["current"].get(thread)
    if not isinstance(message_id, str):
        return
    if subtype == "content_block_start":
        block = body.get("content_block") if isinstance(body.get("content_block"), dict) else {}
        if block.get("type") in ("text", "thinking"):
            host_stream_block(session, message_id, block["type"], body["index"])
        return
    if subtype != "content_block_delta":
        return
    delta = body.get("delta") if isinstance(body.get("delta"), dict) else {}
    kind = {"text_delta": "text", "thinking_delta": "thinking"}.get(delta.get("type"))
    if kind is None or parent is not None:
        return
    text = delta.get("text") if kind == "text" else delta.get("thinking")
    if isinstance(text, str) and text:
        host_delta(session, host_stream_block(session, message_id, kind, body["index"]), text)


def host_assistant_event(provider, sid, tmux, session, event):
    message = event.get("message") if isinstance(event.get("message"), dict) else {}
    message_id = message.get("id")
    parent = event.get("parent_tool_use_id")
    if not isinstance(message_id, str) or not isinstance(message.get("content"), list):
        return
    session["current"][str(parent)] = message_id
    entry = host_stream_message(session, message_id)
    for block in message["content"]:
        if not isinstance(block, dict):
            continue
        if block.get("type") == "tool_use" and isinstance(block.get("id"), str):
            host_event(provider, sid, tmux, session, "item.started",
                       dict(type="tool_call", tool=block.get("name"), input=block.get("input") or {},
                            title=block.get("name"), display_name=block.get("name")), block["id"])
        elif block.get("type") in ("text", "thinking") and parent is None:
            kind = block["type"]
            opened = host_stream_open_block(entry, kind) or host_stream_block(session, message_id, kind)
            if session["pending"].pop(opened["item"], None) is not None and not opened["done"] and opened["text"]:
                host_event(provider, sid, tmux, session, opened["kind"] + ".delta", dict(text=opened["text"], done=False), opened["item"])
            text = block.get("text") if kind == "text" else block.get("thinking")
            host_complete(provider, sid, tmux, session, opened, text if isinstance(text, str) else None)


def host_tool_output(content):
    if isinstance(content, str):
        return host_event_text(content)
    if not isinstance(content, list):
        return ""
    parts = [block.get("text") for block in content
             if isinstance(block, dict) and block.get("type") == "text" and isinstance(block.get("text"), str)]
    return host_event_text("\n".join(parts))


def host_bind_uuid(provider, sid, tmux, session, event):
    names = [event.get("user_message_uuid")]
    listed = event.get("user_message_uuids")
    names.extend(listed if isinstance(listed, list) else [])
    bound = {name for entry in session["sends"] for name in entry.get("uuid") or []}
    uuids = [name for name in names if isinstance(name, str) and name and name not in bound]
    sends = [entry for entry in session["sends"] if not entry.get("uuid")]
    if not uuids or not sends:
        return
    sends[-1]["uuid"] = uuids
    host_turn_started(provider, sid, tmux, session, sends[-1])


def host_turn_started(provider, sid, tmux, session, entry):
    host_event(provider, sid, tmux, session, "turn.started",
               dict(message_id=entry["id"], text_sha256=entry["sha256"], uuids=list(entry.get("uuid") or [])),
               turn=entry["turn"])


def host_user_event(provider, sid, tmux, session, event):
    message = event.get("message") if isinstance(event.get("message"), dict) else {}
    for block in message.get("content") if isinstance(message.get("content"), list) else []:
        if isinstance(block, dict) and block.get("type") == "tool_result" and isinstance(block.get("tool_use_id"), str):
            failed = bool(block.get("is_error"))
            host_event(provider, sid, tmux, session, "item.done",
                       dict(status="failed" if failed else "completed", output=host_tool_output(block.get("content")),
                            is_error=failed), block["tool_use_id"])
    host_bind_uuid(provider, sid, tmux, session, event)


def host_stream_finish(provider, sid, tmux, session):
    for message_id in list(session["messages"]):
        for block in session["messages"].pop(message_id)["blocks"]:
            if not block["done"]:
                host_complete(provider, sid, tmux, session, block)


def host_turn_status(session, event):
    if session.get("interrupted"):
        return "interrupted"
    if event.get("subtype") == "success" and not event.get("is_error"):
        return "completed"
    return "failed"


def host_control_send(session, payload):
    agent = session.get("agent")
    stdin = getattr(agent, "stdin", None) if agent is not None else None
    if stdin is None:
        return False
    try:
        stdin.write((json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8"))
        stdin.flush()
    except (OSError, ValueError):
        return False
    return True


def host_control_response(session, request_id, response):
    return host_control_send(session, dict(type="control_response", response=dict(subtype="success", request_id=request_id,
                                                                                response=response)))


def host_control_request(session, request):
    import uuid
    return host_control_send(session, dict(type="control_request", request_id="host-" + uuid.uuid4().hex,
                                           request=request))


def host_questions(request):
    source = request.get("input") if isinstance(request.get("input"), dict) else {}
    listed = source.get("questions")
    questions = []
    for index, question in enumerate(listed if isinstance(listed, list) else []):
        if not isinstance(question, dict):
            continue
        options = [dict(label=option.get("label"), description=option.get("description"), preview=option.get("preview"))
                   for option in question.get("options") or [] if isinstance(option, dict)]
        questions.append(dict(id=str(index), header=question.get("header"), question=question.get("question"),
                              options=options, multiSelect=bool(question.get("multiSelect"))))
    return questions


def host_approval(request, request_id):
    return dict(request_id=request_id, tool=request.get("tool_name"), tool_use_id=request.get("tool_use_id"),
                input=request.get("input") or {}, title=request.get("title") or request.get("tool_name"),
                display_name=request.get("display_name"), description=request.get("description"),
                decision_reason=request.get("decision_reason"), blocked_path=request.get("blocked_path"),
                suggestions=request.get("permission_suggestions") or [],
                default_to_no=bool(request.get("default_to_no")),
                suppress_always_allow_rule=bool(request.get("suppress_always_allow_rule")),
                requires_user_interaction=bool(request.get("requires_user_interaction")))


def host_control_request_event(provider, sid, tmux, session, event):
    request = event.get("request") if isinstance(event.get("request"), dict) else {}
    request_id = event.get("request_id")
    if not isinstance(request_id, str) or not request_id:
        return
    if request.get("subtype") != "can_use_tool":
        host_control_response(session, request_id, {})
        return
    tool = request.get("tool_name")
    kind = "plan" if tool == "ExitPlanMode" else "question" if tool == "AskUserQuestion" else "approval"
    session["parked"][request_id] = dict(kind=kind, request=request, signal=threading.Event(), at=time.time())
    if kind == "plan":
        session["state"].setdefault("plan_before", session["state"].get("mode") or "default")
        plan = (request.get("input") or {}).get("plan") if isinstance(request.get("input"), dict) else None
        host_event(provider, sid, tmux, session, "plan.proposed",
                   dict(request_id=request_id, tool_use_id=request.get("tool_use_id"),
                        markdown=plan if isinstance(plan, str) else ""))
    elif kind == "question":
        host_event(provider, sid, tmux, session, "question.request",
                   dict(request_id=request_id, tool_use_id=request.get("tool_use_id"), questions=host_questions(request)))
    else:
        host_event(provider, sid, tmux, session, "approval.request", host_approval(request, request_id))
    host_log(provider, sid, "control_request", dict(request_id=request_id, tool=tool, kind=kind))


def host_resolved(provider, sid, tmux, session, parked, payload):
    kind = parked["kind"]
    if kind == "question":
        host_event(provider, sid, tmux, session, "question.resolved", payload)
    elif kind == "plan":
        host_event(provider, sid, tmux, session, "plan.resolved", payload)
    else:
        host_event(provider, sid, tmux, session, "approval.resolved", payload)


def host_control_cancel(provider, sid, tmux, session, request_id):
    parked = session["parked"].pop(request_id, None) if isinstance(request_id, str) else None
    if not isinstance(parked, dict):
        return
    parked["signal"].set()
    if parked["kind"] == "question":
        payload = dict(request_id=request_id, answers={}, dismissed=True)
    elif parked["kind"] == "plan":
        payload = dict(request_id=request_id, action="feedback")
    else:
        payload = dict(request_id=request_id, decision="cancel", by="timeout")
    host_resolved(provider, sid, tmux, session, parked, payload)
    host_log(provider, sid, "control_cancelled", dict(request_id=request_id, kind=parked["kind"]))


def host_session_permissions(request):
    rules = []
    for item in request.get("permission_suggestions") if isinstance(request.get("permission_suggestions"), list) else []:
        if isinstance(item, dict) and item.get("behavior") == "allow":
            rules.extend(rule for rule in item.get("rules") or [] if isinstance(rule, dict))
    return [dict(type="addRules", rules=rules or [dict(toolName=request.get("tool_name"))],
                 behavior="allow", destination="session")]


def host_answers(questions, answers):
    names = {}
    for index, question in enumerate(questions if isinstance(questions, list) else []):
        if isinstance(question, dict) and isinstance(question.get("question"), str):
            names[str(question.get("id", index))] = question["question"]
    resolved = {}
    for key, value in (answers if isinstance(answers, dict) else {}).items():
        if isinstance(value, list):
            value = ", ".join(str(part) for part in value)
        if isinstance(value, str) and value:
            resolved[names.get(str(key), str(key))] = value
    return resolved


def host_set_mode(provider, sid, session, mode):
    if mode == "plan":
        session["state"].setdefault("plan_before", session["state"].get("mode") or "default")
    if not host_control_request(session, dict(subtype="set_permission_mode", mode=mode)):
        return dict(status="error", reason="stdin_write_failed")
    session["state"]["mode"] = mode
    host_state_write(provider, sid, session["state"])
    if "settings" in session:
        host_settings_event(provider, sid, host_chat_name(provider, sid), session)
    return dict(status="ok")


def host_interrupt(provider, sid, session):
    if not host_control_request(session, dict(subtype="interrupt", cancel_queued=True)):
        return dict(status="error", reason="stdin_write_failed")
    session["interrupted"] = True
    inflight = session.get("inflight")
    host_log(provider, sid, "interrupt", dict(id=inflight.get("id") if isinstance(inflight, dict) else None))
    return dict(status="ok")


def host_answer(provider, sid, tmux, session, request):
    operation, request_id = request.get("op"), request.get("request_id")
    parked = session["parked"].get(request_id) if isinstance(request_id, str) else None
    if not isinstance(parked, dict):
        return dict(status="refused", reason="unknown_request")
    body = parked["request"]
    tool_use_id = body.get("tool_use_id")
    kind = parked["kind"]
    if operation == "approval_reply":
        decision = request.get("decision")
        if kind != "approval" or decision not in ("accept", "acceptForSession", "decline", "cancel"):
            return host_refuse("invalid_decision")
        edited = request.get("input") if isinstance(request.get("input"), dict) else None
        if decision in ("accept", "acceptForSession"):
            response = dict(behavior="allow", updatedInput=edited or (body.get("input") or {}), toolUseID=tool_use_id,
                            decisionClassification="user_temporary" if decision == "accept" else "user_permanent")
            if decision == "acceptForSession":
                response["updatedPermissions"] = host_session_permissions(body)
        else:
            response = dict(behavior="deny",
                            message="User declined tool execution." if decision == "decline" else "User cancelled tool execution.",
                            toolUseID=tool_use_id, decisionClassification="user_reject")
            if decision == "cancel":
                response["interrupt"] = True
        payload = dict(request_id=request_id, decision=decision, by="desktop")
    elif operation == "question_reply":
        if kind != "question":
            return host_refuse("invalid_request")
        dismissed = bool(request.get("dismissed"))
        if dismissed:
            response = dict(behavior="deny", message="User cancelled tool execution.", toolUseID=tool_use_id,
                            decisionClassification="user_reject")
            payload = dict(request_id=request_id, answers={}, dismissed=True)
        else:
            input = dict(body.get("input") or {})
            answers = host_answers(input.get("questions"), request.get("answers"))
            response = dict(behavior="allow", updatedInput=dict(input, answers=answers), toolUseID=tool_use_id)
            payload = dict(request_id=request_id, answers=answers, dismissed=False)
    else:
        action = request.get("action")
        if kind != "plan" or action not in ("implement", "feedback"):
            return host_refuse("invalid_action")
        feedback = request.get("feedback") if isinstance(request.get("feedback"), str) else ""
        payload = dict(request_id=request_id, action=action)
        if action == "implement":
            response = dict(behavior="allow", updatedInput=dict(body.get("input") or {}), toolUseID=tool_use_id,
                            decisionClassification="user_temporary")
        else:
            response = dict(behavior="deny", toolUseID=tool_use_id, decisionClassification="user_reject",
                            message=feedback.strip() or HOST_PLAN_STOP_MESSAGE)
            if feedback.strip():
                payload["feedback"] = feedback
    del session["parked"][request_id]
    parked["signal"].set()
    host_control_response(session, request_id, response)
    host_resolved(provider, sid, tmux, session, parked, payload)
    if operation == "plan_reply" and request.get("action") == "implement":
        host_plan_settled(provider, sid, session)
    host_log(provider, sid, "control_answer", dict(request_id=request_id, kind=kind, decision=request.get("decision") or request.get("action")))
    return dict(status="ok")


def host_plan_settled(provider, sid, session):
    before = session["state"].pop("plan_before", None)
    if before in HOST_PERMISSION_MODES:
        session["state"]["mode"] = before
    else:
        session["state"].pop("mode", None)
    host_state_write(provider, sid, session["state"])
    if "settings" in session:
        host_settings_event(provider, sid, host_chat_name(provider, sid), session)


def host_refuse(reason, **extra):
    return dict(status="refused", reason=reason, **extra)


def host_handle_request(provider, sid, tmux, session, request):
    operation = request.get("op")
    if operation == "ping":
        inflight = session.get("inflight")
        return dict(status="ok", pid=os.getpid(), sid=sid, tmux=tmux,
                    inflight=inflight.get("id") if isinstance(inflight, dict) else None,
                    parked=len(session["parked"]), room_message_protocol=ROOM_MESSAGE_PROTOCOL)
    if operation == "stop":
        session["stop"] = True
        return dict(status="ok")
    adapter = HOST_ADAPTERS[provider]
    if operation in ("approval_reply", "question_reply", "plan_reply"):
        return adapter["answer"](provider, sid, tmux, session, request)
    if operation == "interrupt":
        return adapter["interrupt"](provider, sid, session)
    if operation in ("set_model", "set_effort"):
        if provider != "claude":
            return host_refuse("unsupported_provider")
        key = "model" if operation == "set_model" else "effort"
        allowed = ("opus", "sonnet", "haiku") if key == "model" else ("low", "medium", "high")
        value = request.get(key)
        if value not in allowed:
            return host_refuse("invalid_" + key)
        config = host_config(provider, sid)
        if config is None:
            return host_refuse("host_disabled")
        config[key] = value
        host_write(host_paths(provider, sid)["config"], config)
        session["settings"][key] = value
        host_settings_event(provider, sid, tmux, session)
        return dict(status="ok")
    if operation == "set_permission_mode":
        if request.get("mode") not in HOST_PERMISSION_MODES:
            return host_refuse("invalid_permission_mode")
        return adapter["set_mode"](provider, sid, session, request["mode"])
    if operation != "deliver":
        return dict(status="error", reason="invalid_op")
    if provider == "claude" and "settings" in session and (session.get("agent") is None or session.get("starting_settings") != session["settings"]):
        return dict(status="busy", reason="settings_pending")
    if (provider == "claude" and session.get("ready") and session.get("settings", {}).get("model")
            and session.get("init_model") and session["init_model"] != session["settings"]["model"]):
        return host_refuse("model_mismatch")
    token, attempt, epoch, text = request.get("id"), request.get("attempt"), request.get("epoch"), request.get("text")
    if not isinstance(text, str) or not text.strip() or "\x00" in text:
        return host_refuse("invalid_text")
    if (not isinstance(token, str) or re.fullmatch(r"[A-Za-z0-9_-]{16,80}", token) is None
            or not isinstance(attempt, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", attempt)
            or type(epoch) is not int):
        return host_refuse("invalid_message")
    try:
        room_scope = message_room_scope(request["room_scope"]) if "room_scope" in request else None
    except (ValueError, TypeError, AttributeError):
        return host_refuse("invalid_room_scope")
    if room_scope is not None and provider != "claude":
        return host_refuse("unsupported_room_provider")
    written = host_written(session["state"])
    seen = written.get(token)
    if isinstance(seen, dict) and seen.get("epoch") == epoch and seen.get("attempt") == attempt:
        if seen.get("room_scope") != room_scope:
            return host_refuse("room_scope_conflict")
        return dict(status="duplicate", reason="already_written", attempt=seen.get("attempt"))
    inflight = session.get("inflight")
    if room_scope is not None or isinstance(inflight, dict) and inflight.get("room_scope") is not None:
        if session["turn"] is not None or session["sends"] or session["parked"]:
            return dict(status="busy", reason="isolated_room_turn")
    if tmux is None:
        return host_refuse("no_chat")
    if provider != "claude" and not session.get("ready"):
        return dict(status="busy", reason="agent_starting")
    if provider == "hax" and (session["turn"] is not None or session["sends"]):
        return dict(status="busy", reason="hax_run_active")
    if provider == "codex" and not isinstance(session.get("native_turn"), str) and "turn" in session["rpc"].values():
        return dict(status="busy", reason="turn_starting")
    binding = dict(v=1, mode=adapter["binding"], host=os.getpid(), session=sid)
    try:
        claim = host_attempt(provider, sid, tmux, token, attempt, epoch, text, binding)
    except (OSError, ValueError):
        return dict(status="error", reason="attempt_prepare_failed")
    if claim.get("status") == "existing":
        stored = claim.get("binding") if isinstance(claim.get("binding"), dict) else binding
        written[token] = dict(attempt=attempt, epoch=epoch, accepted=False, at=time.time(),
                              mode=stored.get("mode"))
        host_state_write(provider, sid, session["state"])
        return dict(status="duplicate", reason="attempt_exists")
    if claim.get("status") != "claimed":
        return host_refuse("attempt_unavailable")
    if not adapter["write"](provider, sid, session, token, text):
        try:
            host_attempt(provider, sid, tmux, token, attempt, epoch, text, binding, "not_sent")
        except (OSError, ValueError):
            pass
        return host_refuse("stdin_write_failed")
    try:
        host_attempt(provider, sid, tmux, token, attempt, epoch, text, binding, "sent")
    except (OSError, ValueError):
        pass
    written[token] = dict(attempt=attempt, epoch=epoch, accepted=False, at=time.time(),
                          mode=adapter["binding"])
    session["state"]["turns"] = session["state"].get("turns", 0) + 1
    entry = dict(id=token, attempt=attempt, epoch=epoch, at=time.time(), accepted=False,
                 turn="t-" + str(session["state"]["turns"]),
                 sha256=hashlib.sha256(text.encode("utf-8", "surrogatepass")).hexdigest(), uuid=None)
    if room_scope is not None:
        entry["room_scope"] = room_scope
        written[token]["room_scope"] = room_scope
    session["sends"].append(entry)
    if session["turn"] is None:
        session["turn"] = entry["turn"]
    if not isinstance(session.get("inflight"), dict):
        session["inflight"] = entry
    host_turn_started(provider, sid, tmux, session, entry)
    session["state"]["inflight"] = session["inflight"]
    session["state"]["sends"] = [item["id"] for item in session["sends"]]
    host_state_write(provider, sid, session["state"])
    host_log(provider, sid, "delivered", dict(id=token, attempt=attempt))
    return dict(status="sent")


def host_accept(provider, sid, tmux, session, event, only=None):
    sends = [entry for entry in session.get("sends") or [] if not entry.get("accepted") and only in (None, entry["id"])]
    if not sends:
        return
    written = host_written(session["state"])
    for entry in sends:
        entry["accepted"] = True
        digest = hashlib.sha256(("host:" + sid + ":" + entry["id"] + ":" + entry["attempt"]).encode()).hexdigest()
        fact = dict(kind="accepted", sid=sid, id=entry["id"], attempt=entry["attempt"],
                    event_id="host:accepted:" + digest, provenance=HOST_ADAPTERS[provider]["provenance"])
        applied = False
        if tmux is not None:
            try:
                applied = host_apply(provider, sid, tmux, [fact])
            except (OSError, ValueError):
                applied = False
        recorded = written.get(entry["id"])
        if isinstance(recorded, dict):
            recorded["accepted"] = True
        host_log(provider, sid, "accepted", dict(id=entry["id"], attempt=entry["attempt"],
                                                verified=applied, source=event.get("type")))
    session["state"]["inflight"] = session.get("inflight")
    host_state_write(provider, sid, session["state"])


def host_agent_event(provider, sid, tmux, session, raw):
    try:
        event = json.loads(raw)
    except (ValueError, UnicodeError):
        return
    if isinstance(event, dict):
        HOST_ADAPTERS[provider]["event"](provider, sid, tmux, session, event)


def host_claude_event(provider, sid, tmux, session, event):
    kind = event.get("type")
    inflight = session.get("inflight")
    if kind == "system":
        if event.get("subtype") == "init":
            session["ready"] = True
            if "starting_settings" in session:
                settings = session["starting_settings"]
                actual = host_model_name(event.get("model"))
                session["init_model"] = actual
                session["applied_settings"] = dict(settings) if actual and (not settings["model"] or settings["model"] == actual) else None
                host_settings_event(provider, sid, tmux, session)
                if session["applied_settings"] is not None:
                    host_log(provider, sid, "model_effort_settings_applied",
                             dict(model=actual, effort=settings["effort"] or "default"))
                elif settings["model"]:
                    host_log(provider, sid, "model_settings_mismatch",
                             dict(requested=settings["model"], actual=actual or "unknown"))
            if isinstance(event.get("session_id"), str):
                session["state"]["native_session"] = event["session_id"]
            host_state_write(provider, sid, session["state"])
            host_log(provider, sid, "init", dict(session=event.get("session_id")))
            host_event(provider, sid, tmux, session, "host.status", dict(state="ready"))
        elif event.get("subtype") == "permission_denied" and isinstance(event.get("tool_use_id"), str):
            host_event(provider, sid, tmux, session, "item.done",
                       dict(status="failed", output=str(event.get("tool_name") or ""), is_error=True),
                       event["tool_use_id"])
        return
    if kind == "stream_event":
        host_stream_event(provider, sid, tmux, session, event)
        return
    if kind == "assistant":
        host_accept(provider, sid, tmux, session, event)
        host_assistant_event(provider, sid, tmux, session, event)
        return
    if kind == "user":
        host_user_event(provider, sid, tmux, session, event)
        return
    if kind == "control_request":
        host_control_request_event(provider, sid, tmux, session, event)
        return
    if kind == "control_cancel_request":
        host_control_cancel(provider, sid, tmux, session, event.get("request_id"))
        return
    if kind == "control_response":
        response = event.get("response") if isinstance(event.get("response"), dict) else {}
        host_log(provider, sid, "control_response", dict(request_id=response.get("request_id"),
                                                         subtype=response.get("subtype")))
        return
    if kind == "result":
        if isinstance(inflight, dict) and not inflight.get("accepted"):
            host_accept(provider, sid, tmux, session, event)
        session["state"]["result"] = dict(subtype=event.get("subtype"), at=time.time())
        host_turn_end(provider, sid, tmux, session,
                      dict(status=host_turn_status(session, event), stop_reason=event.get("stop_reason"),
                           cost_usd=event.get("total_cost_usd"), usage=event.get("usage") or {}),
                      dict(subtype=event.get("subtype")))


def host_claude_open(provider, sid, tmux, session):
    pass


HOST_SKILL_TOKEN = re.compile(r"(?<!\S)/([A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)?)(?=\s|$)")


def host_skill_file(name, cwd=None):
    home = Path.home() / ".claude"
    if ":" in name:
        plugin, skill = name.split(":", 1)
        files = [f for r in (home / "plugins" / "cache").glob("*/" + plugin + "/*") for f in (r / "skills" / skill / "SKILL.md", r / "commands" / (skill + ".md"))]
    else:
        bases = [home] + ([Path(cwd) / ".claude"] if isinstance(cwd, str) and cwd else [])
        files = [f for b in bases for f in (b / "skills" / name / "SKILL.md", b / "commands" / (name + ".md"))]
    return next((f for f in files if f.is_file()), None)


def host_skill_user_only(path):
    try:
        head = path.read_text(errors="replace")[:4000]
    except OSError:
        return False
    return bool(re.search(r"^disable-model-invocation:\s*true\s*$", head, re.M))


def host_claude_lead_skill(text, cwd=None):
    if not isinstance(text, str) or text.lstrip().startswith("/"):
        return text
    found = [(m, f) for m in HOST_SKILL_TOKEN.finditer(text) for f in [host_skill_file(m.group(1), cwd)] if f is not None]
    if not found:
        return text
    match = next((m for m, f in found if host_skill_user_only(f)), found[0][0])
    rest = (text[:match.start()].rstrip() + " " + text[match.end():].lstrip()).strip()
    return "/" + match.group(1) + (" " + rest if rest else "")


def host_claude_write(provider, sid, session, token, text):
    config = host_config(provider, sid)
    text = host_claude_lead_skill(text, config.get("cwd") if isinstance(config, dict) else None)
    return host_control_send(session, dict(type="user", message=dict(role="user", content=[dict(type="text", text=text)])))


def host_turn_end(provider, sid, tmux, session, payload, detail):
    inflight = session.get("inflight")
    host_stream_finish(provider, sid, tmux, session)
    host_event(provider, sid, tmux, session, "turn.ended", payload)
    session["state"]["inflight"] = None
    session["state"]["sends"] = []
    session["inflight"] = None
    session["sends"] = []
    session["turn"] = None
    session["interrupted"] = False
    host_state_write(provider, sid, session["state"])
    host_log(provider, sid, "turn_done", dict(id=inflight.get("id") if isinstance(inflight, dict) else None, **detail))


def host_codex_policy(session):
    mode = session["state"].get("mode")
    return HOST_CODEX_POLICIES.get(mode, HOST_CODEX_POLICIES["default"])


def host_codex_call(session, method, params, purpose):
    session["seq"] += 1
    ident = session["seq"]
    session["rpc"][ident] = purpose
    if host_control_send(session, dict(jsonrpc="2.0", id=ident, method=method, params=params)):
        return True
    session["rpc"].pop(ident, None)
    return False


def host_codex_reply(session, ident, result=None, error=None):
    payload = dict(jsonrpc="2.0", id=ident)
    payload.update(dict(error=error) if error is not None else dict(result=result or {}))
    return host_control_send(session, payload)


def host_codex_open(provider, sid, tmux, session):
    host_codex_call(session, "initialize", dict(clientInfo=dict(name="multibot", title="Multibot", version="1"),
                                                capabilities=dict(experimentalApi=False, requestAttestation=False)),
                    "initialize")


def host_codex_thread(provider, sid, session, resume):
    state = session["state"]
    approval, sandbox = host_codex_policy(session)
    cwd = host_cwd(provider, sid)
    params = dict(cwd=cwd, approvalPolicy=approval, sandbox=sandbox, developerInstructions=HOST_APPEND_PROMPT)
    native = state.get("native_session")
    try:
        found = isinstance(native, str) and conversation_path(provider, native, cwd) is not None
    except (OSError, ValueError):
        found = False
    if resume and found:
        host_codex_call(session, "thread/resume", dict(params, threadId=native), "resume")
    else:
        host_codex_call(session, "thread/start", params, "start")
    host_log(provider, sid, "codex_permissions", dict(mode=state.get("mode"), approval=approval, sandbox=sandbox,
                                                     resume=bool(resume and found)))


def host_codex_write(provider, sid, session, token, text):
    content = [dict(type="text", text=text, text_elements=[])]
    if isinstance(session.get("native_turn"), str):
        sent = host_codex_call(session, "turn/steer", dict(threadId=session["thread"], expectedTurnId=session["native_turn"],
                                                           input=content), "steer")
    else:
        approval, sandbox = host_codex_policy(session)
        sent = host_codex_call(session, "turn/start", dict(threadId=session["thread"], approvalPolicy=approval,
                                                           sandboxPolicy=HOST_CODEX_SANDBOXES[sandbox], input=content), "turn")
    if sent:
        session["codex_tokens"][session["seq"]] = token
    return sent


def host_unsent(provider, sid, tmux, session, token):
    entry = next((item for item in session.get("sends") or [] if item["id"] == token and not item.get("accepted")), None)
    if entry is None:
        return
    session["sends"].remove(entry)
    digest = hashlib.sha256(("host:" + sid + ":" + token + ":" + entry["attempt"]).encode()).hexdigest()
    fact = dict(kind="not_sent", sid=sid, id=token, attempt=entry["attempt"], event_id="host:not_sent:" + digest,
                provenance=HOST_ADAPTERS[provider]["provenance"])
    try:
        host_apply(provider, sid, tmux, [fact])
    except (OSError, ValueError):
        pass
    session["state"]["sends"] = [item["id"] for item in session["sends"]]
    host_state_write(provider, sid, session["state"])
    host_log(provider, sid, "not_sent", dict(id=token, attempt=entry["attempt"]))


def host_codex_response(provider, sid, tmux, session, message):
    purpose = session["rpc"].pop(message.get("id"), None)
    token = session["codex_tokens"].pop(message.get("id"), None)
    result = message.get("result") if isinstance(message.get("result"), dict) else {}
    failed = "error" in message
    if purpose == "initialize":
        if failed:
            host_log(provider, sid, "host_error", dict(error="codex_initialize_failed"))
            session["stop"] = True
            return
        host_control_send(session, dict(jsonrpc="2.0", method="initialized"))
        host_codex_thread(provider, sid, session, True)
    elif purpose in ("start", "resume"):
        thread = result.get("thread") if isinstance(result.get("thread"), dict) else {}
        native = thread.get("id")
        if failed or not isinstance(native, str) or not native:
            host_log(provider, sid, "codex_thread_failed", dict(step=purpose))
            if purpose == "resume":
                host_codex_thread(provider, sid, session, False)
            else:
                session["stop"] = True
            return
        session["thread"] = native
        session["ready"] = True
        session["state"]["native_session"] = native
        host_state_write(provider, sid, session["state"])
        host_log(provider, sid, "init", dict(session=native, step=purpose))
        host_event(provider, sid, tmux, session, "host.status", dict(state="ready"))
    elif purpose in ("turn", "steer"):
        if not failed:
            turn = result.get("turn") if isinstance(result.get("turn"), dict) else {}
            if purpose == "turn" and isinstance(turn.get("id"), str):
                session["native_turn"] = turn["id"]
            host_accept(provider, sid, tmux, session, message, token)
            return
        host_unsent(provider, sid, tmux, session, token)
        host_log(provider, sid, "codex_turn_failed", dict(step=purpose))
        if purpose == "turn" and session.get("native_turn") is None and not session.get("sends"):
            host_turn_end(provider, sid, tmux, session, dict(status="failed", stop_reason="turn_start_failed", usage={}),
                          dict(subtype="turn_start_failed"))


def host_codex_tool(item):
    kind = item.get("type")
    if kind == "commandExecution":
        return "Bash", dict(command=item.get("command"), cwd=item.get("cwd"))
    if kind == "fileChange":
        return "Edit", dict(changes=item.get("changes") or [])
    if kind == "mcpToolCall":
        return str(item.get("server") or "mcp") + "." + str(item.get("tool") or ""), item.get("arguments") or {}
    if kind == "dynamicToolCall":
        return str(item.get("tool") or "tool"), item.get("arguments") or {}
    if kind == "webSearch":
        return "WebSearch", dict(query=item.get("query"))
    return None, None


def host_codex_output(item):
    kind = item.get("type")
    if kind == "commandExecution":
        return host_event_text(item.get("aggregatedOutput")), item.get("status") != "completed" or item.get("exitCode") not in (0, None)
    if kind == "mcpToolCall":
        error = item.get("error")
        if error:
            return host_event_text(json.dumps(error) if not isinstance(error, str) else error), True
        result = item.get("result") if isinstance(item.get("result"), dict) else {}
        return host_tool_output(result.get("content")), False
    if kind == "dynamicToolCall":
        return "", item.get("success") is False
    return "", item.get("status") in ("failed", "declined")


def host_codex_item(provider, sid, tmux, session, item, done):
    ident = item.get("id")
    if not isinstance(ident, str):
        return
    kind = item.get("type")
    if kind == "agentMessage" and done:
        block = host_stream_block(session, ident, "text", 0)
        host_complete(provider, sid, tmux, session, block, item.get("text") if isinstance(item.get("text"), str) else None)
        return
    if kind == "reasoning" and done:
        for index, text in enumerate(item.get("summary") if isinstance(item.get("summary"), list) else []):
            if isinstance(text, str):
                host_complete(provider, sid, tmux, session, host_stream_block(session, ident, "thinking", index), text)
        return
    tool, arguments = host_codex_tool(item)
    if tool is None:
        return
    if not done:
        host_event(provider, sid, tmux, session, "item.started",
                   dict(type="tool_call", tool=tool, input=arguments, title=tool, display_name=tool), ident)
        return
    output, failed = host_codex_output(item)
    host_event(provider, sid, tmux, session, "item.done",
               dict(status="failed" if failed else "completed", output=output, is_error=failed), ident)


def host_codex_request(provider, sid, tmux, session, message):
    method, ident = message.get("method"), message.get("id")
    params = message.get("params") if isinstance(message.get("params"), dict) else {}
    if method not in HOST_CODEX_APPROVALS:
        host_codex_reply(session, ident, error=dict(code=-32601, message="unsupported request"))
        host_log(provider, sid, "codex_request_refused", dict(method=method))
        return
    key = "codex-" + str(ident)
    command = params.get("command")
    if "command" in params or method == "execCommandApproval":
        tool, arguments = "Bash", dict(command=" ".join(command) if isinstance(command, list) else command, cwd=params.get("cwd"))
    else:
        tool, arguments = "Edit", dict(changes=params.get("fileChanges") or params.get("changes") or {},
                                       grant_root=params.get("grantRoot"))
    request = dict(tool_name=tool, tool_use_id=params.get("itemId") or params.get("callId"), input=arguments,
                   title=tool, description=params.get("reason"))
    session["parked"][key] = dict(kind="approval", request=request, signal=threading.Event(), at=time.time(),
                                  rpc=ident, method=method)
    host_event(provider, sid, tmux, session, "approval.request", host_approval(request, key))
    host_log(provider, sid, "control_request", dict(request_id=key, tool=tool, kind="approval"))


def host_codex_status(session, turn):
    if session.get("interrupted") or turn.get("status") == "interrupted":
        return "interrupted"
    return "completed" if turn.get("status") == "completed" else "failed"


def host_codex_event(provider, sid, tmux, session, message):
    method = message.get("method")
    if method is None:
        host_codex_response(provider, sid, tmux, session, message)
        return
    if "id" in message:
        host_codex_request(provider, sid, tmux, session, message)
        return
    params = message.get("params") if isinstance(message.get("params"), dict) else {}
    if method == "turn/started":
        turn = params.get("turn") if isinstance(params.get("turn"), dict) else {}
        if isinstance(turn.get("id"), str):
            session["native_turn"] = turn["id"]
    elif method == "item/agentMessage/delta" and isinstance(params.get("itemId"), str):
        if isinstance(params.get("delta"), str) and params["delta"]:
            host_delta(session, host_stream_block(session, params["itemId"], "text", 0), params["delta"])
    elif method == "item/reasoning/summaryTextDelta" and isinstance(params.get("itemId"), str):
        index = params.get("summaryIndex") if type(params.get("summaryIndex")) is int else 0
        if isinstance(params.get("delta"), str) and params["delta"]:
            host_delta(session, host_stream_block(session, params["itemId"], "thinking", index), params["delta"])
    elif method in ("item/started", "item/completed") and isinstance(params.get("item"), dict):
        host_codex_item(provider, sid, tmux, session, params["item"], method == "item/completed")
    elif method == "serverRequest/resolved":
        host_control_cancel(provider, sid, tmux, session, "codex-" + str(params.get("requestId")))
    elif method == "error":
        error = params.get("error") if isinstance(params.get("error"), dict) else {}
        host_log(provider, sid, "codex_error", dict(retry=bool(params.get("willRetry")), message=host_event_text(error.get("message"), 200)))
    elif method == "turn/completed":
        turn = params.get("turn") if isinstance(params.get("turn"), dict) else {}
        session["native_turn"] = None
        host_turn_end(provider, sid, tmux, session,
                      dict(status=host_codex_status(session, turn), stop_reason=turn.get("status"), usage={}),
                      dict(subtype=turn.get("status")))


def host_codex_answer(provider, sid, tmux, session, request):
    request_id = request.get("request_id")
    parked = session["parked"].get(request_id) if isinstance(request_id, str) else None
    if request.get("op") != "approval_reply":
        return host_refuse("unsupported_request")
    if not isinstance(parked, dict):
        return dict(status="refused", reason="unknown_request")
    decision = request.get("decision")
    if decision not in HOST_CODEX_LEGACY:
        return host_refuse("invalid_decision")
    del session["parked"][request_id]
    parked["signal"].set()
    legacy = parked["method"] in ("execCommandApproval", "applyPatchApproval")
    host_codex_reply(session, parked["rpc"], dict(decision=HOST_CODEX_LEGACY[decision] if legacy else decision))
    host_resolved(provider, sid, tmux, session, parked, dict(request_id=request_id, decision=decision, by="desktop"))
    host_log(provider, sid, "control_answer", dict(request_id=request_id, kind="approval", decision=decision))
    return dict(status="ok")


def host_codex_interrupt(provider, sid, session):
    if not isinstance(session.get("thread"), str) or not isinstance(session.get("native_turn"), str):
        return host_refuse("no_active_turn")
    if not host_codex_call(session, "turn/interrupt", dict(threadId=session["thread"], turnId=session["native_turn"]), "interrupt"):
        return dict(status="error", reason="stdin_write_failed")
    session["interrupted"] = True
    inflight = session.get("inflight")
    host_log(provider, sid, "interrupt", dict(id=inflight.get("id") if isinstance(inflight, dict) else None))
    return dict(status="ok")


def host_codex_set_mode(provider, sid, session, mode):
    session["state"]["mode"] = mode
    host_state_write(provider, sid, session["state"])
    approval, sandbox = host_codex_policy(session)
    host_log(provider, sid, "codex_permissions", dict(mode=mode, approval=approval, sandbox=sandbox))
    return dict(status="ok")


def host_pi_open(provider, sid, tmux, session):
    session["ready"] = True
    session["state"]["native_session"] = sid
    host_state_write(provider, sid, session["state"])
    host_log(provider, sid, "init", dict(session=sid))
    host_event(provider, sid, tmux, session, "host.status", dict(state="ready"))


def host_pi_write(provider, sid, session, token, text):
    payload = dict(id=token, type="prompt", message=text)
    if isinstance(session.get("inflight"), dict):
        payload["streamingBehavior"] = "followUp"
    return host_control_send(session, payload)


def host_pi_end(provider, sid, tmux, session, aborted):
    last = session.get("pi_last") if isinstance(session.get("pi_last"), dict) else {}
    usage = last.get("usage") if isinstance(last.get("usage"), dict) else {}
    cost = usage.get("cost") if isinstance(usage.get("cost"), dict) else {}
    if aborted or session.get("interrupted") or last.get("stopReason") == "aborted":
        status = "interrupted"
    else:
        status = "failed" if last.get("stopReason") == "error" else "completed"
    session["pi_run"] = False
    session["pi_last"] = None
    host_turn_end(provider, sid, tmux, session,
                  dict(status=status, stop_reason=last.get("stopReason"), cost_usd=cost.get("total"), usage=usage),
                  dict(subtype=status))


def host_pi_update(provider, sid, tmux, session, event):
    update = event.get("assistantMessageEvent") if isinstance(event.get("assistantMessageEvent"), dict) else {}
    part = update.get("type") if isinstance(update.get("type"), str) else ""
    kind = "text" if part.startswith("text_") else "thinking" if part.startswith("thinking_") else None
    if kind is None or type(update.get("contentIndex")) is not int:
        return
    block = host_stream_block(session, "pi-" + str(session["pi_message"]), kind, update["contentIndex"])
    if part.endswith("_delta") and isinstance(update.get("delta"), str) and update["delta"]:
        host_delta(session, block, update["delta"])
    elif part.endswith("_end") and not block["done"]:
        host_complete(provider, sid, tmux, session, block, update.get("content") if isinstance(update.get("content"), str) else None)


def host_pi_event(provider, sid, tmux, session, event):
    kind = event.get("type")
    if kind == "response":
        data = event.get("data") if isinstance(event.get("data"), dict) else {}
        if event.get("command") != "prompt" or not isinstance(event.get("id"), str):
            return
        if event.get("success") is False:
            host_log(provider, sid, "pi_prompt", dict(success=False, disposition=data.get("disposition")))
            host_unsent(provider, sid, tmux, session, event.get("id"))
            if not session.get("pi_run") and not session.get("sends"):
                host_turn_end(provider, sid, tmux, session, dict(status="failed", stop_reason="prompt_failed", usage={}),
                              dict(subtype="prompt_failed"))
            return
        if data.get("disposition") == "handled":
            host_log(provider, sid, "pi_prompt", dict(success=True, disposition="handled"))
            host_accept(provider, sid, tmux, session, event, event.get("id"))
            if not session.get("pi_run"):
                host_turn_end(provider, sid, tmux, session, dict(status="completed", stop_reason="handled", usage={}),
                              dict(subtype="prompt_handled"))
            return
        host_accept(provider, sid, tmux, session, event, event.get("id"))
    elif kind == "agent_start":
        session["pi_run"] = True
        inflight = session.get("inflight")
        if isinstance(inflight, dict):
            host_accept(provider, sid, tmux, session, event, inflight["id"])
    elif kind == "message_start":
        session["pi_message"] += 1
    elif kind == "message_update":
        host_pi_update(provider, sid, tmux, session, event)
    elif kind == "message_end":
        message = event.get("message") if isinstance(event.get("message"), dict) else {}
        if message.get("role") == "assistant":
            session["pi_last"] = message
    elif kind == "tool_execution_start" and isinstance(event.get("toolCallId"), str):
        tool = event.get("toolName")
        host_event(provider, sid, tmux, session, "item.started",
                   dict(type="tool_call", tool=tool, input=event.get("args") or {}, title=tool, display_name=tool),
                   event["toolCallId"])
    elif kind == "tool_execution_end" and isinstance(event.get("toolCallId"), str):
        result = event.get("result") if isinstance(event.get("result"), dict) else {}
        failed = bool(event.get("isError"))
        host_event(provider, sid, tmux, session, "item.done",
                   dict(status="failed" if failed else "completed", output=host_tool_output(result.get("content")),
                        is_error=failed), event["toolCallId"])
    elif kind == "agent_settled":
        host_pi_end(provider, sid, tmux, session, bool(event.get("aborted")))
    elif kind == "extension_ui_request" and event.get("method") in HOST_PI_DIALOGS:
        host_control_send(session, dict(type="extension_ui_response", id=event.get("id"), cancelled=True))
        host_log(provider, sid, "pi_dialog_cancelled", dict(method=event.get("method")))


def host_pi_answer(provider, sid, tmux, session, request):
    return host_refuse("unsupported_request")


def host_pi_interrupt(provider, sid, session):
    if not host_control_send(session, dict(type="abort")):
        return dict(status="error", reason="stdin_write_failed")
    session["interrupted"] = True
    inflight = session.get("inflight")
    host_log(provider, sid, "interrupt", dict(id=inflight.get("id") if isinstance(inflight, dict) else None))
    return dict(status="ok")


def host_pi_set_mode(provider, sid, session, mode):
    return host_refuse("pi_always_full_access")


def host_hax_open(provider, sid, tmux, session):
    session["ready"] = True
    session["hax_message"] = 0
    host_log(provider, sid, "init", dict(session=session["state"].get("native_session")))
    host_event(provider, sid, tmux, session, "host.status", dict(state="ready"))


def host_hax_write(provider, sid, session, token, text):
    return host_control_send(session, dict(type="prompt", id=token, message=text))


def host_hax_record(provider, sid, tmux, session, record):
    kind = record.get("kind")
    if kind == "assistant" and isinstance(record.get("text"), str) and record["text"]:
        session["hax_message"] += 1
        block = host_stream_block(session, "hax-" + str(session["hax_message"]), "text")
        host_complete(provider, sid, tmux, session, block, record["text"])
    elif kind == "tool_call" and isinstance(record.get("call_id"), str):
        tool = record.get("tool_name") if isinstance(record.get("tool_name"), str) else "tool"
        try:
            arguments = json.loads(record.get("arguments") or "{}")
        except (TypeError, ValueError):
            arguments = {"arguments": record.get("arguments")}
        host_event(provider, sid, tmux, session, "item.started",
                   dict(type="tool_call", tool=tool, input=arguments if isinstance(arguments, dict) else {},
                        title=tool, display_name=tool), record["call_id"])
    elif kind == "tool_result" and isinstance(record.get("call_id"), str):
        failed = bool(record.get("is_error"))
        host_event(provider, sid, tmux, session, "item.done",
                   dict(status="failed" if failed else "completed", output=host_tool_output(record.get("output")),
                        is_error=failed), record["call_id"])


def host_hax_event(provider, sid, tmux, session, event):
    kind = event.get("type")
    token = event.get("id")
    if kind == "bridge_start" and isinstance(token, str):
        host_accept(provider, sid, tmux, session, event, token)
    elif kind == "bridge_busy" and isinstance(token, str):
        host_unsent(provider, sid, tmux, session, token)
    elif kind == "hax" and isinstance(event.get("record"), dict):
        host_hax_record(provider, sid, tmux, session, event["record"])
    elif kind == "bridge_end":
        native = event.get("session")
        if isinstance(native, str) and SESSION_ID.fullmatch(native):
            session["state"]["native_session"] = native
        if event.get("outcome") == "spawn_failed" and isinstance(token, str):
            host_unsent(provider, sid, tmux, session, token)
        if session.get("interrupted"):
            status = "interrupted"
        else:
            status = "completed" if event.get("outcome") == "complete" and event.get("exit") == 0 else "failed"
        host_turn_end(provider, sid, tmux, session, dict(status=status, stop_reason=event.get("outcome"), usage={}),
                      dict(subtype=status))


def host_hax_interrupt(provider, sid, session):
    if not host_control_send(session, dict(type="abort")):
        return dict(status="error", reason="stdin_write_failed")
    session["interrupted"] = True
    inflight = session.get("inflight")
    host_log(provider, sid, "interrupt", dict(id=inflight.get("id") if isinstance(inflight, dict) else None))
    return dict(status="ok")


def host_hax_set_mode(provider, sid, session, mode):
    return host_refuse("hax_mode_fixed")


HOST_ADAPTERS = {
    "claude": dict(argv=host_claude_argv, open=host_claude_open, write=host_claude_write, event=host_claude_event,
                   answer=host_answer, interrupt=host_interrupt, set_mode=host_set_mode,
                   binding="stream_json_user_line", provenance="stream_json_turn_start"),
    "codex": dict(argv=host_codex_argv, open=host_codex_open, write=host_codex_write, event=host_codex_event,
                  answer=host_codex_answer, interrupt=host_codex_interrupt, set_mode=host_codex_set_mode,
                  binding="codex_app_server_turn", provenance="codex_turn_start"),
    "pi": dict(argv=host_pi_argv, open=host_pi_open, write=host_pi_write, event=host_pi_event,
               answer=host_pi_answer, interrupt=host_pi_interrupt, set_mode=host_pi_set_mode,
               binding="pi_rpc_prompt", provenance="pi_prompt_accepted"),
    "hax": dict(argv=host_hax_argv, open=host_hax_open, write=host_hax_write, event=host_hax_event,
                answer=host_pi_answer, interrupt=host_hax_interrupt, set_mode=host_hax_set_mode,
                binding="hax_bridge_prompt", provenance="hax_run_started"),
}


def host_agent_read(provider, sid, tmux, session, chunk):
    if not chunk:
        return False
    session["buffer"] += chunk
    while b"\n" in session["buffer"]:
        raw, session["buffer"] = session["buffer"].split(b"\n", 1)
        raw = raw.strip()
        if raw:
            host_agent_event(provider, sid, tmux, session, raw.decode("utf-8", "replace"))
    if len(session["buffer"]) > HOST_LINE_LIMIT:
        session["buffer"] = b""
    return True


def host_accept_client(provider, sid, tmux, session, server):
    import socket as module
    try:
        connection, _ = server.accept()
    except OSError:
        return
    reply = dict(status="error", reason="no_request")
    with connection:
        connection.settimeout(HOST_DELIVERY_TIMEOUT)
        buffer = b""
        try:
            while b"\n" not in buffer and len(buffer) <= 65536:
                chunk = connection.recv(65536)
                if not chunk:
                    break
                buffer += chunk
            if buffer.strip():
                request = json.loads(buffer.split(b"\n", 1)[0].decode("utf-8"))
                if isinstance(request, dict):
                    reply = host_handle_request(provider, sid, tmux, session, request)
        except (OSError, ValueError, UnicodeError):
            reply = dict(status="error", reason="invalid_request")
        try:
            connection.sendall((json.dumps(reply, separators=(",", ":")) + "\n").encode("utf-8"))
        except OSError:
            pass


def host_startup_failure(provider, sid, tmux, state, reason, retry_generation=0):
    state["startup_failure"] = dict(reason=reason, config=host_config(provider, sid), retry_generation=retry_generation)
    host_state_write(provider, sid, state)
    session = dict(state=state, turn="startup-failure")
    text = {"cwd_unavailable": "Could not start chat: working folder unavailable.",
            "agent_not_found": "Could not start chat: agent not found.",
            "agent_unavailable": "Could not start chat: agent unavailable.",
            "owner_unavailable": "Could not start chat as the workspace user."}[reason]
    host_event(provider, sid, tmux, session, "turn.started")
    host_event(provider, sid, tmux, session, "text.done", dict(text=text), "startup-failure")
    host_event(provider, sid, tmux, session, "turn.ended", dict(status="failed"))
    host_log(provider, sid, "host_error", dict(error=reason))


def host_loop(provider, sid, tmux, session, server, agent):
    import selectors
    host_session_reset(session, agent)
    if provider == "claude":
        session["settings"] = host_settings(provider, sid)
        session["starting_settings"] = dict(session["settings"])
        session["applied_settings"] = None
        host_settings_event(provider, sid, tmux, session)
    host_event(provider, sid, tmux, session, "host.status", dict(state="starting"))
    HOST_ADAPTERS[provider]["open"](provider, sid, tmux, session)
    with selectors.DefaultSelector() as selector:
        selector.register(server, selectors.EVENT_READ, "client")
        selector.register(agent.stdout, selectors.EVENT_READ, "agent")
        while not session["stop"]:
            if (provider == "claude" and session["settings"] != session["starting_settings"]
                    and session["turn"] is None and not session["inflight"] and not session["sends"] and not session["parked"]):
                selector.unregister(agent.stdout)
                host_agent_stop(agent)
                session["agent"] = None
                session["ready"] = False
                session["starting_settings"] = dict(session["settings"])
                try:
                    agent = host_agent_start(provider, sid, session["state"].get("mode"))
                except (OSError, RuntimeError):
                    agent = None
                if agent is None:
                    host_log(provider, sid, "settings_restart_failed")
                    return
                session["agent"] = agent
                session["buffer"] = b""
                session["state"]["agent_pid"] = agent.pid
                host_state_write(provider, sid, session["state"])
                selector.register(agent.stdout, selectors.EVENT_READ, "agent")
            idle_expired = time.time() - session["idle"] >= HOST_IDLE_SECONDS
            if session["turn"] is None and not isinstance(session.get("inflight"), dict) and not session["parked"] and idle_expired:
                host_log(provider, sid, "idle_stop")
                return
            for key, _ in selector.select(host_flush_timeout(session)):
                if key.data == "agent":
                    try:
                        chunk = agent.stdout.read1(65536)
                    except (OSError, ValueError):
                        chunk = b""
                    if chunk:
                        session["idle"] = time.time()
                    if not host_agent_read(provider, sid, tmux, session, chunk):
                        owner = conversation_owner(host_cwd(provider, sid), include_home=True)
                        if not session["ready"]:
                            reason = "owner_unavailable" if owner and owner[1] != HOME else "agent_unavailable"
                            host_startup_failure(provider, sid, tmux, session["state"], reason, session.get("retry_generation", 0))
                        host_log(provider, sid, "agent_closed")
                        return
                else:
                    session["idle"] = time.time()
                    host_accept_client(provider, sid, tmux, session, server)
            host_flush(provider, sid, tmux, session)


def host_main(provider, sid):
    import socket as module
    if not host_identity(provider, sid):
        return 2
    if not host_enabled(provider, sid):
        return 3
    tmux = host_chat_name(provider, sid)
    lock = host_lock(provider, sid)
    if lock is None:
        return 1
    retry_generation = message_retry_generation(provider, sid)
    failure = host_session_state(provider, sid).get("startup_failure")
    if host_failure_current(provider, sid, failure, retry_generation):
        host_release(lock)
        return 5 if failure.get("reason") == "agent_not_found" else 4
    paths = host_paths(provider, sid)
    server = None
    agent = None
    try:
        host_reap_agent(provider, sid)
        try:
            paths["socket"].unlink()
        except OSError:
            pass
        state = host_session_state(provider, sid)
        state.pop("startup_failure", None)
        state.update(dict(provider=provider, sid=sid, tmux=tmux, pid=os.getpid(), started=time.time()))
        host_written(state)
        host_write(paths["owner"], dict(owner="host", pid=os.getpid(), at=time.time(), tmux=tmux))
        host_mark_pane(provider, sid)
        host_state_write(provider, sid, state)
        host_log(provider, sid, "start", dict(tmux=tmux))
        server = module.socket(module.AF_UNIX, module.SOCK_STREAM)
        server.bind(str(paths["socket"]))
        os.chmod(paths["socket"], 0o600)
        server.listen(8)
        try:
            agent = host_agent_start(provider, sid)
            reason = "agent_not_found" if agent is None else None
        except (FileNotFoundError, NotADirectoryError, PermissionError) as error:
            reason = "cwd_unavailable" if not os.path.isdir(host_cwd(provider, sid)) else (
                "agent_not_found" if isinstance(error, FileNotFoundError) else "agent_unavailable")
        except RuntimeError as error:
            reason = str(error) if str(error) in ("cwd_unavailable", "agent_not_found", "owner_unavailable") else "owner_unavailable"
        if reason:
            host_startup_failure(provider, sid, tmux, state, reason, retry_generation)
            return 5 if reason == "agent_not_found" else 4
        host_log(provider, sid, "agent_started", dict(pid=agent.pid))
        state["agent_pid"] = agent.pid
        state["mode"] = (host_config(provider, sid) or {}).get("permission_mode") or "default"
        host_state_write(provider, sid, state)
        session = dict(state=state, retry_generation=retry_generation)
        host_loop(provider, sid, tmux, session, server, agent)
        if state.get("startup_failure"):
            return 4
        host_event(provider, sid, tmux, session, "host.status", dict(state="stopped"))
        return 0
    except (OSError, ValueError) as error:
        host_log(provider, sid, "host_error", dict(error=type(error).__name__))
        return 4
    finally:
        host_agent_stop(session.get("agent") if "session" in locals() else agent)
        if server is not None:
            server.close()
        try:
            paths["socket"].unlink()
        except OSError:
            pass
        owner = host_read(paths["owner"])
        if isinstance(owner, dict) and owner.get("pid") == os.getpid():
            try:
                paths["owner"].unlink()
            except OSError:
                pass
        stopped = host_session_state(provider, sid)
        stopped["inflight"] = None
        stopped.pop("agent_pid", None)
        stopped["stopped"] = time.time()
        host_write(paths["state"], stopped)
        host_release(lock)
        host_log(provider, sid, "stop")


def host_status(provider, sid):
    if not host_identity(provider, sid):
        return dict(status="refused", reason="unsupported_provider")
    config = host_config(provider, sid) or {}
    owner = host_owner(provider, sid)
    state = host_session_state(provider, sid)
    written = state.get("written") if isinstance(state.get("written"), dict) else {}
    inflight = state.get("inflight") if isinstance(state.get("inflight"), dict) else None
    return dict(status="ok", enabled=host_enabled(provider, sid), provider=provider, sid=sid,
                owner=owner.get("owner") if isinstance(owner, dict) else None,
                pid=owner.get("pid") if isinstance(owner, dict) else None,
                alive=host_alive(provider, sid), tmux=host_chat_name(provider, sid),
                native_session=state.get("native_session"), inflight=inflight.get("id") if inflight else None,
                accepted=inflight.get("accepted") if inflight else None, written=len(written),
                backoff=round(host_backoff(provider, sid), 3), events=host_log_tail(provider, sid),
                command=config.get("command") if isinstance(config.get("command"), list) else None)


def host_cli(argv):
    if not argv or argv[0] not in ("start", "stop", "status", "enable", "disable", "handover", "adopt", "terminal", "resume", "send"):
        return dict(status="error", reason="invalid_command")
    command, rest = argv[0], argv[1:]
    if len(rest) < 2:
        return dict(status="error", reason="missing_identity")
    provider, sid = rest[0], rest[1]
    if not host_identity(provider, sid):
        return dict(status="refused", reason="unsupported_provider")
    tmux = rest[rest.index("--tmux") + 1] if "--tmux" in rest[:-1] else None
    mode = rest[rest.index("--permission-mode") + 1] if "--permission-mode" in rest[:-1] else None
    folder = None
    if "--cwd" in rest[:-1]:
        folder = host_folder(rest[rest.index("--cwd") + 1])
        if folder is None:
            return dict(status="refused", reason="invalid_cwd")
    if tmux is not None and TMUX_NAME.fullmatch(tmux) is None:
        return dict(status="refused", reason="invalid_chat")
    if mode is not None and mode not in HOST_PERMISSION_MODES:
        return dict(status="refused", reason="invalid_permission_mode")
    paths = host_paths(provider, sid)
    if command == "enable":
        config = host_read(paths["config"]) or {}
        config["enabled"] = True
        if tmux is not None:
            config["tmux"] = tmux
        if folder is not None:
            config["cwd"] = folder
        if mode is not None:
            config["permission_mode"] = mode
        host_write(paths["config"], config)
        if host_handed_over(provider, sid):
            try:
                paths["owner"].unlink()
            except OSError:
                pass
        host_log(provider, sid, "enabled", dict(tmux=config.get("tmux")))
        return dict(status="enabled", sid=sid, tmux=config.get("tmux"), cwd=config.get("cwd"))
    if command == "disable":
        host_stop(provider, sid)
        try:
            paths["config"].unlink()
        except OSError:
            pass
        host_backoff_clear(provider, sid)
        host_log(provider, sid, "disabled")
        return dict(status="disabled", sid=sid)
    if command == "start":
        config = host_read(paths["config"]) or {}
        if config.get("enabled") is True and ((tmux is not None and config.get("tmux") != tmux)
                                             or (folder is not None and config.get("cwd") != folder)
                                             or (mode is not None and config.get("permission_mode") != mode)):
            if tmux is not None:
                config["tmux"] = tmux
            if folder is not None:
                config["cwd"] = folder
            if mode is not None:
                config["permission_mode"] = mode
            host_write(paths["config"], config)
        return host_start(provider, sid)
    if command == "adopt":
        return host_adopt(provider, sid, tmux, folder, mode)
    if command == "terminal":
        return host_terminal(provider, sid, tmux)
    if command == "resume":
        return host_resume(provider, sid, tmux, folder, mode)
    if command == "send":
        words = []
        index = 2
        while index < len(rest):
            if rest[index].startswith("--"):
                index += 2 if rest[index] in ("--tmux", "--text", "--cwd") else 1
                continue
            words.append(rest[index])
            index += 1
        text = rest[rest.index("--text") + 1] if "--text" in rest[:-1] else " ".join(words)
        return host_send(provider, sid, text, tmux)
    if command == "stop":
        return host_stop(provider, sid)
    if command == "handover":
        return host_handover(provider, sid)
    return host_status(provider, sid)


HOST_ADOPT_PANE_TIMEOUT = 20
HOST_ADOPT_PROMPT = re.compile(r"[\$#] ?$")


def host_pane_prompt(tmux):
    try:
        shown = subprocess.run(["tmux", "capture-pane", "-p", "-t", "=" + tmux + ":"],
                               capture_output=True, text=True, timeout=5, check=True).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    lines = [line.rstrip() for line in ANSI.sub("", shown).splitlines() if line.strip()]
    if not lines:
        return None
    return lines[-1] if HOST_ADOPT_PROMPT.search(lines[-1]) else None


HOST_ADOPT_SHELLS = ("bash", "zsh", "sh", "dash", "fish", "ksh")


def host_pane_is_shell(tmux):
    try:
        shown = subprocess.run(["tmux", "display", "-p", "-t", "=" + tmux + ":", "#{pane_current_command}"],
                               capture_output=True, text=True, timeout=5, check=True).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    return shown.strip().split("/")[-1].lower() in HOST_ADOPT_SHELLS


def host_pane_release(tmux, sid):
    if not tmux:
        return dict(status="refused", reason="no_chat")
    bound = resolve_tmux_agent("claude", tmux, require_bound=True)
    if not bound or bound[0] != sid:
        return dict(status="already", reason="no_pane_agent")
    try:
        subprocess.run(["tmux", "send-keys", "-t", "=" + tmux + ":", "-l", "--", "/exit"],
                       capture_output=True, timeout=5, check=True)
        subprocess.run(["tmux", "send-keys", "-t", "=" + tmux + ":", "Enter"],
                       capture_output=True, timeout=5, check=True)
    except (OSError, subprocess.SubprocessError):
        return dict(status="error", reason="pane_input_failed")
    deadline = time.time() + HOST_ADOPT_PANE_TIMEOUT
    while time.time() < deadline:
        current = resolve_tmux_agent("claude", tmux, require_bound=True)
        if not current or current[0] != sid:
            break
        time.sleep(0.2)
    else:
        return dict(status="error", reason="pane_agent_busy")
    deadline = time.time() + HOST_ADOPT_PANE_TIMEOUT
    while time.time() < deadline:
        if host_pane_is_shell(tmux) or host_pane_prompt(tmux) is not None:
            return dict(status="released")
        time.sleep(0.2)
    return dict(status="error", reason="pane_prompt_timeout")


def host_adopt(provider, sid, tmux, cwd=None, mode=None):
    if not host_identity(provider, sid):
        return dict(status="refused", reason="unsupported_provider")
    if tmux is not None and TMUX_NAME.fullmatch(tmux) is None:
        return dict(status="refused", reason="invalid_chat")
    if host_enabled(provider, sid) and host_owned(provider, sid):
        return dict(status="already", reason="host_running")
    released = host_pane_release(tmux, sid)
    if released.get("status") != "released" and released.get("status") != "already":
        return released
    settings = dict(status="ok", released=released.get("status"))
    host_cli(["enable", provider, sid] + (["--tmux", tmux] if tmux else []) + (["--cwd", cwd] if cwd else []) + (["--permission-mode", mode] if mode else []))
    started = host_start(provider, sid)
    settings["start"] = started.get("status")
    settings["reason"] = started.get("reason")
    return settings


def host_dispatch_switch(provider, sid):
    return provider in HOST_PROVIDERS and host_enabled(provider, sid) and not host_handed_over(provider, sid)


def host_dispatch_start(provider, sid):
    if host_backoff(provider, sid) > 0:
        return dict(status="backoff")
    if host_owned(provider, sid):
        return dict(status="owned")
    started = host_start(provider, sid)
    return dict(status="owned" if started.get("status") in ("started", "already") else "start_failed",
                reason=started.get("reason"), permanent=started.get("permanent", False))


def message_store_request(q):
    import fcntl
    provider, sid, tmux = q.get('provider'), q.get('sid'), q.get('tmux')
    if (type(q.get('message_protocol')) is not int or q['message_protocol'] != 1 or
            provider not in HOST_PROVIDERS or not isinstance(sid, str) or not SESSION_ID.fullmatch(sid) or
            not isinstance(tmux, str) or not TMUX_NAME.fullmatch(tmux)):
        raise ValueError('invalid message protocol or identity')
    if q.get('op') not in ('message_dispatch', 'message_reset'):
        return message_store_transaction(q)
    path = message_store_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    lock_path = path.with_suffix('.' + hashlib.sha256((provider + ':' + sid).encode()).hexdigest() + '.dispatch')
    with lock_path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return message_store_transaction(q)


def message_store_transaction(q):
    from contextlib import closing
    import uuid
    provider, sid, tmux = q['provider'], q['sid'], q['tmux']
    op = q.get('op')
    resolved = None
    if op in ('message_submit', 'message_snapshot', 'message_events', 'message_status', 'message_retry', 'message_reset', 'message_dispatch'):
        resolved = resolve_tmux_agent(provider, tmux, require_bound=True)
    with closing(message_store_db(tmux)) as db, db:
        db.execute('BEGIN IMMEDIATE')
        db.execute('INSERT OR IGNORE INTO message_chats(provider,sid) VALUES (?,?)', (provider, sid))
        epoch, seq = db.execute('SELECT epoch,seq FROM message_chats WHERE provider=? AND sid=?', (provider, sid)).fetchone()
        if provider == 'codex':
            saved = db.execute('SELECT state FROM connector_states WHERE provider=? AND sid=?', (provider, sid)).fetchone()
            state = json.loads(saved[0]) if saved else {}
            claims = db.execute("SELECT id,attempt FROM message_attempts WHERE provider=? AND sid=? AND epoch=? AND outcome='accepted'", (provider, sid, epoch)).fetchall()
            facts = [dict(kind='accepted', sid=sid, id=token, attempt=attempt, event_id='durable-queue-ack:' + hashlib.sha256((sid + ':' + token + ':' + attempt).encode()).hexdigest(), provenance='durable_native_queue_ack') for token, attempt in claims]
            if facts:
                message_apply_connector(db, provider, sid, epoch, dict(state=state, facts=facts))
        message_expire(db, provider, sid)
        epoch, seq = db.execute('SELECT epoch,seq FROM message_chats WHERE provider=? AND sid=?', (provider, sid)).fetchone()
        if op in ('message_submit', 'message_snapshot', 'message_events', 'message_status', 'message_retry', 'message_reset'):
            if resolved and resolved[0] == sid:
                db.execute('INSERT OR REPLACE INTO message_locators VALUES (?,?,?)', (provider, sid, tmux))
            elif host_dispatch_switch(provider, sid) and host_chat_name(provider, sid) == tmux:
                db.execute('INSERT OR REPLACE INTO message_locators VALUES (?,?,?)', (provider, sid, tmux))
            if provider == 'claude' and resolved and resolved[0] == sid and op in ('message_submit', 'message_reset'):
                import_legacy_messages(db, sid, resolved[1], epoch)
                seq = db.execute('SELECT seq FROM message_chats WHERE provider=? AND sid=?', (provider, sid)).fetchone()[0]
        if op == 'message_submit':
            token, text = q.get('message_id'), q.get('text')
            if not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_-]{16,80}', token) or not isinstance(text, str) or not text.strip() or '\x00' in text:
                raise ValueError('invalid message')
            replaces = q.get('replaces')
            if isinstance(replaces, str) and replaces:
                if not re.fullmatch(r'[A-Za-z0-9_-]{16,80}', replaces) or replaces == token:
                    raise ValueError('invalid replacement')
                old = db.execute('SELECT * FROM shared_messages WHERE id=?', (replaces,)).fetchone()
                if old:
                    if old[1:3] != (provider, sid):
                        raise ValueError('invalid replacement')
                    aged = old[6] in ('queued', 'sent') and time.time() - float(old[10]) >= 600
                    if old[6] not in ('failed', 'cancelled') and not aged:
                        raise ValueError('message is not retryable')
                    if old[6] != 'cancelled':
                        db.execute("UPDATE shared_messages SET state='cancelled',deadline=NULL WHERE id=? AND state=?", (replaces, old[6]))
                        message_event(db, provider, sid, message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (replaces,)).fetchone()))
            admission_epoch = q.get('admission_epoch')
            if admission_epoch is not None and (type(admission_epoch) is not int or admission_epoch < 0):
                raise ValueError('invalid admission epoch')
            legacy_token = q.get('legacy_token')
            if legacy_token is not None:
                if provider != 'claude' or not isinstance(legacy_token, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', legacy_token) or token != legacy_message_id(legacy_token) or not resolved or resolved[0] != sid:
                    raise ValueError('invalid legacy identity')
                binding = db.execute('SELECT sid,id FROM legacy_bindings WHERE token=?', (legacy_token,)).fetchone()
                if binding and binding != (sid, token):
                    raise ValueError('legacy identity conflict')
                if not binding:
                    info = Path(resolved[1]).stat()
                    db.execute('INSERT INTO legacy_bindings VALUES (?,?,?,?,?,?)', (legacy_token, token, sid, q['queued_at'], info.st_size, info.st_ino))
                legacy_path = next(path for _, name, path in db.execute('PRAGMA database_list') if name == 'legacy')
                db.execute('INSERT OR IGNORE INTO legacy_locations VALUES (?,?)', (legacy_token, legacy_path))
            room_scope = message_room_scope(q['room_scope']) if 'room_scope' in q else None
            if room_scope is not None and (provider != 'claude' or legacy_token is not None):
                raise ValueError('unsupported room provider')
            scope_body = json.dumps(room_scope, sort_keys=True, separators=(',', ':')) if room_scope is not None else None
            existing = db.execute('SELECT * FROM shared_messages WHERE id=?', (token,)).fetchone()
            if existing:
                if existing[1:4] != (provider, sid, text):
                    raise ValueError('message id conflict')
                stored_scope = db.execute('SELECT scope FROM message_room_scopes WHERE id=?', (token,)).fetchone()
                if (stored_scope[0] if stored_scope else None) != scope_body:
                    raise ValueError('room scope conflict')
                if legacy_token is not None:
                    message_event(db, provider, sid, message_row(existing), emit=False)
                return dict(message=message_row(existing), epoch=epoch, seq=seq)
            if admission_epoch is not None and admission_epoch > epoch:
                raise ValueError('future admission epoch')
            position = db.execute('SELECT COALESCE(MAX(position),0)+1 FROM shared_messages WHERE provider=? AND sid=?', (provider, sid)).fetchone()[0]
            state = 'cancelled' if admission_epoch is not None and admission_epoch < epoch else 'queued'
            deadline = None if state == 'cancelled' else time.time() + 15
            db.execute('INSERT INTO shared_messages VALUES (?,?,?,?,?,?,?,?,NULL,?,?)', (token, provider, sid, text, position, uuid.uuid4().hex, state, deadline, epoch, time.time()))
            if scope_body is not None:
                db.execute('INSERT INTO message_room_scopes VALUES (?,?)', (token, scope_body))
            row = message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (token,)).fetchone())
            seq = message_event(db, provider, sid, row)
            return dict(message=row, epoch=epoch, seq=seq)
        if op == 'message_dismiss':
            token = q.get('message_id')
            row = db.execute('SELECT * FROM shared_messages WHERE id=? AND provider=? AND sid=?', (token, provider, sid)).fetchone()
            if not row:
                text = q.get('text')
                if not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_-]{16,80}', token) or not isinstance(text, str) or '\x00' in text or db.execute('SELECT 1 FROM shared_messages WHERE id=?', (token,)).fetchone():
                    raise ValueError('message not found')
                position = db.execute('SELECT COALESCE(MAX(position),0)+1 FROM shared_messages WHERE provider=? AND sid=?', (provider, sid)).fetchone()[0]
                db.execute("INSERT INTO shared_messages VALUES (?,?,?,?,?,?,'cancelled',NULL,NULL,?,?)", (token, provider, sid, text, position, uuid.uuid4().hex, epoch, time.time()))
                row = db.execute('SELECT * FROM shared_messages WHERE id=?', (token,)).fetchone()
                seq = message_event(db, provider, sid, message_row(row))
                return dict(message=message_row(row), epoch=epoch, seq=seq)
            if row[9] != epoch:
                return dict(message=dict(message_row(row), state='cancelled'), epoch=epoch, seq=seq)
            aged = row[6] == 'sent' and time.time() - float(row[10]) >= 600
            if row[6] in ('queued', 'failed') or aged:
                db.execute("UPDATE shared_messages SET state='cancelled',deadline=NULL WHERE id=? AND epoch=? AND state=?", (token, epoch, row[6]))
                row = db.execute('SELECT * FROM shared_messages WHERE id=?', (token,)).fetchone()
                seq = message_event(db, provider, sid, message_row(row))
            elif row[6] != 'cancelled':
                raise ValueError('message already handed off')
            return dict(message=message_row(row), epoch=epoch, seq=seq)
        if op == 'message_retry':
            token, request = q.get('message_id'), q.get('retry_id')
            if not isinstance(token, str) or not isinstance(request, str) or not re.fullmatch(r'[A-Za-z0-9_-]{16,80}', request):
                raise ValueError('invalid retry')
            row = db.execute('SELECT * FROM shared_messages WHERE id=? AND provider=? AND sid=?', (token, provider, sid)).fetchone()
            if not row:
                raise ValueError('message not found')
            previous = db.execute('SELECT attempt FROM message_retries WHERE id=? AND request=?', (token, request)).fetchone()
            if previous:
                return dict(message=message_row(row), epoch=epoch, seq=seq)
            if row[6] != 'failed' or row[9] != epoch:
                raise ValueError('message is not retryable')
            state = host_session_state(provider, sid)
            failure = state.get('startup_failure')
            if (isinstance(failure, dict) and 'retry_generation' not in failure
                    and failure.get('config') == host_config(provider, sid)):
                failure['retry_generation'] = message_retry_generation(provider, sid)
                host_state_write(provider, sid, state)
            attempt = uuid.uuid4().hex
            db.execute('INSERT INTO message_retries VALUES (?,?,?)', (token, request, attempt))
            db.execute("UPDATE shared_messages SET attempt=?,state='queued',deadline=?,receipt=NULL,created=? WHERE id=? AND state='failed' AND epoch=?", (attempt, time.time() + 15, time.time(), token, epoch))
            row = message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (token,)).fetchone())
            seq = message_event(db, provider, sid, row)
            return dict(message=row, epoch=epoch, seq=seq)
        if op == 'message_dispatch':
            row = db.execute("SELECT * FROM shared_messages WHERE provider=? AND sid=? AND state='queued' AND epoch=? ORDER BY position LIMIT 1", (provider, sid, epoch)).fetchone()
            if not row:
                return dict(epoch=epoch, seq=seq, message=None)
            hosting = host_dispatch_switch(provider, sid)
            scope_row = db.execute('SELECT scope FROM message_room_scopes WHERE id=?', (row[0],)).fetchone()
            if scope_row is not None and not hosting:
                return dict(epoch=epoch, seq=seq, message=None)
            if not hosting and (not resolved or resolved[0] != sid):
                db.execute("UPDATE shared_messages SET state='failed',receipt='session_unavailable' WHERE id=? AND state='queued'", (row[0],))
                row = message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (row[0],)).fetchone())
                seq = message_event(db, provider, sid, row)
                return dict(epoch=epoch, seq=seq, message=row)
            if hosting:
                db.commit()
                ready = host_dispatch_start(provider, sid)
                db.execute('BEGIN IMMEDIATE')
                fresh = db.execute('SELECT state,epoch FROM shared_messages WHERE id=?', (row[0],)).fetchone()
                if not fresh or fresh != ('queued', epoch):
                    return dict(epoch=fresh[1] if fresh else epoch, seq=seq, message=None)
                if ready['status'] != 'owned':
                    if ready.get('permanent'):
                        db.execute("UPDATE shared_messages SET state='failed',receipt=? WHERE id=? AND state='queued'", ('host_' + ready['reason'], row[0]))
                        row = message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (row[0],)).fetchone())
                        seq = message_event(db, provider, sid, row)
                        return dict(epoch=epoch, seq=seq, message=row)
                    if ready['status'] == 'start_failed':
                        host_backoff_fail(provider, sid)
                    host_log(provider, sid, 'dispatch_wait', dict(ready=ready['status'], reason=ready.get('reason')))
                    return dict(epoch=epoch, seq=seq, message=None)
            connector = None if hosting else message_connector(provider)
            if not hosting and (connector is None or not callable(connector.get('submit')) or not callable(connector.get('capabilities'))):
                db.execute("UPDATE shared_messages SET state='failed',receipt='connector_unavailable' WHERE id=? AND state='queued'", (row[0],))
                row = message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (row[0],)).fetchone())
                seq = message_event(db, provider, sid, row)
                return dict(epoch=epoch, seq=seq, message=row)
            db.execute("UPDATE shared_messages SET state='sent',deadline=? WHERE id=? AND state='queued'", (time.time() + 15, row[0]))
            db.execute('INSERT OR IGNORE INTO message_attempt_history VALUES (?,?,?)', (row[0], row[5], epoch))
            row = message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (row[0],)).fetchone())
            seq = message_event(db, provider, sid, row)
            db.commit()
            db.execute('BEGIN IMMEDIATE')
            current = db.execute("SELECT state,epoch FROM shared_messages WHERE id=?", (row['id'],)).fetchone()
            if hosting:
                db.commit()
                reply = (host_deliver(provider, sid, row['id'], row['attempt'], epoch, row['text'], room_scope=json.loads(scope_row[0]))
                         if scope_row else host_deliver(provider, sid, row['id'], row['attempt'], epoch, row['text']))
                db.execute('BEGIN IMMEDIATE')
                status = reply.get('status')
                host_log(provider, sid, 'dispatch_delivered', dict(id=row['id'], status=status, reason=reply.get('reason')))
                if status in ('sent', 'duplicate'):
                    host_backoff_clear(provider, sid)
                    return dict(epoch=epoch, seq=seq, message=None)
                if status == 'refused':
                    changed = db.execute("UPDATE shared_messages SET state='failed',deadline=NULL,receipt=? WHERE id=? AND attempt=? AND epoch=? AND state='sent'", ('host_' + str(reply.get('reason') or 'not_sent'), row['id'], row['attempt'], epoch)).rowcount
                    if changed:
                        message_event(db, provider, sid, message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (row['id'],)).fetchone()))
                    return dict(epoch=epoch, seq=seq, message=None)
                changed = db.execute("UPDATE shared_messages SET state='queued',deadline=? WHERE id=? AND attempt=? AND epoch=? AND state='sent'", (time.time() + 15, row['id'], row['attempt'], epoch)).rowcount
                if changed:
                    message_event(db, provider, sid, message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (row['id'],)).fetchone()))
                if status != 'busy':
                    host_backoff_fail(provider, sid)
                return dict(epoch=epoch, seq=seq, message=None)
            db.commit()
            bound = resolve_tmux_agent(provider, tmux, require_bound=True)
            if current != ('sent', epoch) or not bound or bound[0] != sid or Path(bound[1]) != Path(resolved[1]):
                return dict(epoch=epoch, seq=seq, message=None)
            context = dict(provider=provider, sid=sid, tmux=tmux, chat_id=tmux, transcript_path=str(bound[1]))
            if provider == 'codex':
                context.update(codex_queue_attachment(tmux, sid, bound[1]) or {})
            capabilities = connector['capabilities'](context)
            if not isinstance(capabilities, dict) or capabilities.get('protocol') != 1 or capabilities.get('provider') != provider:
                raise ValueError('incompatible connector')
            saved = db.execute('SELECT state FROM connector_states WHERE provider=? AND sid=?', (provider, sid)).fetchone()
            state = json.loads(saved[0]) if saved else {}
            services = message_services(tmux, provider, sid)
            db.commit()
            try:
                result = connector['submit'](context, row, state, services)
            except Exception:
                result = dict(handoff='uncertain', facts=[], state=state)
            db.execute('BEGIN IMMEDIATE')
            current_epoch = db.execute('SELECT epoch FROM message_chats WHERE provider=? AND sid=?', (provider, sid)).fetchone()[0]
            if current_epoch == epoch:
                message_apply_connector(db, provider, sid, epoch, result)
                if result.get('handoff') == 'not_sent':
                    changed = db.execute("UPDATE shared_messages SET state='failed',deadline=NULL,receipt='not_sent' WHERE id=? AND attempt=? AND epoch=? AND state='sent'", (row['id'], row['attempt'], epoch)).rowcount
                    if changed:
                        message_event(db, provider, sid, message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (row['id'],)).fetchone()))
            row = message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (row['id'],)).fetchone())
            seq = db.execute('SELECT seq FROM message_chats WHERE provider=? AND sid=?', (provider, sid)).fetchone()[0]
            return dict(epoch=current_epoch, seq=seq, message=row, handoff=result.get('handoff', 'uncertain'))
        if op == 'message_reset':
            request = q.get('reset_id')
            if not isinstance(request, str) or not re.fullmatch(r'[A-Za-z0-9_-]{16,80}', request):
                raise ValueError('invalid reset id')
            previous = db.execute('SELECT epoch,seq FROM message_resets WHERE provider=? AND sid=? AND request=?', (provider, sid, request)).fetchone()
            if previous:
                return dict(epoch=previous[0], seq=previous[1])
            db.execute('UPDATE message_chats SET epoch=epoch+1 WHERE provider=? AND sid=?', (provider, sid))
            epoch += 1
            db.execute("UPDATE shared_messages SET epoch=? WHERE provider=? AND sid=? AND state IN ('accepted','started')", (epoch, provider, sid))
            rows = db.execute("SELECT id FROM shared_messages WHERE provider=? AND sid=? AND state IN ('queued','sent','failed') ORDER BY position", (provider, sid)).fetchall()
            for (token,) in rows:
                db.execute("UPDATE shared_messages SET state='cancelled',deadline=NULL WHERE id=?", (token,))
                message_event(db, provider, sid, message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (token,)).fetchone()))
            db.execute("UPDATE legacy.messages SET state='cancelled', receipt='@mp-shared-v1:reset:' || key WHERE sid=? AND state IN ('queued','submitted')", (sid,))
            seq = message_event(db, provider, sid, dict(reset_epoch=epoch))
            db.execute('INSERT INTO message_resets VALUES (?,?,?,?,?)', (provider, sid, request, epoch, seq))
            return dict(epoch=epoch, seq=seq)
        if op == 'message_events':
            after = q.get('after')
            if type(after) is not int or after < 0:
                raise ValueError('invalid event cursor')
            return dict(epoch=epoch, seq=seq, **message_events_rows(db, provider, sid, after))
        if op == 'message_snapshot':
            snapshot = q.get('snapshot_id')
            cursor = q.get('cursor', 0)
            if type(cursor) is not int or cursor < 0:
                raise ValueError('invalid snapshot cursor')
            if snapshot is None:
                db.execute('DELETE FROM message_snapshots WHERE created<?', (time.time() - 600,))
                db.execute('DELETE FROM message_snapshots WHERE id IN (SELECT id FROM message_snapshots WHERE provider=? AND sid=? ORDER BY created DESC LIMIT -1 OFFSET 3)', (provider, sid))
                snapshot = uuid.uuid4().hex
                items = [dict(message=message_row(row)) for row in db.execute('SELECT * FROM shared_messages WHERE provider=? AND sid=? ORDER BY position', (provider, sid))]
                items.extend(dict(native_message=json.loads(body)) for (body,) in db.execute("SELECT body FROM connector_facts WHERE provider=? AND sid=? AND json_extract(body,'$.kind')='message' ORDER BY rowid", (provider, sid)))
                items.extend(dict(attempt_receipt=json.loads(body)) for (body,) in db.execute("SELECT body FROM connector_facts WHERE provider=? AND sid=? AND json_extract(body,'$.kind') IN ('accepted','started') ORDER BY rowid", (provider, sid)))
                if provider == "claude" and host_enabled(provider, sid):
                    settings = host_settings(provider, sid)
                    last = db.execute("SELECT body FROM message_events WHERE provider=? AND sid=? AND json_extract(body,'$.kind')='host.settings' ORDER BY seq DESC LIMIT 1", (provider, sid)).fetchone()
                    payload = json.loads(last[0]).get("payload", {}) if last else {}
                    applied = (host_owned(provider, sid) and payload.get("applied") is True
                               and payload.get("effort") == settings["effort"]
                               and (not settings["model"] or payload.get("model") == settings["model"]))
                    if applied and not settings["model"]:
                        settings["model"] = host_model_name(payload.get("model"))
                    items.append(dict(host_settings=dict(settings, applied=applied, mode=host_permission_mode(provider, sid))))
                db.execute('INSERT INTO message_snapshots VALUES (?,?,?,?,?,?,?)', (snapshot, provider, sid, epoch, seq, time.time(), json.dumps(items, separators=(',', ':'))))
            else:
                saved = db.execute('SELECT epoch,seq,body FROM message_snapshots WHERE id=? AND provider=? AND sid=?', (snapshot, provider, sid)).fetchone()
                if not saved or saved[0] != epoch:
                    raise ValueError('snapshot expired or reset')
                epoch, seq, body = saved
                items = json.loads(body)
            if cursor > len(items):
                raise ValueError('invalid snapshot cursor')
            end, size = cursor, 0
            while end < len(items) and end - cursor < 128:
                item_size = len(json.dumps(items[end], ensure_ascii=True, separators=(',', ':')).encode())
                if end > cursor and size + item_size > 512 * 1024:
                    break
                size += item_size
                end += 1
            return dict(snapshot_id=snapshot, epoch=epoch, seq=seq, items=items[cursor:end], next_cursor=end if end < len(items) else None)
        if op == 'message_status':
            row = db.execute('SELECT * FROM shared_messages WHERE id=? AND provider=? AND sid=?', (q.get('message_id'), provider, sid)).fetchone()
            return dict(epoch=epoch, seq=seq, message=message_row(row) if row else None)
        raise ValueError('invalid message operation')


def message_poll(tmux, provider, sid):
    import fcntl
    path = message_store_path()
    lock_path = path.with_suffix('.' + hashlib.sha256((provider + ':' + sid).encode()).hexdigest() + '.dispatch')
    with lock_path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        message_poll_locked(tmux, provider, sid)


def message_poll_locked(tmux, provider, sid):
    from contextlib import closing
    connector = message_connector(provider)
    if connector is None or not callable(connector.get('poll')):
        return
    resolved = resolve_tmux_agent(provider, tmux, require_bound=True)
    if not resolved or resolved[0] != sid:
        return
    with closing(message_store_db(tmux)) as db:
        chat = db.execute('SELECT epoch FROM message_chats WHERE provider=? AND sid=?', (provider, sid)).fetchone()
        if not chat:
            return
        epoch = chat[0]
        saved = db.execute('SELECT state FROM connector_states WHERE provider=? AND sid=?', (provider, sid)).fetchone()
        state = json.loads(saved[0]) if saved else {}
        pending = [message_row(row) for row in db.execute("SELECT * FROM shared_messages WHERE provider=? AND sid=? AND epoch=? AND state IN ('sent','failed','accepted') ORDER BY position", (provider, sid, epoch))]
        context = dict(provider=provider, sid=sid, tmux=tmux, chat_id=tmux, transcript_path=str(resolved[1]))
        if provider == 'codex':
            context.update(codex_queue_attachment(tmux, sid, resolved[1]) or {})
        services = message_services(tmux, provider, sid)
        result = connector['poll'](context, state, pending, services)
        with db:
            db.execute('BEGIN IMMEDIATE')
            current = db.execute('SELECT epoch FROM message_chats WHERE provider=? AND sid=?', (provider, sid)).fetchone()
            if current and current[0] == epoch:
                message_apply_connector(db, provider, sid, epoch, result)


def start_message_worker(q, source, only_if_work=False):
    if source is None:
        return
    if only_if_work:
        from contextlib import closing
        with closing(message_store_db(q['tmux'])) as db:
            if not db.execute("SELECT 1 FROM shared_messages WHERE provider=? AND sid=? AND state IN ('queued','sent','failed','accepted','started') LIMIT 1", (q['provider'], q['sid'])).fetchone():
                return
    import fcntl
    path = message_store_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    lock_path = path.with_suffix('.' + hashlib.sha256((q['provider'] + ':' + q['sid']).encode()).hexdigest() + '.message-worker')
    with lock_path.open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
    bootstrap = "import base64,sys;s=base64.b64decode(sys.stdin.buffer.readline()).decode();exec(compile(s,'<message-worker>','exec'),dict(__name__='__main__',READER_SOURCE=s))"
    child = subprocess.Popen([sys.executable, '-u', '-c', bootstrap, '--message-worker', q['tmux'], q['provider'], q['sid']], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    try:
        child.stdin.write(base64.b64encode(source.encode()) + b'\n')
    finally:
        child.stdin.close()


def superseded_engine():
    engine = globals().get('ENGINE_INFO')
    if not isinstance(engine, dict):
        return False
    try:
        active = json.loads((HOME / '.multiprompt-runtime' / 'active.json').read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return False
    return isinstance(active, dict) and active.get('sha256') not in (None, engine.get('sha256'))


class MessageWorkerTimeout(BaseException):
    pass


@contextmanager
def message_worker_deadline(seconds):
    import signal
    if seconds <= 0:
        raise MessageWorkerTimeout()
    if threading.current_thread() is not threading.main_thread():
        yield
        return

    def expired(signum, frame):
        raise MessageWorkerTimeout()

    previous = signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def message_worker(tmux, provider, sid):
    import fcntl
    import selectors
    from contextlib import closing
    path = message_store_path()
    lock_path = path.with_suffix('.' + hashlib.sha256((provider + ':' + sid).encode()).hexdigest() + '.message-worker')
    with lock_path.open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        request = dict(message_protocol=1, tmux=tmux, provider=provider, sid=sid)
        idle_since = time.time()
        head = None
        stalled_at = time.monotonic()
        with selectors.DefaultSelector() as selector:
            while True:
                try:
                    if head is None:
                        stalled_at = time.monotonic()
                    with message_worker_deadline(MESSAGE_WORKER_STALL_SECONDS - (time.monotonic() - stalled_at)):
                        with closing(message_store_db(tmux)) as db:
                            locator = db.execute('SELECT tmux FROM message_locators WHERE provider=? AND sid=?', (provider, sid)).fetchone()
                        hosting = host_dispatch_switch(provider, sid)
                        if locator:
                            tmux = locator[0]
                        elif hosting:
                            tmux = host_chat_name(provider, sid)
                            if tmux is None:
                                host_log(provider, sid, 'worker_wait', dict(reason='chat_name_unset'))
                                return
                            host_log(provider, sid, 'worker_locator', dict(reason='host_chat_name', tmux=tmux))
                        else:
                            host_log(provider, sid, 'worker_stop', dict(reason='no_locator'))
                            return
                        bound = resolve_tmux_agent(provider, tmux, require_bound=True)
                        if (not bound or bound[0] != sid) and not hosting:
                            host_log(provider, sid, 'worker_stop', dict(reason='no_pane_agent'))
                            return
                        request['tmux'] = tmux
                        if superseded_engine():
                            return
                        with closing(message_store_db(tmux)) as db:
                            pending = db.execute("SELECT id FROM shared_messages WHERE provider=? AND sid=? AND state IN ('queued','sent') ORDER BY position LIMIT 1", (provider, sid)).fetchone()
                        if pending != head:
                            head = pending
                            stalled_at = time.monotonic()
                        message_poll(tmux, provider, sid)
                        message_store_request(dict(request, op='message_dispatch'))
                        with closing(message_store_db(tmux)) as db:
                            queued, deadline, observed = db.execute("SELECT SUM(state='queued'),MIN(CASE WHEN state='sent' THEN deadline END),SUM(state IN ('failed','accepted','started')) FROM shared_messages WHERE provider=? AND sid=?", (provider, sid)).fetchone()
                        if not queued and deadline is None:
                            if not observed or message_connector(provider) is None:
                                return
                            if time.time() - idle_since >= MESSAGE_WORKER_IDLE_SECONDS:
                                return
                        else:
                            idle_since = time.time()
                        timeout = max(0.0, deadline - time.time()) if deadline is not None else 1.0
                        timeout = min(timeout, 1.0)
                    selector.select(timeout)
                except MessageWorkerTimeout:
                    with closing(message_store_db(tmux)) as db, db:
                        db.execute('BEGIN IMMEDIATE')
                        row = db.execute("SELECT id FROM shared_messages WHERE provider=? AND sid=? AND state IN ('queued','sent') ORDER BY position LIMIT 1", (provider, sid)).fetchone()
                        if row:
                            db.execute("UPDATE shared_messages SET state='failed',deadline=NULL,receipt='watchdog_timeout' WHERE id=?", row)
                            message_event(db, provider, sid, message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', row).fetchone()))
                    host_log(provider, sid, 'worker_watchdog', dict(reason='no_progress', id=row[0] if row else None))
                    return
                except (OSError, ValueError, subprocess.SubprocessError):
                    return


def held_message_key(q):
    text, stamp, token = q.get("text"), q.get("queued_at"), q.get("message_id")
    if (not isinstance(text, str) or not text.strip() or "\x00" in text or type(stamp) is not int or stamp <= 0 or
            not isinstance(token, str) or re.fullmatch(r"[A-Za-z0-9_-]{1,80}", token) is None):
        raise ValueError("invalid held message")
    return token


def start_held_worker(tmux, sid, transcript, source):
    if source is None or not held_queue_path(tmux).exists():
        return
    import fcntl
    from contextlib import closing
    with closing(held_queue_db(tmux)) as db:
        if not db.execute("SELECT 1 FROM messages WHERE sid=? AND state IN ('queued','submitted') LIMIT 1", (sid,)).fetchone():
            return
    with held_queue_path(tmux).with_suffix("." + hashlib.sha256(sid.encode()).hexdigest() + ".worker").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
    bootstrap = "import base64,sys;s=base64.b64decode(sys.stdin.buffer.readline()).decode();exec(compile(s,'<held-queue-worker>','exec'),dict(__name__='__main__',READER_SOURCE=s))"
    child = subprocess.Popen([sys.executable, "-u", "-c", bootstrap, "--held-worker", tmux, sid, str(transcript)],
                             stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    try:
        child.stdin.write(base64.b64encode(source.encode()) + b"\n")
    finally:
        child.stdin.close()


def held_queue_worker(tmux, sid, transcript):
    import ctypes
    import fcntl
    import selectors
    import struct
    from contextlib import closing
    with held_queue_path(tmux).with_suffix("." + hashlib.sha256(sid.encode()).hexdigest() + ".worker").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        libc = ctypes.CDLL(None, use_errno=True)
        libc.inotify_init1.argtypes = [ctypes.c_int]
        libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
        fd = libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
        if fd < 0:
            raise OSError(ctypes.get_errno(), "inotify_init1")
        control = None
        try:
            for directory in {transcript.parent, held_queue_path(tmux).parent}:
                if libc.inotify_add_watch(fd, os.fsencode(directory), 0x182) < 0:
                    raise OSError(ctypes.get_errno(), "inotify_add_watch")
            control = subprocess.Popen(["tmux", "-C", "attach-session", "-f", "read-only,ignore-size", "-t", "=" + tmux],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            with selectors.DefaultSelector() as selector:
                selector.register(fd, selectors.EVENT_READ, "file")
                selector.register(control.stdout, selectors.EVENT_READ, "tmux")
                while True:
                    current = resolve_tmux_agent("claude", tmux, require_bound=True)
                    if current is None or current[0] != sid or Path(current[1]) != Path(transcript):
                        return
                    progressing = drain_held_queue(tmux, sid, transcript)
                    if progressing:
                        time.sleep(0.01)
                        continue
                    with closing(held_queue_db(tmux)) as db:
                        pending = db.execute("SELECT 1 FROM messages WHERE sid=? AND state IN ('queued','submitted') LIMIT 1", (sid,)).fetchone()
                    if not pending:
                        return
                    changed = False
                    while not changed:
                        for key, _mask in selector.select():
                            data = os.read(key.fd, 65536)
                            if not data:
                                return
                            if key.data == "tmux":
                                changed = True
                                continue
                            at = 0
                            while at + 16 <= len(data):
                                _wd, mask, _cookie, length = struct.unpack_from("iIII", data, at)
                                name = os.fsdecode(data[at + 16:at + 16 + length].split(b"\0", 1)[0])
                                at += 16 + length
                                if mask & 0x4000 or name in (transcript.name, held_queue_path(tmux).name):
                                    changed = True
        finally:
            os.close(fd)
            if control is not None:
                control.terminate()
                control.communicate()


def legacy_projection(tmux, sid, transcript, ids):
    from contextlib import closing
    with closing(message_store_db(tmux)) as db, db:
        db.execute('BEGIN IMMEDIATE')
        db.execute("INSERT OR IGNORE INTO message_chats(provider,sid) VALUES ('claude',?)", (sid,))
        epoch = db.execute("SELECT epoch FROM message_chats WHERE provider='claude' AND sid=?", (sid,)).fetchone()[0]
        import_legacy_messages(db, sid, transcript, epoch)
        db.execute("INSERT OR REPLACE INTO message_locators VALUES ('claude',?,?)", (sid, tmux))
        path = next(path for _, name, path in db.execute('PRAGMA database_list') if name == 'legacy')
        for token in ids:
            row = db.execute('SELECT m.* FROM legacy_bindings b JOIN shared_messages m ON m.id=b.id WHERE b.token=? AND b.sid=?', (token, sid)).fetchone()
            if row:
                db.execute('INSERT OR IGNORE INTO legacy_locations VALUES (?,?)', (token, path))
                message_event(db, 'claude', sid, message_row(row), emit=False)
        rows = db.execute("SELECT b.token,m.text,b.queued_at,m.state,b.origin,b.inode,m.receipt FROM legacy_bindings b JOIN shared_messages m ON m.id=b.id WHERE b.sid=? ORDER BY m.position", (sid,)).fetchall()
        held = [dict(id=token, text=text, queued_at=stamp, state=legacy_wire_state(state), offset=origin, inode=inode, sid=sid) for token, text, stamp, state, origin, inode, detail in rows if state in ('queued', 'sent')]
        deliveries = [dict(id=token, state=legacy_wire_state(state), detail=('Delivery unconfirmed; check the agent before sending again' if state == 'failed' else detail or '')) for token, text, stamp, state, origin, inode, detail in rows if token in ids]
        return held, deliveries


def enqueue_response(q, source=None):
    from contextlib import closing
    key = held_message_key(q)
    name = q["tmux"]
    resolved = resolve_tmux_agent("claude", name, require_bound=True)
    if not resolved:
        return f"@mp\t{q['id']}\tsend\terror\tagent_unavailable\t0\n{END}\n"
    sid, transcript = resolved
    request = dict(op='message_submit', message_protocol=1, provider='claude', sid=sid, tmux=name,
                   message_id=legacy_message_id(key), legacy_token=key, text=q['text'], queued_at=q['queued_at'])
    result = message_store_request(request)
    start_message_worker(request, source)
    with closing(message_store_db(name)) as db:
        identity = db.execute('SELECT sid,inode,origin FROM legacy_bindings WHERE token=?', (key,)).fetchone()
    message = result['message']
    receipt = wire_row(dict(role='delivery', message_id=key, sid=identity[0], inode=identity[1], offset=identity[2], state=legacy_wire_state(message['state']), detail=message['receipt'] or ''))
    return f"@mp\t{q['id']}\tsend\tok\t-\t0\n{receipt}\n{END}\n"


def cancel_queued_response(q):
    from contextlib import closing
    key = held_message_key(q)
    reason = "not_queued"
    resolved = resolve_tmux_agent('claude', q['tmux'], require_bound=True)
    if not resolved:
        return f"@mp\t{q['id']}\tcancel_queued\terror\tagent_unavailable\t0\n{END}\n"
    sid = resolved[0]
    with closing(message_store_db(q['tmux'])) as db, db:
        db.execute('BEGIN IMMEDIATE')
        binding = db.execute('SELECT id FROM legacy_bindings WHERE token=? AND sid=?', (key, sid)).fetchone()
        if binding:
            changed = db.execute("UPDATE shared_messages SET state='cancelled' WHERE id=? AND sid=? AND state='queued'", (binding[0], sid)).rowcount
            if changed:
                message_event(db, 'claude', sid, message_row(db.execute('SELECT * FROM shared_messages WHERE id=?', (binding[0],)).fetchone()))
            if changed or db.execute("SELECT 1 FROM shared_messages WHERE id=? AND sid=? AND state='cancelled'", (binding[0], sid)).fetchone():
                reason = None
    return f"@mp\t{q['id']}\tcancel_queued\t{'error' if reason else 'ok'}\t{reason or '-'}\t0\n{END}\n"


def held_queue_ready(tmux, path):
    shown = subprocess.run(["tmux", "capture-pane", "-p", "-e", "-J", "-t", "=" + tmux + ":"],
                           capture_output=True, text=True, timeout=5, check=True).stdout
    plain = ANSI.sub("", shown)
    draft = box_draft_text(shown)
    if draft is None or draft.strip() or "❯" not in plain:
        return False
    lower = plain.lower()
    if any(hint in lower for hint in ("edit queued messages", "select a queued message", "enter to select",
                                       "esc to cancel")):
        return False
    if any(hint in lower for hint in ("esc to interrupt", "ctrl+c to interrupt")):
        return True
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
        current = resolve_tmux_agent("claude", tmux, require_bound=True)
        if current is None or current[0] != sid or Path(current[1]) != Path(transcript):
            return
        with transcript.open("rb") as source, db:
            db.execute("BEGIN IMMEDIATE")
            if db.execute("SELECT 1 FROM v1_admission WHERE sid=?", (sid,)).fetchone():
                return
            info = os.fstat(source.fileno())
            db.execute("UPDATE messages SET recovery_scan=origin WHERE recovery_scan IS NULL")
            pending = db.execute("SELECT key,text,recovery_scan,origin,source_inode,state,created FROM messages WHERE sid=? AND state IN ('submitted','queued') AND receipt NOT LIKE '@mp-shared-v1:%' ORDER BY CASE state WHEN 'submitted' THEN 0 ELSE 1 END,created", (sid,)).fetchall()
            if any(inode != info.st_ino or origin > info.st_size or scan > info.st_size for _, _, scan, origin, inode, _, _ in pending):
                return
            deferred = []
            unfinished = False
            progressing = False
            delivered = db.execute("SELECT text,created,source_inode,origin FROM messages WHERE sid=? AND state='delivered'", (sid,)).fetchall()
            for key, text, scanned, origin, inode, state, created in pending:
                claim = db.execute("SELECT offset,digest FROM held_receipts WHERE key=? AND sid=? AND inode=? AND offset>=? ORDER BY offset LIMIT 1", (key, sid, inode, origin)).fetchone()
                if claim:
                    offset, digest = claim
                    source.seek(offset)
                    raw = source.readline()
                    if not raw.endswith(b"\n") or hashlib.sha256(raw).hexdigest() != digest:
                        return
                    event = load_line(raw)
                    if not isinstance(event, dict) or event.get("type") not in ("user", "attachment") or event.get("isSidechain") or not any(row["role"] == "user" and row["text"].replace("\r", "").rstrip(" ") == text.replace("\r", "").rstrip(" ") for row in conversation_rows(event, "claude", offset)):
                        return
                    if state == "submitted":
                        delivered.append((text, created, inode, origin))
                        deferred.append((offset + len(raw), event.get("timestamp", ""), key))
                    else:
                        delivered.append((text, created, inode, origin))
                        db.execute("UPDATE messages SET scan=?1,recovery_scan=?1,state='delivered',receipt=?2 WHERE key=?3 AND state='queued'", (offset + len(raw), event.get("timestamp", ""), key))
                    continue
                scanned = max(scanned, origin)
                source.seek(scanned)
                data = source.read(min(TAIL_BYTES, info.st_size - scanned))
                if b"\n" not in data and scanned + len(data) < info.st_size:
                    source.seek(scanned)
                    data = source.readline(info.st_size - scanned)
                complete = data.rfind(b"\n") + 1
                consumed, receipt = scanned, None
                for raw in data[:complete].splitlines(keepends=True):
                    offset = consumed
                    consumed += len(raw)
                    event = load_line(raw)
                    if isinstance(event, dict) and event.get("type") in ("user", "attachment") and not event.get("isSidechain"):
                        if any(row["role"] == "user" and row["text"].replace("\r", "").rstrip(" ") == text.replace("\r", "").rstrip(" ") for row in conversation_rows(event, "claude", offset)):
                            owner = db.execute("SELECT key FROM held_receipts WHERE sid=? AND inode=? AND offset=?", (sid, inode, offset)).fetchone()
                            if owner and owner[0] != key:
                                continue
                            if state == "queued" and any(old_created < created and old_inode == inode and old_origin <= offset and old_text.replace("\r", "").rstrip(" ") == text.replace("\r", "").rstrip(" ") for old_text, old_created, old_inode, old_origin in delivered):
                                consumed = offset
                                unfinished = True
                                break
                            claimed = db.execute("INSERT OR IGNORE INTO held_receipts(sid,inode,offset,key,digest) VALUES (?,?,?,?,?)", (sid, inode, offset, key, hashlib.sha256(raw).hexdigest())).rowcount
                            owner = db.execute("SELECT key FROM held_receipts WHERE sid=? AND inode=? AND offset=?", (sid, inode, offset)).fetchone()[0]
                            if claimed or owner == key:
                                receipt = event.get("timestamp", "")
                                break
                if receipt is not None and state == "submitted":
                    delivered.append((text, created, inode, origin))
                    deferred.append((consumed, receipt, key))
                elif receipt is not None:
                    delivered.append((text, created, inode, origin))
                    db.execute("UPDATE messages SET scan=?1,recovery_scan=?1,state='delivered',receipt=?2 WHERE key=?3 AND state='queued'", (consumed, receipt, key))
                else:
                    progressing |= consumed > scanned
                    db.execute("UPDATE messages SET scan=?1,recovery_scan=?1 WHERE key=?2 AND state=?3 AND (scan!=?1 OR recovery_scan!=?1)", (consumed, key, state))
                    if consumed < info.st_size:
                        unfinished = True
            if unfinished:
                return progressing
            for consumed, receipt, key in deferred:
                db.execute("UPDATE messages SET scan=?1,recovery_scan=?1,state='delivered',receipt=?2 WHERE key=?3 AND state='submitted'", (consumed, receipt, key))
            if db.execute("SELECT 1 FROM messages WHERE sid=? AND state='submitted' LIMIT 1", (sid,)).fetchone():
                return
            if not db.execute("SELECT 1 FROM messages WHERE sid=? AND state='queued' LIMIT 1", (sid,)).fetchone():
                return
        if not held_queue_ready(tmux, transcript):
            return
        current = resolve_tmux_agent("claude", tmux, require_bound=True)
        if current is None or current[0] != sid or Path(current[1]) != Path(transcript):
            return
        with db:
            db.execute("BEGIN IMMEDIATE")
            if db.execute("SELECT 1 FROM messages WHERE sid=? AND state='submitted' LIMIT 1", (sid,)).fetchone():
                return
            pending = db.execute("SELECT key,text,source_inode FROM messages WHERE sid=? AND state='queued' ORDER BY created LIMIT 1", (sid,)).fetchone()
            if pending is None:
                return
            key, text, inode = pending
            info = transcript.stat()
            if info.st_ino != inode or db.execute("SELECT 1 FROM messages WHERE sid=? AND state='queued' AND (source_inode!=? OR recovery_scan IS NULL OR recovery_scan<?) LIMIT 1", (sid, info.st_ino, info.st_size)).fetchone():
                return
            db.execute("UPDATE messages SET state='submitted',scan=?1,recovery_scan=?1 WHERE key=?2 AND state='queued'", (info.st_size, key))
        try:
            with db:
                db.execute("BEGIN IMMEDIATE")
                current = resolve_tmux_agent("claude", tmux, require_bound=True)
                if current is None or current[0] != sid or Path(current[1]) != Path(transcript):
                    return
                info = transcript.stat()
                if info.st_ino != inode or not db.execute("SELECT 1 FROM messages WHERE key=? AND sid=? AND state='submitted' AND source_inode=? AND origin<=? AND recovery_scan<=?", (key, sid, info.st_ino, info.st_size, info.st_size)).fetchone():
                    return
                result = send_response(dict(id=0, tmux=tmux, text=text))
            failure = "Terminal handoff failed; receipt not confirmed" if "\tsend\terror\t" in result else ""
        except (OSError, subprocess.SubprocessError, UnicodeError):
            failure = "Terminal handoff interrupted; receipt not confirmed"
        if failure:
            with db:
                db.execute("UPDATE messages SET receipt=? WHERE key=? AND state='submitted'", (failure, key))


def held_queue_fields(tmux, sid, with_receipts=False):
    from contextlib import closing
    if not held_queue_path(tmux).exists():
        return dict(queue=[], sent=[])
    with closing(held_queue_db(tmux)) as db:
        queued = db.execute("SELECT text FROM messages WHERE sid=? AND state='queued' ORDER BY created LIMIT ?", (sid, QUEUE_SHOWN)).fetchall()
        sql = "SELECT key,text,source_inode,origin FROM messages WHERE sid=? AND state='delivered' AND receipt NOT LIKE '@mp-shared-v1:%' ORDER BY created DESC LIMIT ?"
        sent = db.execute(sql, (sid, QUEUE_SHOWN)).fetchall()
        result = []
        for key, text, inode, origin in reversed(sent):
            claims = db.execute("SELECT inode,offset,digest FROM held_receipts WHERE key=? AND sid=? AND inode=? AND offset>=? LIMIT 2", (key, sid, inode, origin)).fetchall() if with_receipts else []
            result.append((text, claims[0] if len(claims) == 1 else None) if with_receipts else text)
    return dict(queue=[row[0] for row in queued], sent=result)


def send_response(q):
    rid = q.get("id", 0)
    name = q.get("tmux")
    text = q.get("text")
    reason = None
    if not isinstance(name, str) or re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", name) is None:
        reason = "invalid_tmux_name"
    elif not isinstance(text, str) or not text or "\x00" in text:
        reason = "invalid_text"
    elif host_pane_claimed(name):
        reason = "host_owns_chat"
    else:
        try:
            target = "=" + name + ":"
            wanted = "".join(text.split())
            box = prompt_box_text(target)
            if wanted and wanted == "".join(box.split()):
                pass
            else:
                box, stuck = clear_prompt_box(target, box)
                if not stuck and not box_is_empty(box):
                    reason = "box_not_clear"
                if reason is None:
                    if "\n" in text or "\r" in text:
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
            if reason is None:
                time.sleep(0.15)
                subprocess.run(["tmux", "send-keys", "-t", target, "Enter"], check=True, capture_output=True, timeout=5)
                time.sleep(0.4)
                if wanted and wanted in "".join(prompt_box_text(target).split()):
                    subprocess.run(["tmux", "send-keys", "-t", target, "Enter"], check=True, capture_output=True, timeout=5)
        except (OSError, subprocess.SubprocessError, UnicodeError):
            reason = "tmux_send_failed"
    status = "error" if reason else "ok"
    op = "question_text" if q.get("op") == "question_text" else "send"
    return f"@mp\t{rid}\t{op}\t{status}\t{reason or '-'}\t0\n{END}\n"


def type_into_box(target, text, rid):
    if "\n" in text or "\r" in text:
        buffer = "mp-draft-" + str(os.getpid()) + "-" + str(rid)
        subprocess.run(["tmux", "load-buffer", "-b", buffer, "-"], input=text.encode("utf-8"), check=True, capture_output=True, timeout=5)
        try:
            pasted = subprocess.run(["tmux", "paste-buffer", "-p", "-d", "-b", buffer, "-t", target], capture_output=True, timeout=5)
            if pasted.returncode:
                subprocess.run(["tmux", "send-keys", "-t", target, "-l", "--", text], check=True, capture_output=True, timeout=5)
        finally:
            subprocess.run(["tmux", "delete-buffer", "-b", buffer], capture_output=True, timeout=5)
    else:
        subprocess.run(["tmux", "send-keys", "-t", target, "-l", "--", text], check=True, capture_output=True, timeout=5)


DRAFT_ECHO_SECONDS = 10


def draft_record_path(tmux):
    return HOME / ".local/state/multiprompt/shell-drafts" / (hashlib.sha256(tmux.encode()).hexdigest() + ".json")


def draft_record(tmux):
    try:
        value = json.loads(draft_record_path(tmux).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def draft_note(tmux, **fields):
    path = draft_record_path(tmux)
    try:
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        value = draft_record(tmux)
        value.update(fields)
        temporary = path.with_name(path.name + "." + str(os.getpid()) + ".tmp")
        temporary.write_text(json.dumps(value), encoding="utf-8")
        os.replace(temporary, path)
    except OSError:
        pass


def squashed(text):
    return "".join(text.split())


def own_draft(box, text, typed, sent=''):
    shown = squashed(box)
    return not shown or squashed(text).startswith(shown) or bool(typed) and squashed(typed).startswith(shown) or bool(sent) and shown == squashed(sent)


def late_draft_echo(tmux, text):
    record = draft_record(tmux)
    sent, at = record.get("sent"), record.get("sent_at")
    if not text.strip() or not isinstance(sent, str) or not isinstance(at, (int, float)) or not 0 <= time.time() - at < DRAFT_ECHO_SECONDS:
        return False
    return squashed(sent).startswith(squashed(text))


def restore_displaced_draft(name, target, displaced):
    try:
        for _ in range(10):
            time.sleep(0.2)
            if box_is_empty(prompt_box_text(target)):
                type_into_box(target, displaced, 0)
                draft_note(name, displaced='', restored=displaced, restored_at=time.time())
                return
    except (OSError, subprocess.SubprocessError, UnicodeError):
        pass


def send_response_preserve_draft(q):
    import uuid
    name, sid, text = q.get('tmux'), q.get('sid'), q.get('text')
    if not isinstance(name, str) or not TMUX_NAME.fullmatch(name):
        return dict(status='not_sent', reason='invalid_tmux_name')
    if not isinstance(sid, str) or not SESSION_ID.fullmatch(sid) or not isinstance(text, str) or not text.strip() or '\x00' in text:
        return dict(status='not_sent', reason='invalid_identity_or_text')
    token = q.get('token')
    payload = text + '\n[mp:' + token + ']' if isinstance(token, str) and re.fullmatch(r'[0-9a-f]{16}', token) else text
    target = '=' + name + ':'
    buffer = 'mp-send-' + uuid.uuid4().hex
    try:
        subprocess.run(['tmux', 'load-buffer', '-b', buffer, '-'], input=payload.encode('utf-8'), check=True, capture_output=True, timeout=5)
    except (OSError, subprocess.SubprocessError, UnicodeError):
        return dict(status='not_sent', reason='buffer_unavailable')
    try:
        bound = resolve_tmux_agent('claude', name, require_bound=True)
        if not bound or bound[0] != sid:
            return dict(status='not_sent', reason='session_changed')
        draft = box_draft(target)
        if draft is None:
            return dict(status='not_sent', reason='prompt_unavailable')
        record = draft_record(name)
        typed, sent = record.get('typed'), record.get('sent')
        typed = typed if isinstance(typed, str) else ''
        sent = sent if isinstance(sent, str) else ''
        if re.fullmatch(r'(?:👍|👎|❤️) to: [^\r\n]+ \[mp:[0-9a-f]{16}\]', text):
            typed = ''
        displaced = draft if draft and not own_draft(draft, text, typed, sent) else ''
        if displaced:
            draft_note(name, displaced=displaced, displaced_at=time.time())

        def known(box):
            return own_draft(box, payload, typed, sent) or own_draft(box, text, typed, sent) or bool(displaced) and squashed(box) in squashed(displaced)
        for wait in (0, 0.3, 0.7):
            time.sleep(wait)
            box = prompt_box_text(target)
            if box and not known(box):
                return dict(status='not_sent', reason='draft_present')
            if not box and (draft or typed):
                subprocess.run(['tmux', 'send-keys', '-t', target, 'C-u'], check=True, capture_output=True, timeout=5)
                box = prompt_box_text(target)
                if box and not known(box):
                    return dict(status='not_sent', reason='draft_present')
            box, _ = clear_prompt_box(target, box)
            if box_is_empty(box):
                break
        else:
            return dict(status='not_sent', reason='draft_not_cleared')
        try:
            subprocess.run(['tmux', 'paste-buffer', '-p', '-d', '-b', buffer, '-t', target], check=True, capture_output=True, timeout=5)
            time.sleep(0.3)
            for _ in range(3):
                subprocess.run(['tmux', 'send-keys', '-t', target, 'Enter'], check=True, capture_output=True, timeout=5)
                time.sleep(0.3)
                box = box_draft(target)
                if box is None:
                    return dict(status='uncertain', reason='prompt_unavailable')
                if not box.strip():
                    break
            else:
                return dict(status='not_sent', reason='input_not_submitted')
        except (OSError, subprocess.SubprocessError):
            return dict(status='uncertain', reason='handoff_interrupted')
        draft_note(name, typed='', sent=text, sent_at=time.time())
        if displaced:
            restore_displaced_draft(name, target, displaced)
        return dict(status='sent', reason=None)
    finally:
        try:
            subprocess.run(['tmux', 'delete-buffer', '-b', buffer], capture_output=True, timeout=5, check=False)
        except (OSError, subprocess.SubprocessError):
            pass


def draft_response(q):
    rid = q.get("id", 0)
    name = q.get("tmux")
    text = q.get("text")
    reason = None
    if not isinstance(name, str) or re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", name) is None:
        reason = "invalid_tmux_name"
    elif not isinstance(text, str) or "\x00" in text or len(text) > DRAFT_TEXT:
        reason = "invalid_text"
    elif host_pane_claimed(name):
        reason = "host_owns_chat"
    else:
        try:
            target = "=" + name + ":"
            box = box_draft(target)
            if late_draft_echo(name, text):
                pass
            elif box is None or box.strip() != text.strip():
                box, _ = clear_prompt_box(target, prompt_box_text(target))
                if not box_is_empty(box):
                    reason = "box_not_clear"
                elif text:
                    type_into_box(target, text, rid)
                    draft_note(name, typed=text)
                else:
                    draft_note(name, typed="")
        except (OSError, subprocess.SubprocessError, UnicodeError):
            reason = "tmux_draft_failed"
    status = "error" if reason else "ok"
    return f"@mp\t{rid}\tdraft\t{status}\t{reason or '-'}\t0\n{END}\n"


def settle_unchained(target):
    words = target.split(" ")
    commands = 0
    while commands < len(words) and re.fullmatch(r"/[A-Za-z0-9_:.-]+", words[commands]):
        commands += 1
    if commands < 2 or commands == len(words):
        return None
    return words[0] + " " + " ".join(words[commands:])


def held_settlement(q, sid, rows):
    from contextlib import closing
    identities = q.get("message_ids")
    if identities is None:
        return rows
    if not isinstance(identities, list) or len(identities) != len(rows) or any(not isinstance(key, str) or key and re.fullmatch(r"[A-Za-z0-9_-]{1,80}", key) is None for key in identities):
        raise ValueError("invalid message identities")
    if not any(identities):
        return rows
    with closing(held_queue_db(q["tmux"])) as db:
        for row, key in zip(rows, identities):
            if not key:
                continue
            stored = db.execute("SELECT receipt FROM messages WHERE key=? AND sid=? AND state='delivered'", (key, sid)).fetchone()
            stamp = int(datetime.fromisoformat(stored[0].replace("Z", "+00:00")).timestamp() * 1000) if stored and stored[0] else 0
            row.update(delivered_ms=stamp, line=0)
    return rows


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
        if delegated is not None and (agent == "claude" or delegated != empty_history_response(q, _sid, q["tmux"], agent)):
            return delegated
        targets = [" ".join(clean_command_markup(text).split()) for text in texts]
        chained = [settle_unchained(target) for target in targets]
        rows = [dict(i=i, delivered_ms=0, line=0) for i in range(len(texts))]
        identities = q.get("message_ids")
        if identities and all(identities):
            rows = held_settlement(q, _sid, rows)
            return (f"@mp\t{rid}\tsettle\tok\t-\t{len(rows)}\n" +
                    "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows) + END + "\n")
        paths = [path] if delegated is None else []
        if agent == "claude" and q["after_ms"] > 0:
            paths += sorted((p for p in path.parent.glob("*.jsonl")
                             if p != path and p.stat().st_mtime * 1000 >= q["after_ms"] - 5000),
                            key=lambda p: p.stat().st_mtime, reverse=True)[:SETTLE_SIBLINGS_MAX]
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
                    if event.get("type") not in ("user", "attachment", "response_item", "message"):
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
                    for i in hits[:1] if agent == "pi" else hits:
                        rows[i].update(delivered_ms=stamp, line=line_number)
        rows = held_settlement(q, _sid, rows)
        return (f"@mp\t{rid}\tsettle\tok\t-\t{len(rows)}\n" +
                "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows) + END + "\n")
    except (OSError, ValueError, KeyError, TypeError, IndexError):
        return error("read_failed")


def watch_changes(q, resolved, source, as_owner):
    import ctypes
    import selectors
    import struct

    agent, sid, path, delegated = resolved
    libc = ctypes.CDLL(None, use_errno=True)
    libc.inotify_init1.argtypes = [ctypes.c_int]
    libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
    fd = libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
    if fd < 0:
        raise OSError(ctypes.get_errno(), "inotify_init1")
    control = None
    control_buffer = b""
    attached = False
    selector = None

    def detach_control():
        nonlocal control, control_buffer, attached, selector
        if control is None:
            return
        proc = control
        control = None
        control_buffer = b""
        attached = False
        q["watch_screen"] = ""
        if selector is not None and proc.stdout is not None:
            try:
                selector.unregister(proc.stdout)
            except (KeyError, ValueError, OSError):
                pass
        if proc.poll() is None:
            proc.terminate()
        try:
            proc.communicate()
        except OSError:
            pass

    def capture():
        nonlocal control_buffer, attached
        control.stdin.write(("capture-pane -p -e -J -t =" + q["tmux"] + ":\n").encode())
        control.stdin.flush()
        block = None
        rows = []
        while True:
            while b"\n" not in control_buffer:
                output = os.read(control.stdout.fileno(), 65536)
                if not output:
                    raise OSError("tmux control closed")
                control_buffer += output
            line, control_buffer = control_buffer.split(b"\n", 1)
            if not attached:
                if line.startswith(b"%end "):
                    attached = True
            elif block is None:
                if line.startswith(b"%begin "):
                    block = line[7:]
            elif line == b"%end " + block:
                return b"\n".join(rows).decode("utf-8", "replace")
            elif line == b"%error " + block:
                raise OSError("tmux capture failed")
            else:
                rows.append(line)

    def refresh_screen():
        if control is None:
            return
        try:
            started = time.perf_counter_ns()
            q["watch_screen"] = capture()
            q["watch_capture_us"] = (time.perf_counter_ns() - started) // 1000
        except OSError:
            detach_control()

    def refresh_codex_binding():
        nonlocal resolved, agent, sid, path, delegated
        if agent != "codex" or not q.get("tmux"):
            return True
        latest = resolve_response_path(dict(q, follow=True), source, as_owner)
        if latest[2] is None:
            resolved = latest
            return False
        if latest[2] != path:
            resolved = latest
            agent, sid, path, delegated = latest
            q["sid"] = sid
            if libc.inotify_add_watch(fd, os.fsencode(path.parent), 0x00000FCE) < 0:
                raise OSError(ctypes.get_errno(), "inotify_add_watch")
        return True

    try:
        if libc.inotify_add_watch(fd, os.fsencode(path.parent), 0x00000FCE) < 0:
            raise OSError(ctypes.get_errno(), "inotify_add_watch")
        metadata = HOME / ".claude/sessions"
        metadata_watch = (libc.inotify_add_watch(fd, os.fsencode(metadata), 0x00000FCE)
                          if agent == "claude" and q.get("tmux") and metadata.is_dir() else -1)
        with selectors.DefaultSelector() as opened:
            selector = opened
            selector.register(fd, selectors.EVENT_READ, "file")
            selector.register(sys.stdin.fileno(), selectors.EVENT_READ, "input")
            if as_owner:
                q["watch_screen"] = ""
            if q.get("tmux") and not as_owner:
                control = subprocess.Popen(["tmux", "-C", "attach-session", "-f", "read-only,ignore-size", "-t", "=" + q["tmux"]],
                                           stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                selector.register(control.stdout, selectors.EVENT_READ, "tmux")
            refresh_screen()
            yield resolved
            while True:
                changed = False
                screen_changed = False
                if b"\n" in control_buffer:
                    lines = control_buffer.split(b"\n")
                    control_buffer = lines.pop()
                    changed = any(line.startswith((b"%output ", b"%extended-output ")) for line in lines)
                    if changed:
                        refresh_screen()
                        bound = refresh_codex_binding()
                        yield resolved
                        if not bound:
                            return
                        continue
                for key, _mask in selector.select():
                    if key.data == "input":
                        if not os.read(key.fd, 4096):
                            return
                    elif key.data == "tmux":
                        if control is None:
                            continue
                        try:
                            output = os.read(key.fd, 65536)
                        except OSError:
                            output = b""
                        if not output:
                            detach_control()
                            continue
                        control_buffer += output
                        lines = control_buffer.split(b"\n")
                        control_buffer = lines.pop()
                        screen_changed = screen_changed or any(line.startswith((b"%output ", b"%extended-output ")) for line in lines)
                        changed = changed or screen_changed
                    else:
                        events = os.read(fd, 65536)
                        at = 0
                        while at + 16 <= len(events):
                            _wd, mask, _cookie, length = struct.unpack_from("iIII", events, at)
                            name = os.fsdecode(events[at + 16:at + 16 + length].split(b"\0", 1)[0])
                            at += 16 + length
                            if mask & 0x4000 or name == path.name:
                                changed = True
                            elif q.get("tmux") and not (as_owner and q.get("host_pinned")) and mask & 0x18A and (name.endswith(".jsonl") or _wd == metadata_watch and name.endswith(".json")):
                                latest = resolve_response_path(dict(q, follow=True), source, as_owner)
                                if latest[2] is None and as_owner:
                                    return
                                if latest[2] is not None and latest[2] != path:
                                    resolved = latest
                                    agent, sid, path, delegated = latest
                                    q["sid"] = sid
                                    if libc.inotify_add_watch(fd, os.fsencode(path.parent), 0x00000FCE) < 0:
                                        raise OSError(ctypes.get_errno(), "inotify_add_watch")
                                    changed = True
                if changed:
                    if screen_changed:
                        refresh_screen()
                    bound = refresh_codex_binding()
                    yield resolved
                    if not bound:
                        return
    finally:
        os.close(fd)
        if control is not None:
            control.terminate()
            control.communicate()


def watch(source, as_owner=False):
    q = json.loads(sys.stdin.buffer.readline())
    if not as_owner:
        q = pin_hosted_claude(q, q.get("provider") if q.get("provider") in HOST_PROVIDERS else "codex" if q.get("codex") else "claude")
    owner = conversation_owner(q.get("cwd", "")) if not q.get("codex") and q.get("provider") != "pi" else None
    if not as_owner and q.get("tmux") and q.get("provider") != "pi":
        discovered = tmux_owner_process("codex" if q.get("codex") else "claude", q["tmux"], source)
        if discovered:
            owner = discovered[1]
            q = dict(q, discovery_pid=discovered[0])
    if owner and owner[1] != HOME and not as_owner:
        bootstrap = "import base64,sys;s=base64.b64decode(sys.stdin.buffer.readline()).decode();exec(compile(s,'<watch-owner>','exec'),dict(__name__='__main__',READER_SOURCE=s))"
        child = subprocess.Popen(["sudo", "-n", "-H", "-u", owner[0], "--", "python3", "-u", "-c", bootstrap, "--watch", "--owner"], stdin=subprocess.PIPE)
        try:
            child.stdin.write(base64.b64encode(source.encode()) + b"\n" + json.dumps(q).encode() + b"\n")
            child.stdin.flush()
            while os.read(sys.stdin.fileno(), 4096):
                pass
        finally:
            child.stdin.close()
            child.wait()
        return
    import selectors

    resolved = resolve_response_path(q, source, as_owner)
    agent, sid, path, delegated = resolved
    shown = None
    while delegated is not None or path is None:
        wire = delegated or missing_response(q, "session transcript not found")
        if wire != shown:
            print(json.dumps(dict(id=q["id"], wire=wire)), flush=True)
            shown = wire
        if wire not in (missing_response(q, "session transcript not found"), empty_history_response(q, q["sid"], q["tmux"], agent)):
            return
        with selectors.DefaultSelector() as selector:
            selector.register(sys.stdin.fileno(), selectors.EVENT_READ)
            if selector.select(timeout=0.25) and not os.read(sys.stdin.fileno(), 4096):
                return
        resolved = resolve_response_path(q, source, as_owner)
        agent, sid, path, delegated = resolved
    q = dict(q, sid=sid, follow=False)
    previous = None
    for resolved in watch_changes(q, resolved, source, as_owner):
        while True:
            started = time.perf_counter_ns() - q.pop("watch_capture_us", 0) * 1000
            wire = response(q, source, as_owner, resolved)
            fields = wire.split("\n", 1)[0].split("\t")
            if fields[3] != "ok":
                print(json.dumps(dict(id=q["id"], wire=wire)), flush=True)
                return
            signature = (fields[4], fields[6], fields[7], fields[12], tuple(line for line in wire.splitlines()[1:-1] if json.loads(line).get("role") == "status"))
            if signature != previous:
                frame = dict(id=q["id"], wire=wire, read_us=(time.perf_counter_ns() - started) // 1000)
                print(json.dumps(frame, separators=(",", ":")), flush=True)
                previous = signature
            q.update(cached_sid=fields[4], offset=int(fields[6]), inode=int(fields[7]),
                     mtime=int(fields[11]), probe=fields[12], start=int(fields[14]), before=0)
            if fields[8] != "1":
                if not q["_held_queue_progress"]:
                    break
                time.sleep(0.01)


SERVE_PRINT_LOCK = threading.Lock()


def serve_print(value):
    with SERVE_PRINT_LOCK:
        print(json.dumps(value, separators=(",", ":")), flush=True)


def events_wake():
    import ctypes
    libc = ctypes.CDLL(None, use_errno=True)
    libc.inotify_init1.argtypes = [ctypes.c_int]
    libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
    fd = libc.inotify_init1(os.O_CLOEXEC)
    if fd < 0:
        return None
    folder = message_store_path().parent
    try:
        folder.mkdir(mode=0o700, parents=True, exist_ok=True)
        if libc.inotify_add_watch(fd, os.fsencode(folder), 0x00000002 | 0x00000008 | 0x00000080 | 0x00000100) < 0:
            os.close(fd)
            return None
    except OSError:
        os.close(fd)
        return None
    return fd


def events_subscription(query):
    provider, sid, after = query.get("provider"), query.get("sid"), query.get("after", 0)
    if provider not in HOST_PROVIDERS or not isinstance(sid, str) or SESSION_ID.fullmatch(sid) is None:
        raise ValueError("invalid subscription")
    if type(after) is not int or after < 0:
        raise ValueError("invalid subscription cursor")
    return dict(provider=provider, sid=sid, after=after)


def events_push(subscriptions):
    for rid, subscription in list(subscriptions.items()):
        page = message_events_page(subscription["provider"], subscription["sid"], subscription["after"])
        if not page["events"]:
            continue
        subscription["after"] = page["next_after"]
        serve_print(dict(id=rid, message_protocol=1, result=page))


SERVE_WORK = dict(busy=0, last=time.monotonic())
SERVE_WORK_LOCK = threading.Lock()


def serve_work(delta):
    with SERVE_WORK_LOCK:
        SERVE_WORK['busy'] += delta
        SERVE_WORK['last'] = time.monotonic()


def serve_retire(interval=2.0):
    while True:
        time.sleep(interval)
        if not superseded_engine():
            continue
        with SERVE_WORK_LOCK:
            if not SERVE_WORK['busy'] and time.monotonic() - SERVE_WORK['last'] >= interval:
                os._exit(0)


def serve_push(subscriptions, lock, wake):
    while True:
        if wake is None:
            time.sleep(1.0)
        else:
            try:
                os.read(wake, 65536)
            except OSError:
                return
        with lock:
            events_push(subscriptions)


def serve_request(line, source, subscriptions, lock):
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
        if query.get("op") == "engine_info":
            serve_print(dict(id=rid, engine_info=globals().get("ENGINE_INFO"), message_protocols=[1], room_message_protocols=[ROOM_MESSAGE_PROTOCOL]))
            return
        if query.get("op") == "host_adopt":
            serve_print(dict(id=rid, host_adopt=host_adopt(query.get("provider"), query.get("sid"), query.get("tmux"), query.get("cwd"))))
            return
        if query.get("op") == "host_terminal":
            serve_print(dict(id=rid, host_terminal=host_terminal(query.get("provider"), query.get("sid"), query.get("tmux"))))
            return
        if query.get("op") == "host_resume":
            serve_print(dict(id=rid, host_resume=host_resume(query.get("provider"), query.get("sid"), query.get("tmux"), query.get("cwd"))))
            return
        if query.get("op") == "events_subscribe":
            subscription = events_subscription(query)
            page = message_events_page(subscription["provider"], subscription["sid"], subscription["after"])
            subscription["after"] = page["next_after"]
            with lock:
                subscriptions[rid] = subscription
            serve_print(dict(id=rid, message_protocol=1, result=page))
            return
        if query.get("op") == "events_unsubscribe":
            with lock:
                subscriptions.pop(rid, None)
            serve_print(dict(id=rid, message_protocol=1, result=dict(events=[], next_after=query.get("after", 0))))
            return
        if query.get("op") in ("approval_reply", "question_reply", "plan_reply", "set_permission_mode", "set_model", "set_effort", "interrupt"):
            serve_print(dict(id=rid, message_protocol=1, result=host_reply(query)))
            return
        if query.get("op") in ("message_submit", "message_retry", "message_dismiss", "message_dispatch", "message_status", "message_snapshot", "message_events", "message_reset"):
            result = message_store_request(query)
            if query.get('op') in ('message_submit', 'message_retry', 'message_snapshot'):
                start_message_worker(query, source)
            elif query.get('op') in ('message_events', 'message_status'):
                start_message_worker(query, source, only_if_work=True)
            serve_print(dict(id=rid, message_protocol=1, result=result))
            return
        if query.get("op") in ("send", "question_text"):
            wire = enqueue_response(query, source) if "message_id" in query else send_response(query)
        elif query.get("op") == "cancel_queued":
            wire = cancel_queued_response(query)
        elif query.get("op") == "draft":
            wire = draft_response(query)
        elif query.get("op") in ("stop", "permission", "question", "send_queued", "edit_queued"):
            wire = stop_response(query)
        elif query.get("op") == "settle":
            wire = settle_response(query, source)
        else:
            wire = response(query, source)
    except (OSError, ValueError, KeyError, TypeError, IndexError, sqlite3.Error):
        wire = f"@mp\t{rid}\thistory\terror\t-\t0\n{END}\n"
    serve_print(dict(id=rid, wire=wire))


def serve(source):
    subscriptions = {}
    lock = threading.Lock()
    wake = events_wake()
    push = threading.Thread(target=serve_push, args=(subscriptions, lock, wake), daemon=True)
    push.start()
    threading.Thread(target=serve_retire, daemon=True).start()
    from concurrent.futures import ThreadPoolExecutor
    chats = {}
    try:
        for line in sys.stdin.buffer:
            if not line.strip():
                continue
            serve_work(1)
            try:
                query = json.loads(line)
            except (ValueError, UnicodeError):
                query = None
            if (not isinstance(query, dict) or not str(query.get('op', '')).startswith('message_')
                    or query.get('provider') not in HOST_PROVIDERS or not isinstance(query.get('sid'), str)
                    or not SESSION_ID.fullmatch(query['sid'])):
                try:
                    serve_request(line, source, subscriptions, lock)
                finally:
                    serve_work(-1)
                continue
            key = (query['provider'], query['sid'])
            if key not in chats and len(chats) >= 256:
                for idle, (executor, slots, last) in list(chats.items()):
                    if last.done():
                        executor.shutdown(wait=False)
                        del chats[idle]
            if key not in chats and len(chats) < 256:
                chats[key] = (ThreadPoolExecutor(max_workers=1), threading.BoundedSemaphore(64), None)
            if key not in chats or not chats[key][1].acquire(blocking=False):
                rid = query.get('id', 0)
                rid = rid if type(rid) is int and rid >= 0 else 0
                serve_print(dict(id=rid, wire=f"@mp\t{rid}\thistory\terror\t-\t0\n{END}\n"))
                serve_work(-1)
                continue
            executor, slots, last = chats[key]
            future = executor.submit(serve_request, line, source, subscriptions, lock)
            future.add_done_callback(lambda done, slots=slots: (slots.release(), serve_work(-1)))
            chats[key] = (executor, slots, future)
    finally:
        for executor, slots, last in chats.values():
            executor.shutdown(wait=True)


if __name__ == "__main__" and "--watch" in sys.argv[1:]:
    watch(globals().get("READER_SOURCE"), "--owner" in sys.argv[1:])
elif __name__ == "__main__" and sys.argv[1:2] == ["--held-worker"]:
    held_queue_worker(sys.argv[2], sys.argv[3], Path(sys.argv[4]))
elif __name__ == "__main__" and sys.argv[1:2] == ["--message-worker"]:
    message_worker(sys.argv[2], sys.argv[3], sys.argv[4])
elif __name__ == "__main__" and sys.argv[1:2] == ["--agent-host"]:
    sys.exit(host_main(sys.argv[2], sys.argv[3]))
elif __name__ == "__main__" and sys.argv[1:2] == ["host"]:
    print(json.dumps(host_cli(sys.argv[2:]), separators=(",", ":")))
elif __name__ == "__main__" and sys.argv[1:] == ["--serve"]:
    serve(globals().get("READER_SOURCE"))
elif __name__ == "__main__":
    try:
        query = json.loads(base64.b64decode(sys.argv[1], validate=True))

        if "--discover" in sys.argv[2:]:
            pid = agent_pid_for_panes(query["panes"], local_ssh_process_table(), query["agent"])
            user = pwd.getpwuid(Path(f"/proc/{pid}").stat().st_uid) if pid is not None and pwd is not None else None
            print(json.dumps(dict(pid=pid, user=user.pw_name) if user else None))
        else:
            handler = settle_response if query.get("op") == "settle" else response
            print(handler(query, globals().get("READER_SOURCE"), "--owner" in sys.argv[2:]), end="")
    except (OSError, ValueError, KeyError, TypeError, IndexError):
        print("@mp\t0\thistory\terror\t-\t0\n" + END)

'''
READER_SOURCE = 'CONNECTOR_SOURCES = ' + repr(globals().get('CONNECTOR_SOURCES', {})) + '\n' + READER_SOURCE
RUNTIME_GENERATION = 93

if __name__ == '__main__':
    import base64 as _base64
    import fcntl
    import hashlib as _hashlib
    import json as _json
    import os as _os
    import stat as _stat
    import sys as _sys
    import tempfile as _tempfile

    def _select_runtime(source):
        generation = RUNTIME_GENERATION
        digest = _hashlib.sha256(source).hexdigest()
        home = _os.path.expanduser('~')
        home_fd = _os.open(home, _os.O_RDONLY | _os.O_DIRECTORY | _os.O_NOFOLLOW)
        try:
            try:
                _os.mkdir('.multiprompt-runtime', 0o700, dir_fd=home_fd)
            except FileExistsError:
                pass
            directory = _os.open('.multiprompt-runtime', _os.O_RDONLY | _os.O_DIRECTORY | _os.O_NOFOLLOW, dir_fd=home_fd)
        finally:
            _os.close(home_fd)
        try:
            def check(fd, mode):
                st = _os.fstat(fd)
                if st.st_uid != _os.geteuid() or _stat.S_IMODE(st.st_mode) != mode:
                    raise ValueError('unsafe runtime permissions')
            check(directory, 0o700)
            lock = _os.open('lock', _os.O_RDWR | _os.O_CREAT | _os.O_NOFOLLOW, 0o600, dir_fd=directory)
            try:
                check(lock, 0o600)
                if not _stat.S_ISREG(_os.fstat(lock).st_mode):
                    raise ValueError('invalid lock')
                fcntl.flock(lock, fcntl.LOCK_EX)
                def read(name, limit):
                    fd = _os.open(name, _os.O_RDONLY | _os.O_NOFOLLOW | _os.O_NONBLOCK, dir_fd=directory)
                    try:
                        check(fd, 0o600)
                        st = _os.fstat(fd)
                        if not _stat.S_ISREG(st.st_mode) or st.st_size > limit:
                            raise ValueError('invalid runtime file')
                        with _os.fdopen(fd, 'rb', closefd=False) as stream:
                            return stream.read(limit + 1)
                    finally:
                        _os.close(fd)
                def publish(name, data):
                    fd, path = _tempfile.mkstemp(prefix='.tmp-', dir=_os.path.join(home, '.multiprompt-runtime'))
                    try:
                        with _os.fdopen(fd, 'wb') as stream:
                            stream.write(data)
                            stream.flush()
                            _os.fsync(stream.fileno())
                        _os.replace(path, name, dst_dir_fd=directory)
                        _os.fsync(directory)
                    finally:
                        if _os.path.exists(path):
                            _os.unlink(path)
                def publish_tools(data):
                    try:
                        publish('reader.py', data)
                    except OSError:
                        pass
                    mp_send_source = globals().get('MP_SEND_SOURCE')
                    if isinstance(mp_send_source, str) and mp_send_source:
                        try:
                            publish('mp-send', _base64.b64decode(mp_send_source))
                        except (ValueError, OSError):
                            pass
                    feedback_source = globals().get('FEEDBACK_SOURCE')
                    if isinstance(feedback_source, str) and feedback_source:
                        try:
                            publish('multibot-feedback', _base64.b64decode(feedback_source))
                            _os.chmod('multibot-feedback', 0o700, dir_fd=directory)
                        except (ValueError, OSError, NotImplementedError):
                            pass
                missing = object()
                try:
                    active = _json.loads(read('active.json', 4096))
                except FileNotFoundError:
                    active = missing
                if active is not missing:
                    if (not isinstance(active, dict) or set(active) != {'protocol', 'generation', 'sha256'} or
                            type(active['protocol']) is not int or active['protocol'] != 1 or
                            type(active['generation']) is not int or active['generation'] < 1 or
                            not isinstance(active['sha256'], str) or len(active['sha256']) != 64 or
                            any(c not in '0123456789abcdef' for c in active['sha256'])):
                        raise ValueError('invalid runtime selection')
                    installed = read(active['sha256'] + '.py', 4 * 1024 * 1024)
                    if _hashlib.sha256(installed).hexdigest() != active['sha256']:
                        raise ValueError('corrupt runtime')
                    if active['generation'] > generation:
                        publish_tools(installed)
                        return installed, active
                    if active['generation'] == generation:
                        if active['sha256'] != digest:
                            publish(digest + '.py', source)
                            active = dict(protocol=1, generation=generation, sha256=digest)
                            publish('active.json', _json.dumps(active, separators=(',', ':')).encode())
                            publish_tools(source)
                            return source, active
                        publish_tools(installed)
                        return installed, active
                try:
                    installed = read(digest + '.py', 4 * 1024 * 1024)
                except FileNotFoundError:
                    publish(digest + '.py', source)
                else:
                    if installed != source:
                        raise ValueError('corrupt runtime')
                active = dict(protocol=1, generation=generation, sha256=digest)
                publish('active.json', _json.dumps(active, separators=(',', ':')).encode())
                publish_tools(source)
                return source, active
            finally:
                _os.close(lock)
        finally:
            _os.close(directory)

    try:
        _selected, _info = _select_runtime(READER_SOURCE.encode('utf-8'))
        _worker_source = ('ENGINE_INFO = ' + repr(_info) + '\nMULTIBOT_APP_VERSION = ' +
                          repr(globals().get('MULTIBOT_APP_VERSION', '')) + '\n' + _selected.decode('utf-8'))
        READER_SOURCE = 'READER_SOURCE = ' + repr(_worker_source) + '\n' + _worker_source
    except (OSError, ValueError, UnicodeError):
        def _error(line):
            try:
                request = _json.loads(line)
                rid = request.get('id', 0)
                if type(rid) is not int or rid < 0:
                    rid = 0
            except (ValueError, AttributeError, TypeError):
                rid = 0
            wire = f'@mp\t{rid}\thistory\terror\t-\t0\n@@MP_END@@\n'
            return _json.dumps(dict(id=rid, wire=wire), separators=(',', ':'))
        if _sys.argv[1:] == ['--serve']:
            for _line in _sys.stdin.buffer:
                print(_error(_line), flush=True)
        elif '--watch' in _sys.argv[1:]:
            print(_error(_sys.stdin.buffer.readline()), flush=True)
        else:
            print('@mp\t0\thistory\terror\t-\t0\n@@MP_END@@')
        _sys.exit(1)

exec(compile(READER_SOURCE, '<chat-history-reader>', 'exec'))
