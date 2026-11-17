import json
from pathlib import Path
import subprocess
import sys


BULK_NAMES = [f'pin-{index:02d}' for index in range(12)] + [f'row-{index:02d}' for index in range(28)]
LONG_SESSION = 'cl-long-chat'
LONG_SID = '11560000-0000-4000-8000-000000001156'


def inventory(home):
    names = sorted(subprocess.check_output(['tmux', 'list-sessions', '-F', '#{session_name}'], text=True).splitlines())
    assert len(names) >= 40, 'SEED_INBOX_TOO_SMALL'
    assert set(BULK_NAMES + [LONG_SESSION]) <= set(names), 'SEED_SESSIONS_MISSING'
    source = json.loads((home / 'long-chat-source.json').read_text())
    path = Path(source['path'])
    assert path == home / '.claude/projects' / str(home / 'long-chat-proof').replace('/', '-') / (LONG_SID + '.jsonl'), 'SEED_TRANSCRIPT_IDENTITY'
    size = path.stat().st_size
    with path.open('rb') as stream:
        rows = sum(1 for _ in stream)
    assert size > 18 * 1024 * 1024, 'SEED_TRANSCRIPT_TOO_SMALL'
    assert rows == 1882, 'SEED_TRANSCRIPT_ROWS'
    assert (source['bytes'], source['rows'], source['sid']) == (size, rows, LONG_SID), 'SEED_SOURCE_MISMATCH'
    return dict(sessions=len(names), bulk_sessions=len(BULK_NAMES), session_names=names,
                transcript=dict(session=LONG_SESSION, bytes=size, rows=rows, sid=LONG_SID, path=str(path)))


def check(home, evidence):
    recorded = json.loads(evidence.read_text())
    assert recorded == inventory(home), 'SEED_EVIDENCE_MISMATCH'
    return recorded


if __name__ == '__main__':
    mode = sys.argv[1]
    assert mode in ('record', 'check')
    counts = inventory(Path.home()) if mode == 'record' else check(Path.home(), Path(sys.argv[2]))
    print(json.dumps(counts, indent=2))
