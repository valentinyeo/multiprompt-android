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
import time
import unittest
import uuid
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
    def test_remote_control_fixture_requires_enable_before_phone_and_desktop_turns(self):
        fixture = load('remote_control_fixture', SCRIPTS / 'remote-control-fixture.py')
        control = dict(type='control_request', request_id='host-phone-2541', request=dict(subtype='remote_control', enabled=True))
        users = [dict(type='user', message=dict(content=[dict(type='text', text=text)])) for text in ('PHONE_REMOTE_CONTROL_2541', 'DESKTOP_REMOTE_CONTROL_2541')]
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            counter = home / 'desktop-counter.txt'
            for controls in ([], [dict(control, request=dict(subtype='remote_control', enabled=False))], [control]):
                counter.write_text('0\n')
                receipt = home / 'remote-control-receipts.jsonl'
                receipt.write_text('')
                with patch.object(fixture, 'HOME', home), patch.object(fixture, 'CWD', home), patch.object(fixture, 'boundary'), patch.object(fixture.Path, 'cwd', return_value=home), patch.object(sys, 'argv', ['claude', '--resume', fixture.SID]), patch.object(sys, 'stdin', io.StringIO(''.join(json.dumps(row) + '\n' for row in controls + users))), redirect_stdout(io.StringIO()) as output:
                    if controls != [control]:
                        with self.assertRaisesRegex(AssertionError, 'without enabling remote control'):
                            fixture.claude()
                        self.assertEqual('0\n', counter.read_text())
                        continue
                    fixture.claude()
                rows = [json.loads(line) for line in receipt.read_text().splitlines()]
                self.assertEqual(['remote_control', 'user', 'user'], [row['kind'] for row in rows])
                self.assertEqual(control, rows[0]['frame'])
                self.assertEqual([fixture.SID] * 3, [row['sid'] for row in rows])
                self.assertEqual(['PHONE_REMOTE_CONTROL_2541', 'DESKTOP_REMOTE_CONTROL_2541'], [row['text'] for row in rows[1:]])
                self.assertEqual('2\n', counter.read_text())
                events = [json.loads(line) for line in output.getvalue().splitlines()]
                replies = [event['message']['content'][0]['text'] for event in events if event['type'] == 'assistant']
                self.assertEqual(['REMOTE_CONTROL_CONNECTED_2541 desktop_counter=1', 'DESKTOP_REPLY_2541 desktop_counter=2'], replies)

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
                with patch.object(fixture, 'HOME', home), patch.object(fixture, 'CWD', home / 'ai-switch-work'), patch.object(fixture, 'boundary'), patch.object(fixture.os, 'execvp') as launch, patch.object(sys, 'stdin', io.StringIO('')), patch.dict(os.environ, HOME=str(home), GITHUB_ACTIONS='true'), redirect_stdout(io.StringIO()) as output:
                    fixture.console()
                self.assertIn('AI_SWITCH_READY_2538', output.getvalue())
                launch.assert_called_once_with('sleep', ['sleep', 'infinity'])
                configs = [json.loads(path.read_text()) for path in (home / '.local/state/multiprompt/agent-host').glob('*.config.json')]
                self.assertEqual(1, len(configs))
                self.assertEqual(('claude', fixture.SID, 'live-verify-session', True), tuple(configs[0][key] for key in ('provider', 'sid', 'tmux', 'enabled')))
                reader = load('ai_switch_reader', ROOT / 'app/src/main/assets/chat_history_reader.py')
                with patch.object(reader, 'HOME', home), patch.object(reader, 'host_owned', return_value=False), patch.object(reader, 'host_start', return_value=dict(status='started')):
                    switched = reader.host_switch_ai('claude', fixture.SID, 'pi')
                    self.assertEqual('switched', switched['status'], switched)
                    history = reader.host_paths('pi', switched['sid'])['history'].read_text()
                    self.assertIn('Switched AI: Claude to Pi', history)
                    self.assertIn('CLAUDE_BEFORE_SWITCH_2538', history)
            finally:
                os.chdir(previous)
                subprocess.run(['sudo', 'chown', '-R', f'{os.getuid()}:{os.getgid()}', str(home)], check=True)

    def test_ai_switch_bootstrap_leaves_a_native_sleeping_pane(self):
        socket = 'ai-switch-test-' + uuid.uuid4().hex
        tmux = ['tmux', '-L', socket]
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            (home / 'ai-switch-reader.py').write_bytes((ROOT / 'app/src/main/assets/chat_history_reader.py').read_bytes())
            program = f"import runpy;from pathlib import Path;n=runpy.run_path({str(SCRIPTS / 'ai-switch-fixture.py')!r});n['console'].__globals__.update(HOME=Path({str(home)!r}),CWD=Path({str(home / 'ai-switch-work')!r}),boundary=lambda:None);n['console']()"
            env = dict(os.environ, HOME=str(home), GITHUB_ACTIONS='true')
            env.pop('TMUX', None)
            launch = next(line for line in (SCRIPTS / 'fixture.sh').read_text().splitlines() if 'tmux new-session' in line and 'live-verify-session' in line)
            prefix = shlex.split(launch)[-1].split('/home/verifyssh/claude', 1)[0].replace('/home/verifyssh', str(home))
            command = prefix + shlex.join([sys.executable, '-c', program]) + ' 2>' + shlex.quote(str(home / 'startup.txt'))
            try:
                subprocess.run(tmux + ['new-session', '-d', '-s', 'live-verify-session', command], env=env, check=True)
                pane = ''
                for _ in range(30):
                    pane = subprocess.check_output(tmux + ['display-message', '-p', '-t', 'live-verify-session', '#{session_name}|#{pane_current_command}'], env=env, text=True).strip()
                    if pane == 'live-verify-session|sleep':
                        break
                    time.sleep(0.1)
                self.assertEqual('live-verify-session|sleep', pane)
            finally:
                subprocess.run(tmux + ['kill-server'], env=env, capture_output=True)

    def test_ai_switch_claude_process_stays_ready_for_host_controls(self):
        fixture = load('ai_switch_claude', SCRIPTS / 'ai-switch-fixture.py')
        request = dict(type='control_request', request_id='remote-control-2538', request=dict(subtype='remote_control', enabled=True))
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            with patch.object(fixture, 'HOME', home), patch.object(fixture, 'boundary'), patch.object(sys, 'stdin', io.StringIO(json.dumps(request) + '\n')), redirect_stdout(io.StringIO()) as output:
                fixture.claude()
            events = [json.loads(line) for line in output.getvalue().splitlines()]
            self.assertEqual(dict(type='system', subtype='init', session_id=fixture.SID, model='sonnet'), events[0])
            self.assertEqual(dict(type='control_response', response=dict(subtype='success', request_id='remote-control-2538', response={})), events[1])
            self.assertFalse((home / 'ai-switch-wrong-provider.txt').exists())

    def test_ai_switch_pi_receipts_and_replies_follow_the_received_messages(self):
        fixture = load('ai_switch_pi', SCRIPTS / 'ai-switch-fixture.py')
        sid = 'c001c001-2538-4000-8000-000000002539'
        texts = ['This chat ran on Claude until now and continues on Pi.\nCLAUDE_BEFORE_SWITCH_2538', 'PHONE_AFTER_SWITCH_2538']
        requests = [dict(id=f'prompt-{index}', type='prompt', message=text) for index, text in enumerate(texts)]
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            with patch.object(fixture, 'HOME', home), patch.object(fixture, 'boundary'), patch.object(sys, 'argv', ['pi', '--mode', 'rpc', '--session-id', sid]), patch.object(sys, 'stdin', io.StringIO(''.join(json.dumps(row) + '\n' for row in requests))), redirect_stdout(io.StringIO()) as output:
                fixture.pi()
            receipts = [json.loads(line) for line in (home / 'ai-switch-provider-receipts.jsonl').read_text().splitlines()]
            self.assertEqual([dict(provider='pi', sid=sid, id=row['id'], text=row['message']) for row in requests], receipts)
            events = [json.loads(line) for line in output.getvalue().splitlines()]
            replies = [event['assistantMessageEvent']['content'] for event in events if event.get('assistantMessageEvent', {}).get('type') == 'text_end']
            self.assertEqual(['PI_HANDOVER_READY_2538', 'PI_REPLY_PHONE_AFTER_SWITCH_2538'], replies)
            self.assertEqual(2, sum(event['type'] == 'agent_settled' for event in events))

    def test_ai_switch_holds_completion_until_the_phone_observes_the_stream(self):
        fixture = load('ai_switch_catchup', SCRIPTS / 'ai-switch-fixture.py')
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            hold = home / 'ai-switch-hold-2569'
            hold.touch()
            output = io.StringIO()
            def release(seconds):
                events = list(map(json.loads, output.getvalue().splitlines()))
                self.assertEqual('text_delta', events[-1]['assistantMessageEvent']['type'])
                hold.unlink()
            request = json.dumps(dict(id='phone-2569', type='prompt', message='PHONE_AFTER_SWITCH_2538')) + '\n'
            with patch.object(fixture, 'HOME', home), patch.object(fixture, 'boundary'), patch.object(fixture.time, 'sleep', side_effect=release), patch.object(sys, 'argv', ['pi', '--mode', 'rpc', '--session-id', 'switched-session']), patch.object(sys, 'stdin', io.StringIO(request)), redirect_stdout(output):
                fixture.pi()
            self.assertEqual('agent_settled', json.loads(output.getvalue().splitlines()[-1])['type'])

    def test_ai_switch_pi_keeps_a_reply_streaming_before_the_followup(self):
        fixture = load('ai_switch_stream', SCRIPTS / 'ai-switch-fixture.py')
        requests = [dict(id=f'prompt-{n}', type='prompt', message=text) for n, text in enumerate(('PHONE_STREAM_2569', 'PHONE_DURING_STREAM_2569'))]
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            with patch.object(fixture, 'HOME', home), patch.object(fixture, 'boundary'), patch.object(fixture.time, 'sleep') as delay, patch.object(sys, 'argv', ['pi', '--mode', 'rpc', '--session-id', 'second-session']), patch.object(sys, 'stdin', io.StringIO(''.join(json.dumps(row) + '\n' for row in requests))), redirect_stdout(io.StringIO()) as output:
                fixture.pi()
            delay.assert_called_once_with(20)
            replies = [event['assistantMessageEvent']['content'] for event in map(json.loads, output.getvalue().splitlines()) if event.get('assistantMessageEvent', {}).get('type') == 'text_end']
            self.assertEqual(['PI_REPLY_PHONE_STREAM_2569', 'PI_REPLY_PHONE_DURING_STREAM_2569'], replies)
            self.assertEqual(2, len((home / 'ai-switch-provider-receipts.jsonl').read_text().splitlines()))

    def test_ai_switch_back_to_claude_uses_the_new_session(self):
        fixture = load('ai_switch_reverse', SCRIPTS / 'ai-switch-fixture.py')
        texts = ['This chat ran on Pi until now and continues on Claude.', 'PHONE_SECOND_SWITCH_2569']
        requests = [dict(type='user', message=dict(content=text)) for text in texts]
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            with patch.object(fixture, 'HOME', home), patch.object(fixture, 'boundary'), patch.object(sys, 'argv', ['claude', '--session-id', 'reverse-session']), patch.object(sys, 'stdin', io.StringIO(''.join(json.dumps(row) + '\n' for row in requests))), redirect_stdout(io.StringIO()) as output:
                fixture.claude()
            receipts = [json.loads(line) for line in (home / 'ai-switch-provider-receipts.jsonl').read_text().splitlines()]
            self.assertEqual([dict(provider='claude', sid='reverse-session', text=text) for text in texts], receipts)
            events = list(map(json.loads, output.getvalue().splitlines()))
            self.assertEqual(['CLAUDE_HANDOVER_READY_2569', 'CLAUDE_REPLY_PHONE_SECOND_SWITCH_2569'], [event['message']['content'][0]['text'] for event in events if event['type'] == 'assistant'])
            self.assertFalse((home / 'ai-switch-wrong-provider.txt').exists())

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

    def test_focused_field_is_found_after_the_hint_disappears(self):
        with tempfile.TemporaryDirectory() as temporary:
            xml = Path(temporary) / 'current.xml'
            attributes = dict(text='SHARED_PHONE_SYNTH', **{'class': 'android.widget.EditText', 'package': 'dev.multiprompt.companion.debug', 'focused': 'true', 'enabled': 'true', 'bounds': '[179,872][1048,998]'})
            root = ET.Element('hierarchy')
            ET.SubElement(root, 'node', attributes)
            xml.write_text(ET.tostring(root, encoding='unicode'))
            result = subprocess.run([sys.executable, str(SCRIPTS / 'ui.py'), str(xml), 'focused-field', ''], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['613', '935'], result.stdout.split())
            root[0].set('focused', 'false')
            xml.write_text(ET.tostring(root, encoding='unicode'))
            result = subprocess.run([sys.executable, str(SCRIPTS / 'ui.py'), str(xml), 'focused-field', ''], capture_output=True, text=True)
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
