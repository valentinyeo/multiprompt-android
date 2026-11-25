#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import sys


def read_json(path):
    return json.loads(Path(path).read_text())


def counted(chat):
    path = Path(chat['transcript'])
    size = path.stat().st_size if path.is_file() else 0
    rows = sum(1 for _ in path.open('rb')) if path.is_file() else 0
    return dict(chat, bytes=size, rows=rows)


def small(home):
    home = Path(home)
    chats = []
    long_chat = read_json(home / 'long-chat-source.json')
    chats.append(dict(session='cl-long-chat', marker=long_chat['latest'], transcript=long_chat['path']))
    real = read_json(home / 'chat-real.json')
    chats.append(dict(session=real['session'], marker=real['markers']['reply_newest'], transcript=real['transcript']))
    for shape in read_json(home / 'chat-shapes.json')['shapes']:
        chats.append(dict(session=shape['session'], marker=shape['latest'], transcript=shape['transcript']))
    return [counted(chat) for chat in chats]


def scale(home):
    home = Path(home)
    manifest = read_json(home / 'scale-chat.json')
    return [dict(session=chat['session'], marker=chat['marker'], transcript=chat['transcript'],
                 bytes=chat['bytes'], rows=chat['rows']) for chat in manifest['chats']]


SEEDS = {'small': small, 'scale': scale}


def choose(chats):
    ordered = sorted(chats, key=lambda chat: chat['bytes'], reverse=True)
    if len(ordered) < 5:
        raise SystemExit('TIMING_TARGETS_TOO_FEW: the seed exposes %d chats, five are needed' % len(ordered))
    return dict(largest=ordered[0], chats=ordered[:5], total_bytes=sum(chat['bytes'] for chat in ordered),
                sessions=[chat['session'] for chat in ordered])


def main(argv):
    parser = argparse.ArgumentParser(description='Pick the timing targets from the seeded host.')
    parser.add_argument('--seed', choices=sorted(SEEDS), required=True)
    parser.add_argument('--home', default='/home/verifyssh')
    parser.add_argument('--out', required=True)
    arguments = parser.parse_args(argv[1:])
    chats = SEEDS[arguments.seed](arguments.home)
    payload = dict(seed=arguments.seed, home=arguments.home, **choose(chats))
    Path(arguments.out).write_text(json.dumps(payload, indent=2) + '\n')
    print(json.dumps(payload['chats'], indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
