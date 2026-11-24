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
SID_WORKING = 'c001c001-2681-4000-8000-000000002682'
NATIVE_WORKING = 'c001c001-2681-4000-8000-000000000003'
TMUX_WORKING = 'live-working-mubo2'
CWD = HOME / 'host-restart-work'
READER = HOME / 'host-restart-reader.py'
RECEIPTS = HOME / 'host-restart-receipts.jsonl'
PHONE_BEFORE = HOME / 'phone-restart-before.json'
BATCH_BEFORE = HOME / 'phone-restart-batch-before.json'
RELEASE_WORKING = HOME / 'mubo2-release-working'
HOLD_WORKING_TEXT = 'HOLD_WORKING_MUBO2'


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
    native_row(SID, 'user', 'FOREIGN_QUESTION_2681', 'foreign-user-2681')
    native_row(SID, 'assistant', 'FOREIGN_OLD_SESSION_2681', 'foreign-reply-2681')
    native_row(NATIVE_BEFORE, 'user', 'CURRENT_CHAT_2681', 'initial-user-2681')
    native_row(NATIVE_BEFORE, 'assistant', 'DESKTOP_CHAT_READY_2681', 'initial-reply-2681')
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
    print(json.dumps(dict(old_pid=old, new_pid=owner['pid'], sid=SID,
        native_before=NATIVE_BEFORE, native_after=NATIVE_AFTER, tmux=TMUX)), flush=True)


def finish_stream():
    reader = runtime()
    with sqlite3.connect(reader['message_store_path']().as_uri() + '?mode=ro', uri=True) as db:
        for _ in range(100):
            row = db.execute('SELECT created FROM shared_messages WHERE sid=? AND text=?', (SID, 'PHONE_AFTER_2681')).fetchone()
            if row:
                break
            time.sleep(.1)
        else:
            raise AssertionError('Follow-up was not admitted while streaming')
    (HOME / 'host-restart-release-stream').touch()
    for _ in range(100):
        completed = HOME / 'host-restart-stream-completed'
        if completed.exists():
            done = float(completed.read_text())
            assert row[0] * 1000 < done
            print(json.dumps(dict(submitted=row[0] * 1000, completed=done, followup_submitted_while_streaming=True)))
            return
        time.sleep(.1)
    raise AssertionError('Stream did not complete')


def metadata(publish=False):
    reader = runtime()
    paths = reader['host_paths']('claude', SID)
    for _ in range(100):
        receipts = [json.loads(line) for line in RECEIPTS.read_text().splitlines()]
        desktop = next((row for row in receipts if row['text'] == 'DESKTOP_AFTER_2681'), None)
        state = reader['host_read'](paths['state'])
        if desktop and ((HOME / 'host-restart-stream-enabled').exists() or
                state.get('result', {}).get('at', 0) >= desktop['at']):
            break
        time.sleep(.1)
    else:
        raise AssertionError('Replacement provider did not receive the desktop message')
    events = [json.loads(line) for line in paths['log'].read_text().splitlines()]
    latest_init = next(row['detail']['session'] for row in reversed(events) if row['event'] == 'init')
    assert latest_init == NATIVE_AFTER, events
    state = reader['host_read'](paths['state'])
    state.pop('native_session', None)
    reader['host_write'](paths['state'], state)
    config = reader['host_read'](paths['config'])
    config.pop('native_session', None)
    if publish:
        config['native_session'] = NATIVE_AFTER
    reader['host_write'](paths['config'], config)
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
    resolved = reader['resolve_response_path'](dict(id=7, provider='claude', codex=False, sid=SID,
        tmux=TMUX, cwd=str(CWD), follow=True))
    active = json.loads((HOME / '.multiprompt-runtime/active.json').read_text())
    print(json.dumps(dict(sid=config.get('sid', SID), native_session=config.get('native_session'),
        latest_init=latest_init, private_session_hint=reader['host_read'](paths['state']).get('native_session'),
        history_pids=disconnected, active_runtime=active, resolver_has_fix='hosted_claude_transcript_sid' in reader,
        resolved_transcript=str(resolved[2]))))


