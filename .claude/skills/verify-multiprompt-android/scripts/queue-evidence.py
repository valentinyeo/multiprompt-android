#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys

if os.environ.get('GITHUB_ACTIONS') != 'true':
    raise SystemExit('queue evidence requires the hosted fixture')

groups = {'waiting': ('WAITING_ONE', 'WAITING_TWO'), 'uncertain': ('UNCERTAIN_ONE', 'UNCERTAIN_TWO'), 'followup': ('FOLLOWUP',), 'all': ('WAITING_ONE', 'WAITING_TWO', 'FOLLOWUP', 'UNCERTAIN_ONE', 'UNCERTAIN_TWO')}
if len(sys.argv) < 3 or sys.argv[1] not in groups:
    raise SystemExit('expected fixture group and states')
names = groups[sys.argv[1]]
states = sys.argv[2:]
if len(states) != len(names) or any(state not in ('queued', 'submitted', 'delivered') for state in states):
    raise SystemExit('invalid expected states')
sid = 'a1b2c3d4-1008-4000-8000-000000001008'
path = Path('/home/verifyssh/.local/state/multiprompt/held-messages') / hashlib.sha256(b'verify-session').hexdigest() / 'queue.sqlite3'
try:
    db = sqlite3.connect(f'file:{path}?mode=ro', uri=True)
    db.execute('PRAGMA query_only=ON')
    db.execute('BEGIN')
    placeholders = ','.join('?' for _ in names)
    rows = db.execute(f"SELECT key,text,state,source_inode,origin,scan,created FROM messages WHERE sid=? AND text IN ({placeholders}) ORDER BY created", (sid, *names)).fetchall()
    assert len(rows) == len(names) and [row[1] for row in rows] == list(names)
    by_name = {row[1]: row for row in rows}
    assert set(by_name) == set(names)
    result = []
    for name, state in zip(names, states):
        key, text, actual, inode, origin, scan, created = by_name[name]
        assert key and actual == state
        claims = db.execute('SELECT inode,offset,digest FROM held_receipts WHERE sid=? AND key=? ORDER BY offset', (sid, key)).fetchall()
        assert len(claims) == (1 if state == 'delivered' else 0)
        result.append(dict(id=key, text=text, state=actual, claims=len(claims),
                           receipt_claims=[dict(inode=i, offset=o, digest=d) for i, o, d in claims],
                           inode=inode, origin=origin, scan=scan, created=created))
    assert len({row['id'] for row in result}) == len(result)
    if sys.argv[1] == 'all':
        claims = db.execute('SELECT key,inode,offset FROM held_receipts WHERE sid=?', (sid,)).fetchall()
        assert len(claims) == sum(row['claims'] for row in result) == 3
        assert {claim[0] for claim in claims} == {row['id'] for row in result if row['state'] == 'delivered'}
        assert len({(claim[1], claim[2]) for claim in claims}) == len(claims)
    print(json.dumps(result))
except (sqlite3.Error, AssertionError) as exc:
    print(f'queue evidence not ready: {exc}', file=sys.stderr)
    sys.exit(1)
