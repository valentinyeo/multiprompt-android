#!/usr/bin/env python3
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import sys

SID = 'a1b2c3d4-1008-4000-8000-000000001008'
GROUPS = {'waiting': ('WAITING_ONE', 'WAITING_TWO'), 'uncertain': ('UNCERTAIN_ONE', 'UNCERTAIN_TWO'), 'followup': ('FOLLOWUP',), 'all': ('WAITING_ONE', 'WAITING_TWO', 'FOLLOWUP', 'UNCERTAIN_ONE', 'UNCERTAIN_TWO')}


def read_state(home, group, states):
    names = GROUPS[group]
    if len(states) != len(names) or any(state not in ('queued', 'sent', 'started') for state in states):
        raise ValueError('expected one queued, sent or started state per fixture message')
    path = home / '.local/state/multiprompt/shared-messages/store.sqlite3'
    transcript = home / '.claude/projects' / str(home / 'queued-work').replace('/', '-') / (SID + '.jsonl')
    with closing(sqlite3.connect(f'file:{path}?mode=ro', uri=True)) as db:
        db.execute('PRAGMA query_only=ON')
        db.execute('BEGIN')
        rows = db.execute("SELECT id,text,state,attempt,position FROM shared_messages WHERE provider='claude' AND sid=? ORDER BY position", (SID,)).fetchall()
        rows = [row for row in rows if row[1] in names]
        assert [row[1] for row in rows] == list(names), f'expected {names}, found {[(row[1], row[2]) for row in rows]}'
        facts = [json.loads(row[0]) for row in db.execute("SELECT body FROM connector_facts WHERE provider='claude' AND sid=?", (SID,))]
    result = []
    for (identity, text, actual, attempt, position), expected in zip(rows, states):
        assert identity and attempt and actual == expected, f'{text}: expected {expected}, found {actual}'
        accepted = [fact for fact in facts if fact.get('kind') == 'accepted' and fact.get('id') == identity and fact.get('attempt') == attempt]
        started = [fact for fact in facts if fact.get('kind') == 'started' and fact.get('id') == identity and fact.get('attempt') == attempt]
        count = 1 if expected == 'started' else 0
        assert len(accepted) == len(started) == count, f'{text}: acceptance/start receipt count mismatch'
        claims = []
        for fact in started:
            assert fact['source'] == 'attachment.queued_command' and fact['sid'] == SID
            with transcript.open('rb') as source:
                source.seek(fact['offset'])
                raw = source.read(fact['end'] - fact['offset'])
            event = json.loads(raw)
            body = re.sub(r'\n\[mp:[0-9a-f]{16}\]\s*$', '', event['attachment']['prompt'])
            assert event['type'] == 'attachment' and not event.get('isSidechain') and body == text
            assert accepted[0]['offset'] == fact['offset'] and accepted[0]['end'] == fact['end']
            claims.append(dict(inode=transcript.stat().st_ino, offset=fact['offset'], digest=hashlib.sha256(raw).hexdigest(), event_id=fact['event_id']))
        result.append(dict(id=identity, attempt=attempt, text=text, state=actual, claims=count, receipt_claims=claims, position=position))
    assert len({row['id'] for row in result}) == len(result)
    if group == 'all':
        assert sum(row['claims'] for row in result) == 3
        assert len({(claim['inode'], claim['offset']) for row in result for claim in row['receipt_claims']}) == 3
    return result


if __name__ == '__main__':
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('queue evidence requires the hosted fixture')
    try:
        print(json.dumps(read_state(Path('/home/verifyssh'), sys.argv[1], sys.argv[2:])))
    except (sqlite3.Error, AssertionError, ValueError, KeyError, OSError) as error:
        sys.exit(f'queue evidence not ready: {error}')
