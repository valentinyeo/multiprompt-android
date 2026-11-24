#!/usr/bin/env python3
import gzip
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SNAPSHOT = HERE / 'chat-tail-tool-heavy.jsonl.gz'
STAMP = '2026-11-22T09:00:00.000Z'
SID = 'a2779000-0000-4000-8000-000000002779'
EXCHANGES = 400
TAIL_ROWS = 300
OLDEST_QUESTION = 'SOFT_2779_OLDEST_QUESTION'
NEWEST_QUESTION = 'SOFT_2779_NEWEST_QUESTION'
NEWEST_REPLY = 'SOFT_2779_NEWEST_REPLY'
MIN_BYTES = 2_600_000
MAX_BYTES = 5_400_000
MIN_ROWS = 3_900
MAX_ROWS = 4_600
MIN_TOOL_SHARE = 0.64
MAX_TOOL_SHARE = 0.76


def identity(name):
    return name[:36]


def text_row(role, name, text):
    return dict(type=role, uuid=identity(name), sessionId=SID, timestamp=STAMP,
                message=dict(role=role, id='msg_' + name.replace('-', ''),
                             content=[dict(type='text', text=text)]))


def tool_use(index, slot):
    name = f'{SID[:8]}-t{index:04d}{slot}'
    return dict(type='assistant', uuid=identity(name), sessionId=SID, timestamp=STAMP,
                message=dict(role='assistant', id='msg_' + name.replace('-', ''),
                             content=[dict(type='tool_use', id='toolu_' + name, name='Bash',
                                           input=dict(command='printf %s\\n synthetic-tail-window-' + ('segment ' * 60),
                                                      description=f'Synthetic fixture call {index} slot {slot}'))]))


def tool_result(index, slot, lines=32):
    name = f'{SID[:8]}-r{index:04d}{slot}'
    body = 'Synthetic tool output line.\n' * lines
    return dict(type='user', uuid=identity(name), sessionId=SID, timestamp=STAMP,
                message=dict(role='user', id='msg_' + name.replace('-', ''),
                             content=[dict(type='tool_result', tool_use_id='toolu_' + f'{SID[:8]}-t{index:04d}{slot}',
                                           content=body)]))


def tail_result(index):
    name = f'{SID[:8]}-x{index:04d}'
    body = 'Synthetic tail output line.\n' * 140
    return dict(type='user', uuid=identity(name), sessionId=SID, timestamp=STAMP,
                message=dict(role='user', id='msg_' + name.replace('-', ''),
                             content=[dict(type='tool_result', tool_use_id='toolu_' + name, content=body)]))


def rows():
    found = []
    for index in range(EXCHANGES):
        name = f'{SID[:8]}-{index:04d}'
        question = OLDEST_QUESTION if index == 0 else f'Synthetic prompt {index} of a long tool-heavy chat.'
        if index == EXCHANGES - 1:
            found.append(text_row('user', name + '-a', NEWEST_QUESTION))
            found.append(text_row('assistant', name + '-f', NEWEST_REPLY))
            continue
        found.append(text_row('user', name + '-a', question))
        found.append(text_row('assistant', name + '-b', f'Synthetic reply {index} starts the fixture turn.'))
        for slot in 'abc':
            found.append(tool_use(index, slot))
            found.append(tool_result(index, slot))
        found.append(tool_use(index, 'd'))
        found.append(text_row('assistant', name + '-f', f'Synthetic summary {index} closes the fixture turn.'))
    found.extend(tail_result(index) for index in range(TAIL_ROWS))
    return found


def snapshot():
    return ''.join(json.dumps(row) + '\n' for row in rows()).encode('utf-8')


def write(path=SNAPSHOT):
    Path(path).write_bytes(gzip.compress(snapshot(), mtime=0))
    return Path(path)


def extract(directory):
    path = Path(directory) / (SID + '.jsonl')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.decompress(SNAPSHOT.read_bytes()))
    return path


def counts(path):
    raw = Path(path).read_bytes()
    events = [json.loads(line) for line in raw.splitlines() if line.strip()]
    tool = sum(1 for event in events
               if any(part.get('type') in ('tool_use', 'tool_result') for part in event['message']['content']))
    return dict(bytes=len(raw), rows=len(events), tool_rows=tool)


def check(path):
    measured = counts(path)
    assert MIN_BYTES <= measured['bytes'] <= MAX_BYTES, 'SOFT2779_BYTES'
    assert MIN_ROWS <= measured['rows'] <= MAX_ROWS, 'SOFT2779_ROWS'
    assert MIN_TOOL_SHARE <= measured['tool_rows'] / measured['rows'] <= MAX_TOOL_SHARE, 'SOFT2779_TOOL_SHARE'
    assert gzip.decompress(SNAPSHOT.read_bytes()) == snapshot(), 'SOFT2779_SNAPSHOT_STALE'
    for event in (json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()):
        for part in event['message']['content']:
            if part.get('type') == 'text':
                assert 'Synthetic' in part['text'] or '_2779' in part['text'], 'SOFT2779_SYNTHETIC_ONLY'
    return measured


if __name__ == '__main__':
    import tempfile
    if sys.argv[1:] == ['write']:
        write()
    with tempfile.TemporaryDirectory() as directory:
        measured = check(extract(directory))
    print(json.dumps(dict(snapshot=str(SNAPSHOT), gzip_bytes=SNAPSHOT.stat().st_size, **measured), indent=2))
