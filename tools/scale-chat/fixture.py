#!/usr/bin/env python3
import argparse
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import sys
import time

HOST_ROOT = '.local/state/multiprompt/agent-host'
MANIFEST = 'scale-chat.json'
SCALE = 40
TYPICAL = (1_300_000, 1_100)
BIG = {1: (54_000_000, 30_000), 2: (15_000_000, 8_700), 3: (14_200_000, 8_300), 4: (13_400_000, 7_900),
       5: (12_600_000, 7_400), 6: (11_800_000, 7_000), 7: (10_900_000, 6_500)}
STALE = 3
MARKER = 'SCALE_CHAT_LATEST_4704'
BASE = datetime(2026, 11, 24, 9, 0, 0)
SENTENCE = 'Synthetic filler sentence for the scale fixture, never real chat text. '
METADATA_BYTES = 300
CYCLE_WEIGHTS = (('user', 0.5), ('reply', 2.0), ('tool_use', 1.5), ('tool_result', 2.0),
                 ('tool_use', 1.5), ('tool_result', 2.0), ('reply', 0.5))
TOOLS = ('Bash', 'Read', 'Edit', 'Grep')


def entry(index):
    size = BIG.get(index, TYPICAL)
    return dict(index=index, session=f'live-scale-{index:02d}', project=f'scale-work-{index:02d}',
                chat='b4530000-0000-4000-8000-' + f'{index:012x}',
                sid='b4530030-0000-4000-8000-' + f'{index:012x}',
                planned_bytes=size[0], planned_rows=size[1], marker=f'{MARKER}_{index:02d}')


def plan():
    chats = [entry(index) for index in range(1, SCALE + 1)]
    return dict(scale=SCALE, transcript_target=SCALE * (1 + STALE), stale_per_chat=STALE, chats=chats,
                distribution=dict(typical_bytes=TYPICAL[0], big_chats=sum(1 for chat in chats if chat['planned_bytes'] >= 10_000_000),
                                  largest_bytes=max(chat['planned_bytes'] for chat in chats),
                                  largest_rows=max(chat['planned_rows'] for chat in chats)),
                markers=[chat['marker'] for chat in chats])


def project_folder(home, chat):
    return Path(home) / '.claude/projects' / str(Path(home) / chat['project']).replace('/', '-')


def host_key(chat):
    return hashlib.sha256(('claude:' + chat['chat']).encode()).hexdigest()[:32]


def stale_sid(chat, offset):
    return f'b45301{chat["index"]:02d}-0000-4000-8000-{offset + 1:012x}'


def paths(home, chat):
    folder = project_folder(home, chat)
    host = Path(home) / HOST_ROOT
    key = host_key(chat)
    return dict(cwd=Path(home) / chat['project'], folder=folder, live=folder / (chat['sid'] + '.jsonl'),
                config=host / (key + '.config.json'), state=host / (key + '.state.json'),
                log=host / (key + '.events.log'))


def moment(step):
    return (BASE + timedelta(seconds=step * 5)).isoformat(timespec='milliseconds') + 'Z'


def identifier(sid, step):
    return f'{sid[:8]}-{step:04x}-4000-8000-{step:012x}'


def sized(head, target, tail=''):
    target = max(len(head) + len(tail) + 1, int(target))
    body = head
    while len(body) + len(tail) + len(SENTENCE) <= target:
        body += SENTENCE
    body += SENTENCE[:max(0, target - len(tail) - len(body))]
    return body + tail


def code_block(turn, target):
    lines = '\n'.join(f'    val row{turn}_{number} = syntheticLine({number})' for number in range(1, 6))
    return sized(f'```kotlin\n    fun syntheticTurn{turn}() {{\n{lines}\n', target, '\n    }\n```')


def text_row(role, sid, step, text):
    return dict(type=role, uuid=identifier(sid, step), sessionId=sid, timestamp=moment(step),
                message=dict(role=role, id=f'msg_{step:012x}', content=[dict(type='text', text=text)]))


