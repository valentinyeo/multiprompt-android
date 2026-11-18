#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import runpy
import signal
import shutil
import sqlite3
import subprocess
import sys
import time
import uuid

HOME = Path('/home/verifyssh')
SID = 'c001c001-2681-4000-8000-000000002681'
NATIVE_BEFORE = 'c001c001-2681-4000-8000-000000000001'
NATIVE_AFTER = 'c001c001-2681-4000-8000-000000000002'
TMUX = 'live-verify-session'
CWD = HOME / 'host-restart-work'
READER = HOME / 'host-restart-reader.py'
RECEIPTS = HOME / 'host-restart-receipts.jsonl'


def boundary():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert Path.home() == HOME and os.getuid() != 0


def cli(*args):
    result = subprocess.check_output([sys.executable, str(READER), 'host', *args], text=True)
    value = json.loads(result)
    print(json.dumps(value), flush=True)
    return value


def runtime():
    return runpy.run_path(str(READER), run_name='restart_fixture')


def native_row(sid, role, text, identity):
    path = HOME / '.claude/projects' / str(CWD).replace('/', '-') / (sid + '.jsonl')
    path.parent.mkdir(parents=True, exist_ok=True)
    row = dict(type=role, uuid=identity, sessionId=sid,
        timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z'),
        message=dict(role=role, id=identity, content=[dict(type='text', text=text)]))
    with path.open('a') as output:
        output.write(json.dumps(row) + '\n')


def console():
    CWD.mkdir(exist_ok=True)
    os.chdir(CWD)
    assert cli('enable', 'claude', SID, '--tmux', TMUX, '--cwd', str(CWD))['status'] == 'enabled'
    reader = runtime()
    reader['host_write'](reader['host_paths']('claude', SID)['state'], dict(
        native_session=NATIVE_BEFORE, nightly_day=time.strftime('%Y-%m-%d')))
    for native in (SID, NATIVE_BEFORE):
        native_row(native, 'user', 'CURRENT_CHAT_2681', 'initial-user-2681')
        native_row(native, 'assistant', 'DESKTOP_CHAT_READY_2681', 'initial-reply-2681')
    with reader['message_store_db'](TMUX) as db, db:
        db.execute('INSERT OR IGNORE INTO message_chats(provider,sid) VALUES (?,?)', ('claude', SID))
        for index in range(40):
            token = f'old-message-2681-{index:04d}'
            db.execute("INSERT INTO shared_messages VALUES (?,?,?,?,?,?,'accepted',NULL,?,0,?,'human')",
                (token, 'claude', SID, f'OLD_INCOMING_2681_{index:04d}', index + 1,
                 uuid.uuid4().hex, 'historical-acceptance', time.time() - 3600 + index))
    subprocess.run(['tmux', 'set-option', '-t', TMUX, '@mp_host_sid', SID], check=True)
    RECEIPTS.write_text('')
    print('Claude Code\nHOST_RESTART_READY_2681\nReady', flush=True)
    os.execvp('sleep', ['sleep', 'infinity'])


def restart():
    shutil.copyfile(HOME / '.multiprompt-runtime/reader.py', READER)
    reader = runtime()
    paths = reader['host_paths']('claude', SID)
    owner = json.loads(paths['owner'].read_text())
    old = owner['pid']
    assert old > 1 and owner['tmux'] == TMUX
    os.kill(old, signal.SIGKILL)
    for _ in range(100):
        if not reader['host_alive']('claude', SID):
            break
        time.sleep(.1)
    else:
        raise AssertionError('Fixture host did not stop')
    state = reader['host_read'](paths['state'])
    session = dict(state=state)
    reader['host_fresh_archive']('claude', SID, TMUX, session)
    for index in range(700):
        reader['host_history_append']('claude', SID, session,
            dict(role='tool', id=f'archive-tool-{index}', text='Archived tool step', detail='x' * 1024))
    state['native_session'] = NATIVE_AFTER
    reader['host_write'](paths['state'], state)
    assert cli('start', 'claude', SID)['status'] in ('started', 'already')
    for _ in range(100):
        owner = reader['host_read'](paths['owner']) or {}
        if owner.get('pid') != old and reader['host_alive']('claude', SID):
            break
        time.sleep(.1)
    else:
        raise AssertionError('Fixture replacement host did not start')
    disconnected = []
    for line in (HOME / 'host-restart-history-pids.jsonl').read_text().splitlines():
        pid = json.loads(line)['pid']
        process = Path('/proc') / str(pid)
        try:
            if process.stat().st_uid != os.getuid() or b'--serve' not in (process / 'cmdline').read_bytes():
                continue
            os.kill(pid, signal.SIGTERM)
            disconnected.append(pid)
        except ProcessLookupError:
            pass
        except FileNotFoundError:
            pass
    assert disconnected, 'No phone history channel was open at the restart'
    print(json.dumps(dict(old_pid=old, new_pid=owner['pid'], history_pids=disconnected, sid=SID,
        native_before=NATIVE_BEFORE, native_after=NATIVE_AFTER, tmux=TMUX)), flush=True)


