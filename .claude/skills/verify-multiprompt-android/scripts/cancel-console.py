import datetime
import json
import os
from pathlib import Path
import select
import sys
import termios
import tty
import uuid

HOME = Path.home()
CWD = HOME / 'cancel-work'
SID = 'c001c001-1010-4000-8000-000000001010'
TRANSCRIPT = HOME / '.claude/projects' / str(CWD).replace('/', '-') / (SID + '.jsonl')
READY = HOME / 'cancel-ready'
receipts = Path(sys.argv[1])
CWD.mkdir(exist_ok=True)
os.chdir(CWD)
TRANSCRIPT.parent.mkdir(parents=True, exist_ok=True)
TRANSCRIPT.write_text('')
meta = HOME / '.claude/sessions' / (str(os.getpid()) + '.json')
meta.parent.mkdir(parents=True, exist_ok=True)
meta.write_text(json.dumps(dict(sessionId=SID, cwd=str(CWD), pid=os.getpid())))


def event(role, text):
    message = dict(role=role, content=text)
    if role == 'assistant':
        message.update(id='message-' + uuid.uuid4().hex, stop_reason='end_turn')
    with TRANSCRIPT.open('a') as output:
        output.write(json.dumps(dict(type=role, uuid=str(uuid.uuid4()), sessionId=SID,
            timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(), message=message)) + '\n')


def render():
    print('\x1b[2J\x1b[HClaude Code\nVERIFY_READY_1010\n' +
          ('Ready' if READY.exists() else 'Working... (esc to interrupt)') + '\n\n❯ \n' + '─' * 70, flush=True)


event('user', 'VERIFY_READY_1010')
event('assistant', 'Ready for cancel tests.')
render()
previous = termios.tcgetattr(sys.stdin)
tty.setcbreak(sys.stdin)
buffer = ''
was_ready = READY.exists()
try:
    while True:
        ready, _, _ = select.select([sys.stdin], [], [], 0.1)
        if READY.exists() != was_ready:
            was_ready = READY.exists()
            render()
        if not ready:
            continue
        char = os.read(sys.stdin.fileno(), 1).decode('utf-8', 'replace')
        if not char:
            break
        if char in ('\r', '\n'):
            if buffer:
                with receipts.open('a') as output:
                    output.write('VERIFY_RECEIVED:' + buffer + '\n')
                event('user', buffer)
                event('assistant', 'Received ' + buffer)
                buffer = ''
                render()
        else:
            buffer += char
finally:
    termios.tcsetattr(sys.stdin, termios.TCSADRAIN, previous)
