#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import sys
import uuid

if os.environ.get('GITHUB_ACTIONS') != 'true' or Path.home() != Path('/home/verifyssh') or os.geteuid() == 0:
    raise SystemExit('chat search requires the hosted SSH fixture')

home = Path.home()
cwd = home / 'chat-search-work'
cwd.mkdir(parents=True, exist_ok=True)
os.chdir(cwd)
sid = 'a1b2c3d4-2540-4000-8000-000000002540'
transcript = home / '.claude/projects' / str(cwd).replace('/', '-') / (sid + '.jsonl')
transcript.parent.mkdir(parents=True, exist_ok=True)
meta = home / '.claude/sessions' / (str(os.getpid()) + '.json')
meta.parent.mkdir(parents=True, exist_ok=True)
meta.write_text(json.dumps(dict(sessionId=sid, cwd=str(cwd), pid=os.getpid())))


def event(role, text):
    message = dict(role=role, content=text)
    if role == 'assistant':
        message.update(id='message-' + uuid.uuid4().hex, stop_reason='end_turn')
    with transcript.open('a', encoding='utf-8') as output:
        output.write(json.dumps(dict(type=role, uuid=str(uuid.uuid4()), sessionId=sid,
                                    timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                    message=message)) + '\n')


event('user', 'Review the garden notes.')
event('assistant', 'orchard north')
for index in range(12):
    event('user', f'Garden note {index + 1}')
    event('assistant', 'The path beside the greenhouse is clear. ' * 12)
event('assistant', 'orchard south')
event('user', 'VERIFY_SEARCH_READY_2540')
event('assistant', 'The garden notes are ready to search.')
print('Claude Code\nVERIFY_SEARCH_READY_2540\nReady\n❯ ', flush=True)
for line in sys.stdin:
    with (home / 'receipts.txt').open('a') as output:
        output.write('UNEXPECTED_SEARCH_INPUT:' + line.rstrip('\r\n') + '\n')
