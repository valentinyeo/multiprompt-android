#!/usr/bin/env bash
wait_for_text 'PI_BEFORE_MODEL_2539 opencode-go/deepseek-v4.1-flash'
shot pi-model-before
tap_desc Attach
wait_for_text Model
shot pi-model-plus
tap_text Model
wait_for_text deepseek-v4.1-flash
wait_for_text claude-sonnet-4-6
wait_for_text anthropic
shot pi-model-picker
tap_text claude-sonnet-4-6
wait_for_absence 'Add image'
wait_for_text 'PI_BEFORE_MODEL_2539 opencode-go/deepseek-v4.1-flash'
shot pi-model-selected
fill 'Message verify-session' PHONE_MODEL_2539
shot pi-model-draft
tap_desc 'Send prompt'
wait_for_text 'PI_AFTER_MODEL_2539 anthropic/claude-sonnet-4-6'
shot pi-model-reply
sudo cat /home/verifyssh/pi-model-switch-receipts.jsonl > "$EVIDENCE/pi-model-switch-receipts.txt"
python3 - "$EVIDENCE" <<'PYPROOF'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
rows = [json.loads(line) for line in (root / 'pi-model-switch-receipts.txt').read_text().splitlines()]
assert any(row['type'] == 'models' for row in rows), rows
calls = [row for row in rows if row['type'] != 'models']
assert [row['type'] for row in calls] == ['prompt', 'set_model', 'prompt'], calls
assert all(row['sid'] == 'c001c001-2539-4000-8000-000000002539' for row in calls), calls
assert calls[0]['message'] == 'PI_MODEL_READY_2539' and calls[0]['model'] == 'opencode-go/deepseek-v4.1-flash', calls
assert calls[1]['provider'] == 'anthropic' and calls[1]['modelId'] == 'anthropic/claude-sonnet-4-6', calls
assert calls[2]['message'] == 'PHONE_MODEL_2539' and calls[2]['model'] == 'anthropic/claude-sonnet-4-6', calls
states = {}
expected = {
    'before': ['PI_BEFORE_MODEL_2539 opencode-go/deepseek-v4.1-flash'],
    'plus': ['Model'],
    'picker': ['deepseek-v4.1-flash', 'claude-sonnet-4-6', 'anthropic'],
    'selected': ['PI_BEFORE_MODEL_2539 opencode-go/deepseek-v4.1-flash'],
    'draft': ['PHONE_MODEL_2539'],
    'reply': ['PI_AFTER_MODEL_2539 anthropic/claude-sonnet-4-6'],
}
for phase, required in expected.items():
    files = list(root.glob(f'[0-9][0-9]-pi-model-{phase}.xml'))
    assert len(files) == 1, (phase, files)
    file = files[0]
    nodes = list(ET.parse(file).getroot().iter('node'))
    texts = [node.get('text', '') for node in nodes]
    assert all(any(value in text for text in texts) for value in required), (phase, texts)
    if phase == 'selected':
        assert 'claude-sonnet-4-6' not in texts, texts
    if phase == 'draft':
        assert any(node.get('class') == 'android.widget.EditText' and node.get('text') == 'PHONE_MODEL_2539' for node in nodes)
    assert file.with_suffix('.png').stat().st_size > 100
    states[phase] = dict(xml=file.name, screenshot=file.with_suffix('.png').name,
        text=[text for text in texts if any(value in text for value in required)])
root.joinpath('pi-model-switch-state.json').write_text(json.dumps(states, indent=2) + '\n')
print('PI_MODEL_SWITCH_AND_NEXT_PHONE_REPLY_OK')
PYPROOF
exit 0
