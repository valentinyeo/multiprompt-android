#!/usr/bin/env bash
fixture_session=live-verify-session
wait_for_text CLAUDE_DESKTOP_SESSION_2541
shot remote-control-open
fill 'Message verify-session' PHONE_REMOTE_CONTROL_2541
shot remote-control-draft
tap_desc 'Send prompt'
wait_for_text 'REMOTE_CONTROL_CONNECTED_2541 desktop_counter=1'
shot remote-control-connected
sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/remote-control-reader.py host send claude c001c001-2541-4000-8000-000000002541 DESKTOP_REMOTE_CONTROL_2541 > "$EVIDENCE/remote-control-desktop-send.json"
jq -e '.status == "sent" or .status == "queued"' "$EVIDENCE/remote-control-desktop-send.json"
wait_for_text 'DESKTOP_REPLY_2541 desktop_counter=2'
shot remote-control-desktop-reply
sudo cat /home/verifyssh/remote-control-receipts.jsonl > "$EVIDENCE/remote-control-receipts.jsonl"
sudo cat /home/verifyssh/remote-control-work/desktop-counter.txt > "$EVIDENCE/remote-control-desktop.txt"
sudo -u verifyssh tmux show-option -v -t "$fixture_session" @mp_host_sid > "$EVIDENCE/remote-control-sid.txt"
python3 - "$EVIDENCE" <<'PYPROOF'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
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
states = {}
for phase, expected in [('open', 'CLAUDE_DESKTOP_SESSION_2541'), ('draft', 'PHONE_REMOTE_CONTROL_2541'), ('connected', 'REMOTE_CONTROL_CONNECTED_2541 desktop_counter=1'), ('desktop-reply', 'DESKTOP_REPLY_2541 desktop_counter=2')]:
    files = list(root.glob(f'[0-9][0-9]-remote-control-{phase}.xml'))
    assert len(files) == 1, (phase, files)
    file = files[0]
    nodes = list(ET.parse(file).getroot().iter('node'))
    assert any(expected in node.get('text', '') for node in nodes), (phase, expected)
    if phase == 'draft':
        assert any(node.get('class') == 'android.widget.EditText' and node.get('text') == expected for node in nodes)
    assert file.with_suffix('.png').stat().st_size > 100
    states[phase] = dict(xml=file.name, screenshot=file.with_suffix('.png').name, text=expected)
root.joinpath('remote-control-state.json').write_text(json.dumps(states, indent=2) + '\n')
PYPROOF
printf 'CLAUDE_REMOTE_CONTROL_PHONE_AND_DESKTOP_OK\n'
exit 0
