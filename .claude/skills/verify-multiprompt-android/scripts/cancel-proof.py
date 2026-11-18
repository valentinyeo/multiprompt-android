from contextlib import closing
import json
from pathlib import Path
import sqlite3
import sys
import xml.etree.ElementTree as ET

SID = 'c001c001-1010-4000-8000-000000001010'
BODIES = ('CANCEL_KEEP_ONE', 'CANCEL_TARGET_TWO', 'CANCEL_KEEP_THREE')
STATES = dict(waiting=('queued', 'queued', 'queued'), cancelled=('queued', 'cancelled', 'queued'), delivered=('started', 'cancelled', 'started'))


def host(home, phase):
    path = home / '.local/state/multiprompt/shared-messages/store.sqlite3'
    with closing(sqlite3.connect(f'file:{path}?mode=ro', uri=True)) as db:
        db.execute('PRAGMA query_only=ON')
        db.execute('BEGIN')
        rows = [dict(zip(('id', 'attempt', 'text', 'state'), row)) for row in db.execute("SELECT id,attempt,text,state FROM shared_messages WHERE provider='claude' AND sid=? ORDER BY position", (SID,))]
        facts = [json.loads(row[0]) for row in db.execute("SELECT body FROM connector_facts WHERE provider='claude' AND sid=?", (SID,))]
    assert [row['text'] for row in rows] == list(BODIES), rows
    assert [row['state'] for row in rows] == list(STATES[phase]), rows
    assert len({row['id'] for row in rows}) == 3
    transcript = home / '.claude/projects' / str(home / 'cancel-work').replace('/', '-') / (SID + '.jsonl')
    for row in rows:
        accepted = [fact for fact in facts if fact.get('kind') == 'accepted' and fact.get('id') == row['id'] and fact.get('attempt') == row['attempt']]
        started = [fact for fact in facts if fact.get('kind') == 'started' and fact.get('id') == row['id'] and fact.get('attempt') == row['attempt']]
        assert len(accepted) == len(started) == (1 if row['state'] == 'started' else 0), row
        for fact in started:
            assert fact['sid'] == SID and fact['source'] == 'attachment.queued_command', fact
            with transcript.open('rb') as source:
                source.seek(fact['offset'])
                event = json.loads(source.read(fact['end'] - fact['offset']))
            assert event['attachment']['prompt'].split('\n[mp:', 1)[0] == row['text'], event
        row['receipts'] = started
    receipts = (home / 'receipts.txt').read_text().splitlines()
    expected = ['VERIFY_RECEIVED:' + text for text in (BODIES[0], BODIES[2])] if phase == 'delivered' else []
    assert receipts == expected, receipts
    return dict(phase=phase, rows=rows, providerReceipts=receipts)


def ui(root, proof):
    texts = [node.get('text') for node in root.iter('node')]
    assert not any(label in texts for label in ('Queued', 'Sending', 'Delivered', 'Not sent', 'Cancelled')), texts
    for row in proof['rows']:
        expected = 0 if row['state'] == 'cancelled' else 1
        assert texts.count(row['text']) == expected, row
    if proof['phase'] == 'delivered':
        for text in (BODIES[0], BODIES[2]):
            assert texts.count('Received ' + text) == 1, texts
    return proof


if __name__ == '__main__':
    if sys.argv[1] == 'host':
        print(json.dumps(host(Path('/home/verifyssh'), sys.argv[2])))
    else:
        proof = ui(ET.parse(sys.argv[2]).getroot(), json.loads(Path(sys.argv[3]).read_text()))
        print('CANCEL_' + proof['phase'].upper() + '_VERIFIED ' + json.dumps(proof))
