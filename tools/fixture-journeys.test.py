import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / '.claude/skills/verify-multiprompt-android/scripts'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


runtime = load('shared_fixture_runtime', ROOT / 'tools/shared-fixture-runtime.py')
queue = load('queue_evidence', SCRIPTS / 'queue-evidence.py')
ui = load('queue_ui', SCRIPTS / 'queued.py')


class FixtureJourneyTest(unittest.TestCase):
    def test_real_generation_22_bundle_is_prepared_without_relabeling_current_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary)
            runtime.prepare(destination)
            self.assertIn('RUNTIME_GENERATION = 22', (destination / 'shared-reader.py').read_text())
            self.assertEqual(3, len(list(destination.iterdir())))
            with self.assertRaisesRegex(ValueError, 'SHARED_FIXTURE_RUNTIME_MISMATCH'):
                runtime.prepare(destination / 'wrong', revision='97a1e1e5a5b941d419ce3efc7d58d55be837c69d')

    def store(self, home):
        path = home / '.local/state/multiprompt/shared-messages/store.sqlite3'
        path.parent.mkdir(parents=True)
        db = sqlite3.connect(path)
        db.executescript('CREATE TABLE shared_messages(id TEXT,provider TEXT,sid TEXT,text TEXT,state TEXT,attempt TEXT,position INT); CREATE TABLE connector_facts(provider TEXT,sid TEXT,body TEXT);')
        for position, text in enumerate(('WAITING_ONE', 'WAITING_TWO'), 1):
            db.execute('INSERT INTO shared_messages VALUES (?,?,?,?,?,?,?)', (f'phone-{position}', 'claude', queue.SID, text, 'queued', f'attempt-{position}', position))
        db.commit()
        return db

    def consume(self, home, db, position):
        text = ('WAITING_ONE', 'WAITING_TWO')[position - 1]
        transcript = home / '.claude/projects' / str(home / 'queued-work').replace('/', '-') / (queue.SID + '.jsonl')
        transcript.parent.mkdir(parents=True, exist_ok=True)
        start = transcript.stat().st_size if transcript.exists() else 0
        raw = (json.dumps(dict(type='attachment', attachment=dict(type='queued_command', prompt=text + '\n[mp:0123456789abcdef]'))) + '\n').encode()
        with transcript.open('ab') as output:
            output.write(raw)
        for kind in ('accepted', 'started'):
            fact = dict(kind=kind, id=f'phone-{position}', attempt=f'attempt-{position}', sid=queue.SID,
                        offset=start, end=start + len(raw), source='attachment.queued_command', event_id=f'fact-{position}-{kind}')
            db.execute('INSERT INTO connector_facts VALUES (?,?,?)', ('claude', queue.SID, json.dumps(fact)))
        db.execute("UPDATE shared_messages SET state='started' WHERE id=?", (f'phone-{position}',))
        db.commit()

    def test_current_protocol_queue_becomes_started_only_with_correlated_receipts(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            with self.store(home) as db:
                queued = queue.read_state(home, 'waiting', ['queued', 'queued'])
                self.assertEqual(['phone-1', 'phone-2'], [row['id'] for row in queued])
                db.execute("UPDATE shared_messages SET state='sent' WHERE id='phone-1'")
                db.commit()
                self.assertEqual(0, queue.read_state(home, 'waiting', ['sent', 'queued'])[0]['claims'])
                self.consume(home, db, 1)
                started = queue.read_state(home, 'waiting', ['started', 'queued'])
                self.assertEqual(1, started[0]['claims'])
                self.assertEqual(queued[0]['attempt'], started[0]['attempt'])
                db.execute("UPDATE shared_messages SET state='started' WHERE id='phone-2'")
                db.commit()
                with self.assertRaisesRegex(AssertionError, 'receipt count mismatch'):
                    queue.read_state(home, 'waiting', ['started', 'started'])

    def test_wrong_attempt_receipt_cannot_confirm_a_queued_message(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            with self.store(home) as db:
                self.consume(home, db, 1)
                db.execute("UPDATE connector_facts SET body=replace(body,'attempt-1','other-attempt')")
                db.commit()
                with self.assertRaisesRegex(AssertionError, 'receipt count mismatch'):
                    queue.read_state(home, 'waiting', ['started', 'queued'])

    def test_ui_requires_queued_host_state_then_exactly_one_phone_bubble(self):
        root = ET.fromstring('<hierarchy><node text="WAITING_ONE"/><node text="WAITING_TWO"/></hierarchy>')
        evidence = [dict(id=f'phone-{n}', text=text, state='queued') for n, text in enumerate(('WAITING_ONE', 'WAITING_TWO'), 1)]
        self.assertEqual(2, ui.verify(root, 2, 'QUEUE_WAITING', evidence)['queued'])
        evidence[0]['state'] = 'started'
        with self.assertRaisesRegex(AssertionError, 'queue state mismatch'):
            ui.verify(root, 2, 'QUEUE_WAITING', evidence)
        evidence[0]['state'] = 'queued'
        ET.SubElement(root, 'node', text='WAITING_ONE')
        with self.assertRaisesRegex(AssertionError, 'exactly once'):
            ui.verify(root, 2, 'QUEUE_WAITING', evidence)


if __name__ == '__main__':
    unittest.main()
