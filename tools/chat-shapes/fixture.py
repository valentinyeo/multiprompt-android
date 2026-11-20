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
CODE_BLOCK_FIRST = 'CODE_BLOCK_2975_START ' + ('x' * 180)
CODE_BLOCK_SECOND = 'CODE_BLOCK_2975_TAIL_2715'
CODE_BLOCK_MESSAGE = '```\n' + CODE_BLOCK_FIRST + '\n' + CODE_BLOCK_SECOND + '\n```'

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
)

TRANSCRIPTS = {
    'compacted': {
        'sid': [('user', 'COMPACTED_QUESTION_2715'), ('assistant', 'COMPACTED_BEFORE_2715')],
        'native': [('user', 'COMPACTED_CONTINUE_2715'), ('assistant', 'COMPACTED_AFTER_2715')],
    },
    'fresh': {'sid': [('user', 'FRESH_QUESTION_2715'), ('assistant', 'FRESH_READY_2715')]},
    'resumed': {'native': [('user', 'RESUMED_QUESTION_2715'), ('assistant', 'RESUMED_READY_2715'),
                           ('assistant', CODE_BLOCK_MESSAGE)]},
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
    write_json(home / 'chat-shapes.json', manifest)
    return manifest


def last_text(path):
    lines = [line for line in Path(path).read_text().splitlines() if line.strip()]
    event = json.loads(lines[-1])
    return ''.join(part.get('text', '') for part in event['message']['content'])


def penultimate_text(path):
    lines = [line for line in Path(path).read_text().splitlines() if line.strip()]
    if len(lines) < 2:
        return ''
    event = json.loads(lines[-2])
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
        if entry['shape'] == 'resumed':
            assert penultimate_text(item['transcript']) == entry['latest'], 'SHAPES_TRANSCRIPT_MISMATCH'
            assert CODE_BLOCK_FIRST in last_text(item['transcript']), 'SHAPES_TRANSCRIPT_MISMATCH'
        else:
            assert last_text(item['transcript']) == entry['latest'], 'SHAPES_TRANSCRIPT_MISMATCH'
        assert last_text(item['neighbour_transcript']) == entry['neighbour_text'], 'SHAPES_TRANSCRIPT_MISMATCH'
        native = latest_init(item['log'])
        sids = transcript_sids(home, entry)
        if entry['shape'] == 'compacted':
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
