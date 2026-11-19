#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys

STAMP = '2026-11-21T09:00:00.000Z'
HOST_ROOT = '.local/state/multiprompt/agent-host'
SESSION = 'live-real-2781'
PROJECT = 'real-chat-work'
CHAT = 'a2781000-0000-4000-8000-000000002781'
FIRST = 'a2781001-0000-4000-8000-000000002781'
SECOND = 'a2781002-0000-4000-8000-000000002781'
LIVE = 'a2781003-0000-4000-8000-000000002781'
NEIGHBOUR = 'a2781004-0000-4000-8000-000000002781'
EXCHANGES = 400
USER_NEWEST = 'REAL_CHAT_NEWEST_ROW_2781'
REPLY_NEWEST = 'REAL_CHAT_LATEST_REPLY_2781'
USER_MIDDLE = 'REAL_CHAT_MIDDLE_ROW_2781'
REPLY_MIDDLE = 'REAL_CHAT_MIDDLE_REPLY_2781'
USER_AFTER = 'REAL_CHAT_AFTER_ROW_2781'
STALE_FIRST = 'REAL_CHAT_STALE_FIRST_2781'
STALE_SECOND = 'REAL_CHAT_STALE_SECOND_2781'
NEIGHBOUR_TEXT = 'REAL_CHAT_NEIGHBOUR_2781'
HANDOVER_TEXT = 'REAL_CHAT_HANDOVER_2781'
MIN_BYTES = 2_600_000
MAX_BYTES = 4_400_000
TAIL_BYTES = 512 * 1024
TOOL_TAIL_ROWS = 150
MIN_ROWS = 3_900
MAX_ROWS = 4_300
MIN_TOOL_SHARE = 0.64
MAX_TOOL_SHARE = 0.76
CACHE_ROWS = 12


def host_key(sid):
    return hashlib.sha256(('claude:' + sid).encode()).hexdigest()[:32]


def paths(home):
    home = Path(home)
    cwd = home / PROJECT
    folder = home / '.claude/projects' / str(cwd).replace('/', '-')
    host = home / HOST_ROOT
    key = host_key(CHAT)
    return dict(cwd=cwd, folder=folder, live=folder / (LIVE + '.jsonl'),
                first=folder / (FIRST + '.jsonl'), second=folder / (SECOND + '.jsonl'),
                neighbour=folder / (NEIGHBOUR + '.jsonl'),
                config=host / (key + '.config.json'), state=host / (key + '.state.json'),
                log=host / (key + '.events.log'), host=host, key=key)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, separators=(',', ':'), sort_keys=True))


def read_json(path):
    return json.loads(Path(path).read_text())


def tool_use(index, slot):
    identity = f'{LIVE[:8]}-t{index:04d}{slot}'
    return dict(type='assistant', uuid=identity.replace('-', '-4000-8000-')[:36], sessionId=LIVE,
                timestamp=STAMP, message=dict(role='assistant', id='msg_' + identity,
                    content=[dict(type='tool_use', id='toolu_' + identity, name='Bash',
                        input=dict(command='printf %s\\n synthetic-fixture-output-' + ('segment ' * 60),
                                   description=f'Synthetic fixture tool call {index} slot {slot}'))]))


def tool_result(index, slot):
    identity = f'{LIVE[:8]}-r{index:04d}{slot}'
    body = 'Synthetic tool output line.\n' * 32
    return dict(type='user', uuid=identity.replace('-', '-4000-8000-')[:36], sessionId=LIVE,
                timestamp=STAMP, message=dict(role='user', id='msg_' + identity,
                    content=[dict(type='tool_result', tool_use_id='toolu_' + f'{LIVE[:8]}-t{index:04d}{slot}',
                                  content=body)]))


def tail_tool_result(index):
    identity = f'{LIVE[:8]}-x{index:04d}'
    body = 'Synthetic tail output line.\n' * 140
    return dict(type='user', uuid=identity.replace('-', '-4000-8000-')[:36], sessionId=LIVE,
                timestamp=STAMP, message=dict(role='user', id='msg_' + identity,
                    content=[dict(type='tool_result', tool_use_id='toolu_' + identity, content=body)]))


def text_row(role, identity, text):
    return dict(type=role, uuid=identity[:36], sessionId=LIVE, timestamp=STAMP,
                message=dict(role=role, id='msg_' + identity.replace('-', ''), content=[dict(type='text', text=text)]))


