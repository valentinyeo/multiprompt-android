import json
import sys
import tempfile
import uuid
from unittest.mock import patch

sys.path.insert(0, sys.argv[1])
import room_bridge

SID = 'room-agent-sid'
log = []
submitted = []
state = {'enabled': True, 'protocols': [1]}


def uid():
    return str(uuid.uuid4())


def emit(event):
    log.append({'seq': len(log) + 1, 'event': event})


def runtime(query):
    op = query['op']
    if op == 'engine_info':
        return {'id': 1, 'room_message_protocols': state['protocols']}
    if op == 'message_status':
        return {'id': 1, 'result': {'seq': len(log)}}
    if op == 'message_submit':
        message = query['message_id']
        if message not in submitted:
            submitted.append(message)
            scope = query['room_scope']
            turn = 'turn-' + message
            emit({'sid': SID, 'room_scope': scope, 'message_id': message, 'kind': 'turn.started', 'turn': turn, 'payload': {'message_id': message}})
            emit({'sid': SID, 'kind': 'text.done', 'turn': turn, 'item': 'p', 'payload': {'text': 'PRIVATE SENTINEL'}})
            emit({'sid': SID, 'room_scope': scope, 'message_id': message, 'kind': 'text.done', 'turn': turn, 'item': 'a', 'payload': {'text': 'Answer to ' + query['text']}})
            emit({'sid': SID, 'room_scope': scope, 'message_id': message, 'kind': 'turn.ended', 'turn': turn, 'payload': {'status': 'completed'}})
        return {'id': 1, 'result': {'message': {'id': message}}}
    if op == 'events_subscribe':
        rows = [row for row in log if row['seq'] > query['after']]
        return {'id': 1, 'result': {'events': rows, 'next_after': len(log)}}
    raise AssertionError(op)


def status(sid):
    return {'status': 'ok', 'provider': 'claude', 'sid': sid, 'enabled': state['enabled'], 'tmux': 'room-agent'}


def replies(page):
    return [(event['parent_event_id'], event['text']) for event in page['events'] if event['type'] == 'agent_reply']


with tempfile.TemporaryDirectory() as rooms, patch.object(room_bridge, 'runtime_request', runtime), patch.object(room_bridge, 'host_status', status):
    state['protocols'] = None
    bridge = room_bridge.RoomBridge(rooms)
    assert not bridge.execute({'op': 'capabilities', 'room_messages': 1})['live_send']
    try:
        bridge.execute({'op': 'post_human'})
        raise AssertionError('an old runtime accepted a room message')
    except room_bridge.RoomError as error:
        assert error.code == 'unsupported_remote_capability'
    state['protocols'] = [1]
    host = bridge.execute({'op': 'capabilities'})['host_uuid']
    assert bridge.execute({'op': 'capabilities'}) == {'room_protocol': 1, 'host_uuid': host, 'store_only': True, 'live_send': False, 'reason': 'unsupported_remote_capability'}
    live = bridge.execute({'op': 'capabilities', 'room_messages': 1})
    assert live['live_send'] and live['room_reply_protocol'] == 1
    member = {'alias': 'alpha', 'display_name': 'Alpha', 'host_uuid': host, 'provider': 'claude', 'provider_sid': SID, 'session_identity': 'claude:' + SID}

    def create():
        return bridge.execute({'op': 'create', 'host_uuid': host, 'command_id': uid(), 'name': 'Room', 'members': [member]})['event']['room_id']

    room, other = create(), create()

    def post(text):
        return bridge.execute({'op': 'post_human', 'host_uuid': host, 'command_id': uid(), 'room_id': room, 'expected_revision': 1, 'text': text, 'recipients': []})

    first, second = post('first question'), post('second question')
    read = {'op': 'events', 'host_uuid': host, 'room_id': room, 'after': 0, 'limit': 200}
    page = bridge.execute(read)
    assert replies(page) == [(first['event']['id'], 'Answer to first question'), (second['event']['id'], 'Answer to second question')], replies(page)
    assert 'PRIVATE SENTINEL' not in json.dumps(page)
    assert not replies(bridge.execute(dict(read, room_id=other)))
    assert bridge.execute(read) == page
    bridge.close()
    bridge = room_bridge.RoomBridge(rooms)
    assert bridge.execute(read) == page

    state['enabled'] = False
    before = len(submitted)
    third = post('third question')
    waiting = bridge.execute(read)
    assert len(submitted) == before, 'an offline agent received a message'
    assert len(replies(waiting)) == 2
    state['enabled'] = True
    done = bridge.execute(read)
    assert replies(done)[2] == (third['event']['id'], 'Answer to third question'), replies(done)
    assert len(replies(bridge.execute(read))) == 3
    for refused in ('agent_reply', 'delivery_work'):
        try:
            bridge.execute({'op': refused, 'host_uuid': host})
            raise AssertionError(refused + ' was accepted from the phone')
        except room_bridge.RoomError as error:
            assert error.code == 'unsupported_remote_capability'
    bridge.close()
print('ok')
