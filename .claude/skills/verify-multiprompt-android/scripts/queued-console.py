#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import select
import socket
import sys
import termios
import time
import uuid

if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('HOME') != '/home/verifyssh' or os.geteuid() == 0:
    raise SystemExit('queued console requires the hosted fixture')

HOME = Path('/home/verifyssh')
CWD = HOME / 'queued-work'
SID = 'a1b2c3d4-1008-4000-8000-000000001008'
TRANSCRIPT = HOME / '.claude/projects' / str(CWD).replace('/', '-') / (SID + '.jsonl')
RECEIPTS = HOME / 'receipts.txt'
SOCKET = HOME / 'queued-provider.sock'
DRAFT = 'FIXTURE_OWNED_DRAFT'


def append(row):
    row.update(sessionId=SID, timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z'))
    with TRANSCRIPT.open('a', encoding='utf-8') as output:
        output.write(json.dumps(row, ensure_ascii=False) + '\n')


if len(sys.argv) == 2:
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    client.settimeout(10)
    client.connect(str(SOCKET))
    client.sendall(sys.argv[1].encode())
    result = client.recv(4096).decode()
    print(result)
    if result != 'ok':
        raise SystemExit(1)
    raise SystemExit(0)

CWD.mkdir(parents=True, exist_ok=True)
os.chdir(CWD)
TRANSCRIPT.parent.mkdir(parents=True, exist_ok=True)
TRANSCRIPT.write_text('', encoding='utf-8')
RECEIPTS.write_text('', encoding='utf-8')
meta = HOME / '.claude/sessions' / (str(os.getpid()) + '.json')
meta.parent.mkdir(parents=True, exist_ok=True)
meta.write_text(json.dumps(dict(sessionId=SID, cwd=str(CWD), pid=os.getpid())), encoding='utf-8')
append(dict(type='user', uuid=str(uuid.uuid4()), message=dict(role='user', content='VERIFY_READY_1008')))
append(dict(type='assistant', uuid=str(uuid.uuid4()), message=dict(role='assistant', id='msg_ready', content='Ready for queued messages.')))

attrs = termios.tcgetattr(sys.stdin)
attrs[3] &= ~termios.ECHO
termios.tcsetattr(sys.stdin, termios.TCSANOW, attrs)
server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
SOCKET.unlink(missing_ok=True)
server.bind(str(SOCKET))
server.listen()
draft = DRAFT
native = []
hint_visible = False
lines = ['VERIFY_READY_1008', 'Ready for queued messages.']


def redraw():
    hint = 'press up to edit queued messages\n' if hint_visible else ''
    print('\x1b[2J\x1b[HClaude Code\n' + '\n'.join(lines) + '\n\nWorking... (esc to interrupt)\n' + hint + '❯ ' + draft + '\n' + '─' * 50, flush=True)


def reply(text):
    append(dict(type='assistant', uuid=str(uuid.uuid4()), message=dict(role='assistant', id='msg_' + str(uuid.uuid4()), content=text)))
    with (HOME / 'shell-events.jsonl').open('a') as output:
        output.write(json.dumps(dict(text=text, timestamp=time.time(), monotonic=time.monotonic())) + '\n')
    lines.append(text)
    redraw()


redraw()
while True:
    ready, _, _ = select.select([sys.stdin, server], [], [])
    if sys.stdin in ready:
        text = sys.stdin.readline().rstrip('\r\n')
        with RECEIPTS.open('a', encoding='utf-8') as output:
            output.write('VERIFY_RECEIVED:' + text + '\n')
        native.append(text)
        hint_visible = True
        redraw()
    if server in ready:
        conn, _ = server.accept()
        with conn:
            event = conn.recv(4096).decode()
            result = 'ok'
            if event == 'clear-draft' and draft == DRAFT and not native:
                draft = ''
                redraw()
            elif event == 'clear-hint' and native:
                hint_visible = False
                redraw()
            elif event.startswith('consume:') and native and native[0] == event.partition(':')[2] and native[0] in ('WAITING_ONE', 'WAITING_TWO', 'FOLLOWUP'):
                text = native.pop(0)
                append(dict(type='attachment', uuid=str(uuid.uuid4()), attachment=dict(type='queued_command', prompt=text)))
                hint_visible = bool(native)
                redraw()
            elif event in ('marker:BEFORE_CONSUMPTION_MARKER', 'marker:UNCERTAIN_MARKER_1', 'marker:UNCERTAIN_MARKER_2'):
                reply(event.partition(':')[2])
            elif event == 'reply' and not native:
                reply('Both messages received.')
            elif event == 'followup' and not native:
                append(dict(type='user', isMeta=True, message=dict(role='user', content=[dict(type='image', source=dict(type='base64', data='A' * (1024 * 1024)))])))
                reply('Followup received.')
            else:
                result = 'invalid fixture event: ' + event
            conn.sendall(result.encode())
