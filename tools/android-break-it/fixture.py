import importlib.util
import json
import os
from pathlib import Path
import sys
import time

HOME = Path('/home/verifyssh')
SID = 'b2679000-0000-4000-8000-000000002679'
CHAT = 'live-pi_verify-breakit-probe'


def emit(event):
    print(json.dumps(event), flush=True)


assert Path.home() == HOME and os.getuid() != 0
assert os.environ.get('GITHUB_ACTIONS') == 'true'
if sys.argv[1:] in (['seed'], ['release'], ['proof'], ['stop']):
    spec = importlib.util.spec_from_file_location('reader', str(HOME / 'breakit-reader.py'))
    reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reader)
    if sys.argv[1] == 'stop':
        assert reader.host_cli(['disable', 'pi', SID])['status'] == 'disabled'
        sys.exit(0)
    if sys.argv[1] in ('release', 'proof'):
        with reader.message_store_db(CHAT) as db:
            for attempt in range(300):
                rows = db.execute("SELECT id, text, created FROM shared_messages WHERE sid=? ORDER BY created", (SID,)).fetchall()
                if [row[1] for row in rows] == ['BREAK_IT_STREAM', 'BREAK_IT_FOLLOWUP']:
                    break
                time.sleep(0.1)
            assert [row[1] for row in rows] == ['BREAK_IT_STREAM', 'BREAK_IT_FOLLOWUP'], rows
            events = [json.loads(raw) for raw, in db.execute('SELECT body FROM message_events WHERE sid=? ORDER BY seq', (SID,))]
            turn = next(event['turn'] for event in events if event.get('kind') == 'turn.started' and event['payload'].get('message_id') == rows[0][0])
            completed = next((event['at'] for event in events if event.get('kind') == 'text.done' and event.get('turn') == turn), None)
            if sys.argv[1] == 'release':
                assert completed is None, 'Stream completed before follow-up admission'
                (HOME / 'breakit-release-stream').touch()
            else:
                submitted = rows[1][2] * 1000
                assert completed is not None and submitted < completed, (submitted, completed)
                print(json.dumps(dict(followup_submitted_while_streaming=True, submitted=submitted, completed=completed, receipts=rows)))
        sys.exit(0)
    assert reader.host_cli(['enable', 'pi', SID, '--tmux', CHAT, '--cwd', str(HOME)])['status'] == 'enabled'
    paths = reader.host_paths('pi', SID)
    config = reader.host_read(paths['config'])
    config['command'] = [sys.executable, str(HOME / 'breakit-fixture.py')]
    reader.host_write(paths['config'], config)
    reader.host_state_write('pi', SID, dict(provider='pi', sid=SID, display_history=True))
    reader.host_history_append('pi', SID, dict(state=dict(display_history=True)), dict(role='assistant', text='BREAK_IT_READY'))
    assert reader.host_cli(['start', 'pi', SID])['status'] in ('started', 'already')
else:
    for line in sys.stdin:
        request = json.loads(line)
        if request.get('type') != 'prompt':
            continue
        text = request['message']
        assert text in ('BREAK_IT_STREAM', 'BREAK_IT_FOLLOWUP'), text
        emit(dict(type='response', command='prompt', id=request['id'], success=True))
        emit(dict(type='agent_start'))
        emit(dict(type='message_start'))
        reply = text + '_REPLY'
        for delta in reply if text == 'BREAK_IT_STREAM' else [reply]:
            emit(dict(type='message_update', assistantMessageEvent=dict(type='text_delta', contentIndex=0, delta=delta)))
            if text == 'BREAK_IT_STREAM':
                time.sleep(0.2)
        if text == 'BREAK_IT_STREAM':
            for attempt in range(1200):
                if (HOME / 'breakit-release-stream').exists():
                    break
                time.sleep(0.1)
            assert (HOME / 'breakit-release-stream').exists(), 'Follow-up not admitted while streaming'
        emit(dict(type='message_update', assistantMessageEvent=dict(type='text_end', contentIndex=0, content=reply)))
        emit(dict(type='message_end', message=dict(role='assistant', stopReason='stop')))
        emit(dict(type='agent_settled'))
