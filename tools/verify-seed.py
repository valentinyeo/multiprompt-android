import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


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
    recorded = json.load(sys.stdin) if str(evidence) == '-' else json.loads(evidence.read_text())
    assert recorded == inventory(home), 'SEED_EVIDENCE_MISMATCH'
    return recorded


def inbox(evidence):
    counts = json.loads((evidence / 'seed-counts.json').read_text())
    assert json.loads((evidence / 'seed-check.json').read_text()) == counts, 'SEED_CHECK_EVIDENCE_MISMATCH'
    expected = set(counts['session_names'])
    assert counts['sessions'] == len(expected) >= 42, 'SEED_INBOX_TOO_SMALL'
    pages = sorted(evidence.glob('*-seed-inbox-*.xml'))
    seen = set()
    displayed_count = f"{counts['sessions']} results across all splits"
    count_visible = False
    for page in pages:
        root = ET.parse(page).getroot()
        parents = {child: parent for parent in root.iter() for child in parent}
        for node in root.iter('node'):
            name = node.get('content-desc', '').removeprefix('Robot ')
            is_count = node.get('text') == displayed_count
            if not is_count and (node.get('content-desc') != 'Robot ' + name or name not in expected):
                continue
            match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
            if not match:
                continue
            x1, y1, x2, y2 = map(int, match.groups())
            current = node
            visible = x1 < x2 and y1 < y2
            while current in parents:
                visible &= current.get('visible-to-user') != 'false'
                if current.get('scrollable') == 'true' or parents[current] is root:
                    viewport = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', current.get('bounds', ''))
                    if not viewport:
                        visible = False
                        break
                    left, top, right, bottom = map(int, viewport.groups())
                    visible &= left <= x1 < x2 <= right and top <= y1 < y2 <= bottom
                current = parents[current]
            if visible:
                if is_count:
                    count_visible = True
                else:
                    seen.add(name)
    assert count_visible, 'SEED_INBOX_COUNT_NOT_VISIBLE'
    assert expected <= seen, 'SEED_INBOX_ROWS_MISSING: ' + ', '.join(sorted(expected - seen))
    return dict(sessions_visible=len(seen), session_names=sorted(seen), displayed_count=displayed_count,
                pages=[page.name for page in pages], seed_counts='seed-counts.json', seed_check='seed-check.json')


if __name__ == '__main__':
    mode = sys.argv[1]
    assert mode in ('record', 'check', 'inbox')
    if mode == 'inbox':
        counts = inbox(Path(sys.argv[2]))
    else:
        counts = inventory(Path.home()) if mode == 'record' else check(Path.home(), Path(sys.argv[2]))
    print(json.dumps(counts, indent=2))
