#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

HOME = Path('/home/verifyssh')
SID = 'c001c001-2539-4000-8000-000000002539'
CWD = HOME / 'pi-model-switch-work'
INITIAL = 'opencode-go/deepseek-v4.1-flash'
CHOSEN = 'anthropic/claude-sonnet-4-6'


def receipt(row):
    with (HOME / 'pi-model-switch-receipts.jsonl').open('a') as output:
        output.write(json.dumps(row) + '\n')


def emit(event):
    print(json.dumps(event), flush=True)


def setup():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    CWD.mkdir(exist_ok=True)
    command = [sys.executable, str(HOME / 'pi-model-switch-reader.py'), 'host']
    for args, statuses in [(['enable', 'pi', SID, '--tmux', 'verify-session', '--cwd', str(CWD)], ('enabled',)),
                           (['start', 'pi', SID], ('started',)),
                           (['send', 'pi', SID, '--text', 'PI_MODEL_READY_2539'], ('sent', 'queued'))]:
        result = subprocess.check_output(command + args, text=True)
        assert json.loads(result)['status'] in statuses, result
    print('PI_MODEL_FIXTURE_READY_2539', flush=True)
    for line in sys.stdin:
        receipt(dict(type='wrong_terminal_input', text=line))


def models():
    for line in sys.stdin:
        fields = line.rstrip('\n').split('\t')
        assert fields[1:3] == ['models', 'pi'], fields
        receipt(dict(type='models', id=fields[0]))
        print(f'@mp\t{fields[0]}\tmodels\tok\tpi\t-')
        print('M\tdeepseek-v4.1-flash\topencode-go\tInitial model')
        print('M\tclaude-sonnet-4-6\tanthropic\tDifferent model')
        print('@@MP_END@@', flush=True)


def pi():
    assert '--mode' in sys.argv and sys.argv[sys.argv.index('--mode') + 1] == 'rpc'
    sid = sys.argv[sys.argv.index('--session-id') + 1]
    assert sid == SID
    model = INITIAL
    directory = HOME / '.pi/agent/sessions' / ('--' + str(CWD).lstrip('/').replace('/', '-') + '--')
    directory.mkdir(parents=True, exist_ok=True)
    transcript = directory / ('2026-01-01_' + sid + '.jsonl')

    def event(row):
        with transcript.open('a') as output:
            output.write(json.dumps(dict(row, id=uuid.uuid4().hex,
                timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat())) + '\n')

    transcript.write_text(json.dumps(dict(type='session', version=3, id=sid, cwd=str(CWD))) + '\n')
    for line in sys.stdin:
        request = json.loads(line)
        if request.get('type') == 'set_model':
            assert request['provider'] == 'anthropic' and request['modelId'] == CHOSEN, request
            model = request['modelId']
            receipt(dict(request, sid=sid))
            event(dict(type='model_change', provider=request['provider'], modelId=model))
            emit(dict(type='response', command='set_model', success=True))
        elif request.get('type') == 'prompt':
            text = request['message']
            receipt(dict(request, sid=sid, model=model))
            reply = ('PI_BEFORE_MODEL_2539 ' if text == 'PI_MODEL_READY_2539' else 'PI_AFTER_MODEL_2539 ') + model
            event(dict(type='message', message=dict(role='user', content=text)))
            event(dict(type='message', message=dict(role='assistant', content=[dict(type='text', text=reply)], model=model)))
            emit(dict(type='response', command='prompt', id=request['id'], success=True))
            emit(dict(type='agent_start'))
            emit(dict(type='message_start'))
            emit(dict(type='message_update', assistantMessageEvent=dict(type='text_delta', contentIndex=0, delta=reply)))
            emit(dict(type='message_update', assistantMessageEvent=dict(type='text_end', contentIndex=0, content=reply)))
            emit(dict(type='message_end', message=dict(role='assistant', stopReason='stop')))
            emit(dict(type='agent_settled'))


if __name__ == '__main__':
    assert Path.home() == HOME and os.getuid() != 0
    if sys.argv[1:] == ['setup']:
        setup()
    elif sys.argv[1:] == ['--serve']:
        models()
    else:
        pi()
