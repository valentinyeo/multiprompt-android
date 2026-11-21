#!/usr/bin/env python3
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import uuid

HOME = Path('/home/verifyssh')
SID = 'c0030000-3000-4000-8000-000000003000'
NATIVE_BEFORE = 'c0030000-3000-4000-8000-000000003001'
NATIVE_AFTER = 'c0030000-3000-4000-8000-000000003002'
TMUX = 'live-fresh-start-3000'
CWD = HOME / 'fresh-start-work'
READER = HOME / 'session-fresh-start-reader.py'
DIVIDER = '---\nFresh start\n---'
MARKERS = dict(old='OLD_STALE_3000', handover='HANDOVER_3000', reply='FRESH_REPLY_3000', ready='FRESH_START_READY_3000')
STAMP = '2026-11-20T12:00:00.000Z'


def boundary():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert Path.home() == HOME and os.getuid() != 0


def runtime():
    return runpy.run_path(str(READER), run_name='fresh_start_fixture')


def cli(*args):
    return json.loads(subprocess.check_output([sys.executable, str(READER), 'host', *args], text=True))


def host_key(sid):
    return hashlib.sha256(('claude:' + sid).encode()).hexdigest()[:32]


def project_folder(home):
    return home / '.claude/projects' / str(home / CWD.name).replace('/', '-')


def transcript_row(sid, role, text, identity):
    return dict(type=role, uuid=identity, sessionId=sid, timestamp=STAMP,
                message=dict(role=role, id='msg_' + identity.replace('-', '')[:24],
                             content=[dict(type='text', text=text)]))


def write_transcript(path, sid, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = []
    for index, (role, text) in enumerate(rows):
        identity = f'{sid[:8]}-{index:04d}-4000-8000-000000003000'
        lines.append(json.dumps(transcript_row(sid, role, text, identity)))
    path.write_text('\n'.join(lines) + ('\n' if lines else ''))


def paths(home):
    folder = project_folder(home)
    key = host_key(SID)
    root = home / '.local/state/multiprompt/agent-host'
    return dict(cwd=CWD, folder=folder, root=root,
                config=root / (key + '.config.json'), state=root / (key + '.state.json'),
                log=root / (key + '.events.log'), history=root / (key + '.history.jsonl'),
                before=folder / (NATIVE_BEFORE + '.jsonl'), after=folder / (NATIVE_AFTER + '.jsonl'),
                host_transcript=folder / (SID + '.jsonl'))


def plan():
    return dict(session=TMUX, sid=SID, native_before=NATIVE_BEFORE, native_after=NATIVE_AFTER, markers=MARKERS)


def seed_before(home):
    home = Path(home)
    where = paths(home)
    where['cwd'].mkdir(parents=True, exist_ok=True)
    write_transcript(where['before'], NATIVE_BEFORE,
                     [('user', 'QUESTION_BEFORE_3000'), ('assistant', MARKERS['old'])])
    write_transcript(where['host_transcript'], SID,
                     [('user', 'HOST_GHOST_QUESTION_3000'), ('assistant', 'HOST_GHOST_STALE_3000')])
    write_json(where['config'], dict(enabled=True, provider='claude', sid=SID, tmux=TMUX, cwd=str(where['cwd']),
                                     native_session=NATIVE_BEFORE, permission_mode='bypassPermissions'))
    write_json(where['state'], dict(sid=SID, provider='claude', cwd=str(where['cwd']),
                                    native_session=NATIVE_BEFORE, nightly_day='2026-11-20'))
    events = [dict(at=1.0, event='init', detail=dict(session=NATIVE_BEFORE))]
    where['log'].parent.mkdir(parents=True, exist_ok=True)
    where['log'].write_text(''.join(json.dumps(row) + '\n' for row in events))
    return dict(**plan(), **{key: str(where[key]) for key in ('config', 'state', 'log', 'before', 'host_transcript')})


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, separators=(',', ':'), sort_keys=True))


def read_json(path):
    return json.loads(Path(path).read_text())


