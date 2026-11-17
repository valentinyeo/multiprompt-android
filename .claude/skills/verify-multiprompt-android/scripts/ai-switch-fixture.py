#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

HOME = Path('/home/verifyssh')
SID = 'c001c001-2538-4000-8000-000000002538'
CWD = HOME / 'ai-switch-work'


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
        'claude', SID, '--tmux', 'verify-session', '--cwd', str(CWD)], text=True)
    assert json.loads(enabled)['status'] == 'enabled', enabled
    print('Claude Code\nAI_SWITCH_READY_2538\nReady\n❯ ', flush=True)
    for line in sys.stdin:
        with (HOME / 'ai-switch-wrong-provider.txt').open('a') as output:
            output.write(line)


def emit(event):
    print(json.dumps(event), flush=True)


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
        emit(dict(type='message_update', assistantMessageEvent=dict(type='text_end', contentIndex=0, content=reply)))
        emit(dict(type='message_end', message=dict(role='assistant', stopReason='stop')))
        emit(dict(type='agent_settled'))


if __name__ == '__main__':
    if sys.argv[1:] == ['console']:
        console()
    else:
        pi()
