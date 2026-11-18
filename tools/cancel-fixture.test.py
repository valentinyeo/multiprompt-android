import base64
from contextlib import closing
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / '.claude/skills/verify-multiprompt-android/scripts'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CancelFixtureTest(unittest.TestCase):
    def test_canonical_cancel_keeps_draft_and_delivers_only_survivors(self):
        fixture = load('cancel_fixture', SCRIPTS / 'cancel-console.py')
        proof = load('cancel_proof', SCRIPTS / 'cancel-proof.py')
        reader = load('cancel_reader', ROOT / 'app/src/main/assets/chat_history_reader.py')
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            transcript = home / '.claude/projects' / str(home / 'cancel-work').replace('/', '-') / (fixture.SID + '.jsonl')
            transcript.parent.mkdir(parents=True)
            transcript.write_text('')
            receipts = home / 'receipts.txt'
            receipts.write_text('')
            ready = home / 'cancel-ready'
            fixture.reader = reader
            fixture.draft = fixture.DRAFT
            fixture.receipts = receipts
            sources = {name: base64.b64encode((ROOT / 'app/src/main/assets/chat_connectors' / (name + '.py')).read_bytes()).decode() for name in ('claude', 'codex')}
            with patch.object(fixture, 'TRANSCRIPT', transcript), patch.object(fixture, 'READY', ready), patch.object(reader, 'HOME', home), patch.object(reader, 'CONNECTOR_SOURCES', sources, create=True), patch.object(reader, 'resolve_tmux_agent', return_value=(fixture.SID, transcript)), patch.object(reader, 'send_response_preserve_draft', fixture.accept_input):
                request = dict(message_protocol=1, provider='claude', sid=fixture.SID, tmux='verify-session')
                rows = [reader.message_store_request(dict(request, op='message_submit', message_id=f'cancel_fixture_{n:04d}', text=text))['message'] for n, text in enumerate(proof.BODIES)]
                fixture.advance_queue()
                proof.host(home, 'waiting')
                target = rows[1]
                reader.message_store_request(dict(request, op='message_dismiss', message_id=target['id'], attempt=target['attempt'], epoch=target['epoch']))
                fixture.advance_queue()
                self.assertEqual(fixture.DRAFT, fixture.draft)
                proof.host(home, 'cancelled')
                ready.touch()
                fixture.draft = ''
                for _ in range(4):
                    fixture.advance_queue()
                result = proof.host(home, 'delivered')
                self.assertEqual(['started', 'cancelled', 'started'], [row['state'] for row in result['rows']])
                with closing(reader.message_store_db('verify-session')) as db, db:
                    db.execute("UPDATE shared_messages SET state='started' WHERE id=?", (target['id'],))
                with self.assertRaises(AssertionError):
                    proof.host(home, 'delivered')

    def test_ui_rejects_not_sent_missing_survivor_and_visible_cancelled_target(self):
        proof = load('cancel_ui', SCRIPTS / 'cancel-proof.py')
        state = dict(phase='cancelled', rows=[dict(text=text, state=state) for text, state in zip(proof.BODIES, proof.STATES['cancelled'])])
        root = ET.fromstring('<hierarchy><node text="CANCEL_KEEP_ONE"/><node text="CANCEL_KEEP_THREE"/></hierarchy>')
        proof.ui(root, state)
        for bad in ('Not sent', 'CANCEL_TARGET_TWO', 'CANCEL_KEEP_ONE'):
            node = ET.SubElement(root, 'node', text=bad)
            with self.assertRaises(AssertionError):
                proof.ui(root, state)
            root.remove(node)
        root.remove(root[0])
        with self.assertRaises(AssertionError):
            proof.ui(root, state)

    def test_last_day_merges_did_not_change_cancel_contract(self):
        revisions = ('eb266155', '5f96fe62', '96faea90', '33eb2ff6', 'd03c35ab', 'dd344ad0', '962eecad')
        paths = ('app/src/main/assets/chat_history_reader.py', 'app/src/main/assets/chat_connectors/claude.py',
                 'app/src/main/java/dev/multiprompt/companion/reader/ChatHistory.kt',
                 'app/src/main/java/dev/multiprompt/companion/reader/ChatHistoryConnection.kt',
                 'app/src/main/java/dev/multiprompt/companion/ui/ReplyableBubble.kt',
                 '.claude/skills/verify-multiprompt-android/scripts/cancel-console.py')
        for path in paths:
            blobs = [subprocess.check_output(['git', 'rev-parse', f'{revision}:{path}'], cwd=ROOT, text=True).strip() for revision in revisions]
            self.assertEqual(1, len(set(blobs)), path)
        blocks = []
        for revision in revisions:
            driver = subprocess.check_output(['git', 'show', revision + ':.claude/skills/verify-multiprompt-android/scripts/drive.sh'], cwd=ROOT, text=True)
            blocks.append(driver.split('if [[ "$feature" == chat-cancel.md ]]; then', 1)[1].split('\nfi', 1)[0])
        self.assertEqual(1, len(set(blocks)))


if __name__ == '__main__':
    unittest.main()