def archive_before(home, where):
    rows = []
    for line in where['before'].read_text().splitlines():
        event = json.loads(line)
        content = event['message'].get('content') or []
        text = ''.join(part.get('text', '') for part in content)
        rows.append(dict(role=event['type'], text=text, id=event['uuid'], timestamp=event['timestamp']))
    with where['history'].open('w') as stream:
        for row in rows:
            stream.write(json.dumps(dict(type='multiprompt_history', row=row), separators=(',', ':')) + '\n')


def apply_fresh_start(home):
    home = Path(home)
    reader = runtime()
    where = paths(home)
    archive_before(home, where)
    handover = where['root'] / (host_key(SID) + '.handover-' + uuid.uuid4().hex + '.json')
    write_json(handover, dict(text=MARKERS['handover'], provider='claude', sid=SID, at=datetime.datetime.now().timestamp()))
    state = reader['host_read'](where['state'])
    updated = dict(state, native_session=NATIVE_AFTER, context_session=str(uuid.uuid4()))
    updated.pop('fresh_start', None)
    reader['host_write'](where['state'], updated)
    config = reader['host_read'](where['config'])
    config['native_session'] = NATIVE_AFTER
    reader['host_write'](where['config'], config)
    write_transcript(where['after'], NATIVE_AFTER, [
        ('assistant', DIVIDER),
        ('user', MARKERS['handover']),
        ('assistant', MARKERS['reply']),
    ])
    with where['log'].open('a') as output:
        output.write(json.dumps(dict(at=2.0, event='init', detail=dict(session=NATIVE_AFTER))) + '\n')
    subprocess.run(['tmux', 'set-option', '-t', TMUX, '@mp_host_sid', SID], check=True)
    return dict(applied=True, sid=SID, tmux=TMUX, native_before=NATIVE_BEFORE, native_after=NATIVE_AFTER,
                handover=handover.name, markers=MARKERS)


def visible_transcript(home):
    reader = runtime()
    where = paths(home)
    config = reader['host_read'](where['config']) if where['config'].is_file() else {}
    native = config.get('native_session', NATIVE_BEFORE)
    path = where['folder'] / (native + '.jsonl')
    resolved = reader['resolve_response_path'](dict(id=7, provider='claude', codex=False, sid=SID,
        tmux=TMUX, cwd=str(where['cwd']), follow=True))
    if resolved and resolved[2]:
        path = Path(resolved[2])
    elif not path.is_file() and (where['folder'] / (NATIVE_AFTER + '.jsonl')).is_file():
        path = where['folder'] / (NATIVE_AFTER + '.jsonl')
    texts = []
    for line in Path(path).read_text().splitlines():
        event = json.loads(line)
        if event.get('type') == 'multiprompt_history':
            row = event.get('row') or {}
            texts.append(row.get('text', ''))
        elif event.get('message'):
            content = event['message'].get('content')
            if isinstance(content, list):
                texts.append(''.join(part.get('text', '') for part in content))
            elif isinstance(content, str):
                texts.append(content)
    return dict(path=str(path), visible=texts)


def check(home):
    home = Path(home)
    manifest_path = home / 'session-fresh-start.json'
    if not manifest_path.is_file():
        write_json(manifest_path, seed_before(home))
    manifest = read_json(manifest_path)
    assert manifest['sid'] == SID
    assert Path(manifest['before']).is_file()
    assert MARKERS['old'] in Path(manifest['before']).read_text(), 'BEFORE_STALE_MISSING'
    visible = visible_transcript(home)['visible']
    if MARKERS['reply'] not in visible:
        applied = apply_fresh_start(home)
    else:
        applied = dict(applied=True, cached=True)
    transcript = visible_transcript(home)
    assert MARKERS['old'] not in transcript['visible'], transcript
    assert transcript['visible'][-2:] == [MARKERS['handover'], MARKERS['reply']], transcript
    assert NATIVE_AFTER in Path(transcript['path']).name
    tmux_sid = subprocess.check_output(['tmux', 'show-options', '-t', TMUX, '-v', '@mp_host_sid'], text=True).strip()
    assert tmux_sid == SID, tmux_sid
    folder = project_folder(home)
    sids = sorted(path.stem for path in folder.glob('*.jsonl'))
    assert SID in sids and NATIVE_BEFORE in sids and NATIVE_AFTER in sids, sids
    return dict(manifest=manifest, applied=applied, transcript=transcript)


