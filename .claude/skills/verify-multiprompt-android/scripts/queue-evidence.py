#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys

if os.environ.get('GITHUB_ACTIONS') != 'true':
    raise SystemExit('queue evidence requires the hosted fixture')

state = sys.argv[1:]
path = Path('/home/verifyssh/.local/state/multiprompt/held-messages') / hashlib.sha256(b'verify-session').hexdigest() / 'queue.sqlite3'
try:
    db = sqlite3.connect(f'file:{path}?mode=ro', uri=True)
    db.execute('PRAGMA query_only=ON')
    rows = db.execute("SELECT key,text,state FROM messages WHERE sid=? AND text IN ('WAITING_ONE','WAITING_TWO') ORDER BY created", ('a1b2c3d4-1008-4000-8000-000000001008',)).fetchall()
    assert len(rows) == 2 and [r[1] for r in rows] == ['WAITING_ONE', 'WAITING_TWO']
    assert len({r[0] for r in rows}) == 2 and all(r[0] for r in rows)
    assert [r[2] for r in rows] == state
    print(json.dumps([dict(id=k, text=t, state=s) for k, t, s in rows]))
except (sqlite3.Error, AssertionError) as exc:
    print(f'queue evidence not ready: {exc}', file=sys.stderr)
    sys.exit(1)
