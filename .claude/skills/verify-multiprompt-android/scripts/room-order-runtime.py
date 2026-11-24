#!/usr/bin/env python3
import json
import sys
from pathlib import Path

STATE = Path.home() / '.multiprompt-runtime' / 'state.json'
SUBMITS = Path.home() / 'room-submits.jsonl'
AGENTS = {'alpha': 'tmux-alpha', 'beta': 'tmux-beta', 'gamma': 'tmux-gamma'}


def load():
    if not STATE.exists():
        return {'events': {}}
    return json.loads(STATE.read_text())


def save(state):
    STATE.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state, separators=(',', ':')))
    STATE.chmod(0o600)


def emit(payload):
    print(json.dumps(payload, ensure_ascii=True, separators=(',', ':')))


def status(sid):
    if sid not in AGENTS:
        raise SystemExit(1)
    emit({
        'status': 'ok',
        'provider': 'claude',
        'sid': sid,
        'enabled': True,
        'tmux': AGENTS[sid],
    })


def append_reply(events, sid, message_id, scope, text):
    seq = events[-1]['seq'] if events else 0
    turn = 'turn-' + message_id
    item = 'item-' + message_id
    for kind, payload in (
        ('turn.started', {'message_id': message_id}),
        ('text.done', {'text': text}),
        ('turn.ended', {'status': 'completed'}),
    ):
        seq += 1
        events.append({
            'seq': seq,
            'event': {
                'sid': sid,
                'room_scope': scope,
                'message_id': message_id,
                'kind': kind,
                'turn': turn,
                'item': item,
                'payload': payload,
            },
        })


def record(sid, text):
    with SUBMITS.open('a', encoding='utf-8') as handle:
        handle.write(json.dumps({'sid': sid, 'text': text}, ensure_ascii=True, separators=(',', ':')) + '\n')
    SUBMITS.chmod(0o600)


def recorded_texts(sid):
    if not SUBMITS.exists():
        return []
    texts = []
    for line in SUBMITS.read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get('sid') == sid:
            texts.append(row.get('text'))
    return texts


def serve(query):
    if query.get('op') == 'engine_info':
        return {'id': 1, 'room_message_protocols': [1]}
    sid = query.get('sid')
    if sid not in AGENTS:
        raise SystemExit(1)
    state = load()
    events = state['events'].setdefault(sid, [])
    op = query.get('op')
    if op == 'message_status':
        return {'id': 1, 'result': {'seq': events[-1]['seq'] if events else 0}}
    if op == 'message_submit':
        text = query.get('text')
        message_id = query.get('message_id')
        if sid == 'beta' and not any(item == 'one' for item in recorded_texts('beta')):
            if text != 'one' or len(recorded_texts('alpha')) < 2:
                return {'id': 1, 'result': {'message': {'id': message_id}}}
        if not any(row['event'].get('message_id') == message_id for row in events):
            append_reply(events, sid, message_id, query.get('room_scope'), text)
            save(state)
            record(sid, text)
        return {'id': 1, 'result': {'message': {'id': message_id}}}
    if op == 'events_subscribe':
        after = query.get('after')
        if type(after) is not int:
            raise SystemExit(1)
        page = [row for row in events if row['seq'] > after]
        return {'id': 1, 'result': {'events': page, 'next_after': events[-1]['seq'] if events else after}}
    raise SystemExit(1)


def main():
    if '--serve' in sys.argv:
        emit(serve(json.loads(sys.stdin.readline())))
        return
    if len(sys.argv) == 5 and sys.argv[1:4] == ['host', 'status', 'claude']:
        status(sys.argv[4])
        return
    raise SystemExit(2)


if __name__ == '__main__':
    main()
