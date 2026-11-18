#!/usr/bin/env bash
fixture_session=live-verify-session
wait_for_text CLAUDE_BEFORE_SWITCH_2538
shot ai-switch-before
tap_desc 'Session actions'
wait_for_text 'Search in chat'
shot ai-switch-actions
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
sudo -u verifyssh touch /home/verifyssh/ai-switch-hold-2569
tap_desc 'Send prompt'
wait_for_text PI_REPLY_PHONE_AFTER_SWITCH_2538
shot ai-switch-before-catchup
sudo -u verifyssh rm /home/verifyssh/ai-switch-hold-2569
sudo -u verifyssh env HOME=/home/verifyssh python3 - > "$EVIDENCE/ai-switch-replay.json" <<'PYCATCHUP'
import importlib.util
import json
import time
spec = importlib.util.spec_from_file_location('reader', '/home/verifyssh/ai-switch-reader.py')
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)
provider, sid = reader.host_switch_follow('c001c001-2538-4000-8000-000000002538')
path = reader.host_paths(provider, sid)['history']
with reader.message_store_db('live-verify-session') as db:
    for attempt in range(100):
        events = [json.loads(raw) for raw, in db.execute('SELECT body FROM message_events WHERE provider=? AND sid=? ORDER BY seq', (provider, sid))]
        done = next((event for event in reversed(events) if event.get('kind') == 'text.done' and event.get('payload', {}).get('text') == 'PI_REPLY_PHONE_AFTER_SWITCH_2538'), None)
        if done and 'PI_REPLY_PHONE_AFTER_SWITCH_2538' in path.read_text():
            break
        time.sleep(0.1)
    assert done, 'Host did not complete and archive the reply'
    started = next(event for event in events if event.get('kind') == 'turn.started' and event.get('turn') == done['turn'])
    assert started['payload'].get('message_id'), started
    reader.message_event(db, provider, sid, started)
    reader.message_event(db, provider, sid, done)
reader.host_history_append(provider, sid, dict(state=dict(display_history=True)),
    dict(role='assistant', text='PI_HISTORY_CAUGHT_UP_2569', id='fixture-history-barrier-2569'))
print(json.dumps(dict(sid=sid, replayed=[started, done])))
PYCATCHUP
wait_for_text PI_HISTORY_CAUGHT_UP_2569
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
assert new['enabled'] is True and new['provider'] == 'pi' and new['tmux'] == 'live-verify-session', new
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
        replies = [text for text in texts if text == 'PI_REPLY_PHONE_AFTER_SWITCH_2538']
        assert len(replies) == 1, f'AI_SWITCH_REPLY_DUPLICATE: expected one reply, observed {len(replies)}'
    assert file.with_suffix('.png').stat().st_size > 100
    states[phase] = dict(xml=file.name, screenshot=file.with_suffix('.png').name,
        text=[text for text in texts if text in ('Claude', 'Codex', 'Pi') or '2538' in text or 'Switched AI:' in text])
root.joinpath('ai-switch-state.json').write_text(json.dumps(states, indent=2) + '\n')
PYUI
adb shell input keyevent KEYCODE_BACK
if [[ "${BREAK_IT_MODE:-}" == true ]]; then
    break_it_active=stream-send
    printf '::group::Break-it 5/5 Send while a reply streams\n'
fi
sudo -u verifyssh touch /home/verifyssh/ai-switch-hold-stream-2569
fill 'Message verify-session' PHONE_STREAM_2569
tap_desc 'Send prompt'
wait_for_text PI_REPLY_PHONE_STREAM_2569
shot ai-switch-streaming
fill 'Message verify-session' PHONE_DURING_STREAM_2569
shot ai-switch-during-stream-draft
tap_desc 'Send prompt'
sudo -u verifyssh env HOME=/home/verifyssh python3 - <<'PYADMITTED'
from pathlib import Path
import sqlite3
import time
with sqlite3.connect(Path.home() / '.local/state/multiprompt/shared-messages/store.sqlite3') as db:
    for attempt in range(100):
        admitted = db.execute("SELECT id FROM shared_messages WHERE text='PHONE_DURING_STREAM_2569'").fetchone()
        if admitted:
            break
        time.sleep(0.1)
    assert admitted, 'Follow-up was not admitted while the reply streamed'
