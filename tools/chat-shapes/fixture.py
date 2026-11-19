#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys

STAMP = '2026-11-20T09:00:00.000Z'
HOST_ROOT = '.local/state/multiprompt/agent-host'
LONG_SESSION = 'cl-long-chat'
LONG_SID = '11560000-0000-4000-8000-000000001156'

SHAPES = (
    dict(shape='compacted', session='live-compacted-2715', project='compacted-work',
         sid='c02715c0-0000-4000-8000-000000002715',
         native='c02715c1-0000-4000-8000-000000002715',
         neighbour='c02715c3-0000-4000-8000-000000002715',
         stale='COMPACTED_BEFORE_2715', latest='COMPACTED_AFTER_2715',
         neighbour_text='COMPACTED_NEIGHBOUR_2715'),
    dict(shape='fresh', session='live-fresh-2715', project='fresh-work',
         sid='c02715f0-0000-4000-8000-000000002715',
         native='c02715f0-0000-4000-8000-000000002715',
         neighbour='c02715f2-0000-4000-8000-000000002715',
         stale='', latest='FRESH_READY_2715',
         neighbour_text='FRESH_NEIGHBOUR_2715'),
    dict(shape='resumed', session='live-resumed-2715', project='resumed-work',
         sid='c02715e0-0000-4000-8000-000000002715',
         native='c02715e1-0000-4000-8000-000000002715',
         neighbour='c02715e2-0000-4000-8000-000000002715',
         stale='', latest='RESUMED_READY_2715',
         neighbour_text='RESUMED_NEIGHBOUR_2715'),
    dict(shape='mirror', session='live-mirror-2779', project='mirror-work',
         sid='c0272d90-0000-4000-8000-000000002779',
         native='c0272d91-0000-4000-8000-000000002779',
         neighbour='c0272d92-0000-4000-8000-000000002779',
         stale='', latest='MIRROR_NEWEST_2779',
         neighbour_text='MIRROR_NEIGHBOUR_2779'),
)

MIRROR_TAIL_BYTES = 512 * 1024
MIRROR_TOOL_DETAIL = 4010
MIRROR_EXCHANGES = 130
MIRROR_TOOL_TAIL = 140

TRANSCRIPTS = {
    'mirror': {'native': [('user', 'MIRROR_ASK_2779'), ('assistant', 'MIRROR_NEWEST_2779')]},
    'compacted': {
        'sid': [('user', 'COMPACTED_QUESTION_2715'), ('assistant', 'COMPACTED_BEFORE_2715')],
        'native': [('user', 'COMPACTED_CONTINUE_2715'), ('assistant', 'COMPACTED_AFTER_2715')],
    },
    'fresh': {'sid': [('user', 'FRESH_QUESTION_2715'), ('assistant', 'FRESH_READY_2715')]},
    'resumed': {'native': [('user', 'RESUMED_QUESTION_2715'), ('assistant', 'RESUMED_READY_2715')]},
}


def shape(name):
    for entry in SHAPES:
        if name in (entry['shape'], entry['session'], entry['sid']):
            return entry
    raise KeyError(name)


def host_key(sid):
    return hashlib.sha256(('claude:' + sid).encode()).hexdigest()[:32]


def project_folder(home, entry):
    return home / '.claude/projects' / str(home / entry['project']).replace('/', '-')


def paths(home, entry):
    folder = project_folder(home, entry)
    host = home / HOST_ROOT
    key = host_key(entry['sid'])
    return dict(cwd=home / entry['project'], folder=folder,
                transcript=folder / (entry['native'] + '.jsonl'),
                own=folder / (entry['sid'] + '.jsonl'),
                neighbour=folder / (entry['neighbour'] + '.jsonl'),
                config=host / (key + '.config.json'),
                state=host / (key + '.state.json'),
                log=host / (key + '.events.log'))