def user_row(identity, text):
    return text_row('user', identity, text)


def assistant_row(identity, text):
    return text_row('assistant', identity, text)


def transcript(rows):
    return ''.join(json.dumps(row) + '\n' for row in rows)


def live_rows():
    rows = []
    for index in range(EXCHANGES):
        identity = f'{LIVE[:8]}-{index:04d}'
        if index == 200:
            rows.append(user_row(identity + '-a', USER_MIDDLE))
            rows.append(assistant_row(identity + '-b', REPLY_MIDDLE))
            rows.append(user_row(identity + '-c', USER_AFTER))
        elif index == EXCHANGES - 1:
            rows.append(user_row(identity + '-a', USER_NEWEST))
            rows.append(assistant_row(identity + '-f', REPLY_NEWEST))
        else:
            rows.append(user_row(identity + '-a', f'Synthetic prompt {index} for the phone fixture.'))
            rows.append(assistant_row(identity + '-b', f'Synthetic reply {index} starts the fixture turn.'))
            rows.append(tool_use(index, 'a'))
            rows.append(tool_result(index, 'a'))
            rows.append(tool_use(index, 'b'))
            rows.append(tool_result(index, 'b'))
            rows.append(tool_use(index, 'c'))
            rows.append(tool_result(index, 'c'))
            rows.append(tool_use(index, 'd'))
            rows.append(assistant_row(identity + '-f', f'Synthetic summary {index} closes the fixture turn.'))
    rows.extend(tail_tool_result(index) for index in range(TOOL_TAIL_ROWS))
    return rows


def stale_rows(sid, marker, count):
    rows = []
    for index in range(count):
        identity = f'{sid[:8]}-{index:04d}'
        rows.append(text_row('user', identity + '-a', f'Synthetic earlier prompt {index} of the restarted chat.'))
        rows.append(text_row('assistant', identity + '-b', marker if index == count - 1 else f'Synthetic earlier reply {index}.'))
    return rows


def events():
    return [
        dict(at=1.0, event='init', detail=dict(session=FIRST)),
        dict(at=2.0, event='accepted', detail=dict(id='real-queue-1', attempt='attempt-1')),
        dict(at=3.0, event='delivered', detail=dict(id='real-queue-1', token='token-1')),
        dict(at=4.0, event='handover', detail=dict(previous='stopped')),
        dict(at=5.0, event='init', detail=dict(session=SECOND)),
        dict(at=6.0, event='accepted', detail=dict(id='real-queue-2', attempt='attempt-2')),
        dict(at=7.0, event='delivered', detail=dict(id='real-queue-2', token='token-2')),
        dict(at=8.0, event='init', detail=dict(session=LIVE)),
        dict(at=9.0, event='accepted', detail=dict(id='real-queue-3', attempt='attempt-3')),
        dict(at=10.0, event='delivered', detail=dict(id='real-queue-3', token='token-3')),
    ]


def row_offsets(path):
    found = []
    offset = 0
    for line in Path(path).read_bytes().splitlines(keepends=True):
        found.append((offset, offset + len(line.rstrip(b'\n'))))
        offset += len(line)
    return found


def plan():
    return dict(session=SESSION, chat=CHAT, project=PROJECT, cwd=PROJECT, live=LIVE,
                sessions=[FIRST, SECOND, LIVE], neighbour=NEIGHBOUR, exchanges=EXCHANGES,
                markers=dict(user_newest=USER_NEWEST, reply_newest=REPLY_NEWEST, user_middle=USER_MIDDLE,
                             reply_middle=REPLY_MIDDLE, user_after=USER_AFTER, stale_first=STALE_FIRST,
                             stale_second=STALE_SECOND, neighbour=NEIGHBOUR_TEXT, handover=HANDOVER_TEXT),
                cache=dict(sid=FIRST, rows=CACHE_ROWS))


