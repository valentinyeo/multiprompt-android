#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import sys
import time

HOME = Path('/home/verifyssh')
SID = 'c001c001-0287-4000-8000-000000000287'
CWD = HOME / 'pi-model-switch-work'
TMUX = 'live-verify-session'
MODELS = [('deepseek-v4.1-flash', 'opencode-go', 'Initial model'),
          ('claude-sonnet-4-6', 'anthropic', 'Different model')]
RECEIPTS = HOME / 'pi-model-switch-receipts.jsonl'
LISTED = HOME / 'pi-model-switch-models-listed'
HOLD = HOME / 'pi-model-switch-hold'
SETTLED = HOME / 'pi-model-switch-settled'
PHONE_BEFORE = 'PHONE_MODEL_BEFORE_287'


def boundary():
    assert Path.home() == HOME and os.getuid() != 0


def receipt(row):
    with RECEIPTS.open('a') as output:
        output.write(json.dumps(row) + '\n')


def emit(event):
    print(json.dumps(event), flush=True)


def console():
    boundary()
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    CWD.mkdir(exist_ok=True)
    os.chdir(CWD)
    enabled = subprocess.check_output([sys.executable, str(HOME / 'pi-model-switch-reader.py'), 'host',
        'enable', 'pi', SID, '--tmux', TMUX, '--cwd', str(CWD)], text=True)
    assert json.loads(enabled)['status'] == 'enabled', enabled
    print('PI_MODEL_SWITCH_READY_287', flush=True)
    os.execvp('sleep', ['sleep', 'infinity'])


def models():
    boundary()
    for line in sys.stdin:
        fields = line.rstrip('\n').split('\t')
        if len(fields) < 6 or fields[1:3] != ['models', 'pi']:
            receipt(dict(type='models_invalid', line=line.strip()))
            print('@@MP_END@@', flush=True)
            continue
        first = not LISTED.exists()
        if first:
            LISTED.write_text('1\n')
        receipt(dict(type='models', id=fields[0], result='empty' if first else 'listed'))
        print(f'@mp\t{fields[0]}\tmodels\tok\tpi\t-')
        if not first:
            for name, provider, detail in MODELS:
                print(f'M\t{name}\t{provider}\t{detail}')
        print('@@MP_END@@', flush=True)


def pi():
    boundary()
    assert '--mode' in sys.argv and sys.argv[sys.argv.index('--mode') + 1] == 'rpc'
    sid = sys.argv[sys.argv.index('--session-id') + 1]
    provider = sys.argv[sys.argv.index('--provider') + 1]
    model = provider + '/' + sys.argv[sys.argv.index('--model') + 1]
    for line in sys.stdin:
        request = json.loads(line)
        if request.get('type') == 'set_model':
            model = request['modelId']
            receipt(dict(type='set_model', sid=sid, provider=request.get('provider'),
                         modelId=request.get('modelId'), model=model))
            emit(dict(type='response', command='set_model', success=True))
        elif request.get('type') == 'prompt':
            text = request['message']
            receipt(dict(type='prompt', sid=sid, id=request.get('id'), message=text, model=model))
            marker = 'PI_MODEL_BEFORE_287' if text == PHONE_BEFORE else 'PI_MODEL_AFTER_287'
            reply = f'{marker} {model}'
            emit(dict(type='response', command='prompt', id=request.get('id'), success=True))
            emit(dict(type='agent_start'))
            emit(dict(type='message_start'))
            emit(dict(type='message_update', assistantMessageEvent=dict(type='text_delta', contentIndex=0, delta=reply)))
            if HOLD.exists():
                for attempt in range(960):
                    if not HOLD.exists():
                        break
                    time.sleep(0.25)
            emit(dict(type='message_update', assistantMessageEvent=dict(type='text_end', contentIndex=0, content=reply)))
            emit(dict(type='message_end', message=dict(role='assistant', stopReason='stop')))
            emit(dict(type='agent_settled'))
            SETTLED.write_text('1\n')
            receipt(dict(type='settled', sid=sid, id=request.get('id')))


if __name__ == '__main__':
    if sys.argv[1:] == ['console']:
        console()
    elif '--serve' in sys.argv[1:]:
        models()
    else:
        pi()
