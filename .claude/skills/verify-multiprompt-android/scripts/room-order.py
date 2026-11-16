#!/usr/bin/env python3
import hashlib
import json
import os
import re
import sys
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

ACCOUNT_ID = 'verify-rooms'
HEADER = 'Room messages since your last turn, oldest first:'
PACKAGE = 'dev.multiprompt.companion'
ROOT = Path(__file__).resolve().parents[4]
ASSETS = ROOT / 'app/src/main/assets/rooms'
RUNTIME_SOURCE = Path(__file__).with_name('room-runtime.py')


def account_key():
    return hashlib.sha256(ACCOUNT_ID.encode()).hexdigest()


def room_directory():
    return Path.home() / '.local/state/multiprompt/rooms-accounts' / account_key()


def write_private(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'wb') as handle:
        handle.write(data)
    os.chmod(path, 0o600)


def install():
    runtime = Path.home() / '.multiprompt-runtime'
    runtime.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(runtime, 0o700)
    source = RUNTIME_SOURCE.read_bytes()
    digest = hashlib.sha256(source).hexdigest()
    write_private(runtime / (digest + '.py'), source)
    write_private(runtime / 'reader.py', source)
    active = {'protocol': 1, 'generation': 1, 'sha256': digest}
    write_private(runtime / 'active.json', json.dumps(active, separators=(',', ':')).encode())
    submits = Path.home() / 'room-submits.jsonl'
    if not submits.exists():
        write_private(submits, b'')
    sys.path.insert(0, str(ASSETS))
    import room_bridge
    bridge = room_bridge.RoomBridge(str(room_directory()))
    try:
        host = bridge.execute({'op': 'capabilities'})['host_uuid']
        listed = bridge.execute({'op': 'list', 'host_uuid': host, 'after': 0, 'through': None, 'limit': 20})
        if any(room['name'] == 'Order room' for room in listed['rooms']):
            print('ROOM_READY')
            return
        members = [{
            'alias': alias,
            'display_name': alias.title(),
            'host_uuid': host,
            'provider': 'claude',
            'provider_sid': alias,
            'session_identity': 'claude:' + alias,
        } for alias in ('alpha', 'beta')]
        bridge.execute({
            'op': 'create',
            'host_uuid': host,
            'command_id': str(uuid.uuid4()),
            'name': 'Order room',
            'members': members,
        })
    finally:
        bridge.close()
    print('ROOM_READY')


def package_nodes(path):
    root = ET.parse(path).getroot()
    return [node for node in root.iter('node') if (node.get('package') or '').startswith(PACKAGE)]


def bounds(node):
    match = re.fullmatch(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', node.get('bounds', ''))
    if not match:
        raise SystemExit('room bounds missing')
    rect = tuple(int(part) for part in match.groups())
    if rect[0] >= rect[2] or rect[1] >= rect[3]:
        raise SystemExit('room bounds empty')
    return rect


def exact(found, text):
    return [node for node in found if node.get('text') == text]


def top(found):
    return min(bounds(node)[1] for node in found)


def nothing_sent(path):
    file = Path(path)
    if not file.exists() or file.stat().st_size == 0:
        print('ROOM_NOTHING_SENT')
        return
    raise SystemExit('a room message was submitted')


def load_rows(path):
    file = Path(path)
    if not file.exists():
        return []
    return [json.loads(line) for line in file.read_text().splitlines() if line.strip()]


def alpha_only(path):
    if load_rows(path) != [{'sid': 'alpha', 'text': 'one'}]:
        raise SystemExit('expected only alpha to have the first message')
    print('ROOM_ALPHA_ONLY')


def first(path):
    found = package_nodes(path)
    if not exact(found, 'one'):
        raise SystemExit('first message is not visible')
    if exact(found, 'two') or any(HEADER in (node.get('text') or '') for node in found):
        raise SystemExit('later room text appeared too early')
    print('ROOM_FIRST_MESSAGE')


def stacked(path):
    found = package_nodes(path)
    ones, twos = exact(found, 'one'), exact(found, 'two')
    if not ones or not twos:
        raise SystemExit('both room messages are not visible')
    if top(ones) >= top(twos):
        raise SystemExit('first message is not above the second')
    print('ROOM_MESSAGES_STACKED')


def shown(path, earlier=None):
    found = package_nodes(path)
    contexts = [node for node in found if HEADER in (node.get('text') or '')]
    twos = exact(found, 'two')
    if len(contexts) != 1 or not twos:
        raise SystemExit('ordered room reply is not on screen')
    text = contexts[0].get('text') or ''
    if 'alpha: one' not in text or not text.rstrip().endswith('two'):
        raise SystemExit('room reply is missing the earlier answer')
    if top(twos) >= bounds(contexts[0])[1]:
        raise SystemExit('new message is not above its reply')
    ones = exact(found, 'one')
    if ones:
        if top(ones) >= top(twos):
            raise SystemExit('messages are not oldest first')
    elif earlier:
        stacked(earlier)
    else:
        raise SystemExit('first message is not on screen')
    print('ROOM_ORDER_SHOWN')


def submitted(path):
    rows = load_rows(path)
    alpha = [row['text'] for row in rows if row.get('sid') == 'alpha']
    beta = [row['text'] for row in rows if row.get('sid') == 'beta']
    if not alpha or alpha[0] != 'one' or len(alpha) < 2 or not str(alpha[1]).endswith('two') or HEADER in str(alpha[1]):
        raise SystemExit('alpha did not receive messages in post order')
    second = str(beta[1]) if len(beta) == 2 else ''
    if len(beta) != 2 or beta[0] != 'one' or not second.startswith(HEADER) or 'alpha: one' not in second or not second.endswith('two'):
        raise SystemExit('beta did not keep the earlier message ahead of the new one')
    print('ROOM_ORDER_SUBMITTED')


def main():
    command = sys.argv[1]
    if command == 'account-id':
        print(ACCOUNT_ID)
        return
    if command == 'install':
        install()
        return
    if command == 'nothing-sent':
        nothing_sent(sys.argv[2])
        return
    if command == 'alpha-only':
        alpha_only(sys.argv[2])
        return
    if command == 'first':
        first(sys.argv[2])
        return
    if command == 'stacked':
        stacked(sys.argv[2])
        return
    if command == 'shown':
        shown(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
        return
    if command == 'submitted':
        submitted(sys.argv[2])
        return
    raise SystemExit('unknown room-order command')


if __name__ == '__main__':
    main()
