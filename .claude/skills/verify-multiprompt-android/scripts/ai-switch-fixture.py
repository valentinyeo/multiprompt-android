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
    print('Claude Code\nAI_SWITCH_READY_2538\nPI_PANE_ONLY_545\nReady', flush=True)
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


def models():
    boundary()
    for line in sys.stdin:
        fields = line.rstrip('\n').split('\t')
        assert fields[1:3] == ['models', 'pi'], fields
        choices = [dict(name='pi-fast', provider='anthropic'), dict(name='pi-deep', provider='anthropic')]
        (HOME / 'ai-switch-models.json').write_text(json.dumps(choices))
        print(f'@mp\t{fields[0]}\tmodels\tok\t-\t{len(choices)}')
        for model in choices:
            print(f'M\t{model["name"]}\t{model["provider"]}\t')
        print('@@MP_END@@', flush=True)


def pi():
    boundary()
    assert '--mode' in sys.argv and sys.argv[sys.argv.index('--mode') + 1] == 'rpc'
    sid = sys.argv[sys.argv.index('--session-id') + 1]
    for line in sys.stdin:
        request = json.loads(line)
        if request.get('type') != 'prompt':
            continue
        text = request['message']
        if text == 'PI_LOGIN_PROMPT_2276':
            emit(dict(type='response', command='prompt', id=request['id'], success=True))
            emit(dict(type='extension_ui_request', id='pi-login-2276', method='open_url',
                url='https://pi.example/verify-device', instructions='Sign in to Pi to continue'))
            continue
        if text == 'PI_LOGIN_DONE_2276':
            emit(dict(type='response', command='prompt', id=request['id'], success=True))
            emit(dict(type='response', command='login', success=True, data=dict(providerId='anthropic')))
            continue
        if text == 'PI_LOGIN_AFTER_2276':
            reply = 'PI_REPLY_PI_LOGIN_AFTER_2276'
            emit(dict(type='response', command='prompt', id=request['id'], success=True))
            emit(dict(type='agent_start'))
            emit(dict(type='message_start'))
            emit(dict(type='message_update', assistantMessageEvent=dict(type='text_end', contentIndex=0, content=reply)))
            emit(dict(type='message_end', message=dict(role='assistant', stopReason='stop')))
            emit(dict(type='agent_settled'))
            continue
        with (HOME / 'ai-switch-provider-receipts.jsonl').open('a') as output:
            output.write(json.dumps(dict(provider='pi', sid=sid, id=request['id'], text=text)) + '\n')
        handover = 'This chat ran on Claude until now and continues on Pi.' in text
        reply = 'PI_HANDOVER_READY_2538' if handover else 'PI_REPLY_' + text
        hold = HOME / ('ai-switch-hold-stream-2569' if text == 'PHONE_STREAM_2569' else 'ai-switch-hold-2569')
        controlled = text == 'PHONE_STREAM_2569' or text == 'PHONE_AFTER_SWITCH_2538' and hold.exists()
        emit(dict(type='response', command='prompt', id=request['id'], success=True))
        emit(dict(type='agent_start'))
        emit(dict(type='message_start'))
        for delta in reply if controlled else [reply]:
            emit(dict(type='message_update', assistantMessageEvent=dict(type='text_delta', contentIndex=0, delta=delta)))
            if controlled:
                time.sleep(0.2)
        if controlled:
            for attempt in range(480):
                if not hold.exists():
                    break
                time.sleep(0.25)
            assert not hold.exists(), 'Phone did not release the streamed reply'
        emit(dict(type='message_update', assistantMessageEvent=dict(type='text_end', contentIndex=0, content=reply)))
        emit(dict(type='message_end', message=dict(role='assistant', stopReason='stop')))
        emit(dict(type='agent_settled'))


if __name__ == '__main__':
    if sys.argv[1:] == ['console']:
        console()
    elif sys.argv[1:] == ['--serve']:
        models()
    elif '--print' in sys.argv[1:]:
        claude()
    else:
        pi()
