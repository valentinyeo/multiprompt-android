import importlib.util
import json
from pathlib import Path
import sys
import tempfile

spec = importlib.util.spec_from_file_location('reader', sys.argv[1])
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)

with tempfile.TemporaryDirectory() as temporary:
    reader.HOME = Path(temporary)
    sid = 'switched-pi-2569'
    session = dict(state=dict(display_history=True), turn=None)
    path = reader.host_paths('pi', sid)['history']
    path.parent.mkdir(parents=True, exist_ok=True)
    db = reader.host_db('pi', sid, 'verify-session', session)
    stages = []

    def capture():
        rows = []
        offset = 0
        for line in path.read_bytes().splitlines(keepends=True):
            for row in reader.conversation_rows(json.loads(line), 'pi', offset):
                rows.append(dict(row, offset=offset, end=offset + len(line)))
            offset += len(line)
        events = [json.loads(raw) for raw, in db.execute('SELECT body FROM message_events ORDER BY seq')]
        stages.append(dict(rows=rows, events=events))

    for turn in ('first', 'second', 'after-restart'):
        session['turn'] = turn
        reader.host_history_append('pi', sid, session, dict(role='user', id='phone-' + turn, text='phone-' + turn))
        reader.host_event('pi', sid, 'verify-session', session, 'turn.started', dict(message_id='phone-' + turn))
        reader.host_event('pi', sid, 'verify-session', session, 'text.delta', dict(text='PI_REPLY_2569'), 'pi-1:text:0')
        capture()
        reader.host_event('pi', sid, 'verify-session', session, 'text.done', dict(text='PI_REPLY_2569'), 'pi-1:text:0')
        reader.host_event('pi', sid, 'verify-session', session, 'turn.ended')
        capture()
    db.close()
    print(json.dumps(stages))
