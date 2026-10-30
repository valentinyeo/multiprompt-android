#!/usr/bin/env python3
from contextlib import closing
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import selectors
import socket
import sqlite3
import subprocess
import sys
import xml.etree.ElementTree as ET

HOME = Path.home()
CWD = HOME / "resync-work"
SID = "a1b2c3d4-1011-4000-8000-000000001011"
TMUX = "verify-session"
TRANSCRIPT = HOME / ".claude/projects" / str(CWD).replace("/", "-") / (SID + ".jsonl")
CONTROL = HOME / "resync-control.sock"
TEXTS = {
    "recovery-head": "RECOVERY_HEAD_".ljust(200, "h") + " ",
    "pending-one": "PENDING_ONE",
    "pending-two": "PENDING_TWO",
    "accepted-fourth": "ACCEPTED_FOURTH",
}
ACCEPTED = {"recovery-head", "accepted-fourth"}
DRAFT = "draft stays here"


def require(condition, code):
    if not condition:
        raise ValueError(code)


def fixture_boundary():
    require(os.environ.get("GITHUB_ACTIONS") == "true" and HOME == Path("/home/verifyssh")
            and os.getuid() != 0, "RESYNC_ISOLATED_HOST_REQUIRED")


def event(role, text, identity):
    row = dict(type=role, uuid=identity, sessionId=SID, isSidechain=False,
               timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
               message=dict(role=role, content=text, **({"id": identity} if role == "assistant" else {})))
    with TRANSCRIPT.open("a", encoding="utf-8") as output:
        output.write(json.dumps(row, ensure_ascii=False) + "\n")


def ui_row(path, identity, phase):
    root = ET.parse(path).getroot()
    parents = {child: parent for parent in root.iter() for child in parent}
    wanted = TEXTS[identity].rstrip(" ")
    matches = [node for node in root.iter("node") if node.get("text", "").rstrip(" ") == wanted]
    require(len(matches) == 1, "RESYNC_UI_ROW_MISSING_OR_DUPLICATED:" + identity)
    current = matches[0]
    labels = set()
    while current is not None:
        if current.get("scrollable") == "true":
            break
        bodies = {key for key, text in TEXTS.items() if any(
            node.get("text", "").rstrip(" ") == text.rstrip(" ") for node in current.iter("node"))}
        if bodies != {identity}:
            break
        labels.update(node.get("text") for node in current.iter("node")
                      if node.get("text") in {"Sending", "Queued", "Delivered", "Cancelled"})
        current = parents.get(current)
    expected = {"Sending"} if identity == "recovery-head" else {"Queued"}
    if phase != "before" and identity in ACCEPTED:
        require(not labels or labels == {"Delivered"}, "RESYNC_UI_RECEIPT_STILL_PENDING:" + identity)
    else:
        require(labels == expected, "RESYNC_UI_PENDING_STATE_WRONG:" + identity)
    return dict(id=identity, labels=sorted(labels), occurrences=1)


def draft_bytes(path):
    editors = [node for node in ET.parse(path).iter("node") if node.get("class") == "android.widget.EditText"]
    require(len(editors) == 1, "RESYNC_DRAFT_EDITOR_MISSING")
    data = editors[0].get("text", "").encode("utf-8")
    require(data == DRAFT.encode("utf-8"), "RESYNC_DRAFT_CHANGED")
    return data


