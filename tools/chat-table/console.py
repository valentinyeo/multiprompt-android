#!/usr/bin/env python3
import datetime
import json
import os
from pathlib import Path
import sys
import uuid

if os.environ.get('GITHUB_ACTIONS') != 'true' or Path.home() != Path('/home/verifyssh') or os.geteuid() == 0:
    raise SystemExit('chat table requires the hosted SSH fixture')

home = Path.home()
cwd = home / 'chat-table-work'
cwd.mkdir(parents=True, exist_ok=True)
os.chdir(cwd)
sid = 'a1b2c3d4-2544-4000-8000-000000002544'
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


event('user', 'Show the garden harvest table.')
rows = ['## Garden harvest table', '',
        '| Plot | Northern beds | Southern beds | Western beds | Greenhouse beds | Eastern harvest |',
        '| --- | --- | --- | --- | --- | --- |']
for row in range(1, 25):
    cells = [f'R{row:02d}-C{column:02d} ' + ('harvest' if column == 6 else 'orchard') for column in range(1, 7)]
    rows.append('| ' + ' | '.join(cells) + ' |')
rows.extend(['', 'TABLE_READY_2544'])
event('assistant', '\n'.join(rows))
print('Claude Code\nTABLE_READY_2544\nReady\n❯ ', flush=True)
for line in sys.stdin:
    with (home / 'receipts.txt').open('a') as output:
        output.write('UNEXPECTED_TABLE_INPUT:' + line.rstrip('\r\n') + '\n')
