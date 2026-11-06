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
        if sid:
            return None
        if require_bound:
            return None
        fallback = claude_transcript_for_cwd(cwd) if cwd else None
        if fallback is None:
            return None
        return fallback.stem, fallback
    if cwd is None:
        return None
    return codex_transcript_for_cwd(cwd)


