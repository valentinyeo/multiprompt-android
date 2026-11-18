#!/usr/bin/env bash
fixture_session=live-verify-session
host_restart_fixture() {
    if ! sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/host-restart-fixture.py "$@"; then
        setup_failure=1
        return 1
    fi
}
wait_for_text DESKTOP_CHAT_READY_2681
shot host-restart-open
fill 'Message verify-session' PHONE_BEFORE_2681
shot host-restart-draft
tap_desc 'Send prompt'
wait_for_text REPLY_PHONE_BEFORE_2681
shot host-restart-before
host_restart_fixture restart > "$EVIDENCE/host-restart-processes.txt"
sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 /home/verifyssh/host-restart-reader.py host send claude c001c001-2681-4000-8000-000000002681 DESKTOP_AFTER_2681 > "$EVIDENCE/host-restart-desktop-send.json"
jq -e '.status == "sent" or .status == "queued"' "$EVIDENCE/host-restart-desktop-send.json"
host_restart_fixture evidence > "$EVIDENCE/host-restart-host.json"
if ! wait_for_text REPLY_DESKTOP_AFTER_2681; then
    host_restart_fixture evidence > "$EVIDENCE/host-restart-host.json"
    if ! jq -e '.old_accepted == 40 and any(.history[]; .role == "assistant" and .text == "REPLY_DESKTOP_AFTER_2681")' "$EVIDENCE/host-restart-host.json"; then
        setup_failure=1
        exit 1
    fi
    printf 'HOST_RESTART_REPLY_NOT_VISIBLE_2681\n'
    exit 1
fi
shot host-restart-after
fill 'Message verify-session' PHONE_AFTER_2681
tap_desc 'Send prompt'
if [[ "${BREAK_IT_MODE:-}" == true ]]; then
    host_restart_fixture finish-stream > "$EVIDENCE/host-restart-stream-host.json"
    break_it_stream_send "$EVIDENCE/host-restart-stream-host.json"
fi
if ! wait_for_text REPLY_PHONE_AFTER_2681; then
    host_restart_fixture evidence > "$EVIDENCE/host-restart-host.json"
    printf 'HOST_RESTART_PHONE_REPLY_NOT_VISIBLE_2681\n'
    exit 1
fi
shot host-restart-phone-after
host_restart_fixture evidence > "$EVIDENCE/host-restart-host.json"
python3 - "$EVIDENCE" <<'PYPROOF'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
host = json.loads(root.joinpath('host-restart-host.json').read_text())
process = json.loads(root.joinpath('host-restart-processes.txt').read_text().splitlines()[-1])
assert process['old_pid'] != process['new_pid'] and process['history_pids'], process
assert process['sid'] == host['sid'] and process['tmux'] == host['tmux'], process
assert process['sid'] not in (process['native_before'], process['native_after'])
assert host['old_accepted'] == 40
assert all(not row['timestamp'].endswith('Z') for row in host['history'])
expected = ['PHONE_BEFORE_2681', 'DESKTOP_AFTER_2681', 'PHONE_AFTER_2681']
assert [row['text'] for row in host['receipts']] == expected, host
assert host['receipts'][0]['pid'] != host['receipts'][1]['pid']
assert host['receipts'][1]['pid'] == host['receipts'][2]['pid']
for text in expected:
    assert sum(row['role'] == 'assistant' and row['text'] == 'REPLY_' + text for row in host['history']) == 1, host
states = {}
for phase, text in [('open', 'DESKTOP_CHAT_READY_2681'), ('draft', 'PHONE_BEFORE_2681'), ('before', 'REPLY_PHONE_BEFORE_2681'), ('after', 'REPLY_DESKTOP_AFTER_2681'), ('phone-after', 'REPLY_PHONE_AFTER_2681')]:
    files = list(root.glob(f'[0-9][0-9]-host-restart-{phase}.xml'))
    assert len(files) == 1, (phase, files)
    file = files[0]
    nodes = list(ET.parse(file).getroot().iter('node'))
    assert any(node.get('text') == text for node in nodes), (phase, text)
    assert file.with_suffix('.png').stat().st_size > 100
    states[phase] = dict(xml=file.name, screenshot=file.with_suffix('.png').name, text=text)
root.joinpath('host-restart-state.json').write_text(json.dumps(states, indent=2) + '\n')
PYPROOF
printf 'HOST_RESTART_REPLIES_VISIBLE_2681\n'
exit 0