def console():
    fixture_boundary()
    import fcntl
    require(not TRANSCRIPT.exists() and not CONTROL.exists(), "RESYNC_FIXTURE_NOT_FRESH")
    spec = importlib.util.spec_from_file_location("fixture_reader", HOME / "recovery-reader.py")
    reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reader)
    queue = reader.held_queue_path(TMUX)
    require(not queue.exists(), "RESYNC_QUEUE_NOT_FRESH")
    CWD.mkdir(parents=True)
    os.chdir(CWD)
    TRANSCRIPT.parent.mkdir(parents=True, exist_ok=True)
    event("user", "VERIFY_READY_528", "initial")
    event("assistant", "FIRST REPLY", "msg_first")
    meta = HOME / ".claude/sessions" / (str(os.getpid()) + ".json")
    meta.parent.mkdir(parents=True, exist_ok=True)
    meta.write_text(json.dumps(dict(sessionId=SID, cwd=str(CWD), pid=os.getpid())), encoding="utf-8")
    receipts = HOME / "receipts.txt"
    receipts.write_bytes(b"")
    initial = TRANSCRIPT.stat()
    queued_at = int(datetime.datetime.now(datetime.timezone.utc).timestamp() * 1000)
    with closing(reader.held_queue_db(TMUX)) as db, db:
        for index, (identity, text) in enumerate(TEXTS.items(), 1):
            db.execute("INSERT INTO messages(key,sid,text,queued_at,state,scan,origin,source_inode,created,recovery_scan) VALUES (?,?,?,?,?,?,?,?,?,NULL)",
                       (identity, SID, text, queued_at + index, "submitted" if index == 1 else "queued",
                        initial.st_size, initial.st_size, initial.st_ino, index))
        db.execute("PRAGMA user_version=1")
    lock_path = queue.with_suffix("." + hashlib.sha256(SID.encode()).hexdigest() + ".worker")
    lock = lock_path.open("a")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    baseline = None
    accepted_event = None

    def snapshot(db):
        db.row_factory = sqlite3.Row
        rows = [dict(row) for row in db.execute("SELECT key,sid,state,scan,origin,source_inode,recovery_scan FROM messages ORDER BY created")]
        claims = {row[0]: row[1] for row in db.execute("SELECT key,count(*) FROM held_receipts GROUP BY key")}
        receipt_claims = [dict(row) for row in db.execute("SELECT sid,inode,offset,key FROM held_receipts ORDER BY offset")]
        with lock_path.open("a") as probe:
            try:
                fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                held = True
            else:
                held = False
                fcntl.flock(probe, fcntl.LOCK_UN)
        pane = subprocess.check_output(["tmux", "capture-pane", "-p", "-t", "=" + TMUX + ":"], text=True)
        bound = reader.resolve_tmux_agent("claude", TMUX, require_bound=True)
        require(bound is not None and bound == (SID, TRANSCRIPT), "RESYNC_SOURCE_BINDING_CHANGED")
        info = TRANSCRIPT.stat()
        return dict(sid=SID, inode=info.st_ino, tmux=TMUX, source_bytes=info.st_size,
                    schema_version=db.execute("PRAGMA user_version").fetchone()[0], rows=rows, claims=claims, receipt_claims=receipt_claims,
                    held=[row["key"] for row in rows if row["state"] in ("queued", "submitted")],
                    provider_input_count=len(receipts.read_bytes().splitlines()),
                    provider_input_bytes=receipts.stat().st_size, worker_lock_held=held,
                    provider_busy="esc to interrupt" in pane.lower(),
                    provider_ready=reader.held_queue_ready(TMUX, TRANSCRIPT),
                    schema_reader_sha256=hashlib.sha256((HOME / "recovery-reader.py").read_bytes()).hexdigest(),
                    fixture_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())

    def verify(result, phase):
        require(result["sid"] == SID and result["inode"] == initial.st_ino and result["tmux"] == TMUX,
                "RESYNC_SOURCE_IDENTITY_CHANGED")
        require(result["worker_lock_held"] and result["provider_busy"] and not result["provider_ready"], "RESYNC_INDEPENDENT_GATES_LOST")
        require(result["provider_input_count"] == 0 and result["provider_input_bytes"] == 0, "RESYNC_DUPLICATE_INPUT")
        require({row["key"] for row in result["rows"]} == set(TEXTS), "RESYNC_PENDING_ID_LOST")
        for row in result["rows"]:
            require(row["sid"] == SID and row["source_inode"] == initial.st_ino and row["origin"] == initial.st_size,
                    "RESYNC_ROW_SOURCE_CHANGED:" + row["key"])
            expected = ("submitted" if row["key"] == "recovery-head" else "queued") if phase == "before" else (
                "delivered" if row["key"] in ACCEPTED else "queued")
            require(row["state"] == expected, "RESYNC_RECEIPT_ID_NOT_SETTLED:" + row["key"])
        require(result["claims"] == ({} if phase == "before" else {key: 1 for key in ACCEPTED}),
                "RESYNC_RECEIPT_CLAIM_COUNT_WRONG")
        require(all(claim["sid"] == SID and claim["inode"] == initial.st_ino
                    and initial.st_size <= claim["offset"] < result["source_bytes"] for claim in result["receipt_claims"]),
                "RESYNC_RECEIPT_SOURCE_CHANGED")
        require(set(result["held"]) == (set(TEXTS) if phase == "before" else set(TEXTS) - ACCEPTED),
                "RESYNC_HELD_IDS_WRONG")

    def command(name):
        nonlocal baseline, accepted_event
        with closing(sqlite3.connect(queue, timeout=30)) as db, db:
            if name == "before":
                baseline = snapshot(db)
                verify(baseline, "before")
                return baseline
            if name == "accept":
                require(baseline is not None and accepted_event is None, "RESYNC_EVENT_ORDER_WRONG")
                db.execute("BEGIN IMMEDIATE")
                db.execute("UPDATE messages SET recovery_scan=NULL")
                db.execute("PRAGMA user_version=1")
                legacy = snapshot(db)
                verify(legacy, "before")
                require(all(row["recovery_scan"] is None for row in legacy["rows"]), "RESYNC_LEGACY_CURSOR_NOT_NULL")
                event("user", TEXTS["recovery-head"].rstrip(" "), "receipt-head")
                with TRANSCRIPT.open("a", encoding="utf-8") as output:
                    padding = json.dumps(dict(type="system", sessionId=SID, message="padding" * 128)) + "\n"
                    output.write(padding * (reader.TAIL_BYTES // len(padding.encode()) + 2))
                event("user", TEXTS["accepted-fourth"], "receipt-fourth")
                db.execute("UPDATE messages SET scan=?", (TRANSCRIPT.stat().st_size,))
                appended = snapshot(db)
                require(all(row["recovery_scan"] is None and row["scan"] == appended["source_bytes"]
                            for row in appended["rows"]), "RESYNC_LEGACY_SEED_WRONG")
                accepted_event = dict(legacy_before_receipts=legacy, receipts_appended=appended)
                return accepted_event
            require(name in ("after", "repeat"), "RESYNC_UNKNOWN_EVENT")
            require(accepted_event is not None, "RESYNC_EVENT_ORDER_WRONG")
            result = snapshot(db)
            verify(result, "after")
            if name == "after":
                baseline = result
            else:
                require(result == baseline, "RESYNC_REPEAT_CHANGED_STATE")
            return result

    server = socket.socket(socket.AF_UNIX)
    server.bind(str(CONTROL))
    os.chmod(CONTROL, 0o600)
    server.listen()
    inputs = selectors.DefaultSelector()
    inputs.register(server, selectors.EVENT_READ)
    inputs.register(sys.stdin, selectors.EVENT_READ)
    print("Claude Code\nVERIFY_READY_528\nFIRST REPLY\nWorking... (esc to interrupt)\n❯ ", flush=True)
    while True:
        for key, _ in inputs.select():
            if key.fileobj is server:
                connection, _ = server.accept()
                with connection, connection.makefile("r", encoding="utf-8") as request:
                    try:
                        result = dict(ok=True, evidence=command(request.readline().strip()))
                    except (ValueError, sqlite3.Error, OSError) as error:
                        with closing(sqlite3.connect(queue, timeout=30)) as db:
                            result = dict(ok=False, error=str(error), evidence=snapshot(db))
                    connection.sendall((json.dumps(result) + "\n").encode())
            else:
                line = sys.stdin.readline()
                if not line:
                    return
                with receipts.open("a", encoding="utf-8") as output:
                    output.write("VERIFY_RECEIVED:" + line.rstrip("\r\n") + "\n")
                print("Working... (esc to interrupt)\n❯ ", flush=True)


def main():
    args = sys.argv[1:]
    if args[0] == "ui-row":
        print(json.dumps(ui_row(args[1], args[2], args[3])))
    elif args[0] == "draft":
        Path(args[2]).write_bytes(draft_bytes(args[1]))
    elif args[0] == "event":
        fixture_boundary()
        with socket.socket(socket.AF_UNIX) as client:
            client.settimeout(60)
            client.connect(str(CONTROL))
            client.sendall((args[1] + "\n").encode())
            with client.makefile("r", encoding="utf-8") as response:
                result = json.loads(response.readline())
        print(json.dumps(result, indent=2))
        require(result["ok"], result.get("error", "RESYNC_FIXTURE_EVENT_FAILED"))
    elif args[0] == "console":
        console()
    else:
        raise ValueError("RESYNC_UNKNOWN_MODE")


if __name__ == "__main__":
    main()