(Path.home() / 'ai-switch-hold-stream-2569').unlink()
PYADMITTED
adb shell input keyevent KEYCODE_HOME
shot ai-switch-background
adb shell am start -n "$package/dev.multiprompt.companion.MainActivity"
wait_for_text PI_REPLY_PHONE_DURING_STREAM_2569
shot ai-switch-resumed
tap_desc 'Session actions'
tap_text 'Switch AI'
wait_for_text Claude
tap_text Claude
wait_for_text 'Switched AI: Pi to Claude'
wait_for_text CLAUDE_HANDOVER_READY_2569
shot ai-switch-second-handover
fill 'Message verify-session' PHONE_SECOND_SWITCH_2569
tap_desc 'Send prompt'
wait_for_text CLAUDE_REPLY_PHONE_SECOND_SWITCH_2569
shot ai-switch-second-reply
sudo cat /home/verifyssh/ai-switch-provider-receipts.jsonl > "$EVIDENCE/ai-switch-provider-receipts.jsonl"
sudo -u verifyssh env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 - > "$EVIDENCE/ai-switch-stress-host.json" <<'PYSTRESSHOST'
import json
from pathlib import Path
import sqlite3
home = Path('/home/verifyssh')
root = home / '.local/state/multiprompt'
receipts = [json.loads(line) for line in (home / 'ai-switch-provider-receipts.jsonl').read_text().splitlines()]
assert len(receipts) == 6, receipts
assert [row['provider'] for row in receipts] == ['pi'] * 4 + ['claude'] * 2, receipts
assert [row['text'] for row in receipts[1:4]] == ['PHONE_AFTER_SWITCH_2538', 'PHONE_STREAM_2569', 'PHONE_DURING_STREAM_2569'], receipts
assert receipts[5]['text'] == 'PHONE_SECOND_SWITCH_2569', receipts
assert 'This chat ran on Pi until now and continues on Claude.' in receipts[4]['text'], receipts
assert receipts[4]['sid'] == receipts[5]['sid'] != receipts[0]['sid'], receipts
assert not (home / 'ai-switch-wrong-provider.txt').exists()
with sqlite3.connect(root / 'shared-messages/store.sqlite3') as db:
    events = [json.loads(raw) for raw, in db.execute('SELECT body FROM message_events WHERE sid=? ORDER BY seq', (receipts[2]['sid'],))]
    turn = next(event['turn'] for event in events if event.get('kind') == 'turn.started' and event['payload'].get('message_id') == receipts[2]['id'])
    completed = next(event['at'] for event in events if event.get('kind') == 'text.done' and event.get('turn') == turn)
    submitted = db.execute('SELECT created FROM shared_messages WHERE id=?', (receipts[3]['id'],)).fetchone()[0] * 1000
    assert submitted < completed, (submitted, completed)
configs = [json.loads(path.read_text()) for path in (root / 'agent-host').glob('*.config.json')]
assert [config['provider'] for config in configs if config['enabled']] == ['claude'], configs
print(json.dumps(dict(switches=2, followup_submitted_while_streaming=True, submitted=submitted, completed=completed, receipts=receipts[1:4] + receipts[5:])))
PYSTRESSHOST
python3 - "$EVIDENCE" <<'PYSTRESSUI'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
state = json.loads((root / 'ai-switch-state.json').read_text())
for phase, reply in (('streaming', 'PI_REPLY_PHONE_STREAM_2569'), ('resumed', 'PI_REPLY_PHONE_DURING_STREAM_2569'), ('second-handover', 'CLAUDE_HANDOVER_READY_2569'), ('second-reply', 'CLAUDE_REPLY_PHONE_SECOND_SWITCH_2569')):
    file, = root.glob(f'[0-9][0-9]-ai-switch-{phase}.xml')
    texts = [node.get('text', '') for node in ET.parse(file).getroot().iter('node')]
    assert texts.count(reply) == 1, f'AI_SWITCH_REPLY_DUPLICATE: {reply} observed {texts.count(reply)}'
    state[phase] = dict(xml=file.name, reply=reply, count=texts.count(reply))
(root / 'ai-switch-state.json').write_text(json.dumps(state, indent=2) + '\n')
PYSTRESSUI
break_it_stream_send "$EVIDENCE/ai-switch-stress-host.json"
printf 'AI_SWITCH_CLAUDE_TO_PI_AND_SEND_OK\n'
printf 'AI_SWITCH_TWICE_STREAM_SEND_BACKGROUND_OK\n'
exit 0
