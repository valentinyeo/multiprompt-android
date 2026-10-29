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

        for index in range(321):
            event('user', f'user-{index}', f'Question {index}')
            event('assistant', f'reply-{index}', [dict(type='text',
                text=LATEST if index == 320 else f'Answer {index}')])
            if index < 310:
                for tool in range(2):
                    identity = f'tool-{index}-{tool}'
                    event('assistant', identity, [dict(type='tool_use', id=identity,
                        name='Bash', input=dict(command='printf synthetic-output'))])
                    event('user', f'result-{identity}', [dict(type='tool_result', tool_use_id=identity,
                        content=('Synthetic tool output line.\n' * (2400 if tool == 0 else 50)))])
    assert transcript.stat().st_size > 18 * 1024 * 1024
    return cwd, transcript


if __name__ == '__main__':
    home = Path.home()
    cwd, transcript = create(home)
    os.chdir(cwd)
    meta = home / '.claude/sessions' / (str(os.getpid()) + '.json')
    meta.parent.mkdir(parents=True, exist_ok=True)
    meta.write_text(json.dumps(dict(sessionId=SID, cwd=str(cwd), pid=os.getpid())))
    Path(sys.argv[1]).write_text(json.dumps(dict(bytes=transcript.stat().st_size, rows=1882,
        sid=SID, path=str(transcript), latest=LATEST)))
    print('Claude Code protocol fixture\nReady\n❯ ', flush=True)
    for line in sys.stdin:
        print('Ready\n❯ ', flush=True)