def mirror_rows(entry):
    rows = []
    for index in range(MIRROR_EXCHANGES):
        stem = f'{index:04d}'
        rows.append(dict(role='user', text=f'MIRROR_ASK_{stem}_2779', id='m-u-' + stem,
                         timestamp=STAMP[:-5] + f'{index % 60:02d}.000Z'))
        rows.append(dict(role='assistant', text=f'MIRROR_REPLY_{stem}_2779', id='m-a-' + stem,
                         timestamp=STAMP[:-5] + f'{index % 60:02d}.000Z'))
        for slot in ('a', 'b', 'c', 'd'):
            rows.append(dict(role='tool_result', text='', id=f'm-r-{stem}-{slot}', status='ok',
                             timestamp=STAMP, detail=('Synthetic tool output line.\n' * 140)[:MIRROR_TOOL_DETAIL]))
    rows.append(dict(role='assistant', text=entry['latest'], id='m-a-newest', timestamp=STAMP))
    for index in range(MIRROR_TOOL_TAIL):
        rows.append(dict(role='tool_result', text='', id=f'm-r-tail-{index:04d}', status='ok',
                         timestamp=STAMP, detail=('Synthetic tail output line.\n' * 140)[:MIRROR_TOOL_DETAIL]))
    return rows


def mirror_paths(home, entry):
    root = home / HOST_ROOT
    key = host_key(entry['sid'])
    return dict(history=root / (key + '.history.jsonl'), root=root, key=key)


def mirror_visible(rows):
    return [row for row in rows if row['role'] in ('user', 'assistant')]


def mirror_tail_visible(path):
    raw = Path(path).read_bytes()
    window = raw[max(0, len(raw) - MIRROR_TAIL_BYTES):]
    found = []
    for line in window.splitlines():
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if isinstance(row, dict) and row.get('type') == 'multiprompt_history' and row.get('row', {}).get('role') in ('user', 'assistant'):
            found.append(row)
    return found


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, separators=(',', ':'), sort_keys=True))


def read_json(path):
    return json.loads(Path(path).read_text())


def write_transcript(path, sid, lines):
    path.parent.mkdir(parents=True, exist_ok=True)
    output = []
    for index, (role, text) in enumerate(lines):
        identity = f'{sid[:8]}-{index:04d}-4000-8000-000000002715'
        output.append(dict(type=role, uuid=identity, sessionId=sid, timestamp=STAMP,
                           message=dict(role=role, id='msg_' + identity.replace('-', ''),
                                        content=[dict(type='text', text=text)])))
    path.write_text(''.join(json.dumps(row) + '\n' for row in output))
    return path


def plan():
    return dict(
        shapes=[dict(entry, cwd=entry['project']) for entry in SHAPES],
        long_chat=dict(session=LONG_SESSION, sid=LONG_SID),
        mirror=dict(exchanges=MIRROR_EXCHANGES, tool_tail=MIRROR_TOOL_TAIL, tool_detail=MIRROR_TOOL_DETAIL,
                    tail_bytes=MIRROR_TAIL_BYTES),
        markers=[entry['latest'] for entry in SHAPES] + [entry['neighbour_text'] for entry in SHAPES] +
                [entry['stale'] for entry in SHAPES if entry['stale']])


