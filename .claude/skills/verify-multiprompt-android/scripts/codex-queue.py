import argparse
import datetime
import json
from pathlib import Path
import time
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--thread", required=True)
parser.add_argument("--message", required=True)
args = parser.parse_args()
sid = str(uuid.UUID(args.thread))
if sid != args.thread:
    parser.error("The queue requires the exact native thread ID")
home = Path.home()
transcript = home / ".codex/sessions/2026/10/29" / ("rollout-today-" + sid + ".jsonl")
with transcript.open() as source:
    header = json.loads(source.readline())
if header.get("type") != "session_meta" or header.get("payload", {}).get("id") != sid:
    parser.error("The queue must target its own transcript")
with transcript.open("a") as output:
    for role, text in (("user", args.message), ("assistant", "CODEX_REPLY_codex_" + args.message)):
        output.write(json.dumps(dict(type="response_item", timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                     payload=dict(type="message", role=role, content=[dict(type="input_text" if role == "user" else "output_text", text=text)]))) + "\n")
with (home / "codex-receipts.txt").open("a") as receipt:
    receipt.write("VERIFY_RECEIVED:" + args.message + "\n")
(home / "codex-reply-ns.txt").write_text(str(time.time_ns()))