def cache_lines():
    rows = [
        dict(role='user', text='CACHED_QUESTION_3000', id='cache-user-3000', timestamp=STAMP, offset=0),
        dict(role='assistant', text=MARKERS['old'], id='cache-old-3000', timestamp=STAMP, offset=100),
    ]
    return [json.dumps(row, separators=(',', ':')) for row in rows]


def console():
    boundary()
    CWD.mkdir(exist_ok=True)
    os.chdir(CWD)
    manifest = seed_before(HOME)
    write_json(HOME / 'session-fresh-start.json', manifest)
    assert cli('enable', 'claude', SID, '--tmux', TMUX, '--cwd', str(CWD))['status'] == 'enabled'
    subprocess.run(['tmux', 'set-option', '-t', TMUX, '@mp_host_sid', SID], check=True)
    print('Claude Code\n' + MARKERS['ready'] + '\nReady', flush=True)
    os.execvp('sleep', ['sleep', 'infinity'])


def emit(event):
    print(json.dumps(event), flush=True)


def claude():
    boundary()
    option = next((name for name in ('--session-id', '--resume') if name in sys.argv), None)
    native = sys.argv[sys.argv.index(option) + 1] if option else SID
    emit(dict(type='system', subtype='init', session_id=native, model='sonnet'))
    for line in sys.stdin:
        request = json.loads(line)
        if request.get('type') == 'control_request':
            emit(dict(type='control_response', response=dict(subtype='success', request_id=request['request_id'], response={})))
    return 0


def cache(host, host_id, hostname, port, username):
    name = f'{host_id}-{username}@{hostname}:{port}-{SID}.jsonl'
    payload = dict(name=name, sid=SID, session=TMUX, lines=cache_lines(), stale=MARKERS['old'])
    print(json.dumps(payload, indent=2))
    return payload


COMMANDS = dict(plan=plan, check=check, cache=cache)
FIXTURE_COMMANDS = ('plan', 'seed', 'check', 'console', 'apply-fresh-start', 'evidence', 'cache', 'visible')


def main(argv):
    parser = argparse.ArgumentParser(description='Desktop Fresh start shape for one stable inbox row.')
    parser.add_argument('command', choices=FIXTURE_COMMANDS)
    parser.add_argument('--home', default=None)
    arguments = parser.parse_args(argv[1:])
    home = Path(arguments.home or HOME)
    if arguments.command == 'plan':
        print(json.dumps(plan(), indent=2))
    elif arguments.command == 'seed':
        payload = seed_before(home)
        write_json(home / 'session-fresh-start.json', payload)
        print(json.dumps(payload, indent=2))
    elif arguments.command == 'check':
        if not (home / 'session-fresh-start.json').is_file():
            seed_before(home)
            write_json(home / 'session-fresh-start.json', plan())
        print(json.dumps(check(home), indent=2))
    elif arguments.command == 'console':
        console()
    elif arguments.command == 'apply-fresh-start':
        boundary()
        print(json.dumps(apply_fresh_start(home), indent=2))
    elif arguments.command == 'evidence':
        boundary()
        print(json.dumps(dict(plan(), transcript=visible_transcript(home),
                               tmux_sid=subprocess.check_output(['tmux', 'show-options', '-t', TMUX, '-v', '@mp_host_sid'], text=True).strip()),
                          indent=2))
    elif arguments.command == 'visible':
        print(json.dumps(visible_transcript(home), indent=2))
    else:
        print(json.dumps(cache(home, 'fixture-host', '10.0.2.2', '2222', 'verifyssh'), indent=2))
    return 0


def entry(argv):
    first = argv[1] if len(argv) > 1 else ''
    return main(argv) if first in FIXTURE_COMMANDS else claude()


if __name__ == '__main__':
    sys.exit(entry(sys.argv))