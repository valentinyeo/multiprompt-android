#!/usr/bin/env python3
import argparse
import json
import os
import sys
import time
import uuid
from pathlib import Path

SUCCESS = 'Feedback saved locally. No board ticket filed.'
EMPTY_WANT = 'multibot-feedback: say what you want from Multibot'


def state_path():
    configured = os.environ.get('MULTIBOT_FEEDBACK_LOG')
    if configured:
        return Path(configured).expanduser()
    root = os.environ.get('XDG_STATE_HOME')
    base = Path(root).expanduser() if root else Path(os.path.expanduser('~/.local/state'))
    return base / 'multibot' / 'feedback.jsonl'


def session_name():
    for name in (os.environ.get('ZSB_SESSION'), os.environ.get('MULTIBOT_SESSION')):
        if name:
            return name
    parts = os.environ.get('TMUX', '').split(',')
    return parts[1] if len(parts) > 1 else ''


def feedback_row(want, why):
    return dict(id=str(uuid.uuid4()), at=int(time.time()), want=want, why=why,
                session=session_name(), provider=os.environ.get('MULTIBOT_PROVIDER', ''),
                cwd=os.path.basename(os.getcwd()))


def save(row):
    path = state_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    with os.fdopen(fd, 'a', encoding='utf-8') as stream:
        stream.write(json.dumps(row, sort_keys=True) + '\n')


def main(argv):
    parser = argparse.ArgumentParser(prog='multibot-feedback',
                                     description='Save feedback locally. No board ticket is filed.')
    parser.add_argument('want')
    parser.add_argument('--why', default='')
    args = parser.parse_args(argv)
    want = args.want.strip()
    if not want:
        print(EMPTY_WANT, file=sys.stderr)
        return 2
    try:
        save(feedback_row(want, args.why.strip()))
    except OSError as exc:
        print(f'multibot-feedback: could not save locally: {exc}', file=sys.stderr)
        return 1
    print(SUCCESS)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