def tool_use_row(sid, step, text, target):
    name = TOOLS[step % len(TOOLS)]
    payload = dict(Bash=dict(command=text, description=f'Synthetic command {step}'),
                   Read=dict(file_path='/home/verifyssh/scale-work/src/synthetic-' + f'{step}.kt', offset=1, limit=400),
                   Edit=dict(file_path='/home/verifyssh/scale-work/src/synthetic.kt', old_string=text, new_string=text),
                   Grep=dict(pattern='Synthetic', path='/home/verifyssh/scale-work', output_mode='content'))[name]
    if name == 'Read':
        payload['description'] = sized(f'Synthetic read {step}: ', target)
    return dict(type='assistant', uuid=identifier(sid, step), sessionId=sid, timestamp=moment(step),
                message=dict(role='assistant', id=f'msg_{step:012x}', content=[
                    dict(type='tool_use', id=f'toolu_{step:012x}', name=name, input=payload)]))


def tool_result_row(sid, step, body):
    return dict(type='user', uuid=identifier(sid, step), sessionId=sid, timestamp=moment(step),
                message=dict(role='user', id=f'msg_{step:012x}', content=[
                    dict(type='tool_result', tool_use_id=f'toolu_{step - 1:012x}', content=body)]))


def rows(chat):
    step = 0
    turn = 0
    unit = chat['planned_bytes'] / chat['planned_rows'] * 0.7
    while step < chat['planned_rows']:
        turn += 1
        for kind, weight in CYCLE_WEIGHTS:
            step += 1
            target = weight * unit - METADATA_BYTES
            if kind == 'user':
                yield text_row('user', chat['sid'], step, sized(f'Synthetic prompt {turn}: ', target))
            elif kind == 'reply':
                suffix = '\nThat closes the synthetic turn. ' + f'Turn {turn}.'
                yield text_row('assistant', chat['sid'], step, code_block(turn, target - len(suffix)) + suffix)
            elif kind == 'tool_use':
                yield tool_use_row(chat['sid'], step, sized(f'Synthetic command {turn}: ', target), target)
            else:
                yield tool_result_row(chat['sid'], step, sized(f'Synthetic tool output {turn}: ', target))
    step += 1
    yield text_row('user', chat['sid'], step, f'Synthetic closing prompt {turn + 1}.')
    step += 1
    yield text_row('assistant', chat['sid'], step, sized(f'{chat["marker"]} Synthetic newest reply. ', 900))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, separators=(',', ':'), sort_keys=True))


def read_json(path):
    return json.loads(Path(path).read_text())


def measured(path):
    raw = Path(path).read_bytes()
    return dict(bytes=len(raw), rows=len(raw.splitlines()))


def last_text(path):
    lines = Path(path).read_bytes().splitlines()
    event = json.loads(lines[-1])
    return ''.join(part.get('text', '') for part in event['message']['content'])


def create(home):
    home = Path(home)
    manifest = plan()
    now = time.time()
    for offset, chat in enumerate(manifest['chats']):
        where = paths(home, chat)
        where['cwd'].mkdir(parents=True, exist_ok=True)
        where['folder'].mkdir(parents=True, exist_ok=True)
        with where['live'].open('w', encoding='utf-8') as stream:
            for row in rows(chat):
                stream.write(json.dumps(row) + '\n')
        for stale_offset in range(STALE):
            stale = where['folder'] / (stale_sid(chat, stale_offset) + '.jsonl')
            stale.write_text(''.join(json.dumps(row) + '\n' for row in (
                text_row('user', stale_sid(chat, stale_offset), 1,
                         f'Synthetic earlier prompt {stale_offset + 1} before the current transcript.'),
                text_row('assistant', stale_sid(chat, stale_offset), 2,
                         f'Synthetic earlier reply {stale_offset + 1} before the current transcript.'))))
            os.utime(stale, (now - 3600 * (stale_offset + 1 + offset), now - 3600 * (stale_offset + 1 + offset)))
        os.utime(where['live'], (now, now))
        write_json(where['config'], dict(enabled=True, provider='claude', sid=chat['chat'], tmux=chat['session'],
                                         cwd=str(where['cwd']), native_session=chat['sid'],
                                         permission_mode='bypassPermissions'))
        write_json(where['state'], dict(sid=chat['chat'], provider='claude', cwd=str(where['cwd']),
                                        native_session=chat['sid'], nightly_day='2026-11-24'))
        where['log'].parent.mkdir(parents=True, exist_ok=True)
        where['log'].write_text(json.dumps(dict(at=1.0, event='init', detail=dict(session=chat['sid']))) + '\n')
        size = measured(where['live'])
        chat.update(cwd=str(where['cwd']), transcript=str(where['live']), bytes=size['bytes'], rows=size['rows'],
                    folder=str(where['folder']), config=str(where['config']), state=str(where['state']),
                    log=str(where['log']))
    manifest['measured'] = summary(manifest['chats'])
    write_json(home / MANIFEST, manifest)
    return manifest