def evidence():
    reader = runtime()
    paths = reader['host_paths']('claude', SID)
    rows = []
    for native in (NATIVE_BEFORE, NATIVE_AFTER):
        path = HOME / '.claude/projects' / str(CWD).replace('/', '-') / (native + '.jsonl')
        if path.exists():
            for line in path.read_text().splitlines():
                event = json.loads(line)
                rows.append(dict(role=event['type'], text=''.join(block.get('text', '') for block in event['message']['content']),
                    id=event['uuid'], timestamp=event['timestamp'], native_session=native))
    receipts = [json.loads(line) for line in RECEIPTS.read_text().splitlines()]
    state = reader['host_read'](paths['state'])
    config = reader['host_read'](paths['config'])
    print(json.dumps(dict(sid=SID, tmux=TMUX, history=rows, receipts=receipts,
        config=config, host_state=state,
        active_runtime=json.loads((HOME / '.multiprompt-runtime/active.json').read_text()),
        resolver_has_fix='hosted_claude_transcript_sid' in reader,
        foreign_transcript_exists=(HOME / '.claude/projects' / str(CWD).replace('/', '-') / (SID + '.jsonl')).exists(),
        host_log=paths['log'].read_text()[-12000:],
        host_stderr=paths['errors'].read_text() if paths['errors'].exists() else ''), indent=2))


def host_snapshot(reader, sid):
    paths = reader['host_paths']('claude', sid)
    state = reader['host_read'](paths['state']) or {}
    events = [json.loads(line) for line in paths['log'].read_text().splitlines()] if paths['log'].exists() else []
    return dict(
        sid=sid,
        started=state.get('started'),
        agent_pid=state.get('agent_pid'),
        inflight=bool(state.get('inflight')),
        alive=reader['host_alive']('claude', sid),
        rotate_count=sum(e.get('event') == 'rotate' for e in events),
        restart_now_count=sum(e.get('event') == 'restart_now' for e in events),
        restart_waiting_count=sum(e.get('event') == 'restart_waiting' for e in events),
        restart_file=paths['restart'].exists(),
    )


def console_working():
    CWD.mkdir(exist_ok=True)
    os.chdir(CWD)
    assert cli('enable', 'claude', SID_WORKING, '--tmux', TMUX_WORKING, '--cwd', str(CWD))['status'] == 'enabled'
    reader = runtime()
    reader['host_write'](reader['host_paths']('claude', SID_WORKING)['state'], dict(
        native_session=NATIVE_WORKING, nightly_day=time.strftime('%Y-%m-%d')))
    native_row(NATIVE_WORKING, 'user', 'WORKING_CHAT_MUBO2', 'working-user-mubo2')
    native_row(NATIVE_WORKING, 'assistant', 'WORKING_CHAT_READY_MUBO2', 'working-reply-mubo2')
    subprocess.run(['tmux', 'set-option', '-t', TMUX_WORKING, '@mp_host_sid', SID_WORKING], check=True)
    print('Claude Code\nMUBO2_WORKING_READY\nReady', flush=True)
    os.execvp('sleep', ['sleep', 'infinity'])


def host_diagnostics(reader, sid):
    paths = reader['host_paths']('claude', sid)
    log = paths['log'].read_text() if paths['log'].exists() else ''
    errors = paths['errors'].read_text() if paths['errors'].exists() else ''
    published = reader['host_published_runtime']()
    return dict(
        host_log=log.splitlines()[-60:],
        host_stderr=errors[-8000:],
        state=reader['host_read'](paths['state']),
        owner=reader['host_read'](paths['owner']),
        published_runtime=published,
        published_runtime_exists=bool(published) and Path(published).exists(),
    )


def phone_restart_before():
    published = HOME / '.multiprompt-runtime/reader.py'
    published.parent.mkdir(parents=True, exist_ok=True)
    if not published.is_file():
        shutil.copyfile(READER, published)
    reader = runtime()
    snap = host_snapshot(reader, SID)
    assert snap['alive'] and snap['started'] is not None, snap
    PHONE_BEFORE.write_text(json.dumps(snap, indent=2) + '\n')
    print(json.dumps(snap, indent=2), flush=True)


