#!/usr/bin/env python3
import base64
from contextlib import closing
import datetime
import fcntl
import hashlib
import importlib.util
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
reader = None
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


if os.environ.get('VERIFY_FEATURE') in ('chat-queued.md', 'chat-clear.md', 'send-blocked.md'):
    spec = importlib.util.spec_from_file_location('queue_reader', HOME / 'queue-reader.py')
    reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reader)
    reader.CONNECTOR_SOURCES = {name: base64.b64encode((HOME / ('queue-' + name + '.py')).read_bytes()).decode() for name in ('claude', 'codex')}
    path = reader.message_store_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    lock = path.with_suffix('.' + hashlib.sha256(('claude:' + SID).encode()).hexdigest() + '.message-worker').open('a')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def accept_input(query):
        global hint_visible
        payload = query['text'] + '\n[mp:' + query['token'] + ']'
        native.append(payload)
        with RECEIPTS.open('a') as output:
            output.write('VERIFY_RECEIVED:' + query['text'] + '\n')
        hint_visible = True
        redraw()
        return dict(status='sent')

    reader.send_response_preserve_draft = accept_input


def advance_queue():
    if reader is None:
        return
    with closing(reader.message_store_db('verify-session')) as db, db:
        db.execute("UPDATE shared_messages SET deadline=NULL WHERE provider='claude' AND sid=? AND state IN ('queued','sent') AND deadline IS NOT NULL", (SID,))
        chat = db.execute("SELECT 1 FROM message_chats WHERE provider='claude' AND sid=?", (SID,)).fetchone()
    if not chat:
        return
    reader.message_poll('verify-session', 'claude', SID)
    if not draft and not native:
        reader.message_store_request(dict(op='message_dispatch', message_protocol=1, provider='claude', sid=SID, tmux='verify-session'))


def block_prompt(prompt):
    if reader is None:
        return False
    append(dict(type='system', subtype='informational', level='warning', preventContinuation=True, uuid=str(uuid.uuid4()),
                content='UserPromptSubmit hook blocked the prompt. Original prompt: ' + prompt,
                promptSubmitEnding=dict(ending=dict(kind='blocked_by_hook', reason='the verify fixture hook blocked this prompt'),
                                        promptKept=False)))
    (HOME / 'blocked-at.txt').write_text(str(time.time()) + '\n', encoding='utf-8')
    return True


def fail_messages():
    if reader is None:
        return
    with closing(reader.message_store_db('verify-session')) as db, db:
        db.execute("UPDATE shared_messages SET deadline=NULL,created=1 WHERE provider='claude' AND sid=? AND state IN ('queued','sent')", (SID,))
        reader.message_expire(db, 'claude', SID)


redraw()
while True:
    advance_queue()
    ready, _, _ = select.select([sys.stdin, server], [], [], 0.1)
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
            elif event.startswith('consume:') and native and native[0].split('\n', 1)[0] == event.partition(':')[2] and event.partition(':')[2] in ('WAITING_ONE', 'WAITING_TWO', 'FOLLOWUP'):
                text = native.pop(0)
                append(dict(type='attachment', uuid=str(uuid.uuid4()), attachment=dict(type='queued_command', prompt=text)))
                hint_visible = bool(native)
                redraw()
            elif event in ('marker:BEFORE_CONSUMPTION_MARKER', 'marker:UNCERTAIN_MARKER_1', 'marker:UNCERTAIN_MARKER_2'):
                reply(event.partition(':')[2])
            elif event.startswith('block:') and native and native[0].split('\n', 1)[0] == event.partition(':')[2]:
                result = 'ok' if block_prompt(event.partition(':')[2]) else 'invalid fixture event: ' + event
            elif event == 'fail-messages' and reader is not None:
                fail_messages()
            elif event == 'clear-transcript':
                replacement = TRANSCRIPT.with_name(SID + '.replacement')
                replacement.write_text(''.join(json.dumps(row) + '\n' for row in (
                    dict(type='user', uuid=str(uuid.uuid4()), message=dict(role='user', content='CLEARED_READY_2474')),
                    dict(type='assistant', uuid=str(uuid.uuid4()), message=dict(role='assistant', id='msg_cleared', content='Cleared.')))), encoding='utf-8')
                os.replace(replacement, TRANSCRIPT)
                native.clear()
                hint_visible = False
                lines[:] = ['CLEARED_READY_2474', 'Cleared.']
                redraw()
            elif event == 'reply' and not native:
                reply('Both messages received.')
            elif event == 'followup' and not native:
                append(dict(type='user', isMeta=True, message=dict(role='user', content=[dict(type='image', source=dict(type='base64', data='A' * (1024 * 1024)))])))
                reply('Followup received.')
            else:
                result = 'invalid fixture event: ' + event
            conn.sendall(result.encode())
