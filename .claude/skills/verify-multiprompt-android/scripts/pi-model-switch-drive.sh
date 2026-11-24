#!/usr/bin/env bash
capture_pi_model_host() {
    sudo cat /home/verifyssh/pi-model-switch-receipts.jsonl > "$EVIDENCE/pi-model-switch-receipts.txt" 2>/dev/null || true
    sudo sh -c 'cat /home/verifyssh/.local/state/multiprompt/agent-host/*.events.log' > "$EVIDENCE/pi-model-switch-host-events.txt" 2>/dev/null || true
    sudo sh -c 'cat /home/verifyssh/.local/state/multiprompt/agent-host/*.stderr.log' > "$EVIDENCE/pi-model-switch-host-errors.txt" 2>/dev/null || true
    sudo -u verifyssh env HOME=/home/verifyssh python3 /home/verifyssh/pi-model-switch-reader.py host status pi c001c001-0287-4000-8000-000000000287 > "$EVIDENCE/pi-model-switch-host-status.json" 2>/dev/null || true
    ps -eo pid,ppid,args | grep -E 'pi-model-switch|agent-host' | grep -v grep > "$EVIDENCE/pi-model-switch-host-processes.txt" 2>/dev/null || true
    sudo -u verifyssh env HOME=/home/verifyssh python3 - > "$EVIDENCE/pi-model-switch-store.json" 2>/dev/null <<'PYSTORE' || true
import json
import sqlite3
from pathlib import Path
path = Path('/home/verifyssh/.local/state/multiprompt/shared-messages/store.sqlite3')
rows = []
if path.exists():
    with sqlite3.connect(path) as db:
        rows = [json.loads(raw) for raw, in db.execute('select body from message_events order by seq')]
print(json.dumps(rows, indent=1))
PYSTORE
}
settle_reply() {
    sudo -u verifyssh rm -f /home/verifyssh/pi-model-switch-hold
    local attempt
    for attempt in {1..60}; do
        if sudo test -f /home/verifyssh/pi-model-switch-settled; then return 0; fi
        sleep 1
    done
    return 1
}
prompt_and_reply() {
    local prompt=$1 reply=$2 shot_name=${3:-} attempt seen=false
    sudo -u verifyssh rm -f /home/verifyssh/pi-model-switch-settled
    sudo -u verifyssh touch /home/verifyssh/pi-model-switch-hold
    fill 'Message verify-session' "$prompt"
    for attempt in {1..8}; do
        tap_desc 'Send prompt'
        if wait_for_text "$reply" 5 60; then seen=true; break; fi
        dump_ui || break
        if python3 "$scripts/ui.py" "$EVIDENCE/current.xml" field-value 'Message verify-session' "$prompt" >/dev/null 2>&1; then
            printf 'Journey retry %s: the draft was still in the composer\n' "$attempt" >&2
            continue
        fi
        printf 'Journey prompt %s was accepted but its reply never appeared\n' "$prompt" >&2
        break
    done
    if [[ "$seen" != true ]]; then
        sudo -u verifyssh rm -f /home/verifyssh/pi-model-switch-hold
        capture_pi_model_host
        return 1
    fi
    if [[ -n "$shot_name" ]]; then shot "$shot_name"; fi
    if ! settle_reply; then
        printf 'Journey reply %s never settled on the host\n' "$reply" >&2
        capture_pi_model_host
        return 1
    fi
}
wait_for_text 'Message verify-session'
prompt_and_reply PHONE_MODEL_BEFORE_287 'PI_MODEL_BEFORE_287 opencode-go/deepseek-v4.1-flash' pi-model-before
tap_desc Attach
wait_for_text 'Add image'
tap_text Model
wait_for_text 'No Pi models on this server. Tap to retry.'
shot pi-model-retry
tap_text 'No Pi models on this server. Tap to retry.'
wait_for_text deepseek-v4.1-flash
wait_for_text claude-sonnet-4-6
wait_for_text opencode-go
wait_for_text anthropic
shot pi-model-picker
tap_text Model
wait_for_text 'Add image'
shot pi-model-back
tap_text Model
wait_for_text claude-sonnet-4-6
shot pi-model-reopened
tap_text claude-sonnet-4-6
wait_for_absence 'Add image'
shot pi-model-picked
prompt_and_reply PHONE_MODEL_SWITCH_287 'PI_MODEL_AFTER_287 anthropic/claude-sonnet-4-6' pi-model-reply
capture_pi_model_host
python3 - "$EVIDENCE" <<'PYPROOF'
import json
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
sid = 'c001c001-0287-4000-8000-000000000287'
initial = 'opencode-go/deepseek-v4.1-flash'
chosen = 'anthropic/claude-sonnet-4-6'
rows = [json.loads(line) for line in (root / 'pi-model-switch-receipts.txt').read_text().splitlines()]
models = [row for row in rows if row['type'] == 'models']
snapshots = [row for row in rows if row['type'] == 'snapshot']
assert len(snapshots) >= 2, snapshots
assert len(models) >= 3, models
assert models[0]['result'] == 'empty', models
assert all(row['result'] == 'listed' for row in models[1:]), models
switches = [row for row in rows if row['type'] == 'set_model']
assert len(switches) == 1, switches
switch = switches[0]
assert switch['sid'] == sid and switch['provider'] == 'anthropic', switch
assert switch['modelId'] == chosen, switch
prompts = [row for row in rows if row['type'] == 'prompt']
order = [row for row in rows if row['type'] in ('prompt', 'set_model')]
switch_at = next(index for index, row in enumerate(order) if row['type'] == 'set_model')
before_prompts = [row for row in order[:switch_at] if row['type'] == 'prompt']
after_prompts = [row for row in order[switch_at + 1:] if row['type'] == 'prompt']
assert any(row['message'] == 'PHONE_MODEL_BEFORE_287' and row['model'] == initial for row in before_prompts), order
assert any(row['message'] == 'PHONE_MODEL_SWITCH_287' for row in after_prompts), order
assert all(row['model'] == chosen for row in after_prompts), order
expected = {
    'before': (['PI_MODEL_BEFORE_287 ' + initial], []),
    'retry': (['No Pi models on this server. Tap to retry.'], ['claude-sonnet-4-6']),
    'picker': (['deepseek-v4.1-flash', 'opencode-go', 'claude-sonnet-4-6', 'anthropic'], []),
    'back': (['Add image'], ['claude-sonnet-4-6']),
    'reopened': (['deepseek-v4.1-flash', 'claude-sonnet-4-6'], []),
    'picked': ([], ['Add image']),
    'reply': (['PI_MODEL_AFTER_287 ' + chosen], []),
}
states = {}
for phase, (required, forbidden) in expected.items():
    files = sorted(root.glob(f'[0-9][0-9]-pi-model-{phase}.xml'))
    assert len(files) == 1, (phase, files)
    file = files[0]
    nodes = list(ET.parse(file).getroot().iter('node'))
    texts = [node.get('text', '') for node in nodes]
    for value in required:
        assert any(value in text for text in texts), (phase, value, texts)
    for value in forbidden:
        assert not any(value in text for text in texts), (phase, value, texts)
    assert file.with_suffix('.png').stat().st_size > 100, phase
    states[phase] = dict(xml=file.name, screenshot=file.with_suffix('.png').name, text=required)
root.joinpath('pi-model-switch-state.json').write_text(json.dumps(states, indent=2) + '\n')
proof = dict(models=[row['result'] for row in models],
             snapshots=[row['waited'] for row in snapshots],
             switch=dict(provider=switch['provider'], modelId=switch['modelId'], sid=switch['sid']),
             prompts=[dict(message=row['message'], model=row['model']) for row in prompts],
             phone=dict(before='PI_MODEL_BEFORE_287 ' + initial, reply='PI_MODEL_AFTER_287 ' + chosen))
root.joinpath('pi-model-switch-proof.json').write_text(json.dumps(proof, indent=2) + '\n')
print('PI_MODEL_SWITCH_RETRY_SWITCH_AND_HOST_RECEIPT_OK')
PYPROOF
exit 0
