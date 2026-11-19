#!/usr/bin/env bash
set -euo pipefail
fixture_session=live-verify-session
mpa="$scripts/mpa"
"$mpa" read --wait-text CLAUDE_DESKTOP_SESSION_2541
"$mpa" screenshot remote-control-open
"$mpa" proof --name remote-control-open --require-text CLAUDE_DESKTOP_SESSION_2541
"$mpa" fill 'Message verify-session' PHONE_REMOTE_CONTROL_2541
"$mpa" read --select editor-value --value composer --extra PHONE_REMOTE_CONTROL_2541
"$mpa" screenshot remote-control-draft
"$mpa" proof --name remote-control-draft --require-text PHONE_REMOTE_CONTROL_2541
"$mpa" tap desc 'Send prompt'
"$mpa" read --wait-text 'REMOTE_CONTROL_CONNECTED_2541 desktop_counter=1'
"$mpa" screenshot remote-control-connected
"$mpa" proof --name remote-control-connected --require-text 'REMOTE_CONTROL_CONNECTED_2541 desktop_counter=1'
sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/remote-control-reader.py host send claude c001c001-2541-4000-8000-000000002541 DESKTOP_REMOTE_CONTROL_2541 > "$EVIDENCE/remote-control-desktop-send.json"
jq -e '.status == "sent" or .status == "queued"' "$EVIDENCE/remote-control-desktop-send.json"
"$mpa" read --wait-text 'DESKTOP_REPLY_2541 desktop_counter=2'
"$mpa" screenshot remote-control-desktop-reply
"$mpa" proof --name remote-control-desktop-reply --require-text 'DESKTOP_REPLY_2541 desktop_counter=2'
sudo cat /home/verifyssh/remote-control-receipts.jsonl > "$EVIDENCE/remote-control-receipts.jsonl"
sudo cat /home/verifyssh/remote-control-work/desktop-counter.txt > "$EVIDENCE/remote-control-desktop.txt"
sudo -u verifyssh tmux show-option -v -t "$fixture_session" @mp_host_sid > "$EVIDENCE/remote-control-sid.txt"
python3 - "$EVIDENCE" <<'PYPROOF'
import json
from pathlib import Path
import sys
root = Path(sys.argv[1])
sid = 'c001c001-2541-4000-8000-000000002541'
assert root.joinpath('remote-control-before.txt').read_text().strip() == 'NO_REMOTE_CONTROL_BEFORE_PHONE'
assert root.joinpath('remote-control-sid.txt').read_text().strip() == sid
rows = [json.loads(line) for line in root.joinpath('remote-control-receipts.jsonl').read_text().splitlines()]
assert len(rows) == 3, rows
assert all(row['provider'] == 'claude' and row['sid'] == sid and row['tmux'] == 'live-verify-session' and row['connected'] is True for row in rows), rows
assert rows[0]['kind'] == 'remote_control' and rows[0]['frame']['request'] == dict(subtype='remote_control', enabled=True), rows
assert rows[0]['frame']['request_id'], rows
assert [(row['text'], row['desktop_counter']) for row in rows[1:]] == [('PHONE_REMOTE_CONTROL_2541', 1), ('DESKTOP_REMOTE_CONTROL_2541', 2)], rows
assert root.joinpath('remote-control-desktop.txt').read_text().strip() == '2'
root.joinpath('remote-control-host.json').write_text(json.dumps(dict(sid=sid, tmux='live-verify-session', connected=True, desktop_counter=2, receipts=rows), indent=2) + '\n')
PYPROOF
printf 'CLAUDE_REMOTE_CONTROL_PHONE_AND_DESKTOP_OK\n'
step=$((step + 4))
exit 0
