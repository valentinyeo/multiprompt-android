#!/usr/bin/env python3
import base64
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('HOME') == '/home/verifyssh' and os.getuid() != 0
home = Path('/home/verifyssh')
provider = sys.argv[2] if len(sys.argv) > 2 else 'claude'
assert provider in ('claude', 'codex', 'neighbor', 'codex-neighbor')
sid = {'claude': 'a1b2c3d4-1008-4000-8000-000000001008', 'codex': 'verify-codex', 'neighbor': 'a1b2c3d4-1008-4000-8000-000000001009', 'codex-neighbor': 'verify-codex-neighbor'}[provider]
wire_provider = {'neighbor': 'claude', 'codex-neighbor': 'codex'}.get(provider, provider)
tmux = 'codex-native' if wire_provider == 'codex' else 'verify-session'
dbpath = home / '.local/state/multiprompt/shared-messages/store.sqlite3'
if sys.argv[1] in ('desktop-submit', 'neighbor-submit', 'engine-info', 'bootstrap'):
    sources = {name: base64.b64encode((home / ('shared-' + name + '.py')).read_bytes()).decode('ascii') for name in ('claude', 'codex')}
    bundle = "CONNECTOR_SOURCES = {'claude':'%s','codex':'%s'}\n" % (sources['claude'], sources['codex'])
    bundle += (home / 'shared-reader.py').read_text()
    request = dict(id=1, op='message_submit', message_protocol=1, provider=wire_provider, sid=sid,
                   tmux=tmux, message_id='fixturedesktop000000000000000002' if provider == 'codex' else 'fixturedesktop000000000000000001', text='SHARED_CODEX_DESKTOP' if provider == 'codex' else 'SHARED_DESKTOP_SYNTHETIC')
    if sys.argv[1] == 'neighbor-submit':
        assert provider in ('neighbor', 'codex-neighbor')
        request.update(message_id='sentinelclaude000000000000000001' if provider == 'neighbor' else 'sentinelcodex0000000000000000002', text='NEIGHBOR_SENTINEL_' + provider)
    if sys.argv[1] in ('engine-info', 'bootstrap'):
        request = dict(id=1, op='engine_info')
    requests = [request]
    if sys.argv[1] == 'bootstrap':
        requests.append(dict(id=2, op='message_snapshot', message_protocol=1, provider=wire_provider, sid=sid, tmux=tmux, cursor=0))
    bootstrap = 'import base64,sys;exec(base64.b64decode(sys.stdin.buffer.readline()))'
    process = subprocess.Popen(['python3', '-u', '-c', bootstrap, '--serve'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    encoded = base64.b64encode(bundle.encode()).decode('ascii')
    reply, _ = process.communicate(encoded + '\n' + ''.join(json.dumps(q) + '\n' for q in requests), timeout=30)
    packets = [json.loads(line) for line in reply.splitlines()]
    assert len(packets) == len(requests)
    packet = packets[0]
    assert process.returncode == 0 and packet['id'] == 1
    if sys.argv[1] in ('engine-info', 'bootstrap'):
        info = packet['engine_info']
        assert 1 in packet['message_protocols']
        if sys.argv[1] == 'bootstrap':
            assert info['generation'] == 22
        else:
            assert info['generation'] >= 22
        installed = home / '.multiprompt-runtime' / (info['sha256'] + '.py')
        assert hashlib.sha256(installed.read_bytes()).hexdigest() == info['sha256']
        evidence = dict(engine_info=info, message_protocols=packet['message_protocols'], bundle_sha256=hashlib.sha256(bundle.encode()).hexdigest(), bundle_bytes=len(bundle.encode()), asset_sha256={name: hashlib.sha256((home / ('shared-' + name + '.py')).read_bytes()).hexdigest() for name in ('reader', 'claude', 'codex')})
        if sys.argv[1] == 'bootstrap':
            snapshot = packets[1]
            assert snapshot['id'] == 2 and snapshot['message_protocol'] == 1
            assert snapshot['result']['next_cursor'] is None and snapshot['result']['items'] == []
            evidence['snapshot'] = snapshot['result']
        print(json.dumps(evidence))
    else:
        assert packet['message_protocol'] == 1 and packet['result']['message']['id'] == request['message_id']
        print(json.dumps(packet))
else:
    with sqlite3.connect(f'file:{dbpath}?mode=ro', uri=True) as db:
        chat = db.execute('SELECT epoch,seq FROM message_chats WHERE provider=? AND sid=?', (wire_provider, sid)).fetchone()
        rows = db.execute('SELECT id,attempt,state,epoch,text FROM shared_messages WHERE provider=? AND sid=? ORDER BY position', (wire_provider, sid)).fetchall()
    assert chat is not None
    matching = [dict(id=r[0], attempt=r[1], state=r[2], epoch=r[3], body=r[4]) for r in rows]
    print(json.dumps(dict(provider=wire_provider, sid=sid, epoch=chat[0], seq=chat[1], rows=matching)))