def create(home):
    home = Path(home)
    where = paths(home)
    where['cwd'].mkdir(parents=True, exist_ok=True)
    where['folder'].mkdir(parents=True, exist_ok=True)
    where['host'].mkdir(parents=True, exist_ok=True)
    live = live_rows()
    where['live'].write_text(transcript(live))
    where['first'].write_text(transcript(stale_rows(FIRST, STALE_FIRST, 9)))
    where['second'].write_text(transcript(stale_rows(SECOND, STALE_SECOND, 11)))
    where['neighbour'].write_text(transcript(stale_rows(NEIGHBOUR, NEIGHBOUR_TEXT, 3)))
    write_json(where['config'], dict(enabled=True, provider='claude', sid=CHAT, tmux=SESSION,
                                     cwd=str(where['cwd']), native_session=LIVE, permission_mode='bypassPermissions'))
    write_json(where['state'], dict(sid=CHAT, provider='claude', cwd=str(where['cwd']),
                                    native_session=LIVE, nightly_day='2026-11-21'))
    where['log'].write_text(''.join(json.dumps(row) + '\n' for row in events()))
    handovers = []
    for index, text in enumerate((HANDOVER_TEXT + ' first restart', HANDOVER_TEXT + ' second restart')):
        path = where['host'] / (where['key'] + '.handover-' + f'{index + 1:032x}' + '.json')
        write_json(path, dict(text=text, provider='claude', sid=CHAT, at=float(index + 1)))
        handovers.append(str(path))
    manifest = plan()
    manifest.update(cwd=str(where['cwd']), transcript=str(where['live']), first_transcript=str(where['first']),
                    second_transcript=str(where['second']), neighbour_transcript=str(where['neighbour']),
                    config=str(where['config']), state=str(where['state']), log=str(where['log']),
                    handovers=handovers, init_sessions=[row['detail']['session'] for row in events() if row['event'] == 'init'],
                    accepted=[row['detail']['id'] for row in events() if row['event'] == 'accepted'],
                    delivered=[row['detail']['id'] for row in events() if row['event'] == 'delivered'])
    write_json(home / 'chat-real.json', manifest)
    return manifest


def counts(path):
    raw = Path(path).read_bytes()
    rows = [json.loads(line) for line in raw.splitlines()]
    tool = sum(1 for row in rows if any(part.get('type') in ('tool_use', 'tool_result')
                                        for part in row['message']['content']))
    return dict(bytes=len(raw), rows=len(rows), tool_rows=tool)


def last_text(path):
    found = None
    for line in Path(path).read_text().splitlines():
        if not line.strip():
            continue
        event = json.loads(line)
        if event['message']['role'] in ('user', 'assistant'):
            text = ''.join(part.get('text', '') for part in event['message']['content'])
            found = text or found
    return found


def tail_visible(path):
    raw = Path(path).read_bytes()
    window = raw[max(0, len(raw) - TAIL_BYTES):]
    found = []
    for line in window.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if event.get('message', {}).get('role') not in ('user', 'assistant'):
            continue
        if any(part.get('type') == 'text' for part in event['message'].get('content') or []):
            found.append(event)
    return found


def latest_init(path):
    found = None
    for line in Path(path).read_text().splitlines():
        event = json.loads(line)
        if event.get('event') == 'init' and isinstance(event.get('detail'), dict):
            found = event['detail'].get('session') or found
    return found


def check(home):
    home = Path(home)
    manifest = read_json(home / 'chat-real.json')
    expected = plan()
    assert (manifest['session'], manifest['chat'], manifest['live']) == (expected['session'], expected['chat'], expected['live']), 'REAL_CHAT_IDENTITY'
    measured = counts(manifest['transcript'])
    assert MIN_BYTES <= measured['bytes'] <= MAX_BYTES, 'REAL_CHAT_BYTES'
    assert MIN_ROWS <= measured['rows'] <= MAX_ROWS, 'REAL_CHAT_ROWS'
    assert MIN_TOOL_SHARE <= measured['tool_rows'] / measured['rows'] <= MAX_TOOL_SHARE, 'REAL_CHAT_TOOL_SHARE'
    assert last_text(manifest['transcript']) == REPLY_NEWEST, 'REAL_CHAT_LATEST'
    assert tail_visible(manifest['transcript']) == [], 'REAL_CHAT_TAIL_VISIBLE'
    assert last_text(manifest['first_transcript']) == STALE_FIRST, 'REAL_CHAT_STALE_FIRST'
    assert last_text(manifest['second_transcript']) == STALE_SECOND, 'REAL_CHAT_STALE_SECOND'
    assert last_text(manifest['neighbour_transcript']) == NEIGHBOUR_TEXT, 'REAL_CHAT_NEIGHBOUR'
    config = read_json(manifest['config'])
    assert config.get('enabled') is True and config.get('sid') == CHAT and config.get('tmux') == SESSION, 'REAL_CHAT_CONFIG'
    assert config.get('native_session') == LIVE and config.get('cwd') == manifest['cwd'], 'REAL_CHAT_CONFIG'
    assert Path(manifest['cwd']).is_dir(), 'REAL_CHAT_CWD'
    assert latest_init(manifest['log']) == LIVE, 'REAL_CHAT_INIT'
    assert manifest['init_sessions'] == [FIRST, SECOND, LIVE], 'REAL_CHAT_RESTARTS'
    assert manifest['accepted'] == manifest['delivered'] == ['real-queue-1', 'real-queue-2', 'real-queue-3'], 'REAL_CHAT_DISPATCH'
    assert len(manifest['handovers']) == 2, 'REAL_CHAT_HANDOVER'
    for path in manifest['handovers']:
        assert HANDOVER_TEXT in read_json(path)['text'], 'REAL_CHAT_HANDOVER'
    for text in screen_texts(manifest['transcript']):
        assert 'Synthetic' in text or '_2781' in text, 'REAL_CHAT_SYNTHETIC_ONLY'
    return manifest


