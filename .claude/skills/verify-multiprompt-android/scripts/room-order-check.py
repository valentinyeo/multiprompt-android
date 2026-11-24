#!/usr/bin/env python3
import hashlib
import json
import os
import re
import shutil
import sys
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

ACCOUNT_ID = 'verify-rooms'
HEADER = 'Room messages since your last turn, oldest first:'
ROOM = 'Order room'
PLACEHOLDER = 'Message ' + ROOM
PACKAGE = os.environ.get('VERIFY_PACKAGE') or 'dev.multiprompt.companion'
RUNTIME_SOURCE = Path(__file__).with_name('room-order-runtime.py')
SUBMITS = Path.home() / 'room-submits.jsonl'


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


def install(assets):
    shutil.rmtree(room_directory(), ignore_errors=True)
    runtime = Path.home() / '.multiprompt-runtime'
    runtime.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(runtime, 0o700)
    source = RUNTIME_SOURCE.read_bytes()
    digest = hashlib.sha256(source).hexdigest()
    write_private(runtime / (digest + '.py'), source)
    write_private(runtime / 'reader.py', source)
    write_private(runtime / 'active.json', json.dumps({'protocol': 1, 'generation': 1, 'sha256': digest}, separators=(',', ':')).encode())
    SUBMITS.unlink(missing_ok=True)
    SUBMITS.write_bytes(b'')
    os.chmod(SUBMITS, 0o600)
    sys.path.insert(0, str(assets))
    import room_bridge
    bridge = room_bridge.RoomBridge(str(room_directory()))
    try:
        host = bridge.execute({'op': 'capabilities'})['host_uuid']
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
            'name': ROOM,
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


def top(found):
    return min(bounds(node)[1] for node in found)


def bubbles(found, text):
    return [node for node in found if node.get('text') == text and node.get('class') != 'android.widget.EditText']


def ip_family(ip):
    if re.fullmatch(r'(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}', ip):
        return 'v4'
    if re.fullmatch(r'(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}', ip) and re.search(r'[0-9A-Fa-f]', ip):
        return 'v6'
    raise SystemExit('account address is not an ip')


def block_lines(path):
    found = [line.strip() for line in Path(path).read_text().splitlines() if line.strip()]
    lines = [f'{ip_family(ip)} {ip}' for ip in sorted(set(found))]
    if not lines:
        raise SystemExit('account origin did not resolve')
    return '\n'.join(lines)


def composer(path):
    found = package_nodes(path)
    if not any(node.get('class') == 'android.widget.EditText' for node in found):
        raise SystemExit('room composer is missing')
    labeled = any(PLACEHOLDER in (node.get('text') or '') or PLACEHOLDER in (node.get('content-desc') or '') for node in found)
    in_room = any(node.get('text') == ROOM for node in found) and any(node.get('text') == 'Send' for node in found)
    if not labeled and not in_room:
        raise SystemExit('room composer is missing')
    print('ROOM_COMPOSER')


def holding(path, text):
    found = package_nodes(path)
    if any(node.get('class') == 'android.widget.EditText' and (node.get('text') or '') == text for node in found):
        print('ROOM_DRAFT_HELD')
        return
    raise SystemExit(1)


def load_rows(path):
    file = Path(path)
    if not file.exists():
        return []
    return [json.loads(line) for line in file.read_text().splitlines() if line.strip()]


def nothing_sent(path):
    if load_rows(path):
        raise SystemExit('a room message was submitted')
    print('ROOM_NOTHING_SENT')


def alpha_only(path):
    if load_rows(path) != [{'sid': 'alpha', 'text': 'one'}]:
        raise SystemExit('expected only alpha to have the first message')
    print('ROOM_ALPHA_ONLY')


def first(path):
    found = package_nodes(path)
    if not bubbles(found, 'one'):
        raise SystemExit('first message is not visible')
    if any(node.get('class') == 'android.widget.EditText' and (node.get('text') or '') == 'one' for node in found):
        raise SystemExit('first message is still in the composer')
    if bubbles(found, 'two') or any(HEADER in (node.get('text') or '') for node in found):
        raise SystemExit('later room text appeared too early')
    print('ROOM_FIRST_MESSAGE')


def stacked(path):
    found = package_nodes(path)
    ones, twos = bubbles(found, 'one'), bubbles(found, 'two')
    if not ones or not twos:
        raise SystemExit('both room messages are not visible')
    if top(ones) >= top(twos):
        raise SystemExit('first message is not above the second')
    print('ROOM_MESSAGES_STACKED')


def shown(path):
    found = package_nodes(path)
    contexts = [node for node in found if HEADER in (node.get('text') or '') and node.get('class') != 'android.widget.EditText']
    twos = bubbles(found, 'two')
    ones = bubbles(found, 'one')
    if len(contexts) != 1 or not twos:
        raise SystemExit('ordered room reply is not on screen')
    text = contexts[0].get('text') or ''
    if 'alpha: one' not in text or not text.rstrip().endswith('two'):
        raise SystemExit('room reply is missing the earlier answer')
    if top(twos) >= bounds(contexts[0])[1]:
        raise SystemExit('new message is not above its reply')
    if not ones or top(ones) >= top(twos):
        raise SystemExit('messages are not oldest first')
    print('ROOM_ORDER_SHOWN')


def submitted(path):
    rows = load_rows(path)
    alpha = [row['text'] for row in rows if row.get('sid') == 'alpha']
    beta = [row['text'] for row in rows if row.get('sid') == 'beta']
    if len(alpha) < 2 or alpha[0] != 'one' or HEADER in str(alpha[0]) or not str(alpha[1]).endswith('two') or HEADER in str(alpha[1]):
        raise SystemExit('alpha did not receive messages in post order')
    second = str(beta[1]) if len(beta) == 2 else ''
    if len(beta) != 2 or beta[0] != 'one' or not second.startswith(HEADER) or 'alpha: one' not in second or not second.endswith('two'):
        raise SystemExit('beta did not keep the earlier message ahead of the new one')
    print('ROOM_ORDER_SUBMITTED')


def main():
    command = sys.argv[1]
    if command == 'account-id':
        print(ACCOUNT_ID)
    elif command == 'install':
        install(Path(sys.argv[2]).resolve())
    elif command == 'block-rules':
        print(block_lines(sys.argv[2]))
    elif command == 'composer':
        composer(sys.argv[2])
    elif command == 'holding':
        holding(sys.argv[2], sys.argv[3])
    elif command == 'nothing-sent':
        nothing_sent(sys.argv[2])
    elif command == 'alpha-only':
        alpha_only(sys.argv[2])
    elif command == 'first':
        first(sys.argv[2])
    elif command == 'stacked':
        stacked(sys.argv[2])
    elif command == 'shown':
        shown(sys.argv[2])
    elif command == 'submitted':
        submitted(sys.argv[2])
    else:
        raise SystemExit('unknown room-order-check command')


if __name__ == '__main__':
    main()
