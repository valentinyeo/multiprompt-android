import datetime
import json
import os
from pathlib import Path
import sys

SID = '11560000-0000-4000-8000-000000001156'
LATEST = 'LONG_CHAT_LATEST_1156'


def create(home):
    cwd = home / 'long-chat-proof'
    cwd.mkdir(parents=True, exist_ok=True)
    transcript = home / '.claude/projects' / str(cwd).replace('/', '-') / (SID + '.jsonl')
    transcript.parent.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    with transcript.open('w') as output:
        def event(role, identity, content):
            output.write(json.dumps(dict(type=role, uuid=identity, sessionId=SID,
                timestamp=stamp, message=dict(role=role, id=identity, content=content))) + '\n')

        reply = 'A wider message leaves more room for each line. Long replies should be easier to read without changing the text size. Both sides share the same maximum width.'
        event('assistant', 'agent-long', [dict(type='text', text='Agent: ' + reply)])
        event('user', 'user-long', 'User: ' + reply)
        event('assistant', 'agent-short', [dict(type='text', text='Ready')])
        event('user', 'user-short', 'Thanks')
    return cwd, transcript


if __name__ == '__main__':
    home = Path.home()
    cwd, transcript = create(home)
    os.chdir(cwd)
    meta = home / '.claude/sessions' / (str(os.getpid()) + '.json')
    meta.parent.mkdir(parents=True, exist_ok=True)
    meta.write_text(json.dumps(dict(sessionId=SID, cwd=str(cwd), pid=os.getpid())))
    Path(sys.argv[1]).write_text(json.dumps(dict(bytes=transcript.stat().st_size, rows=4,
        sid=SID, path=str(transcript), latest=LATEST)))
    print('Claude Code protocol fixture\nReady\n❯ ', flush=True)
    for line in sys.stdin:
        print('Ready\n❯ ', flush=True)
