#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

HOME = Path('/home/verifyssh')
SID = 'c001c001-2538-4000-8000-000000002538'
CWD = HOME / 'ai-switch-work'
TMUX = 'live-verify-session'


def boundary():
    assert Path.home() == HOME and os.getuid() != 0


def console():
    boundary()
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    CWD.mkdir(exist_ok=True)
    os.chdir(CWD)
    transcript = HOME / '.claude/projects' / str(CWD).replace('/', '-') / (SID + '.jsonl')
    transcript.parent.mkdir(parents=True, exist_ok=True)
    meta = HOME / '.claude/sessions' / (str(os.getpid()) + '.json')
    meta.parent.mkdir(parents=True, exist_ok=True)
    meta.write_text(json.dumps(dict(sessionId=SID, cwd=str(CWD), pid=os.getpid())))
    with transcript.open('w') as output:
        for role, text in [('user', 'AI_SWITCH_READY_2538'), ('assistant', 'CLAUDE_BEFORE_SWITCH_2538')]:
            output.write(json.dumps(dict(type=role, uuid=str(uuid.uuid4()), sessionId=SID,
                timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                message=dict(role=role, content=text, id='msg_' + uuid.uuid4().hex))) + '\n')
    enabled = subprocess.check_output([sys.executable, str(HOME / 'ai-switch-reader.py'), 'host', 'enable',
        'claude', SID, '--tmux', TMUX, '--cwd', str(CWD)], text=True)
    assert json.loads(enabled)['status'] == 'enabled', enabled
    print('Claude Code\nAI_SWITCH_READY_2538\nReady', flush=True)
    os.execvp('sleep', ['sleep', 'infinity'])


def emit(event):
    print(json.dumps(event), flush=True)


def claude():
    boundary()
    option = next((option for option in ('--session-id', '--resume') if option in sys.argv), None)
    sid = sys.argv[sys.argv.index(option) + 1] if option else SID
    emit(dict(type='system', subtype='init', session_id=sid, model='sonnet'))
    for line in sys.stdin:
        request = json.loads(line)
        if request.get('type') == 'control_request':
            emit(dict(type='control_response', response=dict(subtype='success', request_id=request['request_id'], response={})))
        elif request.get('type') == 'user':
            if sid == SID:
                with (HOME / 'ai-switch-wrong-provider.txt').open('a') as output:
                    output.write(line)
                continue
            content = request['message']['content']
            text = content if isinstance(content, str) else ''.join(part.get('text', '') for part in content)
            with (HOME / 'ai-switch-provider-receipts.jsonl').open('a') as output:
                output.write(json.dumps(dict(provider='claude', sid=sid, text=text)) + '\n')
            handover = 'This chat ran on Pi until now and continues on Claude.' in text
            reply = 'CLAUDE_HANDOVER_READY_2569' if handover else 'CLAUDE_REPLY_' + text
            emit(dict(type='assistant', uuid=str(uuid.uuid4()), session_id=sid,
                message=dict(id='msg_' + uuid.uuid4().hex, role='assistant', content=[dict(type='text', text=reply)])))
            emit(dict(type='result', subtype='success', stop_reason='end_turn', session_id=sid, usage={}))


def pi():
    boundary()
    assert '--mode' in sys.argv and sys.argv[sys.argv.index('--mode') + 1] == 'rpc'
    sid = sys.argv[sys.argv.index('--session-id') + 1]
    for line in sys.stdin:
        request = json.loads(line)
        if request.get('type') != 'prompt':
            continue
        text = request['message']
        with (HOME / 'ai-switch-provider-receipts.jsonl').open('a') as output:
            output.write(json.dumps(dict(provider='pi', sid=sid, id=request['id'], text=text)) + '\n')
        handover = 'This chat ran on Claude until now and continues on Pi.' in text
        reply = 'PI_HANDOVER_READY_2538' if handover else 'PI_REPLY_' + text
        emit(dict(type='response', command='prompt', id=request['id'], success=True))
        emit(dict(type='agent_start'))
        emit(dict(type='message_start'))
        emit(dict(type='message_update', assistantMessageEvent=dict(type='text_delta', contentIndex=0, delta=reply)))
        hold = HOME / 'ai-switch-hold-2569'
        if text == 'PHONE_AFTER_SWITCH_2538' and hold.exists():
            for attempt in range(120):
                if not hold.exists():
                    break
                time.sleep(0.25)
            assert not hold.exists(), 'Phone did not observe the streamed reply'
        if text == 'PHONE_STREAM_2569':
            time.sleep(20)
        emit(dict(type='message_update', assistantMessageEvent=dict(type='text_end', contentIndex=0, content=reply)))
        emit(dict(type='message_end', message=dict(role='assistant', stopReason='stop')))
        emit(dict(type='agent_settled'))


if __name__ == '__main__':
    if sys.argv[1:] == ['console']:
        console()
    elif '--print' in sys.argv[1:]:
        claude()
    else:
        pi()