def wait_phone_restart():
    before = json.loads(PHONE_BEFORE.read_text())
    reader = runtime()
    deadline = time.time() + 40
    snap = None
    while time.time() < deadline:
        snap = host_snapshot(reader, SID)
        if (snap['started'] != before['started'] and snap['rotate_count'] > before['rotate_count']
                and snap['alive'] and not snap['restart_file']):
            break
        time.sleep(0.2)
    else:
        failure = dict(before=before, after=snap, rotated=False, diagnostics=host_diagnostics(reader, SID))
        print(json.dumps(failure, indent=2), flush=True)
        raise AssertionError(f'Phone chat restart did not rotate host: before={before} after={snap}')
    result = dict(before=before, after=snap, rotated=True)
    print(json.dumps(result, indent=2), flush=True)


def setup_batch():
    RELEASE_WORKING.unlink(missing_ok=True)
    subprocess.run(
        ['tmux', 'new-session', '-d', '-s', TMUX_WORKING, '-x', '100', '-y', '32',
         'exec env GITHUB_ACTIONS=true HOME=/home/verifyssh python3 -u /home/verifyssh/host-restart-fixture.py console-working 2>/home/verifyssh/host-restart-working-startup.txt'],
        check=True,
    )
    deadline = time.time() + 30
    while time.time() < deadline:
        pane = subprocess.run(['tmux', 'capture-pane', '-pt', TMUX_WORKING], capture_output=True, text=True)
        cmd = subprocess.run(['tmux', 'display-message', '-p', '-t', TMUX_WORKING, '#{pane_current_command}'], capture_output=True, text=True)
        if pane.returncode == 0 and 'MUBO2_WORKING_READY' in pane.stdout and cmd.stdout.strip() == 'sleep':
            break
        time.sleep(0.2)
    else:
        raise AssertionError('Working fixture session did not start')
    started = subprocess.check_output([sys.executable, str(READER), 'host', 'start', 'claude', SID_WORKING], text=True)
    assert json.loads(started).get('status') in ('started', 'already'), started
    sent = subprocess.check_output([sys.executable, str(READER), 'host', 'send', 'claude', SID_WORKING, HOLD_WORKING_TEXT], text=True)
    assert json.loads(sent).get('status') in ('sent', 'queued'), sent
    reader = runtime()
    deadline = time.time() + 30
    working_snap = None
    idle_snap = None
    while time.time() < deadline:
        working_snap = host_snapshot(reader, SID_WORKING)
        idle_snap = host_snapshot(reader, SID)
        if working_snap['alive'] and working_snap['inflight'] and idle_snap['alive'] and not idle_snap['inflight']:
            break
        time.sleep(0.2)
    else:
        raise AssertionError(f'Batch setup did not reach idle+working state: idle={idle_snap} working={working_snap}')
    payload = dict(idle=idle_snap, working=working_snap)
    BATCH_BEFORE.write_text(json.dumps(payload, indent=2) + '\n')
    print(json.dumps(payload, indent=2), flush=True)


def wait_batch_restart():
    before = json.loads(BATCH_BEFORE.read_text())
    reader = runtime()
    deadline = time.time() + 45
    idle_after = None
    while time.time() < deadline:
        idle_after = host_snapshot(reader, SID)
        if (idle_after['started'] != before['idle']['started']
                and idle_after['rotate_count'] > before['idle']['rotate_count']
                and idle_after['alive'] and not idle_after['restart_file']):
            break
        time.sleep(0.2)
    else:
        RELEASE_WORKING.touch()
        failure = dict(before=before, after=idle_after, changed=False, diagnostics=host_diagnostics(reader, SID))
        print(json.dumps(failure, indent=2), flush=True)
        raise AssertionError(f'Inbox batch restart did not rotate idle chat: before={before} after={idle_after}')
    time.sleep(1.0)
    working_after = host_snapshot(reader, SID_WORKING)
    RELEASE_WORKING.touch()
    subprocess.run([sys.executable, str(READER), 'host', 'stop', 'claude', SID_WORKING], capture_output=True, text=True)
    subprocess.run(['tmux', 'kill-session', '-t', TMUX_WORKING], capture_output=True, text=True)
    assert working_after['started'] == before['working']['started'], (before['working'], working_after)
    assert working_after['agent_pid'] == before['working']['agent_pid'], (before['working'], working_after)
    assert working_after['rotate_count'] == before['working']['rotate_count'] == 0, working_after
    assert working_after['restart_now_count'] == 0, working_after
    assert working_after['restart_waiting_count'] == 0, working_after
    assert working_after['restart_file'] is False, working_after
    assert working_after['inflight'] is True, working_after
    result = dict(
        idle_before=before['idle'],
        idle_after=idle_after,
        working_before=before['working'],
        working_after=working_after,
        idle_rotated=True,
        working_untouched=True,
    )
    print(json.dumps(result, indent=2), flush=True)