def create(home):
    home = Path(home)
    manifest = plan()
    for item in manifest['shapes']:
        entry = shape(item['shape'])
        where = paths(home, entry)
        where['cwd'].mkdir(parents=True, exist_ok=True)
        for role, lines in TRANSCRIPTS[entry['shape']].items():
            write_transcript(project_folder(home, entry) / (entry[role] + '.jsonl'), entry[role], lines)
        write_transcript(where['neighbour'], entry['neighbour'],
                         [('user', entry['neighbour_text'].replace('_2715', '_ASK_2715')),
                          ('assistant', entry['neighbour_text'])])
        write_json(where['config'], dict(enabled=True, provider='claude', sid=entry['sid'],
                                         tmux=entry['session'], cwd=str(where['cwd']),
                                         native_session=entry['native'], permission_mode='bypassPermissions'))
        write_json(where['state'], dict(sid=entry['sid'], provider='claude', cwd=str(where['cwd']),
                                        native_session=entry['sid'] if entry['shape'] == 'compacted'
                                        else entry['native'], nightly_day='2026-11-20'))
        init = []
        if entry['shape'] in ('compacted', 'resumed'):
            init.append(dict(at=1.0, event='init', detail=dict(session=entry['sid'])))
        init.append(dict(at=2.0, event='init', detail=dict(session=entry['native'])))
        where['log'].parent.mkdir(parents=True, exist_ok=True)
        where['log'].write_text(''.join(json.dumps(row) + '\n' for row in init))
        item.update(cwd=str(where['cwd']), transcript=str(where['transcript']), own_transcript=str(where['own']),
                    neighbour_transcript=str(where['neighbour']), config=str(where['config']),
                    state=str(where['state']), log=str(where['log']))
        if entry['shape'] == 'mirror':
            item.update(seed_mirror(home, entry))
    write_json(home / 'chat-shapes.json', manifest)
    return manifest


def seed_mirror(home, entry):
    where = mirror_paths(home, entry)
    rows = mirror_rows(entry)
    where['history'].parent.mkdir(parents=True, exist_ok=True)
    where['history'].write_text(''.join(json.dumps(dict(type='multiprompt_history', row=row), separators=(',', ':')) + '\n'
                                        for row in rows))
    config = read_json(where['root'] / (where['key'] + '.config.json'))
    write_json(where['root'] / (where['key'] + '.config.json'), dict(config, display_history=True))
    write_json(where['root'] / (where['key'] + '.state.json'), dict(read_json(where['root'] / (where['key'] + '.state.json')),
                                                                    display_history=True))
    events = [dict(at=float(index + 1), event='init', detail=dict(session=sid))
              for index, sid in enumerate((entry['sid'], entry['native'], entry['sid']))]
    events += [dict(at=float(len(events) + index + 1), event=kind, detail=dict(id=f'mirror-queue-{index}', attempt=f'attempt-{index}'))
               for index, kind in enumerate(('accepted', 'delivered'))]
    events.append(dict(at=float(len(events) + 1), event='handover', detail=dict(previous='stopped')))
    where['root'].joinpath(where['key'] + '.events.log').write_text(''.join(json.dumps(row) + '\n' for row in events))
    handovers = []
    for index in range(2):
        path = where['root'] / (where['key'] + '.handover-' + f'{index + 1:032x}' + '.json')
        write_json(path, dict(text='MIRROR_HANDOVER_2779 ' + str(index + 1), provider='claude', sid=entry['sid'], at=float(index + 1)))
        handovers.append(str(path))
    return dict(mirror=str(where['history']), mirror_bytes=where['history'].stat().st_size,
                mirror_rows=len(rows), handovers=handovers,
                init_sessions=[row['detail']['session'] for row in events if row['event'] == 'init'])


def last_text(path):
    lines = [line for line in Path(path).read_text().splitlines() if line.strip()]
    event = json.loads(lines[-1])
    return ''.join(part.get('text', '') for part in event['message']['content'])


def transcript_sids(home, entry):
    return sorted(path.stem for path in project_folder(home, entry).glob('*.jsonl'))


def latest_init(path):
    found = None
    for line in Path(path).read_text().splitlines():
        event = json.loads(line)
        if event.get('event') == 'init' and isinstance(event.get('detail'), dict):
            found = event['detail'].get('session') or found
    return found


