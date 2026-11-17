#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

HOME = Path('/home/verifyssh')
SID = 'c001c001-2541-4000-8000-000000002541'
CWD = HOME / 'remote-control-work'
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
    with transcript.open('w') as output:
        for role, text in [('user', 'REMOTE_CONTROL_READY_2541'), ('assistant', 'CLAUDE_DESKTOP_SESSION_2541')]:
            output.write(json.dumps(dict(type=role, uuid=str(uuid.uuid4()), sessionId=SID,
                timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                message=dict(role=role, content=text, id='msg_' + uuid.uuid4().hex))) + '\n')
    enabled = subprocess.check_output([sys.executable, str(HOME / 'remote-control-reader.py'), 'host', 'enable',
        'claude', SID, '--tmux', TMUX, '--cwd', str(CWD)], text=True)
    assert json.loads(enabled)['status'] == 'enabled', enabled
    subprocess.run(['tmux', 'set-option', '-t', TMUX, '@mp_host_sid', SID], check=True)
    (HOME / 'remote-control-receipts.jsonl').write_text('')
    (CWD / 'desktop-counter.txt').write_text('0\n')
    print('Claude Code\nREMOTE_CONTROL_READY_2541\nReady', flush=True)
    os.execvp('sleep', ['sleep', 'infinity'])


def emit(event):
    print(json.dumps(event), flush=True)


def record(**row):
    with (HOME / 'remote-control-receipts.jsonl').open('a') as output:
        output.write(json.dumps(dict(provider='claude', sid=SID, tmux=TMUX, **row)) + '\n')


def claude():
    boundary()
    assert str(Path.cwd()) == str(CWD)
    assert '--resume' in sys.argv and sys.argv[sys.argv.index('--resume') + 1] == SID
    connected = False
    emit(dict(type='system', subtype='init', session_id=SID, model='sonnet'))
    for line in sys.stdin:
        request = json.loads(line)
        if request.get('type') == 'control_request':
            control = request.get('request', {})
            if control.get('subtype') == 'remote_control':
                connected = control.get('enabled') is True
                record(kind='remote_control', frame=request, connected=connected)
            emit(dict(type='control_response', response=dict(subtype='success', request_id=request['request_id'], response={})))
        elif request.get('type') == 'user':
            content = request['message']['content']
            text = content if isinstance(content, str) else ''.join(block.get('text', '') for block in content)
            assert connected, 'Phone message reached Claude without enabling remote control'
            counter = CWD / 'desktop-counter.txt'
            value = int(counter.read_text()) + 1
            counter.write_text(str(value) + '\n')
            record(kind='user', text=text, connected=connected, desktop_counter=value)
            reply = f'REMOTE_CONTROL_CONNECTED_2541 desktop_counter={value}' if value == 1 else f'DESKTOP_REPLY_2541 desktop_counter={value}'
            emit(dict(type='assistant', uuid=str(uuid.uuid4()), session_id=SID,
                message=dict(id='msg_' + uuid.uuid4().hex, role='assistant', content=[dict(type='text', text=reply)])))
            emit(dict(type='result', subtype='success', stop_reason='end_turn', session_id=SID, usage={}))


if __name__ == '__main__':
    if sys.argv[1:] == ['console']:
        console()
    else:
        claude()
