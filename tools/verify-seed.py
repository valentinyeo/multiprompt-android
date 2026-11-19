import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


BULK_NAMES = [f'pin-{index:02d}' for index in range(12)] + [f'row-{index:02d}' for index in range(28)]
LONG_SESSION = 'cl-long-chat'
LONG_SID = '11560000-0000-4000-8000-000000001156'
SHAPES_SCRIPT = 'chat-shapes-fixture.py'
SHAPES_MANIFEST = 'chat-shapes.json'
REAL_SCRIPT = 'real-chat-fixture.py'
REAL_MANIFEST = 'chat-real.json'
REAL_SESSION = 'live-real-2781'
LIVE_PREFIX = 'live-'


def shapes(home, names):
    script = home / SHAPES_SCRIPT
    assert script.is_file(), 'SEED_SHAPES_MISSING'
    manifest = json.loads(subprocess.check_output([sys.executable, '-B', str(script), 'check', '--home', str(home)], text=True))
    recorded = []
    for item in manifest['shapes']:
        assert item['session'] in names, 'SEED_SHAPES_SESSION_MISSING'
        option = subprocess.check_output(['tmux', 'show-options', '-t', item['session'], '-v', '@mp_host_sid'], text=True).strip()
        assert option == item['sid'], 'SEED_SHAPES_IDENTITY'
        recorded.append(dict(shape=item['shape'], session=item['session'], sid=item['sid'], native=item['native'],
                             latest=item['latest'], stale=item['stale'], transcript=item['transcript']))
    return recorded


def real_chat(home, names):
    script = home / REAL_SCRIPT
    assert script.is_file(), 'SEED_REAL_CHAT_MISSING'
    manifest = json.loads(subprocess.check_output([sys.executable, '-B', str(script), 'check', '--home', str(home)], text=True))
    assert manifest['session'] in names, 'SEED_REAL_CHAT_SESSION_MISSING'
    option = subprocess.check_output(['tmux', 'show-options', '-t', manifest['session'], '-v', '@mp_host_sid'], text=True).strip()
    assert option == manifest['chat'], 'SEED_REAL_CHAT_IDENTITY'
    rows = 0
    with Path(manifest['transcript']).open('rb') as stream:
        for _ in stream:
            rows += 1
    return dict(session=manifest['session'], chat=manifest['chat'], live=manifest['live'], bytes=Path(manifest['transcript']).stat().st_size,
                rows=rows, transcript=manifest['transcript'], markers=manifest['markers'], cache=manifest['cache'],
                init_sessions=manifest['init_sessions'], accepted=manifest['accepted'], delivered=manifest['delivered'])


def inventory(home):
    names = sorted(subprocess.check_output(['tmux', 'list-sessions', '-F', '#{session_name}'], text=True).splitlines())
    assert len(names) >= 40, 'SEED_INBOX_TOO_SMALL'
    assert set(BULK_NAMES + [LONG_SESSION]) <= set(names), 'SEED_SESSIONS_MISSING'
    live_shapes = shapes(home, names)
    real = real_chat(home, names)
    source = json.loads((home / 'long-chat-source.json').read_text())
    path = Path(source['path'])
    assert path == home / '.claude/projects' / str(home / 'long-chat-proof').replace('/', '-') / (LONG_SID + '.jsonl'), 'SEED_TRANSCRIPT_IDENTITY'
    size = path.stat().st_size
    with path.open('rb') as stream:
        rows = sum(1 for _ in stream)
    assert size > 18 * 1024 * 1024, 'SEED_TRANSCRIPT_TOO_SMALL'
    assert rows == 1882, 'SEED_TRANSCRIPT_ROWS'
    assert (source['bytes'], source['rows'], source['sid']) == (size, rows, LONG_SID), 'SEED_SOURCE_MISMATCH'
    return dict(sessions=len(names), bulk_sessions=len(BULK_NAMES), session_names=names, shapes=live_shapes, real_chat=real,
                transcript=dict(session=LONG_SESSION, bytes=size, rows=rows, sid=LONG_SID, path=str(path), latest=source['latest']))


def check(home, evidence):
    recorded = json.load(sys.stdin) if str(evidence) == '-' else json.loads(evidence.read_text())
    assert recorded == inventory(home), 'SEED_EVIDENCE_MISMATCH'
    return recorded


def target(evidence):
    counts = json.loads((evidence / 'seed-counts.json').read_text())
    pages = list(evidence.glob('*-seed-deep-before.xml'))
    if len(pages) != 1:
        raise ValueError('SEED_DEEP_SETUP_FAILED: expected one initial inbox dump')
    root = ET.parse(pages[0]).getroot()
    present = {value for node in root.iter('node') for value in
               (node.get('text', ''), node.get('content-desc', '').removeprefix('Robot '))}
    name = next((name for name in counts['session_names']
                 if name not in present and not name.startswith(LIVE_PREFIX)), None)
    if name is None:
        raise ValueError('SEED_DEEP_SETUP_FAILED: every seeded session is present in the initial dump; no off-screen target')
    return dict(name=name, description='Robot ' + name, reader_label='Message ' + name,
                ready_text=counts['transcript']['latest'] if name == counts['transcript']['session'] else 'VERIFY_READY_528',
                initial_xml=pages[0].name, initial_names=[name for name in counts['session_names'] if name in present])


if __name__ == '__main__':
    mode = sys.argv[1]
    assert mode in ('record', 'check', 'target')
    if mode == 'target':
        counts = target(Path(sys.argv[2]))
    else:
        counts = inventory(Path.home()) if mode == 'record' else check(Path.home(), Path(sys.argv[2]))
    print(json.dumps(counts, indent=2))
