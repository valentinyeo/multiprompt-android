#!/usr/bin/env bash
wait_for_text CLAUDE_BEFORE_SWITCH_2538
shot ai-switch-before
tap_desc 'Session actions'
tap_text 'Switch AI'
wait_for_text Codex
wait_for_text Pi
wait_for_absence Claude
shot ai-switch-picker
tap_text Pi
wait_for_text 'Switched AI: Claude to Pi'
wait_for_text PI_HANDOVER_READY_2538
shot ai-switch-reattached
fill 'Message verify-session' PHONE_AFTER_SWITCH_2538
shot ai-switch-draft
tap_desc 'Send prompt'
wait_for_text PI_REPLY_PHONE_AFTER_SWITCH_2538
shot ai-switch-reply
tap_desc 'Session actions'
tap_text 'Switch AI'
wait_for_text Claude
wait_for_text Codex
wait_for_absence Pi
shot ai-switch-current-pi
sudo cat /home/verifyssh/ai-switch-provider-receipts.jsonl > "$EVIDENCE/ai-switch-provider-receipts.jsonl"
sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 - "$EVIDENCE" > "$EVIDENCE/ai-switch-host.json" <<'PYHOST'
import json
from pathlib import Path
import sys
home = Path('/home/verifyssh')
sid = 'c001c001-2538-4000-8000-000000002538'
root = home / '.local/state/multiprompt/agent-host'
moved = json.loads((root / 'switched.json').read_text())[sid]
assert moved[0] == 'pi' and moved[1] != sid, moved
configs = [json.loads(file.read_text()) for file in root.glob('*.config.json')]
old = next(config for config in configs if config['sid'] == sid)
new = next(config for config in configs if config['sid'] == moved[1])
assert old['enabled'] is False and old['switched_to'] == dict(provider='pi', sid=moved[1]), old
assert new['enabled'] is True and new['provider'] == 'pi' and new['tmux'] == 'verify-session', new
receipts = [json.loads(line) for line in (home / 'ai-switch-provider-receipts.jsonl').read_text().splitlines()]
assert len(receipts) == 2, receipts
assert all(row['provider'] == 'pi' and row['sid'] == moved[1] for row in receipts), receipts
assert 'This chat ran on Claude until now and continues on Pi.' in receipts[0]['text'], receipts
assert 'CLAUDE_BEFORE_SWITCH_2538' in receipts[0]['text'], receipts
assert receipts[1]['text'] == 'PHONE_AFTER_SWITCH_2538', receipts
assert not (home / 'ai-switch-wrong-provider.txt').exists()
print(json.dumps(dict(old_provider='claude', provider='pi', sid=moved[1], tmux=new['tmux'], receipts=receipts)))
PYHOST
python3 - "$EVIDENCE" <<'PYUI'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
states = {}
for phase in ('before', 'picker', 'reattached', 'draft', 'reply', 'current-pi'):
    files = list(root.glob(f'[0-9][0-9]-ai-switch-{phase}.xml'))
    assert len(files) == 1, (phase, files)
    file = files[0]
    nodes = list(ET.parse(file).getroot().iter('node'))
    texts = [node.get('text', '') for node in nodes]
    if phase == 'before':
        assert any('CLAUDE_BEFORE_SWITCH_2538' in text for text in texts)
    elif phase in ('picker', 'current-pi'):
        expected = ('Codex', 'Pi') if phase == 'picker' else ('Claude', 'Codex')
        excluded = 'Claude' if phase == 'picker' else 'Pi'
        assert all(text in texts for text in expected) and excluded not in texts, texts
    elif phase == 'draft':
        assert any(node.get('class') == 'android.widget.EditText' and node.get('text') == 'PHONE_AFTER_SWITCH_2538' for node in nodes)
    elif phase == 'reattached':
        assert any('Switched AI: Claude to Pi' in text for text in texts)
        assert any('PI_HANDOVER_READY_2538' in text for text in texts)
    else:
        assert any('PI_REPLY_PHONE_AFTER_SWITCH_2538' in text for text in texts)
    assert file.with_suffix('.png').stat().st_size > 100
    states[phase] = dict(xml=file.name, screenshot=file.with_suffix('.png').name,
        text=[text for text in texts if text in ('Claude', 'Codex', 'Pi') or '2538' in text or 'Switched AI:' in text])
root.joinpath('ai-switch-state.json').write_text(json.dumps(states, indent=2) + '\n')
PYUI
printf 'AI_SWITCH_CLAUDE_TO_PI_AND_SEND_OK\n'
exit 0
