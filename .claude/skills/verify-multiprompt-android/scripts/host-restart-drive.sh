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
host_restart_fixture metadata-legacy > "$EVIDENCE/host-restart-legacy-metadata.json"
host_restart_fixture evidence > "$EVIDENCE/host-restart-host.json"
if ! wait_for_text REPLY_DESKTOP_AFTER_2681; then
    host_restart_fixture evidence > "$EVIDENCE/host-restart-host.json"
    if ! jq -e 'any(.history[]; .role == "assistant" and .text == "REPLY_DESKTOP_AFTER_2681")' "$EVIDENCE/host-restart-host.json"; then
        setup_failure=1
        exit 1
    fi
    printf 'HOST_RESTART_REPLY_NOT_VISIBLE_2681\n'
    exit 1
fi
shot host-restart-after
host_restart_fixture evidence > "$EVIDENCE/host-restart-after-host.json"
host_restart_fixture metadata-config > "$EVIDENCE/host-restart-config-metadata.json"
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
legacy = json.loads(root.joinpath('host-restart-legacy-metadata.json').read_text())
assert process['old_pid'] != process['new_pid'] and legacy['history_pids'], (process, legacy)
assert process['sid'] == host['sid'] and process['tmux'] == host['tmux'], process
assert process['sid'] not in (process['native_before'], process['native_after'])
assert host['config']['native_session'] == process['native_after']
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
    if phase in ('after', 'phone-after'):
        assert not any('FOREIGN_OLD_SESSION_2681' in node.get('text', '') for node in nodes), f'FOREIGN_TRANSCRIPT_VISIBLE_2681: {phase}'
    assert file.with_suffix('.png').stat().st_size > 100
    states[phase] = dict(xml=file.name, screenshot=file.with_suffix('.png').name, text=text)
root.joinpath('host-restart-state.json').write_text(json.dumps(states, indent=2) + '\n')
PYPROOF
printf 'HOST_RESTART_REPLIES_VISIBLE_2681\n'
host_restart_fixture phone-restart-before > "$EVIDENCE/phone-restart-before.json"
tap_desc 'Session actions'
wait_for_text 'Restart on newest version'
shot phone-restart-chat-menu
tap_text 'Restart on newest version'
host_restart_fixture wait-phone-restart > "$EVIDENCE/phone-restart-chat-host.json"
wait_for_text REPLY_PHONE_AFTER_2681
shot phone-restart-chat-after
host_restart_fixture setup-batch > "$EVIDENCE/phone-restart-batch-before.json"
tap_desc Back
wait_for_text Inbox
tap_desc 'Session list actions'
wait_for_text 'Restart all idle chats'
shot phone-restart-inbox-menu
tap_text 'Restart all idle chats'
host_restart_fixture wait-batch-restart > "$EVIDENCE/phone-restart-batch-host.json"
shot phone-restart-inbox-after
python3 - "$EVIDENCE" <<'PYMUBO2'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
chat_host = json.loads(root.joinpath('phone-restart-chat-host.json').read_text())
batch_host = json.loads(root.joinpath('phone-restart-batch-host.json').read_text())
assert chat_host['rotated'] is True, chat_host
assert batch_host['idle_rotated'] is True and batch_host['working_untouched'] is True, batch_host
states = {}
for phase, required, forbidden in [
    ('chat-menu', ['Restart on newest version'], []),
    ('chat-after', ['REPLY_PHONE_AFTER_2681', 'verify-session'], ['FOREIGN_OLD_SESSION_2681', 'Restart on newest version']),
    ('inbox-menu', ['Restart all idle chats'], []),
    ('inbox-after', ['Inbox'], ['Restart all idle chats']),
]:
    files = list(root.glob(f'[0-9][0-9]-phone-restart-{phase}.xml'))
    assert len(files) == 1, (phase, files)
    file = files[0]
    texts = [node.get('text', '') for node in ET.parse(file).getroot().iter('node')]
    for value in required:
        assert any(value in text for text in texts), (phase, value)
    for value in forbidden:
        assert not any(value in text for text in texts), (phase, value)
    assert file.with_suffix('.png').stat().st_size > 100
    states[phase] = dict(xml=file.name, screenshot=file.with_suffix('.png').name, required=required)
root.joinpath('phone-restart-state.json').write_text(json.dumps(states, indent=2) + '\n')
root.joinpath('phone-restart-host.json').write_text(json.dumps(dict(chat=chat_host, batch=batch_host, working_untouched=True), indent=2) + '\n')
PYMUBO2
printf 'PHONE_RESTART_NEWEST_OK_MUBO2\n'
exit 0
