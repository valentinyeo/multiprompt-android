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
        for index in range(941):
            for role in ('user', 'assistant'):
                text = f'Question {index}' if role == 'user' else (
                    f'Reply {index}\n\n' + ('A long conversation with paragraphs, code and tool results.\n' * 420)
                    + '\n```text\n' + 'example output\n' * 20 + '```\n')
                if index == 940 and role == 'assistant':
                    text = LATEST
                output.write(json.dumps(dict(type=role, uuid=f'{role}-{index}', sessionId=SID,
                    timestamp=stamp, message=dict(role=role, id=f'message-{index}',
                    content=[dict(type='text', text=text)]))) + '\n')
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