def emit(event):
    print(json.dumps(event), flush=True)


def claude():
    assert Path.cwd() == CWD
    option = '--resume' if '--resume' in sys.argv else '--session-id'
    native = sys.argv[sys.argv.index(option) + 1]
    assert native in (NATIVE_BEFORE, NATIVE_AFTER, NATIVE_WORKING) and native not in (SID, SID_WORKING)
    emit(dict(type='system', subtype='init', session_id=native, model='sonnet'))
    for line in sys.stdin:
        request = json.loads(line)
        if request.get('type') == 'control_request':
            emit(dict(type='control_response', response=dict(subtype='success', request_id=request['request_id'], response={})))
        elif request.get('type') == 'user':
            content = request['message']['content']
            text = content if isinstance(content, str) else ''.join(block.get('text', '') for block in content)
            reply = 'REPLY_' + text
            answer_id = 'msg_' + uuid.uuid4().hex
            if text == HOLD_WORKING_TEXT:
                emit(dict(type='stream_event', event=dict(type='message_start', message=dict(id=answer_id))))
                for _ in range(1200):
                    if RELEASE_WORKING.exists():
                        break
                    time.sleep(.1)
                native_row(native, 'user', text, request['uuid'])
                native_row(native, 'assistant', reply, answer_id)
                emit(dict(type='assistant', uuid=str(uuid.uuid4()), session_id=native,
                    message=dict(id=answer_id, role='assistant', content=[dict(type='text', text=reply)])))
                emit(dict(type='result', subtype='success', stop_reason='end_turn', session_id=native, usage={}))
                continue
            with RECEIPTS.open('a') as output:
                output.write(json.dumps(dict(text=text, reply=reply, pid=os.getpid(), sid=SID, native_session=native, at=time.time())) + '\n')
            native_row(native, 'user', text, request['uuid'])
            if text == 'DESKTOP_AFTER_2681' and (HOME / 'host-restart-stream-enabled').exists():
                for event in [dict(type='message_start', message=dict(id=answer_id)),
                        dict(type='content_block_start', index=0, content_block=dict(type='text')),
                        dict(type='content_block_delta', index=0, delta=dict(type='text_delta', text=reply))]:
                    emit(dict(type='stream_event', event=event))
                for _ in range(1800):
                    if (HOME / 'host-restart-release-stream').exists():
                        break
                    time.sleep(.1)
                else:
                    raise AssertionError('Streaming follow-up never arrived')
                (HOME / 'host-restart-stream-completed').write_text(str(time.time() * 1000))
            native_row(native, 'assistant', reply, answer_id)
            emit(dict(type='assistant', uuid=str(uuid.uuid4()), session_id=native,
                message=dict(id=answer_id, role='assistant', content=[dict(type='text', text=reply)])))
            emit(dict(type='result', subtype='success', stop_reason='end_turn', session_id=native, usage={}))


if __name__ == '__main__':
    boundary()
    if sys.argv[1:] == ['console']:
        console()
    elif sys.argv[1:] == ['console-working']:
        console_working()
    elif sys.argv[1:] == ['restart']:
        restart()
    elif sys.argv[1:] == ['finish-stream']:
        finish_stream()
    elif sys.argv[1:] == ['metadata-legacy']:
        metadata()
    elif sys.argv[1:] == ['metadata-config']:
        metadata(publish=True)
    elif sys.argv[1:] == ['evidence']:
        evidence()
    elif sys.argv[1:] == ['phone-restart-before']:
        phone_restart_before()
    elif sys.argv[1:] == ['wait-phone-restart']:
        wait_phone_restart()
    elif sys.argv[1:] == ['setup-batch']:
        setup_batch()
    elif sys.argv[1:] == ['wait-batch-restart']:
        wait_batch_restart()
    else:
        claude()