def evidence():
    reader = runtime()
    history = reader['host_paths']('claude', SID)['history']
    all_rows = [json.loads(line)['row'] for line in history.read_text().splitlines()]
    rows = [row for row in all_rows if row['role'] in ('user', 'assistant')]
    receipts = [json.loads(line) for line in RECEIPTS.read_text().splitlines()]
    paths = reader['host_paths']('claude', SID)
    state = reader['host_read'](paths['state'])
    log = paths['log'].read_text()[-20000:]
    errors = paths['errors'].read_text() if paths['errors'].exists() else ''
    with sqlite3.connect(reader['message_store_path']().as_uri() + '?mode=ro', uri=True) as db:
        messages = [dict(zip(('id', 'text', 'state', 'deadline', 'receipt'), row)) for row in
            db.execute('SELECT id,text,state,deadline,receipt FROM shared_messages WHERE sid=? ORDER BY position', (SID,))]
    print(json.dumps(dict(sid=SID, tmux=TMUX, history=rows, history_bytes=history.stat().st_size,
        archived_tools=sum(row['role'] == 'tool' for row in all_rows),
        old_accepted=sum(row['state'] == 'accepted' and row['id'].startswith('old-message-2681-') for row in messages),
        receipts=receipts, messages=messages[-3:],
        host_state=state, host_log=log, host_stderr=errors), indent=2))


def emit(event):
    print(json.dumps(event), flush=True)


def claude():
    assert Path.cwd() == CWD
    option = '--resume' if '--resume' in sys.argv else '--session-id'
    native = sys.argv[sys.argv.index(option) + 1]
    assert native in (NATIVE_BEFORE, NATIVE_AFTER) and native != SID
    emit(dict(type='system', subtype='init', session_id=native, model='sonnet'))
    for line in sys.stdin:
        request = json.loads(line)
        if request.get('type') == 'control_request':
            emit(dict(type='control_response', response=dict(subtype='success', request_id=request['request_id'], response={})))
        elif request.get('type') == 'user':
            content = request['message']['content']
            text = content if isinstance(content, str) else ''.join(block.get('text', '') for block in content)
            reply = 'REPLY_' + text
            with RECEIPTS.open('a') as output:
                output.write(json.dumps(dict(text=text, reply=reply, pid=os.getpid(), sid=SID, native_session=native)) + '\n')
            native_row(native, 'user', text, request['uuid'])
            answer_id = 'msg_' + uuid.uuid4().hex
            native_row(native, 'assistant', reply, answer_id)
            emit(dict(type='assistant', uuid=str(uuid.uuid4()), session_id=native,
                message=dict(id=answer_id, role='assistant', content=[dict(type='text', text=reply)])))
            emit(dict(type='result', subtype='success', stop_reason='end_turn', session_id=native, usage={}))


if __name__ == '__main__':
    boundary()
    if sys.argv[1:] == ['console']:
        console()
    elif sys.argv[1:] == ['restart']:
        restart()
    elif sys.argv[1:] == ['evidence']:
        evidence()
    else:
        claude()
