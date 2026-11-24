#!/usr/bin/env python3
import json
import os
import sqlite3
import sys
from pathlib import Path

LEAD = 'Valentin mentioned you in '
STORE_PATH = os.environ.get('MP_MENTION_STORE') or '/home/verifyssh/.local/state/multiprompt/shared-messages/store.sqlite3'
STORE = Path(STORE_PATH)

provider, sid, visible = sys.argv[1], sys.argv[2], sys.argv[3]
expected = LEAD + 'verify-session (FYI only; read along, do not act unless asked). Context:\n\n' + visible
if not STORE.exists():
    raise SystemExit('no shared message store yet')

with sqlite3.connect('file:' + str(STORE) + '?mode=ro', uri=True) as db:
    rows = db.execute(
        'SELECT id, provider, sid, state, text FROM shared_messages WHERE text LIKE ? ORDER BY position',
        (LEAD + '%',),
    ).fetchall()

copies = [dict(id=row[0], provider=row[1], sid=row[2], state=row[3], text=row[4]) for row in rows]
report = dict(store=str(STORE), provider=provider, sid=sid, visible=visible, expected=expected, copies=copies)
print(json.dumps(report, separators=(',', ':')))
if not any(copy['text'] == expected and copy['provider'] == provider and copy['sid'] == sid for copy in copies):
    raise SystemExit(1)
