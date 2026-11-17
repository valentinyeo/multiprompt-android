import ast
import base64
from contextlib import closing, redirect_stdout
import io
import os
import pwd
import shlex
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
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


runtime = load('shared_fixture_runtime', ROOT / 'tools/shared-fixture-runtime.py')
queue = load('queue_evidence', SCRIPTS / 'queue-evidence.py')
ui = load('queue_ui', SCRIPTS / 'queued.py')


class FixtureJourneyTest(unittest.TestCase):
    def test_ai_switch_fixture_user_can_enable_the_initial_chat(self):
        fixture = load('ai_switch_fixture', SCRIPTS / 'ai-switch-fixture.py')
        branch = (SCRIPTS / 'fixture.sh').read_text().split('== ai-switch.md ]]; then', 1)[1].split('elif', 1)[0]
        setup = next(line.strip() for line in branch.splitlines() if '/.local/bin' in line and ('mkdir' in line or 'install -d' in line))
        user = pwd.getpwuid(os.getuid()).pw_name
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            try:
                command = setup.replace('/home/verifyssh', str(home)).replace('verifyssh', user)
                subprocess.run(shlex.split(command), check=True)
                self.assertTrue(os.access(home / '.local', os.W_OK), 'the fixture user must own the parent needed for host state')
                (home / 'ai-switch-reader.py').write_bytes((ROOT / 'app/src/main/assets/chat_history_reader.py').read_bytes())
                with patch.object(fixture, 'HOME', home), patch.object(fixture, 'CWD', home / 'ai-switch-work'), patch.object(fixture, 'boundary'), patch.object(sys, 'stdin', io.StringIO('')), patch.dict(os.environ, HOME=str(home), GITHUB_ACTIONS='true'), redirect_stdout(io.StringIO()) as output:
                    fixture.console()
                self.assertIn('AI_SWITCH_READY_2538', output.getvalue())
                configs = [json.loads(path.read_text()) for path in (home / '.local/state/multiprompt/agent-host').glob('*.config.json')]
                self.assertEqual(1, len(configs))
                self.assertEqual(('claude', fixture.SID, 'verify-session', True), tuple(configs[0][key] for key in ('provider', 'sid', 'tmux', 'enabled')))
            finally:
                os.chdir(previous)
                subprocess.run(['sudo', 'chown', '-R', f'{os.getuid()}:{os.getgid()}', str(home)], check=True)

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

    def test_fixture_uses_canonical_dispatch_and_connector_receipts(self):
        reader = load('fixture_canonical_reader', ROOT / 'app/src/main/assets/chat_history_reader.py')
        source = ast.parse((SCRIPTS / 'queued-console.py').read_text())
        functions = [node for node in ast.walk(source) if isinstance(node, ast.FunctionDef) and node.name in ('advance_queue', 'accept_input')]
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            transcript = home / '.claude/projects' / str(home / 'queued-work').replace('/', '-') / (queue.SID + '.jsonl')
            transcript.parent.mkdir(parents=True)
            transcript.write_text('')
            sources = {name: base64.b64encode((ROOT / 'app/src/main/assets/chat_connectors' / (name + '.py')).read_bytes()).decode() for name in ('claude', 'codex')}
            namespace = dict(reader=reader, SID=queue.SID, RECEIPTS=home / 'receipts.txt', draft='FIXTURE_OWNED_DRAFT', native=[], redraw=lambda: None, closing=closing)
            exec(compile(ast.Module(body=functions, type_ignores=[]), '<queued-fixture-controller>', 'exec'), namespace)
            with patch.object(reader, 'HOME', home), patch.object(reader, 'CONNECTOR_SOURCES', sources, create=True), patch.object(reader, 'resolve_tmux_agent', return_value=(queue.SID, transcript)), patch.object(reader, 'send_response_preserve_draft', namespace['accept_input']):
                request = dict(message_protocol=1, provider='claude', sid=queue.SID, tmux='verify-session')
                for n, text in enumerate(('WAITING_ONE', 'WAITING_TWO'), 1):
                    reader.message_store_request(dict(request, op='message_submit', message_id=f'message_fixture_{n:04d}', text=text))
                namespace['advance_queue']()
                self.assertEqual(['queued', 'queued'], [row['state'] for row in queue.read_state(home, 'waiting', ['queued', 'queued'])])
                self.assertFalse((home / 'receipts.txt').exists())
                namespace['draft'] = ''
                namespace['advance_queue']()
                queue.read_state(home, 'waiting', ['sent', 'queued'])
                payload = namespace['native'].pop(0)
                event = dict(type='attachment', uuid='native-1', sessionId=queue.SID, timestamp=datetime.now(timezone.utc).isoformat(), attachment=dict(type='queued_command', prompt=payload))
                transcript.write_text(json.dumps(event) + '\n')
                namespace['advance_queue']()
                state = queue.read_state(home, 'waiting', ['started', 'sent'])
                self.assertEqual(1, state[0]['claims'])
                self.assertEqual(['VERIFY_RECEIVED:WAITING_ONE', 'VERIFY_RECEIVED:WAITING_TWO'], (home / 'receipts.txt').read_text().splitlines())

    def test_focused_field_can_be_cleared_after_the_hint_disappears(self):
        with tempfile.TemporaryDirectory() as temporary:
            xml = Path(temporary) / 'current.xml'
            attributes = dict(text='SHARED_PHONE_SYNTH', **{'class': 'android.widget.EditText', 'package': 'dev.multiprompt.companion.debug', 'focused': 'true', 'enabled': 'true', 'bounds': '[179,872][1048,998]'})
            root = ET.Element('hierarchy')
            ET.SubElement(root, 'node', attributes)
            xml.write_text(ET.tostring(root, encoding='unicode'))
            result = subprocess.run([sys.executable, str(SCRIPTS / 'ui.py'), str(xml), 'clear-field', ''], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['123'] + ['67'] * len(attributes['text']), result.stdout.split())
            root[0].set('focused', 'false')
            xml.write_text(ET.tostring(root, encoding='unicode'))
            result = subprocess.run([sys.executable, str(SCRIPTS / 'ui.py'), str(xml), 'clear-field', ''], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)

    def test_answered_phase_requires_the_actual_visible_reply(self):
        root = ET.fromstring('<hierarchy><node text="WAITING_ONE"/><node text="WAITING_TWO"/></hierarchy>')
        evidence = [dict(id=f'phone-{n}', text=text, state='started') for n, text in enumerate(('WAITING_ONE', 'WAITING_TWO'), 1)]
        with self.assertRaisesRegex(AssertionError, 'provider answer must be visible'):
            ui.verify(root, 0, 'QUEUE_ANSWERED', evidence)
        ET.SubElement(root, 'node', text='Both messages received.')
        self.assertEqual('Both messages received.', ui.verify(root, 0, 'QUEUE_ANSWERED', evidence)['answer'])

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