def screen_texts(path):
    found = []
    for line in Path(path).read_text().splitlines():
        row = json.loads(line)
        for part in row['message']['content']:
            if part.get('type') == 'text':
                found.append(part['text'])
    return found


def cache_key(host_id, hostname, port, username, agent, tmux, cwd):
    return '\u0000'.join([host_id, hostname, str(port), username, agent, tmux, cwd])


def cache(home, host_id, hostname, port, username, agent, tmux, cwd):
    home = Path(home)
    manifest = check(home)
    older = Path(manifest['first_transcript'])
    offsets = row_offsets(older)
    rows = []
    for index in range(max(0, len(offsets) - CACHE_ROWS), len(offsets)):
        row = json.loads(older.read_bytes().splitlines()[index])
        row['offset'], row['end'] = offsets[index]
        if row['message']['role'] == 'user':
            row = dict(role='user', text=''.join(part.get('text', '') for part in row['message']['content']),
                       id=row['uuid'], timestamp=row['timestamp'], offset=row['offset'], end=row['end'], queued=False)
        else:
            row = dict(role='assistant', text=''.join(part.get('text', '') for part in row['message']['content']),
                       id=row['uuid'], timestamp=row['timestamp'], offset=row['offset'], end=row['end'], queued=False)
        rows.append(row)
    header = dict(version=6, sid=manifest['cache']['sid'], inode=older.stat().st_ino,
                  offset=rows[-1]['end'], start=rows[0]['offset'], mtime=int(older.stat().st_mtime),
                  probe=hashlib.sha256(older.read_bytes()).hexdigest(), more=True)
    digest = hashlib.sha256(cache_key(host_id, hostname, port, username, agent, tmux, cwd).encode()).hexdigest()
    name = digest + '-' + hashlib.sha256(manifest['cache']['sid'].encode()).hexdigest() + '.jsonl'
    return dict(name=name, lines=[json.dumps(header)] + [json.dumps(row) for row in rows],
                sid=manifest['cache']['sid'], rows=len(rows), offset=header['offset'])


COMMANDS = {'plan': plan, 'seed': create, 'check': check}


def main(argv):
    parser = argparse.ArgumentParser(description='Real-size synthetic Claude chat for the phone tests.')
    parser.add_argument('command', choices=sorted(COMMANDS) + ['cache'])
    parser.add_argument('--home', default=None)
    parser.add_argument('--host-id')
    parser.add_argument('--hostname')
    parser.add_argument('--port', default='22')
    parser.add_argument('--username')
    parser.add_argument('--agent', default='CLAUDE')
    parser.add_argument('--tmux', default=SESSION)
    parser.add_argument('--cwd')
    parser.add_argument('--out')
    arguments = parser.parse_args(argv[1:])
    home = Path(arguments.home or os.environ.get('HOME') or Path.home())
    if arguments.command == 'cache':
        payload = cache(home, arguments.host_id, arguments.hostname, arguments.port, arguments.username,
                        arguments.agent, arguments.tmux, arguments.cwd)
        if arguments.out:
            Path(arguments.out).write_text('\n'.join(payload['lines']) + '\n')
    else:
        payload = plan() if arguments.command == 'plan' else COMMANDS[arguments.command](home)
    print(json.dumps(payload, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
