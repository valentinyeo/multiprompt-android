import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import select
import socket
import sys

SID = "c001c001-2532-4000-8000-000000002532"
TMUX = "verify-session"
HOME = Path("/home/verifyssh")
CWD = HOME / "projection-work"
TRANSCRIPT = HOME / ".claude/projects" / str(CWD).replace("/", "-") / (SID + ".jsonl")
CONTROL = HOME / "projection-control.sock"


def boundary():
    assert os.environ.get("GITHUB_ACTIONS") == "true" and Path.home() == HOME and os.getuid() != 0


def command(name):
    boundary()
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(20)
        client.connect(str(CONTROL))
        client.sendall(name.encode())
        data = bytearray()
        while chunk := client.recv(65536):
            data.extend(chunk)
    result = json.loads(data)
    assert "error" not in result, result
    print(json.dumps(result))


def console():
    boundary()
    namespace = {}
    exec((HOME / "projection-reader.py").read_text(), namespace)
    reader = {}
    exec(namespace["READER_SOURCE"], reader)
    CWD.mkdir()
    os.chdir(CWD)
    TRANSCRIPT.parent.mkdir(parents=True, exist_ok=True)
    TRANSCRIPT.write_text("")
    receipts = HOME / "receipts.txt"
    receipts.write_text("")
    meta = HOME / ".claude/sessions" / (str(os.getpid()) + ".json")
    meta.parent.mkdir(parents=True, exist_ok=True)
    meta.write_text(json.dumps(dict(sessionId=SID, cwd=str(CWD), pid=os.getpid())))
    path = reader["message_store_path"]()
    path.parent.mkdir(parents=True, exist_ok=True)
    lock_path = path.with_suffix("." + hashlib.sha256(("claude:" + SID).encode()).hexdigest() + ".message-worker")
    lock = lock_path.open("a")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    db = reader["message_store_db"](TMUX)
    with db:
        db.execute("INSERT OR IGNORE INTO message_chats(provider,sid) VALUES ('claude',?)", (SID,))
    accepted = []

    def append(role, text, identity, fresh=False):
        stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
        if fresh:
            stamp = stamp.removesuffix("+00:00")
        message = dict(role=role, content=text)
        if role == "assistant":
            message["id"] = identity
        row = dict(type=role, uuid=identity, timestamp=stamp, sessionId=SID, message=message)
        with TRANSCRIPT.open("a") as output:
            output.write(json.dumps(row) + "\n")
        return row

    append("assistant", "PROJECTION_READY_2532", "msg_ready")
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
        server.bind(str(CONTROL))
        server.listen()
        print("Claude Code\nPROJECTION_READY_2532\nWorking... (esc to interrupt)\n❯ FIXTURE_OWNED_DRAFT", flush=True)
        while True:
            select.select([server], [], [])
            client, _ = server.accept()
            with client:
                name = client.recv(4096).decode()
                try:
                    if name in ("accept-before", "accept-after"):
                        fresh = name == "accept-after"
                        expected = "PHONE_AFTER_2532" if fresh else "PHONE_BEFORE_2532"
                        record = db.execute("SELECT * FROM shared_messages WHERE provider='claude' AND sid=? AND text=? AND state='queued'", (SID, expected)).fetchall()
                        assert len(record) == 1, record
                        row = reader["message_row"](record[0])
                        token = row["id"]
                        n = len(accepted) + 1
                        native = token if fresh else "native-before-2532"
                        if fresh:
                            append("assistant", "Fresh start", "msg_divider")
                        transcript = append("user", row["text"], native, fresh)
                        with db:
                            db.execute("UPDATE shared_messages SET state='accepted',receipt=? WHERE id=?", ("fixture-receipt-" + token, token))
                            row.update(state="accepted", receipt="fixture-receipt-" + token)
                            reader["message_event"](db, "claude", SID, row)
                            payload = dict(message_id=token)
                            if not fresh:
                                payload["uuids"] = [native]
                            for kind, item, body in (("turn.started", None, payload),
                                                     ("text.done", f"msg_reply_{n}:text:0", dict(text=f"REPLY_{'AFTER' if fresh else 'BEFORE'}_2532")),
                                                     ("turn.ended", None, {})):
                                reader["message_event"](db, "claude", SID, dict(kind=kind, sid=SID, turn=f"turn-{n}", item=item, payload=body))
                        evidence = dict(phone_id=token, native_id=native, receipt=row["receipt"], text=row["text"], transcript=transcript, live_item=f"msg_reply_{n}:text:0")
                        accepted.append(evidence)
                        with receipts.open("a") as output:
                            output.write(json.dumps(evidence) + "\n")
                    elif name == "catchup":
                        assert len(accepted) == 2
                        saved = [json.loads(line) for line in TRANSCRIPT.read_text().splitlines()]
                        temporary = TRANSCRIPT.with_suffix(".catchup")
                        temporary.write_text("")
                        temporary.replace(TRANSCRIPT)
                        for event in saved:
                            with TRANSCRIPT.open("a") as output:
                                output.write(json.dumps(event) + "\n")
                            for n, receipt in enumerate(accepted, 1):
                                if event["uuid"] == receipt["native_id"]:
                                    phase = "BEFORE" if n == 1 else "AFTER"
                                    append("assistant", f"REPLY_{phase}_2532", f"msg_reply_{n}")
                    else:
                        assert name == "read", name
                    result = dict(sid=SID, accepted=accepted, transcript=[json.loads(line) for line in TRANSCRIPT.read_text().splitlines()],
                                  events=[json.loads(row[0]) for row in db.execute("SELECT body FROM message_events WHERE provider='claude' AND sid=? ORDER BY seq", (SID,))])
                except Exception as error:
                    result = dict(error=str(error))
                client.sendall(json.dumps(result).encode())


if __name__ == "__main__":
    if sys.argv[1:] == ["console"]:
        console()
    else:
        command(sys.argv[1])
