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
import sys
import termios
import tty
import uuid

HOME = Path('/home/verifyssh')
CWD = HOME / 'cancel-work'
SID = 'c001c001-1010-4000-8000-000000001010'
TRANSCRIPT = HOME / '.claude/projects' / str(CWD).replace('/', '-') / (SID + '.jsonl')
READY = HOME / 'cancel-ready'
DRAFT = 'FIXTURE_OWNED_DRAFT'


def event(role, text):
    message = dict(role=role, content=text)
    if role == 'assistant':
        message.update(id='message-' + uuid.uuid4().hex, stop_reason='end_turn')
    with TRANSCRIPT.open('a') as output:
        output.write(json.dumps(dict(type=role, uuid=str(uuid.uuid4()), sessionId=SID,
            timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(), message=message)) + '\n')


def accept_input(query):
    assert READY.exists() and draft == ''
    with receipts.open('a') as output:
        output.write('VERIFY_RECEIVED:' + query['text'] + '\n')
    with TRANSCRIPT.open('a') as output:
        output.write(json.dumps(dict(type='attachment', uuid=str(uuid.uuid4()), sessionId=SID,
            timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
            attachment=dict(type='queued_command', prompt=query['text'] + '\n[mp:' + query['token'] + ']'))) + '\n')
    event('assistant', 'Received ' + query['text'])
    return dict(status='sent')


def advance_queue():
    with closing(reader.message_store_db('verify-session')) as db, db:
        db.execute("UPDATE shared_messages SET deadline=NULL WHERE provider='claude' AND sid=? AND state IN ('queued','sent') AND deadline IS NOT NULL", (SID,))
        chat = db.execute("SELECT 1 FROM message_chats WHERE provider='claude' AND sid=?", (SID,)).fetchone()
    if not chat:
        return
    reader.message_poll('verify-session', 'claude', SID)
    if READY.exists() and not draft:
        reader.message_store_request(dict(op='message_dispatch', message_protocol=1, provider='claude', sid=SID, tmux='verify-session'))


def render():
    print('\x1b[2J\x1b[HClaude Code\nVERIFY_READY_1010\n' +
          ('Ready' if READY.exists() else 'Working... (esc to interrupt)') + '\n\n❯ ' + draft + '\n' + '─' * 70, flush=True)


if __name__ == '__main__':
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('HOME') != str(HOME) or os.geteuid() == 0:
        raise SystemExit('cancel console requires the hosted fixture')
    receipts = Path(sys.argv[1])
    receipts.write_text('')
    CWD.mkdir(exist_ok=True)
    os.chdir(CWD)
    TRANSCRIPT.parent.mkdir(parents=True, exist_ok=True)
    TRANSCRIPT.write_text('')
    meta = HOME / '.claude/sessions' / (str(os.getpid()) + '.json')
    meta.parent.mkdir(parents=True, exist_ok=True)
    meta.write_text(json.dumps(dict(sessionId=SID, cwd=str(CWD), pid=os.getpid())))
    spec = importlib.util.spec_from_file_location('cancel_reader', HOME / 'cancel-reader.py')
    reader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(reader)
    reader.CONNECTOR_SOURCES = {name: base64.b64encode((HOME / ('cancel-' + name + '.py')).read_bytes()).decode() for name in ('claude', 'codex')}
    path = reader.message_store_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    lock = path.with_suffix('.' + hashlib.sha256(('claude:' + SID).encode()).hexdigest() + '.message-worker').open('a')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    reader.send_response_preserve_draft = accept_input
    draft = DRAFT
    event('user', 'VERIFY_READY_1010')
    event('assistant', 'Ready for cancel tests.')
    render()
    previous = termios.tcgetattr(sys.stdin)
    tty.setcbreak(sys.stdin)
    try:
        while True:
            if READY.exists() and draft:
                assert draft == DRAFT
                draft = ''
                render()
            advance_queue()
            ready, _, _ = select.select([sys.stdin], [], [], 0.1)
            if ready:
                raise AssertionError('canonical fixture must not receive raw terminal input')
    finally:
        termios.tcsetattr(sys.stdin, termios.TCSADRAIN, previous)