def summary(chats):
    ordered = sorted(chat['bytes'] for chat in chats)
    total = sum(ordered)
    return dict(total_bytes=total, total_rows=sum(chat['rows'] for chat in chats), median_bytes=ordered[len(ordered) // 2],
                p90_bytes=ordered[min(len(ordered) - 1, int(len(ordered) * 0.9))], largest_bytes=ordered[-1],
                largest_rows=max(chat['rows'] for chat in chats),
                big_chats=sum(1 for value in ordered if value >= 10_000_000),
                largest_marker=max(chats, key=lambda chat: chat['bytes'])['marker'],
                largest_session=max(chats, key=lambda chat: chat['bytes'])['session'])


def check(home, expect_distribution=True):
    home = Path(home)
    manifest = read_json(home / MANIFEST)
    expected = plan()
    assert [(chat['index'], chat['session'], chat['sid'], chat['chat']) for chat in manifest['chats']] == \
        [(chat['index'], chat['session'], chat['sid'], chat['chat']) for chat in expected['chats']], 'SCALE_CHAT_MANIFEST_STALE'
    transcripts = 0
    for chat in manifest['chats']:
        where = paths(home, chat)
        assert where['live'].is_file(), 'SCALE_CHAT_TRANSCRIPT_MISSING'
        measured_size = measured(where['live'])
        assert 0.8 * chat['planned_bytes'] <= measured_size['bytes'] <= 1.25 * chat['planned_bytes'], 'SCALE_CHAT_BYTES'
        assert 0.9 * chat['planned_rows'] <= measured_size['rows'] <= 1.2 * chat['planned_rows'], 'SCALE_CHAT_ROWS'
        assert last_text(where['live']).startswith(chat['marker']), 'SCALE_CHAT_MARKER'
        config = read_json(where['config'])
        assert config.get('enabled') is True and config.get('sid') == chat['chat'], 'SCALE_CHAT_CONFIG'
        assert config.get('tmux') == chat['session'] and config.get('native_session') == chat['sid'], 'SCALE_CHAT_CONFIG'
        assert config.get('cwd') == str(where['cwd']) and where['cwd'].is_dir(), 'SCALE_CHAT_CWD'
        assert len(list(where['folder'].glob('*.jsonl'))) == 1 + STALE, 'SCALE_CHAT_TRANSCRIPT_COUNT'
        transcripts += 1 + STALE
    assert transcripts == expected['transcript_target'], 'SCALE_CHAT_TRANSCRIPT_COUNT'
    counts = summary(manifest['chats'])
    assert manifest['measured'] == counts, 'SCALE_CHAT_MEASURED_STALE'
    assert_distribution(counts, expect_distribution)
    return manifest


def assert_distribution(counts, expect_distribution):
    if not expect_distribution:
        return
    assert counts['largest_bytes'] >= 50 * 1024 * 1024, 'SCALE_CHAT_LARGEST_BYTES'
    assert counts['largest_rows'] >= 25_000, 'SCALE_CHAT_LARGEST_ROWS'
    assert counts['big_chats'] >= 6, 'SCALE_CHAT_BIG_CHATS'
    assert 1_000_000 <= counts['median_bytes'] <= 2_200_000, 'SCALE_CHAT_MEDIAN_BYTES'
    assert counts['p90_bytes'] >= 10_000_000, 'SCALE_CHAT_P90_BYTES'


COMMANDS = {'plan': plan, 'seed': create, 'check': check}


def main(argv):
    parser = argparse.ArgumentParser(description='Synthetic Claude chats at the owner server scale for the phone timing job.')
    parser.add_argument('command', choices=sorted(COMMANDS))
    parser.add_argument('--home', default=None)
    arguments = parser.parse_args(argv[1:])
    home = Path(arguments.home or os.environ.get('HOME') or Path.home())
    payload = plan() if arguments.command == 'plan' else COMMANDS[arguments.command](home)
    print(json.dumps(payload, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
