import datetime
import json
import os
from pathlib import Path
import uuid

home = Path.home()
cwd = home / 'gesture-work'
sid = '11791179-1179-4000-8000-000000001179'
cwd.mkdir(exist_ok=True)
os.chdir(cwd)
transcript = home / '.claude/projects' / str(cwd).replace('/', '-') / (sid + '.jsonl')
transcript.parent.mkdir(parents=True, exist_ok=True)
meta = home / '.claude/sessions' / (str(os.getpid()) + '.json')
meta.parent.mkdir(parents=True, exist_ok=True)
meta.write_text(json.dumps(dict(sessionId=sid, cwd=str(cwd), pid=os.getpid())))
text = '```\nCODE_START ' + ' '.join(f'column_{i:03d}' for i in range(80)) + ' CODE_END\n```\n\nGESTURE_REPLY_TARGET'
row = dict(type='assistant', uuid=str(uuid.uuid4()), sessionId=sid,
           timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
           message=dict(role='assistant', content=text, id='gesture-message', stop_reason='end_turn'))
transcript.write_text(json.dumps(row) + '\n')
print('Claude Code\nGESTURE_REPLY_TARGET\nReady\n❯ ', flush=True)
for line in __import__('sys').stdin:
    print('Ready\n❯ ', flush=True)