def check(home):
    home = Path(home)
    manifest = read_json(home / 'chat-shapes.json')
    expected = plan()
    assert [(item['shape'], item['session'], item['sid']) for item in manifest['shapes']] == \
        [(item['shape'], item['session'], item['sid']) for item in expected['shapes']], 'SHAPES_MANIFEST_STALE'
    for item in manifest['shapes']:
        entry = shape(item['shape'])
        config = read_json(item['config'])
        assert config.get('enabled') is True, 'SHAPES_CONFIG_DISABLED'
        assert config.get('sid') == entry['sid'] and config.get('tmux') == entry['session'], 'SHAPES_CONFIG_MISMATCH'
        assert config.get('cwd') == item['cwd'] == str(home / entry['project']), 'SHAPES_CONFIG_MISMATCH'
        assert config.get('native_session') == entry['native'], 'SHAPES_CONFIG_MISMATCH'
        assert Path(item['cwd']).is_dir(), 'SHAPES_CWD_MISSING'
        for big in (item['transcript'], item['neighbour_transcript']):
            assert Path(big).is_file() and Path(big).stat().st_size < 8192, 'SHAPES_TRANSCRIPT_MISMATCH'
        assert last_text(item['transcript']) == entry['latest'], 'SHAPES_TRANSCRIPT_MISMATCH'
        assert last_text(item['neighbour_transcript']) == entry['neighbour_text'], 'SHAPES_TRANSCRIPT_MISMATCH'
        native = latest_init(item['log'])
        sids = transcript_sids(home, entry)
        if entry['shape'] == 'mirror':
            mirror = Path(item['mirror'])
            assert mirror.is_file(), 'SHAPES_MIRROR_MISSING'
            rows = [json.loads(line)['row'] for line in mirror.read_text().splitlines()]
            visible = mirror_visible(rows)
            tools = [row for row in rows if row['role'] not in ('user', 'assistant')]
            assert 0.60 <= len(tools) / len(rows) <= 0.80, 'SHAPES_MIRROR_TOOL_SHARE'
            assert 2_400_000 <= item['mirror_bytes'] <= 3_400_000, 'SHAPES_MIRROR_BYTES'
            assert visible[-1]['text'] == entry['latest'], 'SHAPES_MIRROR_NEWEST'
            assert mirror_tail_visible(mirror) == [], 'SHAPES_MIRROR_TAIL_VISIBLE'
            assert item['init_sessions'] == [entry['sid'], entry['native'], entry['sid']], 'SHAPES_MIRROR_RESTARTS'
            assert len(item['handovers']) == 2, 'SHAPES_MIRROR_HANDOVER'
            assert read_json(item['config']).get('display_history') is True, 'SHAPES_MIRROR_DISPLAY'
        elif entry['shape'] == 'compacted':
            assert entry['sid'] != native == entry['native'], 'SHAPES_COMPACTED_IDENTITY'
            assert last_text(item['own_transcript']) == entry['stale'], 'SHAPES_TRANSCRIPT_MISMATCH'
            assert entry['sid'] in sids and entry['native'] in sids, 'SHAPES_COMPACTED_TRANSCRIPTS'
        elif entry['shape'] == 'fresh':
            assert native == entry['sid'] == entry['native'], 'SHAPES_FRESH_IDENTITY'
            assert entry['sid'] in sids and entry['neighbour'] in sids, 'SHAPES_FRESH_TRANSCRIPTS'
        else:
            assert native == item['native'] != item['sid'], 'SHAPES_RESUMED_IDENTITY'
            assert entry['sid'] not in sids and entry['native'] in sids, 'SHAPES_RESUMED_TRANSCRIPTS'
    return manifest


COMMANDS = {'plan': plan, 'seed': create, 'check': check}


def main(argv):
    parser = argparse.ArgumentParser(description='Synthetic desktop chat shapes for the phone tests.')
    parser.add_argument('command', choices=sorted(COMMANDS))
    parser.add_argument('--home', default=None)
    arguments = parser.parse_args(argv[1:])
    home = Path(arguments.home or os.environ.get('HOME') or Path.home())
    payload = plan() if arguments.command == 'plan' else COMMANDS[arguments.command](home)
    print(json.dumps(payload, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
