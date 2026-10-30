import base64
import importlib.util
import hashlib
import json
import io
import os
import selectors
import shutil
import sqlite3
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('reader', ROOT / 'src/chat_history_reader.py')
reader = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reader)


class HistoryReaderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.addCleanup(patch.stopall)
        patch.object(reader, 'HOME', self.home).start()
        self.path = self.home / '.claude/projects/-work-demo/sid.jsonl'
        self.path.parent.mkdir(parents=True)
        self.query = dict(id=7, codex=False, sid='sid', tmux='term-1', cwd='/work/demo',
                          cached_sid='', inode=0, offset=0)

    def event(self, text='hello', **extra):
        return dict(type='assistant', message=dict(role='assistant', content=[dict(type='text', text=text)]), **extra)

    def write(self, *events):
        self.path.write_text(''.join(json.dumps(e) + '\n' for e in events))

    def read(self):
        wire = reader.response(self.query)
        lines = wire.splitlines()
        self.assertEqual(lines[-1], reader.END)
        fields = lines[0].split('\t')
        self.assertEqual(fields[:4], ['@mp', '7', 'history', 'ok'])
        if fields[13] != 'older':
            self.query.update(cached_sid=fields[4], inode=int(fields[7]), offset=int(fields[6]), mtime=int(fields[11]), probe=fields[12])
        self.query['start'] = int(fields[14])
        self.query.pop('before', None)
        return fields, [json.loads(line) for line in lines[1:-1]]

    def test_held_queue_rejects_unbound_workspace_neighbour(self):
        self.assertTrue(callable(getattr(reader, 'enqueue_response', None)), 'safe held-send boundary is missing')
        self.write(self.event('unrelated completed turn'))
        with patch.object(reader, 'subprocess') as processes, \
                patch.object(reader, 'process_table', return_value={99: (1, ['claude'])}), \
                patch.object(reader, 'read_process_cwd', return_value='/work/demo'), \
                patch.object(reader, 'open_claude_transcript', return_value=None), \
                patch.object(reader, 'held_queue_ready', return_value=True), \
                patch.object(reader, 'send_response') as send:
            processes.run.return_value = types.SimpleNamespace(returncode=0, stdout='99\n')
            reply = reader.enqueue_response(dict(id=1, tmux='term-1', text='must not reach a neighbour', queued_at=1, message_id='bound-message'))
        self.assertIn('\tsend\terror\tagent_unavailable\t', reply)
        send.assert_not_called()

    def test_igsh1063_only_structured_pending_questions(self):
        questions = [dict(question="Which colour?", header="Accent", multiSelect=False,
                          options=[dict(label="Lime", description="Bright contrast"),
                                   dict(label="Orange", description="Warm contrast")]),
                     dict(question="Which devices?", header="Devices", multiSelect=True,
                          options=[dict(label="Desktop", description="Windows"),
                                   dict(label="Phone", description="Android")])]
        prose = self.event("I need three answers from you:\n1. Figma team\n2. Figma login\n3. QA tickets")
        self.write(prose)
        self.assertFalse(any(row.get("question") for row in self.read()[1]))
        call = self.tool_call("AskUserQuestion", dict(questions=questions))
        self.write(prose, call)
        status = next((row for row in self.read()[1] if row["role"] == "status"), None)
        self.assertIsNotNone(status, "pending structured question must reach the client")
        self.assertEqual(status["question"], dict(id="call-263", questions=questions))
        self.assertEqual(next(row for row in self.read()[1] if row["role"] == "status")["question"], status["question"])
        with self.path.open("a") as target:
            target.write(json.dumps(self.tool_result("answered")) + "\n")
        self.assertIsNone(next(row for row in self.read()[1] if row["role"] == "status")["question"])

    def test_igsh1063_codex_structured_questions_complete(self):
        self.query["tmux"] = ""
        self.query["codex"] = True
        self.path = self.home / ".codex/sessions/rollout-2026-sid.jsonl"
        self.path.parent.mkdir(parents=True)
        question = dict(id="colour", question="Which colour?", header="Accent",
                        options=[dict(label="Lime", description="Bright contrast"),
                                 dict(label="Orange", description="Warm contrast")])
        self.write(dict(type="response_item", payload=dict(type="function_call", call_id="q1",
                   name="request_user_input", arguments=json.dumps(dict(questions=[question])))))
        status = next((row for row in self.read()[1] if row["role"] == "status"), None)
        self.assertIsNotNone(status, "pending Codex question must reach the client")
        self.assertEqual(status["question"]["id"], "q1")
        self.assertEqual(status["question"]["provider"], "codex")
        self.assertEqual(status["question"]["questions"], [question])
        with self.path.open("a") as target:
            target.write(json.dumps(dict(type="response_item", payload=dict(type="function_call_output", call_id="q1", output="done"))) + "\n")
        self.assertIsNone(next(row for row in self.read()[1] if row["role"] == "status")["question"])

    def test_igsh1063_answered_sidechain_and_embedded_question_text_not_pending(self):
        question = dict(question="Real question?", header="Pick", options=[dict(label="A"), dict(label="B")])
        self.write(self.tool_call("AskUserQuestion", dict(questions=[question])), self.tool_result("done"),
                   self.event('AskUserQuestion {"questions": []}'),
                   dict(self.tool_call("AskUserQuestion", dict(questions=[question])), isSidechain=True))
        self.assertFalse(any(row.get("question") for row in self.read()[1]))

    def test_igsh1063_question_keys_keep_order_and_permission_boundaries(self):
        for keys, expected in (([49], ["1"]), ([50], ["2"]), ([49, 50, 13], ["1", "2", "Enter"]), ([9], ["Up", "Tab"])):
            with patch.object(reader.subprocess, "run") as run:
                wire = reader.stop_response(dict(op="question", id=7, tmux="term-1", keys=keys))
            self.assertIn("\tquestion\tok\t", wire)
            self.assertEqual(run.call_args.args[0], ["tmux", "send-keys", "-t", "=term-1:", *expected])
        for keys in ([], [27], [10], [True], [49, 50, "Enter"], None):
            with patch.object(reader.subprocess, "run") as run:
                wire = reader.stop_response(dict(op="question", id=7, tmux="term-1", keys=keys))
            self.assertIn("\tquestion\terror\t", wire)
            run.assert_not_called()
        with patch.object(reader.subprocess, "run") as run:
            self.assertIn("\tpermission\terror\t", reader.stop_response(dict(op="permission", id=7, tmux="term-1", digit=13)))
        run.assert_not_called()

    def test_igsh1063_other_text_keeps_question_reply_operation(self):
        with patch.object(reader, "prompt_box_text", return_value=""), patch.object(reader, "clear_prompt_box", return_value=("", False)), patch.object(reader.time, "sleep"), patch.object(reader.subprocess, "run") as run:
            wire = reader.send_response(dict(op="question_text", id=7, tmux="term-1", text="Purple"))
        self.assertIn("\tquestion_text\tok\t", wire)
        self.assertEqual(run.call_args_list[0].args[0], ["tmux", "send-keys", "-t", "=term-1:", "-l", "--", "Purple"])
        self.assertEqual(run.call_args_list[1].args[0], ["tmux", "send-keys", "-t", "=term-1:", "Enter"])
    def test_igsh1229_live_native_processes_keep_separate_histories(self):
        root = self.home / '.codex/sessions/2026/10/30'
        root.mkdir(parents=True)
        binary = self.home / 'codex'
        binary.symlink_to(sys.executable)
        children, paths = [], []
        try:
            for sid in ('first', 'second'):
                path = root / ('rollout-2026-' + sid + '.jsonl')
                path.write_text(json.dumps(dict(type='session_meta', payload=dict(id=sid, cwd=str(self.home)))) + '\n' +
                                json.dumps(dict(type='response_item', payload=dict(type='message', role='assistant',
                                                content=[dict(type='output_text', text=sid + ' reply')]))) + '\n')
                child = subprocess.Popen([str(binary), '-c',
                    'import sys; f=open(sys.argv[1], "rb"); print("ready", flush=True); sys.stdin.read()', str(path)],
                    cwd=self.home, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
                children.append(child)
                paths.append(path)
                self.assertEqual(child.stdout.readline(), b'ready\n')
            table = {10: (1, ['bash']), children[0].pid: (10, [str(binary)]),
                     20: (1, ['bash']), children[1].pid: (20, [str(binary)])}
            def panes(command, **kwargs):
                return subprocess.CompletedProcess(command, 0, '10\n' if '=first-tab' in command else '20\n', '')
            with patch.object(reader, 'process_table', return_value=table), \
                    patch.object(reader.subprocess, 'run', side_effect=panes):
                for changed in (paths[1], paths[0], paths[1]):
                    with changed.open('a') as output:
                        output.write('\n')
                    for index, name in enumerate(('first-tab', 'second-tab')):
                        sid = ('first', 'second')[index]
                        wire = reader.response(dict(self.query, codex=True, sid='', tmux=name,
                                                    cwd=str(self.home), follow=True))
                        self.assertIn('\thistory\tok\t' + sid + '\t', wire)
                        self.assertIn(sid + ' reply', wire)
                        self.assertNotIn(('second', 'first')[index] + ' reply', wire)
        finally:
            for child in children:
                child.stdin.close()
                child.wait(timeout=5)
                child.stdout.close()

    def test_igsh1229_native_codex_same_cwd_uses_owned_fds(self):
        root = self.home / '.codex/sessions/2026/10/30'
        root.mkdir(parents=True)
        paths = []
        for sid, text in [('first', 'First session reply'), ('second', 'Second session reply')]:
            path = root / ('rollout-2026-' + sid + '.jsonl')
            path.write_text(json.dumps(dict(type='session_meta', payload=dict(id=sid, cwd='/shared'))) + '\n' +
                            json.dumps(dict(type='response_item', payload=dict(type='message', role='assistant',
                                            content=[dict(type='output_text', text=text)]))) + '\n')
            paths.append(path)
        fds = {}
        for pid, path in [(12, paths[0]), (22, paths[1])]:
            folder = self.home / ('fd-' + str(pid))
            folder.mkdir()
            (folder / '3').symlink_to(path)
            fds[Path('/proc') / str(pid) / 'fd'] = folder
        empty = self.home / 'fd-launcher'
        empty.mkdir()
        fds[Path('/proc/11/fd')] = empty
        original_iterdir = Path.iterdir
        def iterdir(path):
            return original_iterdir(fds.get(path, path))
        table = {10: (1, ['bash']), 11: (10, ['node', '/bin/codex.js']),
                 12: (11, ['/bin/codex']), 20: (1, ['bash']), 22: (20, ['/bin/codex'])}
        def panes(command, **kwargs):
            return subprocess.CompletedProcess(command, 0, '10\n' if '=first-tab' in command else '20\n', '')
        with patch.object(Path, 'iterdir', iterdir), patch.object(reader, 'process_table', return_value=table), \
                patch.object(reader.subprocess, 'run', side_effect=panes):
            for newer in (paths[0], paths[1], paths[0]):
                with newer.open('a') as output:
                    output.write('\n')
                for name, sid, expected, other in [('first-tab', 'first', 'First session reply', 'Second session reply'),
                                                   ('second-tab', 'second', 'Second session reply', 'First session reply')]:
                    query = dict(self.query, codex=True, sid='', tmux=name, cwd='/shared', follow=True)
                    wire = reader.response(query)
                    self.assertIn('\thistory\tok\t' + sid + '\t', wire)
                    self.assertIn(expected, wire)
                    self.assertNotIn(other, wire)
            for follow in (None, False):
                query = dict(self.query, codex=True, sid='first', cached_sid='first',
                             tmux='second-tab', cwd='/shared')
                if follow is not None:
                    query['follow'] = follow
                wire = reader.response(query)
                self.assertIn('\thistory\tok\tsecond\t', wire)
                self.assertIn('Second session reply', wire)
                self.assertNotIn('First session reply', wire)
                resolved = reader.resolve_response_path(query, reader.READER_SOURCE, True)
                incoming, writer = os.pipe()
                try:
                    with patch.object(reader.sys, 'stdin', types.SimpleNamespace(fileno=lambda: incoming)):
                        events = reader.watch_changes(query, resolved, reader.READER_SOURCE, True)
                        try:
                            initial = reader.response(query, reader.READER_SOURCE, True, next(events))
                            self.assertIn('\thistory\tok\tsecond\t', initial)
                            self.assertIn('Second session reply', initial)
                            self.assertNotIn('First session reply', initial)
                        finally:
                            events.close()
                finally:
                    os.close(incoming)
                    os.close(writer)
            archive = reader.response(dict(self.query, codex=True, sid='first', tmux='', cwd='/shared'))
            self.assertIn('\thistory\tok\tfirst\t', archive)
            self.assertIn('First session reply', archive)
            (fds[Path('/proc/12/fd')] / '4').symlink_to(paths[1])
            self.assertIsNone(reader.resolve_tmux_agent('codex', 'first-tab'))
            self.assertIn('history\tmissing', reader.response(dict(self.query, codex=True, sid='',
                                                                  tmux='first-tab', cwd='/shared', follow=True)))
            self.assertEqual(reader.resolve_tmux_agent('codex', 'second-tab'), ('second', paths[1]))
        self.assertIsNone(reader.agent_pid_for_panes([10], {**table, 13: (10, ['/bin/codex'])}, 'codex'))

    def test_IGSH_1266_codex_socket_bridge_rejects_shared_or_changed_sources(self):
        uid = os.getuid()
        table = {10: (1, ['codex']), 20: (1, ['codex', 'app-server', '--listen'])}
        daemon = '/tmp/codex-daemon-' + str(uid) + '/' + 'a' * 64
        links = {'11': ('22', '*'), '22': ('11', daemon), '25': ('26', '/unrelated'), '26': ('25', '*')}
        sockets = {10: {'11'}, 20: {'22', '25'}}
        actual_stat = Path.stat
        def stat(path, *args, **kwargs):
            if str(path) in ('/proc/10', '/proc/20', '/proc/30'):
                return types.SimpleNamespace(st_uid=uid)
            return actual_stat(path, *args, **kwargs)
        with patch.object(Path, 'stat', stat), patch.object(reader.os, 'readlink', return_value='/synthetic/codex'), \
                patch.object(reader, 'process_table', return_value=table), \
                patch.object(reader, 'codex_process_start', return_value='birth'), \
                patch.object(reader, 'codex_socket_fds', side_effect=lambda pid: sockets.get(pid)), \
                patch.object(reader, 'codex_unix_connections', return_value=links), \
                patch.object(reader, 'open_codex_transcript', return_value={('session', Path('/synthetic'))}):
            self.assertEqual(reader.codex_daemon_transcript(10), ('session', Path('/synthetic')))
            links['22'] = ('11', '/tmp/codex-daemon-' + str(uid))
            self.assertIsNone(reader.codex_daemon_transcript(10))
            links['22'] = ('11', daemon)
            table[30] = (1, ['codex'])
            sockets[20].add('24')
            sockets[30] = {'13'}
            links.update({'24': ('13', daemon), '13': ('24', '*')})
            self.assertIsNone(reader.codex_daemon_transcript(10))
            sockets[20].remove('24')
            del table[30]
            with patch.object(reader, 'open_codex_transcript', side_effect=[{('session', Path('/synthetic'))}, set()]):
                self.assertIsNone(reader.codex_daemon_transcript(10))
            with patch.object(reader, 'codex_unix_connections', return_value={'11': ('22', '*'), '22': ('99', daemon)}):
                self.assertIsNone(reader.codex_daemon_transcript(10))

    def test_IGSH_1266_codex_transcript_namespace_is_exact(self):
        wanted = self.home / 'native' / 'sessions'
        other = self.home / 'foreign' / 'sessions'
        wanted.mkdir(parents=True)
        other.mkdir(parents=True)
        for directory in (wanted, other):
            (directory / 'rollout-fixture-sid.jsonl').write_text(json.dumps(dict(type='session_meta', payload=dict(id='sid'))) + '\n')
        fds = self.home / 'native-fds'
        fds.mkdir()
        (fds / '3').symlink_to(other / 'rollout-fixture-sid.jsonl')
        original_iterdir = Path.iterdir
        def iterdir(path):
            return original_iterdir(fds if path == Path('/proc/10/fd') else path)
        with patch.object(Path, 'iterdir', iterdir):
            self.assertEqual(reader.open_codex_transcript(10, {10: (1, ['codex'])}, wanted.parent), set())
            self.assertEqual(reader.open_codex_transcript(10, {10: (1, ['codex'])}, other.parent), {('sid', (other / 'rollout-fixture-sid.jsonl').resolve())})

    def test_IGSH_1266_attachment_rejects_foreign_daemon_session(self):
        table = {10: (1, ['codex'])}
        with patch.object(reader.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '10\n', '')), \
                patch.object(reader, 'process_table', return_value=table), \
                patch.object(reader, 'agent_pid_for_panes', return_value=10), \
                patch.object(reader, 'codex_daemon_transcript', return_value=(('other', Path('/other')), 'unix:///fixture')):
            self.assertIsNone(reader.codex_queue_attachment('fixture', 'wanted', Path('/wanted')))

    def test_IGSH_1266_codex_ambiguous_fds_never_use_daemon(self):
        table = {10: (1, ['codex'])}
        with patch.object(reader, 'process_table', return_value=table), \
                patch.object(reader, 'open_hax_transcript', return_value=None), \
                patch.object(reader, 'read_process_cwd', return_value='/synthetic'), \
                patch.object(reader, 'open_codex_transcript', return_value={('a', Path('/a')), ('b', Path('/b'))}), \
                patch.object(reader, 'codex_daemon_transcript') as daemon:
            self.assertIsNone(reader.resolve_tmux_agent('codex', 'synthetic', pid=10))
            daemon.assert_not_called()

    def test_igsh1229_codex_watcher_never_switches_on_neighbour_write(self):
        root = self.home / '.codex/sessions'
        root.mkdir(parents=True)
        own = root / 'rollout-day-own.jsonl'
        other = root / 'rollout-day-other.jsonl'
        own.write_text('{}\n')
        other.write_text('{}\n')
        incoming, writer = os.pipe()
        self.addCleanup(os.close, incoming)
        self.addCleanup(os.close, writer)
        query = dict(self.query, codex=True, sid='own', follow=True)
        resolved = ('codex', 'own', own, None)
        stdin = types.SimpleNamespace(fileno=lambda: incoming)
        with patch.object(reader.sys, 'stdin', stdin), \
                patch.object(reader, 'resolve_response_path', return_value=resolved):
            events = reader.watch_changes(query, resolved, reader.READER_SOURCE, True)
            self.assertEqual(next(events), resolved)
            other.write_text('neighbour update\n')
            with own.open('a') as output:
                output.write('own update\n')
            self.assertEqual(next(events), resolved)
            events.close()

    def test_igsh1043_hax_only_processes_are_discovered_without_provider_children(self):
        table = {10: (1, ['bash']), 11: (10, ['/opt/bin/hax', '-p', 'neutral']),
                 20: (1, ['bash']), 21: (20, ['/opt/bin/hax', '--resume=neutral'])}
        self.assertEqual(reader.agent_pid_for_panes([10], table, 'claude'), 11)
        self.assertEqual(reader.agent_pid_for_panes([20], table, 'codex'), 21)
        table[12] = (11, ['codex'])
        self.assertEqual(reader.agent_pid_for_panes([10], table, 'claude'), 11)

    def test_igsh1043_native_rewrite_cannot_reuse_another_message_identity(self):
        def saved_message(text, offset):
            rows = reader.conversation_rows(dict(kind='assistant', text=text), 'claude', offset)
            self.assertEqual(len(rows), 1, 'Native saved reply must be readable')
            self.assertIn('id', rows[0])
            return rows[0]
        first = saved_message('Original saved answer', 123)
        again = saved_message('Original saved answer', 123)
        replaced = saved_message('Rewritten different answer', 123)
        repeated = saved_message('Original saved answer', 456)
        self.assertEqual(first['id'], again['id'])
        self.assertNotEqual(first['id'], replaced['id'])
        self.assertNotEqual(first['id'], repeated['id'])

    def test_igsh1043_hax_without_materialized_file_never_reads_another_claude_chat(self):
        self.write(self.event('Unrelated Claude conversation'))
        with patch.object(reader.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '10\n', '')), \
                patch.object(reader, 'process_table', return_value={10: (1, ['hax'])}), \
                patch.object(reader, 'read_process_cwd', return_value='/work/demo'), \
                patch.object(reader, 'open_hax_transcript', return_value=None, create=True):
            self.assertIsNone(reader.resolve_tmux_agent('claude', 'runner-starting'))

    def test_igsh1043_idle_hax_keeps_its_exact_saved_session(self):
        sid = '11111111-1111-4111-8111-111111111111'
        path = self.home / '.local/state/hax/sessions/shared-runners' / ('2026-01-01T00-00-00Z_' + sid + '.jsonl')
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps(dict(type='session', version=1, id=sid, cwd='/work/demo')) + '\n' + json.dumps(dict(kind='assistant', text='Saved reply after runner exits')) + '\n')
        self.query.update(sid=sid, follow=True)
        with patch.object(reader, 'resolve_tmux_agent', return_value=None):
            fields, rows = self.read()
        self.assertEqual(fields[4], sid)
        self.assertEqual([row['text'] for row in rows if row['role'] == 'assistant'], ['Saved reply after runner exits'])

    def test_igsh1043_live_hax_uses_owned_descriptor_not_newest_same_folder(self):
        root = self.home / '.local/state/hax/sessions/shared-runners'
        root.mkdir(parents=True)
        sid = '11111111-1111-4111-8111-111111111111'
        owned = root / ('2026-01-01T00-00-00Z_' + sid + '.jsonl')
        owned.write_text(json.dumps(dict(type='session', version=1, id=sid, cwd='/work/demo')) + '\n')
        other = root / '2026-01-02T00-00-00Z_22222222-2222-4222-8222-222222222222.jsonl'
        other.write_text(json.dumps(dict(type='session', version=1, id='22222222-2222-4222-8222-222222222222', cwd='/work/demo')) + '\n')
        os.utime(owned, (1, 1))
        os.utime(other, (2, 2))
        original_iterdir = Path.iterdir
        def iterdir(path):
            return iter([Path('/proc/11/fd/3')]) if path == Path('/proc/11/fd') else original_iterdir(path)
        with patch.object(Path, 'iterdir', iterdir), \
                patch.object(reader.os, 'readlink', return_value=str(owned)), \
                patch.object(reader, 'read_process_cwd', return_value='/work/demo'):
            self.assertEqual(reader.resolve_tmux_agent('claude', 'runner-one', pid=11), (sid, owned))
            self.assertEqual(reader.resolve_tmux_agent('codex', 'runner-one', pid=11), (sid, owned))

    def test_igsh1043_native_hax_saved_sessions_keep_distinct_messages_and_offsets(self):
        root = self.home / '.local/state/hax/sessions/work-demo-neutral'
        root.mkdir(parents=True)
        for index, sid in enumerate(['11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222']):
            path = root / ('2026-01-01T00-00-00Z_' + sid + '.jsonl')
            events = [dict(type='session', version=1, hax_version='neutral', id=sid, cwd='/work/demo', timestamp='2026-01-01T00:00:00Z', provider='codex', model='neutral'),
                      dict(kind='user', text=f'Runner {index} question'),
                      dict(kind='assistant', text=f'Runner {index} saved reply')]
            path.write_text(''.join(json.dumps(event) + '\n' for event in events))
            self.query = dict(id=7, codex=False, sid=sid, tmux='', cwd='/work/demo', cached_sid='', inode=0, offset=0, source_offsets=True)
            fields, rows = self.read()
            messages = [row for row in rows if row['role'] in ('user', 'assistant')]
            self.assertEqual(fields[4], sid)
            self.assertEqual([row['text'] for row in messages], [f'Runner {index} question', f'Runner {index} saved reply'])
            identities = [row.get('read_id') or row.get('id') for row in messages]
            self.assertTrue(all(identities))
            self.assertEqual(len(set(identities)), 2)
            with path.open('a') as output:
                output.write(json.dumps(dict(kind='assistant', text=f'Runner {index} next reply')) + '\n')
            _, delta = self.read()
            self.assertEqual([row['text'] for row in delta if row['role'] == 'assistant'], [f'Runner {index} next reply'])
            found = reader.response(dict(self.query, search=f'Runner {index} saved reply'))
            self.assertIn(f'Runner {index} saved reply', found)
            self.query.update(cached_sid='', inode=0, offset=0)
            _, again = self.read()
            self.assertEqual([row.get('read_id') or row.get('id') for row in again if row['role'] in ('user', 'assistant')][:2], identities)

    def test_claude_status_header(self):
        todos = [dict(content=f"Task {i}\tline\nnext", status="completed" if i < 3 else "in_progress" if i == 3 else "pending") for i in range(7)]
        old = self.tool_call("TodoWrite", dict(todos=[dict(content="old", status="pending")]))
        latest = self.tool_call("TodoWrite", dict(todos=todos))
        latest["message"].update(model="claude-opus-5-5", usage=dict(input_tokens=2000, cache_read_input_tokens=70000, cache_creation_input_tokens=4000))
        self.write(old, latest)
        _, rows = self.read()
        status = [row for row in rows if row["role"] == "status"]
        self.assertEqual(len(status), 1)
        self.assertEqual(status[0]["model"], "Opus 5.5")
        self.assertEqual(status[0]["percent_left"], 62)
        self.assertEqual(len(status[0]["todos"]), 7)
        self.assertEqual(sum(t["status"] == "completed" for t in status[0]["todos"]), 3)
        self.assertEqual(status[0]["todos"][0]["text"], todos[0]["content"])
        _, rows = self.read()
        self.assertEqual([row for row in rows if row["role"] == "status"], status)

    def test_codex_status_header(self):
        self.query["tmux"] = ""
        self.query["codex"] = True
        self.path = self.home / ".codex/sessions/2026/10/20/rollout-2026-sid.jsonl"
        self.path.parent.mkdir(parents=True)
        plan = [dict(step=f"Step {i}", status="completed" if i < 3 else "pending") for i in range(7)]
        self.write(dict(type="turn_context", payload=dict(model="gpt-5.6")),
                   dict(type="response_item", payload=dict(type="function_call", name="update_plan", arguments=json.dumps(dict(plan=plan)))),
                   dict(type="event_msg", payload=dict(type="token_count", info=dict(last_token_usage=dict(input_tokens=76000), model_context_window=200000))))
        _, rows = self.read()
        status = next(row for row in rows if row["role"] == "status")
        self.assertEqual(status["model"], "gpt-5.6")
        self.assertEqual(status["percent_left"], 62)
        self.assertEqual(len(status["todos"]), 7)
        self.assertEqual(sum(t["status"] == "completed" for t in status["todos"]), 3)

    def test_status_todo_beyond_tail(self):
        todo = self.tool_call("TodoWrite", dict(todos=[dict(content="still relevant", status="in_progress")]))
        self.write(todo)
        with self.path.open("a") as target:
            for _ in range(7000):
                target.write(json.dumps(dict(type="progress", padding="x" * 100)) + "\n")
            target.write(json.dumps(self.event()) + "\n")
        self.assertGreater(self.path.stat().st_size, reader.TAIL_BYTES)
        _, rows = self.read()
        self.assertEqual(next(row for row in rows if row["role"] == "status")["todos"], [dict(text="still relevant", status="in_progress")])

    def test_status_cache_reads_only_new_bytes(self):
        event = self.event("status " + "x" * 1000)
        event["message"].update(model="claude-opus-5-5", usage=dict(input_tokens=76000))
        line = (json.dumps(event) + "\n").encode()
        self.path.write_bytes(line * (2 * 1024 * 1024 // len(line) + 1))
        _, rows = self.read()
        self.assertEqual(next(row for row in rows if row["role"] == "status")["percent_left"], 62)
        event = self.event("new usage")
        event["message"]["usage"] = dict(input_tokens=100000)
        delta = (json.dumps(event) + "\n").encode()
        with self.path.open("ab") as target:
            target.write(delta)
        original_open = Path.open
        read_bytes = 0

        class Counted:
            def __init__(self, source):
                self.source = source

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return self.source.__exit__(*args)

            def __getattr__(self, name):
                return getattr(self.source, name)

            def read(self, *args):
                nonlocal read_bytes
                data = self.source.read(*args)
                read_bytes += len(data)
                return data

        def counted_open(path, *args, **kwargs):
            source = original_open(path, *args, **kwargs)
            return Counted(source) if path == self.path and args == ("rb",) else source

        with patch.object(Path, "open", counted_open):
            fields, rows = self.read()
        self.assertEqual(fields[13], "delta")
        self.assertLess(read_bytes, len(delta) + 256 * 1024)
        status = next(row for row in rows if row["role"] == "status")
        self.assertEqual(status["model"], "Opus 5.5")
        self.assertEqual(status["percent_left"], 50)
        self.assertNotIn("todos", status)

    def test_status_cache_resets_on_new_inode(self):
        old = self.tool_call("TodoWrite", dict(todos=[dict(content="old", status="pending")]))
        old["message"].update(model="claude-opus-5-5", usage=dict(input_tokens=76000))
        self.write(old)
        self.assertIn("todos", next(row for row in self.read()[1] if row["role"] == "status"))
        inode = self.path.stat().st_ino
        new = self.event("new file " + "x" * 1000)
        new["message"].update(model="claude-sonnet-5-5", usage=dict(input_tokens=100000))
        replacement = self.path.with_suffix(".new")
        replacement.write_text(json.dumps(new) + "\n")
        replacement.replace(self.path)
        self.assertNotEqual(inode, self.path.stat().st_ino)
        status = next(row for row in self.read()[1] if row["role"] == "status")
        self.assertEqual(status["model"], "Sonnet 5.5")
        self.assertEqual(status["percent_left"], 50)
        self.assertNotIn("todos", status)

    def test_status_cache_new_todowrite_updates_count(self):
        old = self.tool_call("TodoWrite", dict(todos=[dict(content="one", status="pending")]))
        self.write(old)
        status = next(row for row in self.read()[1] if row["role"] == "status")
        self.assertEqual((sum(t["status"] == "completed" for t in status["todos"]), len(status["todos"])), (0, 1))
        todos = [dict(content="one", status="completed"), dict(content="two", status="pending")]
        with self.path.open("a") as target:
            target.write(json.dumps(self.tool_call("TodoWrite", dict(todos=todos))) + "\n")
        status = next(row for row in self.read()[1] if row["role"] == "status")
        self.assertEqual((sum(t["status"] == "completed" for t in status["todos"]), len(status["todos"])), (1, 2))

    def test_status_cache_absent_todos_become_present_then_empty(self):
        event = self.event()
        event["message"].update(model="claude-opus-5-5", usage=dict(input_tokens=76000))
        self.write(event)
        self.assertNotIn("todos", next(row for row in self.read()[1] if row["role"] == "status"))
        for todos in ([dict(content="new task", status="completed")], []):
            with self.path.open("a") as target:
                target.write(json.dumps(self.tool_call("TodoWrite", dict(todos=todos))) + "\n")
            status = next(row for row in self.read()[1] if row["role"] == "status")
            self.assertEqual(status["todos"], [dict(text=t["content"], status=t["status"]) for t in todos])
            self.assertEqual((status["model"], status["percent_left"]), ("Opus 5.5", 62))

    def test_status_cache_codex_updates_plan_and_usage(self):
        self.query["tmux"] = ""
        self.query["codex"] = True
        self.path = self.home / ".codex/sessions/rollout-2026-sid.jsonl"
        self.path.parent.mkdir(parents=True)
        self.write(dict(type="turn_context", payload=dict(model="gpt-5.6")),
                   dict(type="event_msg", payload=dict(type="token_count", info=dict(last_token_usage=dict(input_tokens=76000), model_context_window=200000))))
        self.assertNotIn("todos", next(row for row in self.read()[1] if row["role"] == "status"))
        plan = [dict(step="new step", status="completed")]
        with self.path.open("a") as target:
            target.write(json.dumps(dict(type="response_item", payload=dict(type="function_call", name="update_plan", arguments=json.dumps(dict(plan=plan))))) + "\n")
            target.write(json.dumps(dict(type="event_msg", payload=dict(type="token_count", info=dict(last_token_usage=dict(input_tokens=100000), model_context_window=400000)))) + "\n")
        status = next(row for row in self.read()[1] if row["role"] == "status")
        self.assertEqual((status["model"], status["percent_left"]), ("gpt-5.6", 75))
        self.assertEqual(status["todos"], [dict(text="new step", status="completed")])

    def test_status_cache_finishes_partial_line_and_resets_on_shrink(self):
        event = self.event()
        event["message"].update(model="claude-opus-5-5", usage=dict(input_tokens=76000))
        self.write(event)
        self.read()
        update = self.event("x" * (reader.TAIL_BYTES + 100))
        update["message"].update(model="claude-sonnet-5-5", usage=dict(input_tokens=100000))
        line = json.dumps(update).encode()
        with self.path.open("ab") as target:
            target.write(line)
        self.assertEqual(next(row for row in self.read()[1] if row["role"] == "status")["model"], "Opus 5.5")
        with self.path.open("ab") as target:
            target.write(b"\n")
        status = next(row for row in self.read()[1] if row["role"] == "status")
        self.assertEqual((status["model"], status["percent_left"]), ("Sonnet 5.5", 50))
        self.write(dict(type="progress"))
        self.assertFalse(any(row["role"] == "status" for row in self.read()[1]))

    def test_status_cache_skips_decoding_older_usage_and_bounds_entries(self):
        event = self.event()
        event["message"].update(model="claude-opus-5-5", usage=dict(input_tokens=76000))
        self.write(*(event for _ in range(1000)))
        original_loads = json.loads
        with self.path.open("rb") as source, patch.object(reader.json, "loads", wraps=original_loads) as loads:
            reader.status_header(source, self.path.stat().st_size, "claude")
        self.assertEqual(loads.call_count, 1)
        self.assertLessEqual(len(reader.STATUS_CACHE), 256)
        for i in range(257):
            path = self.home / f"status-{i}.jsonl"
            path.write_text(json.dumps(event) + "\n")
            with path.open("rb") as source:
                reader.status_header(source, path.stat().st_size, "claude")
        self.assertEqual(len(reader.STATUS_CACHE), 256)
        self.assertNotIn((str(self.path.resolve()), self.path.stat().st_ino), reader.STATUS_CACHE)

    def test_status_bounds_models_and_context(self):
        for model, display in (("claude-sonnet-5-5", "Sonnet 5.5"), ("claude-fable-5-1", "Fable 5.1"), ("claude-haiku-4-5-20251001", "Haiku 4.5"), ("claude-future", "future")):
            self.query.update(cached_sid="", offset=0)
            event = self.tool_call("TodoWrite", dict(todos=[dict(content="😀" * 250, status="completed")] * 60))
            event["message"].update(model=model, usage=dict(input_tokens=380000))
            event["context"] = "[1m]"
            self.write(event)
            _, rows = self.read()
            status = next(row for row in rows if row["role"] == "status")
            self.assertEqual(status["model"], display)
            self.assertEqual(status["percent_left"], 62)
            self.assertEqual(len(status["todos"]), 50)
            self.assertEqual(len(status["todos"][0]["text"]), 200)
        self.query.update(cached_sid="", offset=0)
        self.write(dict(type="assistant", message=dict(content=[], usage=dict(input_tokens=300000))))
        self.assertEqual(next(row for row in self.read()[1] if row["role"] == "status")["percent_left"], 0)

    def test_status_ignores_partial_record_larger_than_chunk(self):
        self.write(dict(type="assistant", message=dict(content=[], model="claude-opus-5-5", usage=dict(input_tokens=76000))))
        with self.path.open("ab") as target:
            target.write(b'{"type":"assistant","message":{"model":"wrong","padding":"' + b'x' * (reader.TAIL_BYTES + 100))
        with self.path.open("rb") as source:
            status = reader.status_header(source, self.path.stat().st_size, "claude")
        self.assertEqual(status["model"], "Opus 5.5")
        self.assertEqual(status["percent_left"], 62)

    def search(self, text):
        wire = reader.response(dict(self.query, search=text, offset='ignored', before=-1, probe=None))
        lines = wire.splitlines()
        self.assertEqual(lines[-1], reader.END)
        fields = lines[0].split('\t')
        self.assertEqual(fields[:5], ['@mp', '7', 'search', 'ok', 'sid'])
        rows = [json.loads(line) for line in lines[1:-1]]
        self.assertEqual(int(fields[7]), len(rows))
        self.assertEqual(fields[9:], ['claude', 'term-1'])
        return fields, rows

    def test_igsh268_first_middle_offsets_and_casefold(self):
        first = dict(type='user', message=dict(role='user', content='First Straße phrase'))
        middle = self.event('Middle literal .* phrase')
        self.write(first, self.event('x' * (reader.TAIL_BYTES + 100)), middle, self.event('last'))
        raw = self.path.read_bytes().splitlines(keepends=True)
        _, rows = self.search('STRASSE')
        self.assertEqual([(r['offset'], r['end'], r['role']) for r in rows], [(0, len(raw[0]), 'user')])
        self.assertIn('Straße', rows[0]['snippet'])
        _, rows = self.search('LITERAL .*')
        self.assertEqual(rows[0]['offset'], len(raw[0]) + len(raw[1]))
        self.assertEqual(rows[0]['end'], sum(map(len, raw[:3])))
        self.assertEqual(rows[0]['role'], 'assistant')

    def test_igsh268_absent_sidechain_and_compaction(self):
        self.write(self.event('visible'), self.event('hidden', isSidechain=True), self.event('hidden', isCompactSummary=True))
        self.assertEqual(self.search('absent')[1], [])
        self.assertEqual(self.search('hidden')[1], [])
        self.assertEqual(len(self.search('visible')[1]), 1)

    def test_igsh268_cap_and_snippet(self):
        self.write(*(self.event('x' * 300 + 'needle' + 'y' * 300) for _ in range(510)))
        fields, rows = self.search('needle')
        self.assertEqual(len(rows), 500)
        self.assertEqual(fields[8], '1')
        self.assertEqual([r['offset'] for r in rows], sorted(r['offset'] for r in rows))
        self.assertTrue(all(len(r['snippet']) <= 160 and 'needle' in r['snippet'] for r in rows))

    def test_igsh268_tool_text_and_detail(self):
        self.write(self.tool_call(value=dict(command='unique tool command')), self.tool_result('distinct result text'))
        _, rows = self.search('unique tool')
        self.assertEqual(rows[0]['role'], 'tool')
        self.assertEqual(rows[0]['offset'], 0)
        _, rows = self.search('distinct result')
        self.assertEqual(rows[0]['role'], 'tool')
        self.assertEqual(rows[0]['offset'], len(self.path.read_bytes().splitlines(keepends=True)[0]))

    def test_igsh268_only_current_visible_tool_details_match(self):
        self.write(self.tool_call(value=dict(command='x' * 2000 + 'hidden input')),
                   self.tool_result('obsolete output'), self.tool_result('current output'))
        self.assertEqual(self.search('hidden input')[1], [])
        self.assertEqual(self.search('obsolete output')[1], [])
        self.assertEqual(len(self.search('current output')[1]), 1)
        self.write(self.tool_result('orphan output'))
        self.assertEqual(self.search('orphan output')[1], [])

    def test_igsh268_search_prefilter_preserves_tool_bookkeeping(self):
        for agent, kinds in [('claude', ['tool_use']), ('codex', ['function_call', 'custom_tool_call', 'local_shell_call', 'tool_use'])]:
            for kind in kinds:
                with self.subTest(agent=agent, kind=kind):
                    if agent == 'claude':
                        call = self.tool_call(value=dict(command='unrelated input'))
                        result = self.tool_result('distinctive needle output')
                        replacement = self.tool_result('replacement without matching text')
                    else:
                        call = dict(type='response_item', payload=dict(type=kind, call_id='call-prefilter', name='exec_command', arguments='{"cmd":"unrelated input"}'))
                        result_kind = 'tool_result' if kind == 'tool_use' else kind + '_output'
                        result = dict(type='response_item', payload=dict(type=result_kind, call_id='call-prefilter', output='distinctive needle output'))
                        replacement = dict(type='response_item', payload=dict(type=result_kind, call_id='call-prefilter', output='replacement without matching text'))
                    self.write(call, result)
                    fields = reader.search_response(dict(self.query, search='distinctive needle'), self.path, agent, 'sid').splitlines()
                    rows = [json.loads(line) for line in fields[1:-1]]
                    self.assertEqual(fields[0].split('\t')[8], '0')
                    self.assertEqual(len(rows), 1)
                    self.assertEqual(rows[0]['offset'], len(self.path.read_bytes().splitlines(keepends=True)[0]))
                    self.assertEqual(rows[0]['role'], 'tool')
                    self.write(call, result, replacement)
                    self.assertEqual(len(reader.search_response(dict(self.query, search='distinctive needle'), self.path, agent, 'sid').splitlines()), 2)
        self.write(self.tool_call(value=dict(command='x' * 2000 + 'distinctive needle')), self.tool_result('unrelated result'))
        self.assertEqual(self.search('distinctive needle')[1], [])
        self.write(self.tool_call(name='Read', value=dict(file_path='project/example.txt')))
        self.assertEqual(len(self.search('Read example')[1]), 1)
        self.write(self.tool_call(value=dict(description='normalized   summary', command='true')))
        self.assertEqual(len(self.search('normalized summary')[1]), 1)

    def test_igsh268_search_prefilter_covers_170_mb_and_chunk_boundaries(self):
        padding = (json.dumps(self.event('cmd ' + 'x' * 15996)) + '\n').encode()
        hit = (json.dumps(self.event('distinctive middle needle', timestamp='middle')) + '\n').encode()
        last = (json.dumps(self.event('distinctive final needle', timestamp='last')) + '\n').encode()
        count = 170_000_000 // len(padding) // 2 + 1
        with self.path.open('wb') as output:
            for _ in range(count):
                output.write(padding)
            middle = output.tell()
            output.write(hit)
            for _ in range(count):
                output.write(padding)
            final = output.tell()
            output.write(last)
            output.write(json.dumps(self.event('distinctive incomplete needle')).encode())
        self.assertGreaterEqual(self.path.stat().st_size, 170_000_000)
        with patch.object(reader.json, 'loads', wraps=json.loads) as loads, patch.object(reader.time, 'monotonic', return_value=0.0):
            fields, rows = self.search('distinctive')
        self.assertEqual(fields[8], '0')
        self.assertEqual([(row['offset'], row['end'], row['timestamp']) for row in rows],
                         [(middle, middle + len(hit), 'middle'), (final, final + len(last), 'last')])
        self.assertLess(loads.call_count, 20)
        with patch.object(reader.json, 'loads', wraps=json.loads) as loads, patch.object(reader.time, 'monotonic', return_value=0.0):
            fields, rows = self.search('md instructions for')
        self.assertEqual(fields[8], '0')
        self.assertEqual(rows, [])
        self.assertEqual(loads.call_count, 0)
        data = (json.dumps(self.event('x' * (1024 * 1024 - 20) + 'boundary needle')) + '\n').encode()
        self.path.write_bytes(b'{}\n' + data + (json.dumps(self.event('boundary needle')) + '\n').encode())
        fields, rows = self.search('boundary needle')
        self.assertEqual(fields[8], '0')
        self.assertEqual(len(rows), 2)
        self.assertEqual([row['offset'] for row in rows], [3, 3 + len(data)])

    def test_igsh268_search_prefilter_keeps_escaped_unicode_and_markup_matches(self):
        for text, needle in [('Straße', 'strasse'), ('é', 'é'), ('quote " and \\ path', 'quote " and \\ path'),
                             ('prefix <local-command-stdout>hidden</local-command-stdout>suffix', 'prefix suffix'),
                             ('pre<local-command-stdout>hidden</local-command-stdout>fix', 'prefix'),
                             ('<command-name>deploy</command-name><command-args>production now</command-args>', 'deploy production'),
                             ('some<pasted_content>joined words</pasted_content> here', 'some joined words'),
                             ('Kelvin ſtraße ﬃle', 'kelvin strasse ffile'), ('İ', 'i'), ('ŉame', 'name'),
                             ('ßfoo', 'sfoo'), ('barﬀ', 'barf'), ('ﬃ', 'f'), ('ﬀ', 'f')]:
            for ascii_json in (True, False):
                with self.subTest(needle=needle, ascii_json=ascii_json):
                    event = dict(type='user', message=dict(role='user', content=text))
                    self.path.write_text(json.dumps(event, ensure_ascii=ascii_json) + '\n')
                    self.assertEqual(len(self.search(needle)[1]), 1)
        self.path.write_bytes(json.dumps(self.event('needle')).replace('needle', '\\u006eeedle').encode() + b'\n')
        self.assertEqual(len(self.search('needle')[1]), 1)
        self.path.write_bytes((json.dumps(self.event('needle')) + '\n').encode('utf-16-be'))
        self.assertEqual(len(self.search('needle')[1]), 1)
        for identity in ['escaped/id', 'escaped\\id', 'escaped"id', 'é', 'plain']:
            self.write(self.tool_call(identity=identity), self.tool_result('distinctive needle', identity=identity))
            self.assertEqual(len(self.search('distinctive needle')[1]), 1)

    def test_igsh268_search_prefilter_unicode_fold_table_is_exhaustive(self):
        expected = {chr(code) for code in range(128, sys.maxunicode + 1)
                    if any('a' <= char <= 'z' for char in chr(code).casefold())}
        self.assertEqual(set(reader.SEARCH_ASCII_FOLDS), expected)

    def test_igsh268_pure_function_codex_and_service_dispatch(self):
        self.write(dict(type='response_item', timestamp='now', payload=dict(type='message', role='user', content=[dict(type='input_text', text='Codex needle')])) )
        pure = reader.search_response(dict(self.query, search='NEEDLE'), self.path, 'codex', 'sid')
        self.assertIn('"role":"user"', pure)
        self.assertIn('\tsearch\tok\tsid\t', pure)
        self.write(self.event('service needle'))
        request = dict(self.query, search='NEEDLE')
        result = subprocess.run([sys.executable, str(ROOT / 'tools/chat-bench/reader-service.py'), str(ROOT / 'src/chat_history_reader.py')],
                                input=json.dumps(dict(job=12, protocol='history', request=json.dumps(request))) + '\n', text=True,
                                capture_output=True, check=True, env=dict(os.environ, HOME=str(self.home)))
        envelope = json.loads(result.stdout)
        self.assertEqual(envelope['job'], 12)
        self.assertIn('\tsearch\tok\tsid\t', envelope['wire'])
        result = subprocess.run([sys.executable, str(ROOT / 'src/chat_history_reader.py'), '--serve'],
                                input=json.dumps(request) + '\n', text=True, capture_output=True, check=True,
                                env=dict(os.environ, HOME=str(self.home)))
        self.assertIn('\tsearch\tok\tsid\t', json.loads(result.stdout)['wire'])

    def test_igsh268_invalid_and_time_cap(self):
        self.write(self.event('needle'))
        for value in ('', 'x' * 201, None, 4, []):
            with self.assertRaises(ValueError):
                reader.response(dict(self.query, search=value))
        with patch.object(reader.time, 'monotonic', side_effect=[0, 2]):
            fields, rows = self.search('needle')
        self.assertEqual(fields[8], '1')
        self.assertEqual(rows, [])

    def test_status_and_whole_conversation_search_share_source_offsets(self):
        todo = self.tool_call("TodoWrite", dict(todos=[dict(content="status-only needle", status="pending")]))
        todo["message"].update(model="claude-opus-5-5", usage=dict(input_tokens=76000))
        self.write(self.event("conversation needle"), todo)
        self.query["source_offsets"] = True
        _, rows = self.read()
        status = next(row for row in rows if row["role"] == "status")
        self.assertEqual((status["model"], status["percent_left"]), ("Opus 5.5", 62))
        self.assertNotIn("offset", status)
        message = next(row for row in rows if row.get("text") == "conversation needle")
        cache = dict(reader.STATUS_CACHE)
        _, hits = self.search("conversation needle")
        self.assertEqual((hits[0]["offset"], hits[0]["end"]), (message["offset"], message["end"]))
        self.assertEqual(reader.STATUS_CACHE, cache)
        self.assertEqual(self.search("Opus 5.5")[1], [])
        self.assertEqual(next(row for row in self.read()[1] if row["role"] == "status"), status)

    def tool_call(self, name='Bash', value=None, identity='call-263'):
        return dict(type='assistant', timestamp='2026-10-18T11:00:00Z', message=dict(role='assistant', content=[
            dict(type='tool_use', id=identity, name=name, input=value or dict(command='zig build test'))]))

    def tool_result(self, output='passed', error=False, identity='call-263'):
        return dict(type='user', timestamp='2026-10-18T11:00:01Z', message=dict(role='user', content=[
            dict(type='tool_result', tool_use_id=identity, content=output, is_error=error)]))

    def diff_row(self, name, value, result=None):
        events = [self.tool_call(name, value)]
        if result is not None:
            events.append(dict(self.tool_result(), toolUseResult=result))
        self.write(*events)
        self.query.update(cached_sid='', offset=0)
        return self.read()[1][0]

    def test_igsh270_edit_structured_patch(self):
        row = self.diff_row('Edit', dict(file_path='foo.zig', old_string='wrong', new_string='fallback'),
                            dict(structuredPatch=[dict(lines=[' context', '-before', '+after', '+extra'])]))
        self.assertEqual(row['diff'], ' context\n-before\n+after\n+extra')
        self.assertEqual(row['text'], 'Edited foo.zig +2 -1')

    def test_igsh270_edit_fallback_uses_uncapped_input(self):
        old = 'same\n' * 1000 + 'before'
        new = 'same\n' * 1000 + 'after'
        row = self.diff_row('Edit', dict(file_path='foo.zig', old_string=old, new_string=new))
        self.assertEqual(row['diff'], ' same\n-before\n+after')
        self.assertEqual(row['text'], 'Edited foo.zig +1 -1')

    def test_igsh270_multiedit(self):
        row = self.diff_row('MultiEdit', dict(file_path='foo.zig', edits=[dict(old_string='a', new_string='b'),
                                                                       dict(old_string='c', new_string='d\ne')]))
        self.assertEqual(row['diff'], '-a\n+b\n-c\n+d\n+e')
        self.assertEqual(row['text'], 'Edited foo.zig +3 -2')

    def test_igsh270_write_create_all_green(self):
        for result in [None, dict(type='create', structuredPatch=[], content='first\nsecond\n')]:
            row = self.diff_row('Write', dict(file_path='new.zig', content='first\nsecond\n'), result)
            self.assertEqual(row['diff'], '+first\n+second')
            self.assertEqual(row['text'], 'Wrote new.zig +2 -0')

    def test_igsh270_codex_apply_patch(self):
        value = '*** Begin Patch\n*** Update File: foo.zig\n@@\n context\n-before\n+after\n*** Add File: new.zig\n+new\n*** End Patch'
        for kind, field, payload in [('custom_tool_call', 'input', value),
                                     ('function_call', 'arguments', json.dumps(dict(input=value)))]:
            event = dict(type='response_item', payload=dict(type=kind, name='apply_patch', call_id='patch', **{field: payload}))
            row = reader.tool_events(event, 'codex')[0]
            self.assertEqual(row['diff'], ' context\n-before\n+after\n+new')
            self.assertEqual(row['text'], 'Edited foo.zig +2 -1')

    def test_igsh270_diff_cap_counts_all_lines(self):
        row = self.diff_row('Edit', dict(file_path='foo.zig', old_string='old', new_string='new'),
                            dict(structuredPatch=[dict(lines=['-old'] * 25 + ['+new'] * 30)]))
        self.assertEqual(len(row['diff'].splitlines()), 41)
        self.assertEqual(row['diff'].splitlines()[-1], ' … 15 more lines')
        self.assertEqual(row['text'], 'Edited foo.zig +30 -25')

    def test_igsh270_result_delta_and_flag(self):
        result = dict(self.tool_result(), toolUseResult=dict(structuredPatch=[dict(lines=['-a', '+b'])]))
        self.write(self.tool_call('Edit', dict(file_path='foo', old_string='a', new_string='c')))
        self.read()
        with self.path.open('a') as output:
            output.write(json.dumps(result) + '\n')
        row = self.read()[1][0]
        self.assertEqual(row['diff'], '-a\n+b')
        self.assertEqual((row['added'], row['removed']), (1, 1))
        with patch.object(reader, 'ISSUE_270_DIFF_CARDS', False):
            self.assertNotIn('diff', reader.tool_events(result, 'claude')[0])

    def test_igsh402_ordered_segments_keep_ids_offsets_and_adjacent_text(self):
        event = self.tool_call()
        event['message']['id'] = 'reply-402'
        tool = event['message']['content'][0]
        event['message']['content'] = [dict(type='text', text='Before'), dict(type='text', text='still before'),
                                      tool, dict(type='thinking', thinking='hidden'), dict(type='text', text='After'),
                                      dict(tool, id='second-call'), dict(type='text', text='Last')]
        self.query['source_offsets'] = True
        self.write(event)
        fields, rows = self.read()
        self.assertEqual([row['role'] for row in rows], ['assistant', 'tool', 'assistant', 'tool', 'assistant'])
        self.assertEqual([row['text'] for row in rows if row['role'] == 'assistant'], ['Before\nstill before', 'After', 'Last'])
        self.assertEqual(rows[0]['id'], 'reply-402')
        self.assertEqual(len({row['id'] for row in rows}), len(rows))
        self.assertTrue(all((row['offset'], row['end']) == (0, self.path.stat().st_size) for row in rows))
        self.assertEqual(reader.response(dict(self.query, cached_sid='', offset=0)).splitlines()[1:-1],
                         [reader.wire_row(row) for row in rows])
        self.assertEqual(self.read()[1], [])
        self.assertEqual(fields[13], 'tail')

    def test_igsh402_live_delta_partial_write_and_result_pairing(self):
        self.write(self.event('initial'))
        self.read()
        event = self.tool_call()
        event['message'].update(id='reply-402', content=[dict(type='text', text='Before'),
            *event['message']['content'], dict(type='text', text='After')])
        offset = self.query['offset']
        raw = (json.dumps(event) + '\n').encode()
        with self.path.open('ab') as output:
            output.write(raw[:-1])
        self.assertEqual(self.read()[1], [])
        self.assertEqual(self.query['offset'], offset)
        with self.path.open('ab') as output:
            output.write(raw[-1:])
        with patch.object(reader, 'PAGE_BYTES', 1):
            fields, rows = self.read()
        self.assertEqual(fields[13], 'delta')
        self.assertEqual([row['role'] for row in rows], ['assistant', 'tool', 'assistant'])
        self.assertEqual(rows[1]['status'], 'running')
        with self.path.open('a') as output:
            output.write(json.dumps(self.tool_result()) + '\n')
        result = self.read()[1]
        self.assertEqual([row['role'] for row in result], ['tool_result'])
        paired = reader.pair_tool_rows(rows + result)
        self.assertEqual([row['role'] for row in paired], ['assistant', 'tool', 'assistant'])
        self.assertEqual(paired[1]['status'], 'ok')
        self.assertEqual(len({row['id'] for row in paired}), len(paired))

    def test_igsh814_idless_codex_records_have_stable_read_identity(self):
        event = dict(type='response_item', timestamp='2026-10-25T11:00:00.000Z',
                     payload=dict(type='message', role='assistant', content=[dict(type='output_text', text='Unread Codex reply')]))
        first = reader.conversation_rows(event, 'codex', offset=100)[0]
        reordered = json.loads(json.dumps(event, sort_keys=True))
        replay = reader.conversation_rows(reordered, 'codex', offset=900)[0]
        self.assertEqual(first['id'], '')
        self.assertTrue(first['read_id'].startswith('event:'))
        self.assertEqual(first['read_id'], replay['read_id'])
        later = dict(event, timestamp='2026-10-25T11:00:00.001Z')
        self.assertNotEqual(first['read_id'], reader.conversation_rows(later, 'codex')[0]['read_id'])

    def test_igsh814_mixed_message_read_identity_survives_source_offset_changes(self):
        event = self.tool_call()
        event.update(uuid='stable-source-message')
        event['message'].update(id='reply-814', content=[dict(type='text', text='Before'),
            *event['message']['content'], dict(type='text', text='After')])
        before = reader.conversation_rows(event, 'claude', offset=100)
        after = reader.conversation_rows(event, 'claude', offset=900)
        self.assertNotEqual(before[-1]['id'], after[-1]['id'])
        self.assertEqual(before[-1]['read_id'], after[-1]['read_id'])
        self.assertEqual(before[-1]['read_id'], 'stable-source-message:text:2')

    def test_igsh402_missing_message_ids_and_repeated_message_ids_are_stable(self):
        for identity in ('', 'shared-message'):
            with self.subTest(identity=identity):
                event = self.tool_call()
                event['message'].update(id=identity, content=[dict(type='text', text='Before'),
                    *event['message']['content'], dict(type='text', text='After')])
                later = dict(event, message=dict(event['message'], content=[dict(event['message']['content'][1], id='later-call'),
                                                                          dict(type='text', text='Later')]))
                self.write(event, later)
                self.query.update(cached_sid='', offset=0)
                _, rows = self.read()
                self.assertTrue(all(row['id'] for row in rows))
                self.assertEqual(len({row['id'] for row in rows}), len(rows))
                replay = reader.response(dict(self.query, cached_sid='', offset=0)).splitlines()[1:-1]
                self.assertEqual([reader.wire_row(row) for row in rows], replay)

    def test_igsh402_search_matches_visible_segments_in_source_order(self):
        event = self.tool_call(value=dict(command='needle command'))
        event['message']['content'] = [dict(type='text', text='needle before'),
                                      *event['message']['content'], dict(type='text', text='needle after')]
        self.write(event, self.tool_result('finished'))
        for query in ('needle', 'needle ', 'before\nneedle'):
            with self.subTest(query=query):
                _, hits = self.search(query)
                if query.startswith('before'):
                    self.assertEqual(hits, [])
                else:
                    self.assertEqual([hit['role'] for hit in hits], ['assistant', 'tool', 'assistant'])
                    self.assertEqual(hits[0]['snippet'], 'needle before')
                    self.assertEqual(hits[-1]['snippet'], 'needle after')
                    self.assertTrue(all(hit['offset'] == 0 for hit in hits))

    def test_igsh402_identical_text_and_tables_have_stable_search_segments(self):
        table = '| needle wide heading | Other heading |\n|---|---|\n| cell | value |'
        for text in ('needle', table):
            for identity in ('', 'reply-402'):
                with self.subTest(text=text, identity=identity):
                    event = self.tool_call(name='Read', value={})
                    event['message'].update(id=identity, content=[dict(type='text', text=text),
                        *event['message']['content'], dict(type='text', text=text)])
                    self.write(event)
                    self.query.update(cached_sid='', offset=0, source_offsets=True)
                    _, rows = self.read()
                    messages = [row for row in rows if row['role'] == 'assistant']
                    _, hits = self.search('needle')
                    self.assertEqual(len(hits), 2)
                    self.assertEqual([row['segment'] for row in messages], [0, 2])
                    self.assertEqual([hit['segment'] for hit in hits], [0, 2])
                    for hit, message in zip(hits, messages):
                        matches = [row for row in messages if (row['offset'], row['segment']) ==
                                   (hit['offset'], hit['segment'])]
                        self.assertEqual(matches, [message])
                    replay = reader.response(dict(self.query, cached_sid='', offset=0)).splitlines()[1:-1]
                    self.assertEqual(replay, [reader.wire_row(row) for row in rows])

    def test_igsh402_hidden_events_and_disabled_tools_keep_previous_contract(self):
        event = self.tool_call()
        event['message'].update(id='reply-402', content=[dict(type='text', text='Before'),
            *event['message']['content'], dict(type='text', text='After')])
        for flag in ('isMeta', 'isSidechain', 'isCompactSummary', 'isVisibleInTranscriptOnly'):
            self.assertEqual(reader.conversation_rows(dict(event, **{flag: True}), 'claude'), [])
        with patch.object(reader, 'ISSUE_263_TOOL_STEPS', False):
            self.assertEqual(reader.conversation_rows(event, 'claude'),
                             [reader.conversation_event(event, 'claude')])
        plain = self.event('plain')
        self.assertEqual(reader.conversation_rows(plain, 'claude'), [reader.conversation_event(plain, 'claude')])

    def test_igsh263_claude_paired_ok_error_running_and_later_delta(self):
        for error in (False, True):
            self.query.update(cached_sid='', offset=0)
            self.write(self.tool_call(), self.tool_result(error=error))
            _, rows = self.read()
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0]['role'], 'tool')
            self.assertEqual(rows[0]['status'], 'error' if error else 'ok')
            self.assertEqual(rows[0]['text'], 'Ran zig build test')
            self.assertIn('\n\npassed', rows[0]['detail'])
        self.query.update(cached_sid='', offset=0)
        self.write(self.tool_call())
        _, rows = self.read()
        self.assertEqual(rows[0]['status'], 'running')
        with self.path.open('a') as source:
            source.write(json.dumps(self.tool_result()) + '\n')
        _, rows = self.read()
        self.assertEqual(rows, [dict(role='tool_result', id='call-263', timestamp='2026-10-18T11:00:01Z',
                                     text='', status='ok', detail='passed')])

    def test_igsh263_latest_result_replaces_output_without_losing_input(self):
        self.write(self.tool_call(), self.tool_result('failed', error=True), self.tool_result('passed'))
        _, rows = self.read()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['status'], 'ok')
        self.assertEqual(rows[0]['detail'], '{"command":"zig build test"}\n\npassed')

    def test_igsh263_result_only_is_not_a_question_or_user_row(self):
        event = self.tool_result()
        self.assertFalse(reader.asks_question(json.dumps(event), 'claude'))
        self.write(event)
        _, rows = self.read()
        self.assertEqual([r['role'] for r in rows], ['tool_result'])
        with patch.object(reader, 'ISSUE_263_TOOL_STEPS', False):
            self.query.update(cached_sid='', offset=0)
            _, rows = self.read()
            self.assertEqual(rows, [])

    def test_igsh263_codex_real_shapes_and_errors(self):
        self.query['tmux'] = ''
        self.path = self.home / '.codex/sessions/2026/10/18/rollout-test-sid.jsonl'
        self.path.parent.mkdir(parents=True)
        self.query.update(codex=True)
        for call_kind, result_kind in [('function_call', 'function_call_output'), ('custom_tool_call', 'custom_tool_call_output'),
                                       ('local_shell_call', 'local_shell_call_output')]:
            for output, status in [('Exit code: 0\npassed', 'ok'), ('Process exited with code 2', 'error'),
                                   ('Exit code: 17', 'error'), ({'metadata': {'exit_code': 1}, 'output': 'failed'}, 'error'),
                                   ({'success': False}, 'error'), ({'status': 'failed'}, 'error'),
                                   ({'error': 'command rejected'}, 'error'), ('Error: command rejected', 'error'),
                                   ('apply_patch verification failed: missing file', 'error'),
                                   ('{"metadata":{"exit_code":3}}', 'error'),
                                   ([{'type': 'input_text', 'text': 'Process exited with code 1'}], 'error')]:
                self.query.update(cached_sid='', offset=0)
                args = dict(cmd='zig build test')
                payload = dict(type=call_kind, call_id='call-codex', name='exec_command', arguments=json.dumps(args))
                if call_kind == 'custom_tool_call':
                    payload['input'] = payload.pop('arguments')
                if call_kind == 'local_shell_call':
                    payload.pop('name')
                    payload.pop('arguments')
                    payload['action'] = dict(command=['zig', 'build', 'test'])
                self.write(dict(type='response_item', payload=payload),
                           dict(type='response_item', payload=dict(type=result_kind, call_id='call-codex', output=output)))
                _, rows = self.read()
                self.assertEqual(len(rows), 1)
                self.assertEqual(rows[0]['status'], status)
                self.assertEqual(rows[0]['text'], 'Ran zig build test')
                self.assertEqual(rows[0]['id'], 'call-codex')
        self.query.update(cached_sid='', offset=0)
        self.write(dict(type='response_item', payload=payload))
        _, rows = self.read()
        self.assertEqual(rows[0]['status'], 'running')
        with self.path.open('a') as source:
            source.write(json.dumps(dict(type='response_item', payload=dict(type=result_kind, call_id='call-codex', output='Exit code: 0'))) + '\n')
        _, rows = self.read()
        self.assertEqual(rows[0]['role'], 'tool_result')
        self.assertEqual(rows[0]['status'], 'ok')

    def test_tool_codex_result_metadata_overrides_output_examples(self):
        outputs = ['Error: documented example', 'Exit code: 17', 'Process exited with code 2']
        for metadata in [{'exit_code': 0}, {'is_error': False}, {'success': True}, {'status': 'completed'}]:
            for output in outputs:
                for value in [dict(metadata, output=output), dict(metadata=metadata, output=output),
                              json.dumps(dict(metadata=metadata, output=output))]:
                    with self.subTest(value=value):
                        event = dict(type='response_item', payload=dict(type='function_call_output',
                                     call_id='command', output=value))
                        self.assertEqual(reader.tool_events(event, 'codex')[0]['status'], 'ok')
        for metadata in [{'exit_code': 1}, {'is_error': True}, {'success': False}, {'status': 'failed'}]:
            event = dict(type='response_item', payload=dict(type='function_call_output',
                         call_id='command', output=dict(metadata=metadata, output='all fine')))
            self.assertEqual(reader.tool_events(event, 'codex')[0]['status'], 'error')
        for output in outputs:
            event = dict(type='response_item', payload=dict(type='function_call_output',
                         call_id='command', output=output))
            self.assertEqual(reader.tool_events(event, 'codex')[0]['status'], 'error')

    def test_igsh263_summary_per_tool_and_detail_cap_without_images(self):
        cases = [('Edit', {'file_path': '/tmp/main.zig'}, 'Edited main.zig'),
                 ('Write', {'file_path': 'C:\\src\\main.zig'}, 'Wrote main.zig'),
                 ('MultiEdit', {'file_path': '/tmp/main.zig'}, 'Edited main.zig'),
                 ('apply_patch', '*** Begin Patch\n*** Update File: src/main.zig\n', 'Edited main.zig'),
                 ('apply_patch', '*** Add File: src/new.zig\n', 'Wrote new.zig'),
                 ('Read', {'file_path': '/tmp/main.zig'}, 'Read main.zig'),
                 ('Bash', {'description': 'tests', 'command': 'zig build test'}, 'Ran tests'),
                 ('shell', {'command': 'zig build'}, 'Ran zig build'),
                 ('exec_command', {'cmd': 'zig build'}, 'Ran zig build'),
                 ('Grep', {'pattern': 'needle'}, 'Searched needle'),
                 ('Glob', {'pattern': '*.zig'}, 'Searched *.zig'),
                 ('WebFetch', {'url': 'https://example.com/a'}, 'Fetched example.com'),
                 ('WebSearch', {'query': 'zig tests'}, 'Searched web zig tests'),
                 ('Task', {'description': 'review'}, 'Agent: review'),
                 ('Agent', {'description': 'review'}, 'Agent: review'),
                 ('other', {}, 'other')]
        for name, value, expected in cases:
            self.assertEqual(reader.tool_summary(name, value), expected)
        self.write(self.tool_call(value=dict(command='echo ' + 'word ' * 2000)),
                   self.tool_result([dict(type='text', text='output ' * 1000),
                                     dict(type='image', source=dict(type='base64', data='A' * 2000))]))
        _, rows = self.read()
        self.assertLessEqual(len(rows[0]['text']), 120)
        self.assertEqual(len(rows[0]['detail']), 4000)
        self.assertTrue(rows[0]['detail'].endswith('…'))
        self.assertIn('\n\noutput ', rows[0]['detail'])
        self.assertNotIn('A' * 256, rows[0]['detail'])
        self.assertNotIn('A' * 256, reader.tool_text('data:image/png;base64,' + 'A' * 2000))

    def test_igsh263_nested_input_is_compact_and_image_payloads_are_removed(self):
        value = dict(action=dict(command=['zig', 'build']), source='ordinary source code', image=dict(type='image', source=dict(type='base64', data='short_base64')))
        text = reader.tool_text(value)
        self.assertEqual(json.loads(text)['action'], dict(command=['zig', 'build']))
        self.assertEqual(json.loads(text)['source'], 'ordinary source code')
        self.assertNotIn('short_base64', text)
        self.assertNotIn('\n', text)

    def test_igsh263_tail_search_passes_tool_result_events_to_find_question(self):
        question = dict(type='user', message=dict(role='user', content='QUESTION-263 do the work'))
        hidden = dict(type='progress', text='hidden ' * (reader.TAIL_BYTES // 7 + 100))
        self.write(self.event('earlier'), question, hidden, self.tool_call(), self.tool_result(), self.event('answer'))
        _, rows = self.read()
        self.assertEqual(rows[0]['text'], 'QUESTION-263 do the work')
        self.assertEqual([row['role'] for row in rows], ['user', 'tool', 'assistant'])

    def test_igsh263_page_boundary_keeps_running_then_result_and_budgets_tools(self):
        self.write(self.event('initial'))
        self.read()
        with self.path.open('a') as source:
            source.write(json.dumps(self.tool_call()) + '\n' + json.dumps(self.tool_result('result ' * 40)) + '\n')
        with patch.object(reader, 'PAGE_BYTES', 300):
            fields, rows = self.read()
            self.assertEqual(fields[8], '1')
            self.assertEqual(rows[0]['status'], 'running')
            _, rows = self.read()
            self.assertEqual(rows[0]['role'], 'tool_result')
        self.query.update(cached_sid='', offset=0)
        self.write(*(self.tool_call(value=dict(command='word ' * 900), identity=str(i)) for i in range(250)))
        _, rows = self.read()
        self.assertLessEqual(sum(len(reader.wire_row(row)) + 1 for row in rows), reader.TAIL_BYTES)

    def test_igsh361_compaction_summary_never_shows_as_the_newest_message(self):
        fixture = json.loads((ROOT / 'tools/fixtures/igsh361/upwork-compact.json').read_text())
        self.write(*fixture['records'])
        _, rows = self.read()
        self.assertFalse(any('This session is being continued' in row.get('text', '') for row in rows))
        self.assertEqual(rows[-1]['role'], 'assistant')
        self.assertTrue(rows[-1]['text'].startswith("**Yes, I've sent 3 more proposals"))

    def test_igsh349_pasted_tags_keep_neutral_inner_text(self):
        self.write(dict(type='user', timestamp='2026-10-17T15:06:44.490Z',
                        message=dict(role='user', content='<pasted_content id="5ebf">\nplease check the build\n</pasted_content id="5ebf">')))
        _, rows = self.read()
        self.assertEqual(rows[0]['text'], 'please check the build')
        self.assertEqual(reader.clean_command_markup('<PASTED_CONTENT id="x" other="y">\nplease check the build\n</PASTED_CONTENT id="x">'), 'please check the build')
        self.assertEqual(reader.clean_command_markup('one<pasted_content id="x">two</pasted_content id="x">three'), 'one two three')
        self.assertEqual(reader.clean_command_markup('before\n<pasted_content id="x">\nplease check the build\n</pasted_content id="x">\nafter'), 'before\nplease check the build\nafter')

    def test_igsh437_reminders_are_hidden_without_losing_conversation_text(self):
        reminder = '<system-reminder>\nSynthetic background task completed.\n</system-reminder>'
        for role in ('user', 'assistant'):
            for content in (reminder, 'Before\n\n' + reminder,
                            reminder + '\nAfter', 'Before\n' + reminder + '\nAfter'):
                with self.subTest(role=role, content=content):
                    event = dict(type=role, message=dict(role=role, content=[dict(type='text', text=content)]))
                    rows = reader.conversation_rows(event, 'claude')
                    expected = content.replace(reminder + '\n', '').replace(reminder, '')
                    self.assertEqual([row['text'] for row in rows], [expected] if expected.strip() else [])

    def test_igsh437_inline_mentions_incomplete_tags_and_task_handling_are_preserved(self):
        for content in ('Explain <system-reminder>example</system-reminder> literally.',
                        '<system-reminder>incomplete example'):
            event = dict(type='user', message=dict(role='user', content=content))
            self.assertEqual(reader.conversation_event(event, 'claude')['text'], content)
        event = dict(type='user', message=dict(role='user', content='<task-notification>synthetic task</task-notification>'))
        self.assertIsNone(reader.conversation_event(event, 'claude'))

    def test_igsh349_queued_command_uses_latest_exact_queue_stamp(self):
        text = 'please check the build'
        self.query['settlement'] = True
        self.write(
            dict(type='queue-operation', operation='enqueue', timestamp='2026-10-17T15:06:45.089Z', content=text),
            self.event('neutral reply', timestamp='2026-10-17T15:06:46.479Z'),
            self.event('another neutral reply', timestamp='2026-10-17T15:06:47.140Z'),
            dict(type='user', timestamp='2026-10-17T15:06:48.337Z', message=dict(role='user', content=[dict(type='tool_result', content='neutral result')])),
            dict(type='queue-operation', operation='remove', timestamp='2026-10-17T15:06:48.689Z', content=text),
            dict(type='attachment', timestamp='2026-10-17T15:06:44.490Z', attachment=dict(type='queued_command', prompt=text)))
        _, rows = self.read()
        self.assertEqual(rows[-1]['timestamp'], '2026-10-17T15:06:48.689Z')
        self.assertGreaterEqual(rows[-1]['timestamp'], rows[0]['timestamp'])
        self.query.update(offset=0, cached_sid='', settlement=False)
        _, rows = self.read()
        self.assertEqual(rows[-1]['timestamp'], '2026-10-17T15:06:48.689Z')
        self.query.update(offset=0, cached_sid='')
        with patch.object(reader, 'ISSUE_349_QUEUED_STAMP_FROM_QUEUE', False):
            _, legacy = self.read()
        self.assertEqual(legacy[-1]['timestamp'], '2026-10-17T15:06:44.490Z')

    def owner(self, name='secondary', uid=1001, home='/home/secondary'):
        return types.SimpleNamespace(pw_name=name, pw_uid=uid, pw_dir=home)

    def test_owner_is_only_a_nonroot_account_matching_an_unambiguous_home(self):
        with patch.object(reader.pwd, 'getpwnam', return_value=self.owner()):
            self.assertEqual(reader.conversation_owner('/home/secondary/projects/demo'), ('secondary', Path('/home/secondary')))
            for cwd in ['/work/demo', '/home/secondary/../root/demo', '/home/-root/demo', '/home/secondary']:
                self.assertIsNone(reader.conversation_owner(cwd))
        with patch.object(reader.pwd, 'getpwnam', return_value=self.owner(uid=0)):
            self.assertIsNone(reader.conversation_owner('/home/secondary/demo'))
        with patch.object(reader.pwd, 'getpwnam', return_value=self.owner(home='/root')):
            self.assertIsNone(reader.conversation_owner('/home/secondary/demo'))
        with patch.object(reader.pwd, 'getpwnam', side_effect=KeyError):
            self.assertIsNone(reader.conversation_owner('/home/unknown/demo'))

    def test_configured_and_xdg_claude_roots_and_safe_symlink_containment(self):
        for directory in [self.home / 'custom-config', self.home / '.config/claude']:
            path = directory / 'projects/-work-demo/configured.jsonl'
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps(self.event('configured')) + '\n')
            with patch.dict('os.environ', {'CLAUDE_CONFIG_DIR': str(directory)}):
                self.assertEqual(reader.conversation_path('claude', 'configured', '/work/demo'), path)
            path.unlink()
        with patch.dict('os.environ', {'CLAUDE_CONFIG_DIR': ''}):
            path.write_text(json.dumps(self.event('xdg')) + '\n')
            self.assertEqual(reader.conversation_path('claude', 'configured', '/work/demo'), path)
        outside = self.home / 'outside.jsonl'
        outside.write_text('{}\n')
        self.path.symlink_to(outside)
        self.assertIsNone(reader.conversation_path('claude', 'sid', '/work/demo'))

    def test_owner_home_is_read_directly_before_sudo(self):
        owner_home = self.home / 'owner'
        path = owner_home / '.claude/projects/-work-demo/sid.jsonl'
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps(self.event('owner readable')) + '\n')
        with patch.object(reader, 'conversation_owner', return_value=('secondary', owner_home)), \
                patch.object(reader.subprocess, 'run', side_effect=AssertionError('no sudo for readable files')):
            wire = reader.response(self.query, source='reader source')
        self.assertIn('owner readable', wire)
        self.write(self.event('own readable'))
        with patch.object(reader, 'conversation_owner', return_value=('secondary', owner_home)):
            self.assertIn('own readable', reader.response(self.query))

    def test_path_probe_distinguishes_denied_from_missing_and_tries_other_roots(self):
        self.write(self.event('denied'))
        original_open = Path.open
        def denied_open(path, *args, **kwargs):
            if path == self.path:
                raise PermissionError('not readable')
            return original_open(path, *args, **kwargs)
        with patch.object(Path, 'open', denied_open):
            with self.assertRaises(PermissionError):
                reader.conversation_path('claude', 'sid', '/work/demo')
        alternate = self.home / '.config/claude/projects/-work-demo/sid.jsonl'
        alternate.parent.mkdir(parents=True)
        alternate.write_text(json.dumps(self.event('alternative readable')) + '\n')
        with patch.object(Path, 'open', denied_open):
            self.assertEqual(reader.conversation_path('claude', 'sid', '/work/demo'), alternate)

    def test_permission_denied_reexecutes_same_source_and_query_without_shell_or_prompt(self):
        expected = '@mp\t7\thistory\tok\tsid\t0\n{"text":"owner rows"}\n@@MP_END@@\n'
        with patch.object(reader, 'conversation_owner', return_value=('secondary', Path('/home/secondary'))), \
                patch.object(reader, 'conversation_path', side_effect=[None, PermissionError()]), \
                patch.object(reader.subprocess, 'run', side_effect=[subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 0, expected, '')]) as run:
            self.assertEqual(reader.response(self.query, source=reader.READER_SOURCE), expected)
        self.assertEqual(run.call_args_list[0].args[0], ['sudo', '-n', '-u', 'secondary', '--', 'true'])
        command = run.call_args_list[1].args[0]
        self.assertEqual(command[:8], ['sudo', '-n', '-H', '-u', 'secondary', '--', 'python3', '-'])
        self.assertEqual(json.loads(base64.b64decode(command[8])), self.query)
        self.assertEqual(command[9], '--owner')
        self.assertEqual(run.call_args_list[1].kwargs['input'], reader.READER_SOURCE)
        self.assertNotIn('shell', run.call_args_list[1].kwargs)

    def test_sudo_unavailable_timeout_and_owner_reexec_never_prompt_or_loop(self):
        for failure in [subprocess.CompletedProcess([], 1), FileNotFoundError(), subprocess.TimeoutExpired('sudo', 3)]:
            effect = failure if isinstance(failure, Exception) else [failure]
            with patch.object(reader, 'conversation_owner', return_value=('secondary', Path('/home/secondary'))), \
                    patch.object(reader, 'conversation_path', side_effect=[None, PermissionError()]), \
                    patch.object(reader.subprocess, 'run', side_effect=effect) as run:
                wire = reader.response(self.query, source=reader.READER_SOURCE)
            self.assertIn('\thistory\tmissing\t', wire)
            self.assertIn('permission denied', wire)
            self.assertEqual(run.call_count, 1)
        with patch.object(reader, 'conversation_owner', return_value=('secondary', Path('/home/secondary'))), \
                patch.object(reader, 'conversation_path', side_effect=[None, PermissionError()]), \
                patch.object(reader, 'resolve_tmux_agent', return_value=None), \
                patch.object(reader.subprocess, 'run', side_effect=AssertionError('must not loop')):
            self.assertIn('permission denied', reader.response(self.query, reader.READER_SOURCE, as_owner=True))

    def test_missing_reason_and_stdin_source_can_reexecute_without_a_local_reader_file(self):
        self.query['tmux'] = ''
        self.assertIn('session transcript not found', reader.response(self.query))
        self.write(self.event('reexecuted stdin'))
        with patch.dict('os.environ', {'HOME': str(self.home)}):
            encoded = base64.b64encode(json.dumps(self.query).encode()).decode()
            result = subprocess.run([sys.executable, '-', encoded, '--owner'], input=reader.READER_SOURCE,
                                    capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('reexecuted stdin', result.stdout)

    def test_complete_history_pages_then_only_new_bytes(self):
        self.write(*(self.event(f'message {i} ' + 'x' * 4000) for i in range(500)))
        fields, rows = self.read()
        self.assertGreater(int(fields[5]), 0)
        self.assertTrue(rows[-1]['text'].startswith('message 499 '))
        latest_offset = self.query['offset']
        pages = 1
        while self.query['start']:
            self.query['before'] = self.query['start']
            fields, page = self.read()
            rows = page + rows
            pages += 1
            self.assertEqual(self.query['offset'], latest_offset)
        self.assertGreater(pages, 1)
        self.assertEqual(len(rows), 500)
        self.assertTrue(rows[0]['text'].startswith('message 0 '))
        self.assertTrue(rows[-1]['text'].startswith('message 499 '))
        offset = self.query['offset']
        fields, page = self.read()
        self.assertEqual(int(fields[5]), offset)
        self.assertEqual(page, [])
        with self.path.open('a') as f:
            f.write(json.dumps(self.event('new delta')) + '\n')
        fields, page = self.read()
        self.assertEqual(int(fields[5]), offset)
        self.assertEqual([r['text'] for r in page], ['new delta'])

    def test_long_message_is_sent_whole_not_trimmed_to_180kb(self):
        text = 'start ' + 'ę' * 400000 + ' end'
        self.write(self.event(text))
        fields, rows = self.read()
        self.assertEqual(rows, [])
        self.query['before'] = int(fields[14])
        _, rows = self.read()
        self.assertEqual(rows[0]['text'], text)

    def test_partial_utf8_record_does_not_advance_cursor(self):
        self.write(self.event('first'))
        _, rows = self.read()
        offset = self.query['offset']
        raw = (json.dumps(self.event('ę second'), ensure_ascii=False) + '\n').encode()
        with self.path.open('ab') as f:
            f.write(raw[:-2])
        fields, rows = self.read()
        self.assertEqual(self.query['offset'], offset)
        self.assertEqual(rows, [])
        with self.path.open('ab') as f:
            f.write(raw[-2:])
        fields, rows = self.read()
        self.assertEqual([r['text'] for r in rows], ['ę second'])

    def test_igsh1154_oversized_delta_records_keep_messages_and_cursor(self):
        for codex in (False, True):
            for incomplete in (False, True):
                with self.subTest(codex=codex, incomplete=incomplete):
                    self.query = dict(id=7, codex=codex, sid='sid', tmux='', cwd='/work/demo',
                                      cached_sid='', inode=0, offset=0, no_jump=True)
                    if codex:
                        self.path = self.home / '.codex/sessions/rollout-2026-sid.jsonl'
                        self.path.parent.mkdir(parents=True, exist_ok=True)

                    def event(text):
                        if codex:
                            return dict(type='response_item', payload=dict(type='message', role='assistant',
                                        content=[dict(type='output_text', text=text)]))
                        return self.event(text)

                    self.write(event('anchor'))
                    _, initial = self.read()
                    self.assertEqual([row['text'] for row in initial if row['role'] == 'assistant'], ['anchor'])
                    anchor = self.query['offset']
                    self.assertGreater(anchor, 0)
                    self.assertEqual(self.query['cached_sid'], 'sid')
                    self.assertEqual(self.query['inode'], self.path.stat().st_ino)
                    large = 'large ' + 'x' * (512 * 1024) + ' end'
                    record = (json.dumps(event(large)) + '\n').encode()
                    following = (json.dumps(event('after large')) + '\n').encode()
                    self.assertGreater(len(record), reader.SCAN_BYTES)
                    with self.path.open('ab') as target:
                        target.write(record[:-1] if incomplete else record + following)
                    if incomplete:
                        fields, rows = self.read()
                        self.assertEqual(fields[13], 'delta')
                        self.assertEqual(int(fields[5]), anchor)
                        self.assertEqual(int(fields[6]), anchor)
                        self.assertEqual(fields[8], '0')
                        self.assertEqual([row for row in rows if row['role'] in ('user', 'assistant')], [])
                        with self.path.open('ab') as target:
                            target.write(b'\n' + following)
                    fields, rows = self.read()
                    self.assertEqual(fields[13], 'delta')
                    self.assertEqual(int(fields[5]), anchor)
                    self.assertEqual(int(fields[6]), anchor + len(record))
                    self.assertEqual(fields[8], '1')
                    self.assertEqual([row['text'] for row in rows if row['role'] == 'assistant'], [large])
                    fields, rows = self.read()
                    self.assertEqual(fields[13], 'delta')
                    self.assertEqual(int(fields[5]), anchor + len(record))
                    self.assertEqual(int(fields[6]), anchor + len(record) + len(following))
                    self.assertEqual(fields[8], '0')
                    self.assertEqual([row['text'] for row in rows if row['role'] == 'assistant'], ['after large'])
                    self.assertEqual(self.query['offset'], self.path.stat().st_size)

    def test_tool_only_pages_advance_without_dropping_next_message(self):
        self.write(self.event('first'))
        self.read()
        tool = json.dumps(dict(type='progress', text='large tool data' * 10)) + '\n'
        with self.path.open('a') as source:
            source.write(tool + json.dumps(self.event('reply')) + '\n')
        with patch.object(reader, 'SCAN_BYTES', len(tool)):
            fields, rows = self.read()
        self.assertEqual(rows, [])
        self.assertEqual(fields[8], '1')
        _, rows = self.read()
        self.assertEqual(rows[0]['text'], 'reply')

    def test_replaced_file_and_wrong_cached_sid_restart_at_zero(self):
        self.write(self.event('old'))
        self.read()
        fresh = self.path.with_suffix('.tmp')
        fresh.write_text(json.dumps(self.event('new inode')) + '\n')
        fresh.replace(self.path)
        fields, rows = self.read()
        self.assertEqual(fields[5], '0')
        self.assertEqual(rows[0]['text'], 'new inode')
        self.query['cached_sid'] = 'another'
        self.assertEqual(self.read()[0][5], '0')

    def test_issue358_first_page_starts_at_the_question_behind_a_big_hidden_record(self):
        hidden = dict(type='user', isMeta=True, message=dict(role='user', content=[dict(type='text', text='s' * (reader.TAIL_BYTES + 100000))]))
        question = dict(type='user', message=dict(role='user', content='QUESTION-358 research the best b2b repos'))
        self.write(self.event('EARLIER-358'), self.event('p' * (reader.TAIL_BYTES + 1000)), question, hidden, self.event('ANSWER-358'))
        old = subprocess.CompletedProcess([], 0, (ROOT / 'tools/fixtures/reader-before-e85be39.py').read_text(), '')
        control = types.ModuleType('reader358control')
        exec(compile(old.stdout, 'reader358control.py', 'exec'), control.__dict__)
        control.HOME = self.home
        control_rows = control.response(dict(self.query)).splitlines()[1:-1]
        self.assertEqual([json.loads(row)['role'] for row in control_rows], ['assistant'])
        fields, rows = self.read()
        self.assertEqual(fields[13], 'tail')
        self.assertEqual([row['text'] for row in rows], ['QUESTION-358 research the best b2b repos', 'ANSWER-358'])
        self.assertEqual(fields[14], fields[5])
        self.query['before'] = int(fields[5])
        fields, rows = self.read()
        self.assertEqual(fields[13], 'older')
        self.assertIn('EARLIER-358', [row['text'] for row in rows])

    def test_issue358_look_back_gives_up_before_the_reply_gets_too_big(self):
        hidden = dict(type='user', isMeta=True, message=dict(role='user', content=[dict(type='text', text='s' * (reader.TAIL_BYTES + 100000))]))
        question = dict(type='user', message=dict(role='user', content='QUESTION-358 far back'))
        talk = [self.event('t' * 4000) for _ in range(reader.TAIL_BYTES // 4000 + 10)]
        self.write(question, *talk, hidden, self.event('ANSWER-358'))
        size = self.path.stat().st_size
        wire = reader.response(dict(self.query))
        fields = wire.splitlines()[0].split('\t')
        costs = [len(row.encode('utf-8')) + 1 for row in wire.splitlines()[1:-1]]
        self.assertLessEqual(sum(costs), reader.TAIL_BYTES + max(costs))
        self.assertIn('t' * 4000, wire)
        self.assertNotIn('QUESTION-358', wire)
        self.assertIn('ANSWER-358', wire)
        with patch.object(reader, 'ISSUE_358_TAIL_STARTS_AT_QUESTION', False):
            self.write(question, hidden, self.event('ANSWER-358'))
            self.assertNotIn('QUESTION-358', reader.response(dict(self.query)))

    def test_igsh631_first_page_keeps_replies_before_a_record_bigger_than_the_tail(self):
        question = dict(type='user', message=dict(role='user', content='QUESTION-631 far back'))
        talk = [self.event('t' * 4000) for _ in range(reader.TAIL_BYTES // 4000 + 10)]
        hidden = self.tool_result('s' * (reader.TAIL_BYTES + 800000))
        for after in ((), (self.event('AFTER-631'),)):
            with self.subTest(after=bool(after)):
                self.write(question, *talk, self.event('REPLY-631-a'), self.event('REPLY-631-b'), hidden, *after)
                wire = reader.response(dict(self.query))
                self.assertIn('REPLY-631-b', wire)
                self.assertIn('REPLY-631-a', wire)
                self.assertEqual(wire.splitlines()[0].split('\t')[13], 'tail')
                costs = [len(row.encode('utf-8')) + 1 for row in wire.splitlines()[1:-1]]
                self.assertLessEqual(sum(costs), reader.TAIL_BYTES + max(costs))
                with patch.object(reader, 'ISSUE_631_TAIL_FILLS_SHOWN_BUDGET', False):
                    self.assertNotIn('REPLY-631-b', reader.response(dict(self.query)))

    def test_shrunken_source_during_scroll_resets_to_tail_instead_of_retrying_stale_page_forever(self):
        self.write(self.event('x' * (reader.TAIL_BYTES + 100)), self.event('old latest'))
        self.read()
        self.assertGreater(self.query['start'], reader.TAIL_BYTES)
        self.query['before'] = self.query['start']
        self.write(self.event('new smaller source'))
        fields, rows = self.read()
        self.assertEqual(fields[13], 'tail')
        self.assertEqual([row['text'] for row in rows], ['new smaller source'])

    def test_same_inode_rewrites_and_changed_append_boundary_restart_at_zero(self):
        self.write(self.event('old reply'))
        self.read()
        self.write(self.event('new reply'))
        fields, rows = self.read()
        self.assertEqual(fields[5], '0')
        self.assertEqual(rows[0]['text'], 'new reply')
        self.write(self.event('newer reply'), self.event('appended'))
        fields, rows = self.read()
        self.assertEqual(fields[5], '0')
        self.assertEqual([r['text'] for r in rows], ['newer reply', 'appended'])

    def test_missing_sid_uses_exact_tmux_process_metadata(self):
        self.write(self.event('mapped history'))
        self.query['sid'] = 'stale'
        with patch.object(reader, 'resolve_tmux_agent', return_value=('sid', self.path)) as resolve:
            fields, rows = self.read()
        resolve.assert_called_once_with('claude', 'term-1')
        self.assertEqual(fields[4], 'sid')
        self.assertEqual(rows[0]['text'], 'mapped history')
        with patch.object(reader, 'resolve_tmux_agent', side_effect=AssertionError('exact sid does not scan proc')):
            self.query['sid'] = 'sid'
            self.read()

    def test_live_session_replaces_readable_saved_sid(self):
        self.write(self.event('saved old reply'))
        live = self.path.with_name('live.jsonl')
        live.write_text(json.dumps(self.event('current reply')) + '\n')
        self.query.update(follow=True, cached_sid='sid', inode=self.path.stat().st_ino,
                          offset=self.path.stat().st_size)
        with patch.object(reader, 'resolve_tmux_agent', return_value=('live', live)):
            fields, rows = self.read()
        self.assertEqual(fields[4], 'live')
        self.assertEqual(fields[5], '0')
        self.assertEqual([r['text'] for r in rows], ['current reply'])

    def test_live_session_missing_retries_without_reading_saved_sid(self):
        self.write(self.event('saved old reply'))
        self.query['follow'] = True
        with patch.object(reader, 'resolve_tmux_agent', return_value=None):
            for _ in range(2):
                wire = reader.response(self.query)
                self.assertIn('history\tmissing', wire)
                self.assertNotIn('saved old reply', wire)
            with patch.object(reader, 'resolve_tmux_agent', return_value=('sid', self.path)):
                self.assertIn('saved old reply', reader.response(self.query))

    def test_IGSH_1064_follow_uses_known_owners_tmux_namespace(self):
        self.query.update(follow=True, sid='stale', cwd='/home/secondary/project')
        expected = '@mp\t7\thistory\tok\tlive\t0\n{"role":"assistant","text":"owner history"}\n@@MP_END@@\n'
        with patch.object(reader, 'conversation_owner', return_value=('secondary', Path('/home/secondary'))), \
                patch.object(reader, 'resolve_tmux_agent', return_value=None), \
                patch.object(reader, 'tmux_owner_process', return_value=None), \
                patch.object(reader, 'owner_response', return_value=expected):
            self.assertEqual(reader.response(self.query, reader.READER_SOURCE), expected)

    def test_IGSH_1064_stale_owner_cwd_still_discovers_exact_live_process(self):
        self.write(self.event('current live reply'))
        self.query.update(follow=True, cwd='/home/secondary/old-project')
        with patch.object(reader, 'conversation_owner', return_value=('secondary', Path('/home/secondary'))), \
                patch.object(reader, 'owner_response', side_effect=lambda q, *_: reader.missing_response(q, 'session transcript not found')), \
                patch.object(reader, 'resolve_tmux_agent', return_value=('sid', self.path)):
            wire = reader.response(self.query, reader.READER_SOURCE)
        self.assertIn('current live reply', wire)
        self.assertNotIn('history\tmissing', wire)

    def test_IGSH_1064_owner_namespace_never_recurses_or_uses_saved_history(self):
        self.write(self.event('saved stale reply'))
        self.query.update(follow=True, cwd='/home/secondary/project')
        with patch.object(reader, 'conversation_owner', return_value=('secondary', Path('/home/secondary'))), \
                patch.object(reader, 'resolve_tmux_agent', return_value=None), \
                patch.object(reader, 'owner_response', side_effect=AssertionError('no recursive delegation')):
            wire = reader.response(self.query, reader.READER_SOURCE, as_owner=True)
        self.assertIn('history\tmissing', wire)
        self.assertNotIn('saved stale reply', wire)

    def test_live_cross_user_discovery_ignores_saved_sid(self):
        self.write(self.event('saved old reply'))
        self.query['follow'] = True
        owner = ('secondary', Path('/home/secondary'))
        expected = '@mp\t7\thistory\tok\tlive\t0\n{"text":"live owner rows"}\n@@MP_END@@\n'
        with patch.object(reader, 'resolve_tmux_agent', return_value=None), \
                patch.object(reader, 'tmux_owner_process', return_value=(99, owner)), \
                patch.object(reader, 'owner_response', return_value=expected) as read_owner:
            self.assertEqual(reader.response(self.query, reader.READER_SOURCE), expected)
        read_owner.assert_called_once_with(dict(self.query, sid='', discovery_pid=99), owner, reader.READER_SOURCE)
        with patch.object(reader, 'resolve_tmux_agent', return_value=('sid', self.path)) as resolve:
            self.assertIn('saved old reply', reader.response(dict(self.query, sid='', discovery_pid=99),
                                                           reader.READER_SOURCE, as_owner=True))
        resolve.assert_called_once_with('claude', 'term-1', 99)

    def test_pane_discovery_visits_only_descendants_and_follows_restarts(self):
        self.query['follow'] = True
        self.write(self.event('first session'))
        root = self.home / 'proc'

        def process(pid, parent, argv, children=(), thread=None):
            entry = root / str(pid)
            entry.mkdir(parents=True, exist_ok=True)
            (entry / 'stat').write_text(f'{pid} (process) S {parent} 0 0')
            (entry / 'cmdline').write_bytes(b'\0'.join(arg.encode() for arg in argv) + b'\0')
            task = entry / 'task' / str(thread or pid)
            task.mkdir(parents=True, exist_ok=True)
            (task / 'children').write_text(' '.join(map(str, children)))

        process(10, 1, ['bash'], [11])
        process(11, 10, ['sudo', 'claude'], [12], thread=110)
        process(12, 11, ['node', '/opt/bin/claude.js'])
        process(9999, 1, ['claude'])
        original_table = reader.process_table
        original_read = Path.read_bytes
        visited = []

        def read_bytes(path):
            if path.name == 'cmdline' and path.parent.parent == root:
                visited.append(int(path.parent.name))
            return original_read(path)

        def opened(pid):
            return self.path if pid == 12 else replacement if pid == 13 else None

        replacement = self.path.with_name('replacement.jsonl')
        replacement.write_text(json.dumps(self.event('replacement session')) + '\n')
        with patch.object(reader.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '10\n', '')), \
                patch.object(reader, 'process_table', side_effect=lambda **kwargs: original_table(root, **kwargs)), \
                patch.object(reader, 'read_process_cwd', return_value='/work/demo'), \
                patch.object(reader, 'open_claude_transcript', side_effect=opened), \
                patch.object(Path, 'read_bytes', read_bytes):
            fields, rows = self.read()
            self.assertEqual(fields[4], 'sid')
            self.assertEqual([row['text'] for row in rows], ['first session'])
            self.assertEqual(set(visited), {10, 11, 12}, 'idle poll must not scan unrelated processes')
            visited.clear()
            process(11, 10, ['sudo', 'claude'], [13], thread=110)
            process(13, 11, ['claude'])
            fields, rows = self.read()
            self.assertEqual(fields[4], 'replacement')
            self.assertEqual([row['text'] for row in rows], ['replacement session'])
            self.assertEqual(set(visited), {10, 11, 13})

    def test_claude_pid_map_resolves_its_exact_jsonl(self):
        self.write(self.event())
        metadata = self.home / '.claude/sessions/99.json'
        metadata.parent.mkdir(parents=True)
        metadata.write_text(json.dumps(dict(sessionId='sid', cwd='/work/demo')))
        with patch.object(reader.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '10\n', '')), \
                patch.object(reader, 'process_table', return_value={10: (1, ['bash']), 99: (10, ['claude'])}), \
                patch.object(reader, 'read_process_cwd', return_value='/wrong'):
            self.assertEqual(reader.resolve_tmux_agent('claude', 'term-1'), ('sid', self.path))

    def test_unknown_claude_identity_never_shows_same_folder_neighbour(self):
        self.write(self.event('neighbour reply'))
        self.query['follow'] = True
        with patch.object(reader.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '10\n', '')), \
                patch.object(reader, 'process_table', return_value={10: (1, ['bash']), 99: (10, ['claude'])}), \
                patch.object(reader, 'read_process_cwd', return_value='/work/demo'), \
                patch.object(reader, 'open_hax_transcript', return_value=None), \
                patch.object(reader, 'open_claude_transcript', return_value=None), \
                patch.object(reader, 'claude_session_metadata', return_value=(None, None)), \
                patch.object(reader, 'tmux_owner_process', return_value=None):
            self.assertIsNone(reader.resolve_tmux_agent('claude', 'term-1'))
            self.assertIsNone(reader.resolve_tmux_agent('claude', 'term-1', require_bound=True))
            wire = reader.response(self.query)
        self.assertIn('history\tmissing', wire)
        self.assertNotIn('neighbour reply', wire)

    def test_new_session_without_transcript_never_shows_a_neighbours_chat(self):
        self.write(self.event())
        metadata = self.home / '.claude/sessions/99.json'
        metadata.parent.mkdir(parents=True)
        metadata.write_text(json.dumps(dict(sessionId='fresh', cwd='/work/demo')))
        with patch.object(reader.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '10\n', '')), \
                patch.object(reader, 'process_table', return_value={10: (1, ['bash']), 99: (10, ['claude'])}), \
                patch.object(reader, 'read_process_cwd', return_value='/work/demo'):
            self.assertIsNone(reader.resolve_tmux_agent('claude', 'term-1'))

    def test_descends_sudo_su_and_runtime_without_mistaking_arguments_for_agents(self):
        table = {10: (1, ['bash']), 11: (10, ['sudo', '-u', 'secondary', 'claude']),
                 12: (11, ['su', 'secondary', '-c', 'claude']),
                 13: (12, ['node', '/opt/bin/claude.js']),
                 20: (1, ['claude'])}
        self.assertEqual(reader.agent_pid_for_panes([10], table, 'claude'), 13)
        self.assertIsNone(reader.agent_pid_for_panes([99], table, 'claude'))
        self.assertIsNone(reader.agent_pid_for_panes([10], {10: (1, ['python3', '-c', 'claude'])}, 'claude'))
        old = subprocess.CompletedProcess([], 0, (ROOT / 'tools/fixtures/reader-before-7a02ac9.py').read_text(), '')
        control = types.ModuleType('reader708')
        exec(compile(old.stdout, 'reader708.py', 'exec'), control.__dict__)
        self.assertNotEqual(control.agent_pid_for_panes([10], table, 'claude'), 13)

    def test_local_ssh_socket_join_separates_same_user_same_cwd_sessions(self):
        table = {10: (1, ['bash']), 11: (10, ['ssh', '-t', 'secondary@localhost']),
                 20: (1, ['ssh', '-t', 'secondary@localhost']),
                 30: (2, ['sshd: secondary [priv]']), 31: (30, ['sshd: secondary@pts/1']),
                 32: (31, ['bash']), 33: (32, ['claude']),
                 40: (2, ['sshd: secondary [priv]']), 41: (40, ['bash']), 42: (41, ['claude'])}
        client = ('0100007F:ABCD', '0100007F:0016')
        other = ('0100007F:ABCE', '0100007F:0016')
        connections = {11: [client], 20: [other], 30: [client[::-1]], 31: [client[::-1]], 40: [other[::-1]]}
        self.assertIsNone(reader.agent_pid_for_panes([10], table, 'claude'))
        linked = reader.link_local_ssh(table.copy(), connections)
        self.assertEqual(reader.agent_pid_for_panes([10], linked, 'claude'), 33)
        self.assertEqual(reader.agent_pid_for_panes([20], linked, 'claude'), 42)
        self.assertIsNone(reader.agent_pid_for_panes([10], reader.link_local_ssh(table.copy(), {11: [client], 30: [other[::-1]]}), 'claude'))
        ipv6 = ('00000000000000000000000001000000:ABCD', '00000000000000000000000001000000:0016')
        self.assertEqual(reader.agent_pid_for_panes([10], reader.link_local_ssh(table.copy(), {11: [ipv6], 30: [ipv6[::-1]]}), 'claude'), 33)

    def test_IGSH_1064_ssh_bridge_follows_only_its_attached_tmux_client_pane(self):
        table = {10: (1, ['ssh', '-t', 'secondary@localhost']),
                 30: (10, ['sshd: secondary@pts/1']),
                 31: (30, ['/usr/bin/tmux', 'attach', '-t', 'design']),
                 40: (2, ['/usr/bin/tmux', 'attach', '-t', 'other']),
                 50: (2, ['bash']), 51: (50, ['claude']),
                 60: (2, ['bash']), 61: (60, ['claude'])}
        clients = subprocess.CompletedProcess([], 0, '40\t60\n31\t50\n', '')
        with patch.object(Path, 'stat', return_value=types.SimpleNamespace(st_uid=1001)), \
                patch.object(reader.pwd, 'getpwuid', return_value=self.owner()), \
                patch.object(reader.subprocess, 'run', return_value=clients):
            self.assertEqual(reader.agent_pid_for_panes([10], table, 'claude'), 51)
            self.assertEqual(reader.agent_pid_for_panes([40], table, 'claude'), 61)
            with patch.object(reader.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '40\t60\n', '')):
                self.assertIsNone(reader.agent_pid_for_panes([10], table, 'claude'))
            with patch.object(reader.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'no server')):
                self.assertIsNone(reader.agent_pid_for_panes([10], table, 'claude'))
            with patch.object(reader.subprocess, 'run', side_effect=PermissionError()):
                self.assertIsNone(reader.agent_pid_for_panes([10], table, 'claude'))

    def test_proc_snapshot_reads_tcp_socket_inodes_and_tolerates_inaccessible_fds(self):
        root = self.home / 'proc'
        (root / 'net').mkdir(parents=True)
        (root / 'net/tcp').write_text('header\n0: 0100007F:ABCD 0100007F:0016 01 0 0 0 0 0 100\n0: 0100007F:0016 0100007F:ABCD 01 0 0 0 0 0 101\n')
        (root / 'net/tcp6').write_text('header\n')
        for pid, inode in [(10, 100), (30, 101)]:
            (root / str(pid) / 'fd').mkdir(parents=True)
            (root / str(pid) / 'fd/3').symlink_to(f'socket:[{inode}]')
        table = {10: (1, ['ssh']), 30: (2, ['sshd: secondary [priv]']), 31: (30, ['claude']), 40: (2, ['sshd: unrelated'])}
        with patch.object(reader, 'process_table', return_value=table):
            linked = reader.local_ssh_process_table(root)
        self.assertEqual(reader.agent_pid_for_panes([10], linked, 'claude'), 31)

    def test_open_transcript_is_exact_newest_and_excludes_subagents_and_outside_paths(self):
        self.write(self.event('open transcript'))
        newer = self.path.with_name('newer.jsonl')
        newer.write_text(json.dumps(self.event('newest')) + '\n')
        import os
        os.utime(self.path, (1, 1))
        os.utime(newer, (2, 2))
        subagent = self.path.parent / 'sid/subagents/agent-other.jsonl'
        subagent.parent.mkdir(parents=True)
        subagent.write_text('{}\n')
        outside = self.home / 'outside.jsonl'
        outside.write_text('{}\n')
        fd = self.home / 'fd'
        fd.mkdir()
        for index, target in enumerate([self.path, newer, subagent, outside]):
            (fd / str(index)).symlink_to(target)
        original_iterdir = Path.iterdir
        def proc_fds(path):
            return fd.iterdir() if str(path) == '/proc/99/fd' else original_iterdir(path)
        with patch.object(Path, 'iterdir', proc_fds), patch.object(reader, 'read_process_cwd', return_value='/wrong'), \
                patch.object(reader, 'claude_session_metadata', side_effect=AssertionError('open transcript wins')):
            self.assertEqual(reader.resolve_tmux_agent('claude', 'term-1', 99), ('newer', newer))

    def test_empty_identity_discovers_owner_then_reads_same_source_as_owner_once(self):
        self.query.update(sid='', cwd='')
        expected = '@mp\t7\thistory\tok\tsid\t0\n{"text":"cross-user"}\n@@MP_END@@\n'
        with patch.object(reader, 'resolve_tmux_agent', return_value=None), \
                patch.object(reader, 'tmux_owner_process', return_value=(99, ('secondary', Path('/home/secondary')))), \
                patch.object(reader, 'owner_response', return_value=expected) as read_owner:
            self.assertEqual(reader.response(self.query, reader.READER_SOURCE), expected)
        owner_query = dict(self.query, discovery_pid=99)
        read_owner.assert_called_once_with(owner_query, ('secondary', Path('/home/secondary')), reader.READER_SOURCE)
        self.write(self.event('owner process map'))
        with patch.object(reader, 'resolve_tmux_agent', return_value=('sid', self.path)) as resolve, \
                patch.object(reader, 'tmux_owner_process', side_effect=AssertionError('no recursive sudo')):
            self.assertIn('owner process map', reader.response(owner_query, reader.READER_SOURCE, as_owner=True))
        resolve.assert_called_once_with('claude', 'term-1', 99)

    def test_stale_saved_sid_returns_to_the_callers_tmux_server_for_owner_discovery(self):
        missing = reader.missing_response(self.query, 'session transcript not found')
        expected = '@mp\t7\thistory\tok\tlive-sid\t0\n{"text":"live rows"}\n@@MP_END@@\n'
        owner = ('secondary', Path('/home/secondary'))
        with patch.object(reader, 'conversation_owner', return_value=owner), \
                patch.object(reader, 'conversation_path', side_effect=[None, PermissionError()]), \
                patch.object(reader, 'owner_response', side_effect=[missing, expected]) as read_owner, \
                patch.object(reader, 'resolve_tmux_agent', return_value=None), \
                patch.object(reader, 'tmux_owner_process', return_value=(99, owner)):
            self.assertEqual(reader.response(self.query, reader.READER_SOURCE), expected)
        self.assertEqual(read_owner.call_count, 2)
        self.assertEqual(read_owner.call_args.args[0], dict(self.query, sid='', discovery_pid=99))

    def test_privileged_discovery_is_noninteractive_stdin_only_and_cannot_select_root(self):
        outputs = [subprocess.CompletedProcess([], 0, '10\n', ''),
                   subprocess.CompletedProcess([], 0, '{"pid":99,"user":"secondary"}', '')]
        with patch.object(reader.subprocess, 'run', side_effect=outputs) as run, \
                patch.object(reader.pwd, 'getpwnam', return_value=self.owner()):
            self.assertEqual(reader.tmux_owner_process('claude', 'remote-3', reader.READER_SOURCE), (99, ('secondary', Path('/home/secondary'))))
        self.assertEqual(run.call_args_list[0].args[0][3], '=remote-3')
        command = run.call_args_list[1].args[0]
        self.assertEqual(command[:5], ['sudo', '-n', '--', 'python3', '-'])
        self.assertEqual(json.loads(base64.b64decode(command[5])), dict(agent='claude', panes=[10]))
        self.assertEqual(command[6], '--discover')
        self.assertEqual(run.call_args_list[1].kwargs['input'], reader.READER_SOURCE)
        self.assertNotIn('shell', run.call_args_list[1].kwargs)
        for failure in [subprocess.CompletedProcess([], 1, '', 'denied'), subprocess.TimeoutExpired('sudo', 8),
                        subprocess.CompletedProcess([], 0, 'not json', '')]:
            with patch.object(reader.subprocess, 'run', side_effect=[outputs[0], failure]):
                self.assertIsNone(reader.tmux_owner_process('claude', 'remote-3', reader.READER_SOURCE))
        with patch.object(reader.subprocess, 'run', side_effect=outputs), \
                patch.object(reader.pwd, 'getpwnam', return_value=self.owner(uid=0)):
            self.assertIsNone(reader.tmux_owner_process('claude', 'remote-3', reader.READER_SOURCE))

    def test_filtering_keeps_queued_prompt_and_plain_text_only(self):
        self.write(dict(type='attachment', attachment=dict(type='queued_command', prompt='queued')),
                   dict(type='user', message=dict(role='user', content='<command-name>/test</command-name><command-args>foo</command-args>')),
                   self.event('sidechain', isSidechain=True),
                   dict(type='assistant', message=dict(role='assistant', content=[dict(type='tool_use', input='private')])),
                   self.event('answer', timestamp='2026-10-03T12:34:00Z'))
        _, rows = self.read()
        self.assertEqual([r['text'] for r in rows], ['queued', '/test foo', 'answer'])

    def test_refused_slash_command_facts_are_opt_in_and_additive(self):
        prompt = '/hormozi-skills:audit-offer can you use this also to review my own offer'
        enqueue = dict(type='queue-operation', operation='enqueue', timestamp='2026-10-13T11:06:22.591Z', content=prompt)
        refused = dict(type='system', subtype='informational', level='warning',
                       timestamp='2026-10-13T11:06:59.442Z', content='Unknown command: /hormozi-skills:audit-offer')
        args = dict(refused, content='Args from unknown skill: can you use this also to review my own offer')
        self.write(enqueue, refused, args, self.event('normal reply'))
        _, legacy = self.read()
        self.assertEqual([r['text'] for r in legacy], ['normal reply'])
        self.query.update(settlement=True, offset=0, cached_sid='')
        _, rows = self.read()
        self.assertEqual(rows[-1], legacy[0])
        self.assertEqual([r['settlement']['operation'] for r in rows[:-1]], ['enqueue', 'rejected', 'rejected'])
        self.assertEqual(rows[0]['settlement']['content'], prompt)
        self.assertEqual(rows[1]['timestamp'], refused['timestamp'])
        self.assertEqual(rows[1]['settlement']['content'], refused['content'])
        self.assertIsNone(reader.settlement_event(dict(refused, isSidechain=True), 'claude'))
        self.assertIsNone(reader.settlement_event(dict(refused, content='Compacted while idle'), 'claude'))
        self.assertIsNone(reader.settlement_event(refused, 'codex'))

    def test_queue_removal_and_contentless_dequeue_popall_survive_delta_paging(self):
        self.query['settlement'] = True
        enqueue = dict(type='queue-operation', operation='enqueue', content='please review my offer', timestamp='2026-10-13T11:06:22.591Z')
        self.write(enqueue)
        _, rows = self.read()
        self.assertEqual(rows[0]['settlement']['operation'], 'enqueue')
        with self.path.open('a') as output:
            for event in [dict(enqueue, operation='remove'),
                          dict(type='queue-operation', operation='dequeue', timestamp='2026-10-13T11:06:59Z'),
                          dict(type='queue-operation', operation='popAll', timestamp='2026-10-13T11:07:00Z')]:
                output.write(json.dumps(event) + '\n')
        _, rows = self.read()
        self.assertEqual([r['settlement']['operation'] for r in rows], ['remove', 'dequeue', 'popAll'])
        self.assertEqual(rows[0]['settlement']['content'], enqueue['content'])
        self.assertEqual(rows[1]['settlement']['content'], '')

    def test_enqueued_command_delivered_midturn_and_normal_user_remain_user_rows(self):
        self.query['settlement'] = True
        prompt = 'please review my offer'
        self.write(dict(type='queue-operation', operation='enqueue', content=prompt, timestamp='2026-10-13T11:06:22.591Z'),
                   dict(type='queue-operation', operation='remove', content=prompt, timestamp='2026-10-13T11:06:59.442Z'),
                   dict(type='attachment', timestamp='2026-10-13T11:06:59.443Z',
                        attachment=dict(type='queued_command', prompt=prompt)),
                   dict(type='user', timestamp='2026-10-13T11:07:00Z', message=dict(role='user', content='normal user message')))
        _, rows = self.read()
        self.assertEqual([r['text'] for r in rows[2:]], [prompt, 'normal user message'])
        self.assertEqual([r['role'] for r in rows[2:]], ['user', 'user'])
        self.assertEqual(rows[2]['timestamp'], '2026-10-13T11:06:59.443Z')

    def test_codex_rollout_and_input_output_text(self):
        self.query['tmux'] = ''
        path = self.home / '.codex/sessions/2026/rollout-day-sid.jsonl'
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps(dict(type='response_item', payload=dict(type='message', role='user', content=[dict(type='input_text', text='codex prompt')]))) + '\n')
        self.query['codex'] = True
        _, rows = self.read()
        self.assertEqual(rows[0]['text'], 'codex prompt')

    def test_untrusted_identity_fails_and_corrupt_record_is_skipped(self):
        self.query['sid'] = '../escape'
        with self.assertRaises(ValueError):
            reader.response(self.query)
        self.query['sid'] = 'sid'
        self.path.write_text('{invalid}\n')
        self.assertEqual(self.read()[1], [])

    def test_50mb_cold_start_reads_only_tail_and_rejects_706_positive_control(self):
        self.path.write_text(json.dumps(dict(type='progress', text='x' * (40 * 1024 * 1024))) + '\n')
        row = json.dumps(self.event('latest ' + 'y' * 2000)) + '\n'
        with self.path.open('a') as source:
            while source.tell() < 50 * 1024 * 1024:
                source.write(row)
            source.write(json.dumps(self.event('LATEST-50MB')) + '\n')
        size = self.path.stat().st_size
        original_open = Path.open

        def measure(module):
            read_bytes = 0

            class Counted:
                def __init__(self, source):
                    self.source = source

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    return self.source.__exit__(*args)

                def __getattr__(self, name):
                    return getattr(self.source, name)

                def read(self, *args):
                    nonlocal read_bytes
                    data = self.source.read(*args)
                    read_bytes += len(data)
                    return data

                def readline(self, *args):
                    nonlocal read_bytes
                    data = self.source.readline(*args)
                    read_bytes += len(data)
                    return data

            def counted_open(path, *args, **kwargs):
                source = original_open(path, *args, **kwargs)
                return Counted(source) if path == self.path and args == ('rb',) else source

            start = time.monotonic()
            with patch.object(Path, 'open', counted_open):
                wire = module.response(self.query)
            fields = wire.splitlines()[0].split('\t')
            elapsed = time.monotonic() - start
            return read_bytes, elapsed, fields, wire

        def bounded(result):
            count, elapsed, fields, wire = result
            self.assertLessEqual(count, reader.QUESTION_SCAN_BYTES + 64)
            self.assertGreaterEqual(int(fields[5]), size - reader.TAIL_BYTES)
            self.assertEqual(int(fields[6]), size)
            self.assertIn('LATEST-50MB', wire)
            self.assertLess(elapsed, 2.0)

        result = measure(reader)
        bounded(result)
        print(f'CPU707 first-load source_bytes={size} read_bytes={result[0]} ms={result[1] * 1000:.1f}')
        old = subprocess.CompletedProcess([], 0, (ROOT / 'tools/fixtures/reader-before-5880899.py').read_text(), '')
        control = types.ModuleType('reader706')
        exec(compile(old.stdout, 'reader706.py', 'exec'), control.__dict__)
        control.HOME = self.home
        with self.assertRaises(AssertionError):
            bounded(measure(control))

    def test_stale_cursor_on_145mb_log_jumps_to_newest_in_one_reply(self):
        row_size = 4096
        overhead = len((json.dumps(self.event('')) + '\n').encode())
        row = (json.dumps(self.event('y' * (row_size - overhead))) + '\n').encode()
        self.assertEqual(len(row), row_size)
        with self.path.open('wb') as output:
            output.write(b'{"type":"progress","text":"')
            chunk = b'x' * (1024 * 1024)
            for _ in range(120):
                output.write(chunk)
            output.write(b'"}\n')
            while output.tell() < 132 * 1024 * 1024:
                output.write(row * min(256, (132 * 1024 * 1024 - output.tell() + row_size - 1) // row_size))
        self.read()
        stale = dict(self.query)
        self.assertEqual(stale['offset'], self.path.stat().st_size)
        with self.path.open('ab') as output:
            for _ in range(13):
                output.write(row * 256)
            output.write((json.dumps(self.event('NEWEST-145MB')) + '\n').encode())
        size = self.path.stat().st_size
        self.assertGreaterEqual(size, 145 * 1024 * 1024)
        behind = size - stale['offset']
        self.assertGreater(behind, 2 * reader.SCAN_BYTES)
        original_open = Path.open
        read_bytes = 0

        class Counted:
            def __init__(self, source):
                self.source = source

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return self.source.__exit__(*args)

            def __getattr__(self, name):
                return getattr(self.source, name)

            def read(self, *args):
                nonlocal read_bytes
                data = self.source.read(*args)
                read_bytes += len(data)
                return data

        def counted_open(path, *args, **kwargs):
            source = original_open(path, *args, **kwargs)
            return Counted(source) if path == self.path and args == ('rb',) else source

        with patch.object(Path, 'open', counted_open):
            start = time.monotonic()
            wire = reader.response(stale)
            elapsed = time.monotonic() - start
        fields = wire.splitlines()[0].split('\t')
        self.assertEqual(fields[13], 'tail')
        self.assertIn('NEWEST-145MB', wire)
        self.assertEqual(fields[8], '0')
        self.assertEqual(int(fields[6]), size)
        self.assertEqual(fields[14], fields[5])
        self.assertGreaterEqual(int(fields[5]), size - reader.TAIL_BYTES)
        self.assertLessEqual(read_bytes, reader.QUESTION_SCAN_BYTES + 128)
        self.assertLess(elapsed, 0.3)
        print(f'stale-cursor source_bytes={size} behind_bytes={behind} ms={elapsed * 1000:.1f} read_bytes={read_bytes}')

    def test_stale_cursor_switch_off_preserves_delta(self):
        self.write(self.event('cached'))
        self.read()
        offset = self.query['offset']
        row = json.dumps(self.event('new row')) + '\n'
        with self.path.open('a') as output:
            output.write(row * 8)
        with patch.object(reader, 'SCAN_BYTES', len(row) * 2), \
                patch.object(reader, 'ISSUE_345_STALE_JUMPS_TO_TAIL', False):
            fields, rows = self.read()
        self.assertEqual(fields[13], 'delta')
        self.assertEqual(fields[8], '1')
        self.assertEqual(int(fields[5]), offset)
        self.assertEqual(int(fields[6]), offset + len(row) * 2)
        self.assertEqual([row['text'] for row in rows], ['new row', 'new row'])

    def test_recent_cursor_appends_exact_new_rows_as_delta(self):
        for behind in (1, 2):
            with self.subTest(scan_multiple=behind):
                self.write(self.event('cached'))
                self.read()
                offset = self.query['offset']
                events = [self.event('new one'), self.event('new two')]
                data = ''.join(json.dumps(event) + '\n' for event in events)
                with self.path.open('a') as output:
                    output.write(data)
                with patch.object(reader, 'SCAN_BYTES', len(data) // behind):
                    fields, rows = self.read()
                    self.assertEqual(fields[13], 'delta')
                    if fields[8] == '1':
                        fields, page = self.read()
                        self.assertEqual(fields[13], 'delta')
                        rows += page
                self.assertEqual([row['text'] for row in rows], ['new one', 'new two'])
                self.assertEqual(fields[8], '0')
                self.assertEqual(self.query['offset'], offset + len(data))

    def test_stale_cursor_with_empty_tail_keeps_existing_delta_logic(self):
        self.write(self.event('cached'))
        self.read()
        offset = self.query['offset']
        row = json.dumps(self.event('new row')) + '\n'
        with self.path.open('a') as output:
            output.write(row)
            output.write(json.dumps(dict(type='progress', text='x' * (reader.TAIL_BYTES + 100))) + '\n')
        with patch.object(reader, 'SCAN_BYTES', len(row)):
            fields, rows = self.read()
        self.assertEqual(fields[13], 'delta')
        self.assertEqual(fields[8], '1')
        self.assertEqual(int(fields[5]), offset)
        self.assertEqual(int(fields[6]), offset + len(row))
        self.assertEqual([row['text'] for row in rows], ['new row'])

    def test_stale_cursor_whose_tail_has_no_messages_keeps_delta(self):
        self.write(self.event('cached'))
        self.read()
        offset = self.query['offset']
        row = json.dumps(self.event('new row')) + '\n'
        tool = json.dumps(dict(type='progress', text='t' * 200)) + '\n'
        with self.path.open('a') as output:
            output.write(row + tool * (reader.TAIL_BYTES // len(tool) + 10))
        with patch.object(reader, 'SCAN_BYTES', len(row) + len(tool)):
            fields, rows = self.read()
        self.assertEqual(fields[13], 'delta')
        self.assertEqual(int(fields[5]), offset)
        self.assertEqual([row['text'] for row in rows], ['new row'])

    def start_server(self, mode="--serve"):
        bootstrap = "import base64,sys;exec(base64.b64decode(sys.stdin.buffer.readline()))"
        server = subprocess.Popen([sys.executable, '-u', '-c', bootstrap, mode],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  env=dict(os.environ, HOME=str(self.home)))
        def cleanup():
            server.stdin.close()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=5)
            server.stdout.close()
            server.stderr.close()
        self.addCleanup(cleanup)
        server.stdin.write(base64.b64encode((ROOT / 'src/chat_history_reader.py').read_bytes()) + b'\n')
        server.stdin.flush()
        return server

    def server_request(self, server, query):
        line = query if isinstance(query, bytes) else json.dumps(query).encode()
        server.stdin.write(line + b'\n')
        server.stdin.flush()
        with selectors.DefaultSelector() as selector:
            selector.register(server.stdout, selectors.EVENT_READ)
            self.assertTrue(selector.select(timeout=5), 'reader reply timed out')
        line = server.stdout.readline()
        self.assertTrue(line.startswith(b'{'), 'reader must keep a framed connection: ' + repr(line))
        reply = json.loads(line)
        self.assertTrue(reply['wire'].endswith(reader.END + '\n'))
        return reply

    def test_watch_owner_restart_reconnects_for_fresh_parent_discovery(self):
        self.assertTrue(hasattr(reader, 'watch_changes'), 'reader must provide an event watch')
        self.write(self.event('original owner session'))
        incoming, writer = os.pipe()
        self.addCleanup(os.close, incoming)
        self.addCleanup(os.close, writer)
        stdin = types.SimpleNamespace(fileno=lambda: incoming)
        resolved = ('claude', 'sid', self.path, None)
        with patch.object(reader.sys, 'stdin', stdin), \
                patch.object(reader, 'resolve_response_path', return_value=('claude', '', None, 'missing')) as resolve:
            events = reader.watch_changes(self.query, resolved, reader.READER_SOURCE, True)
            self.assertEqual(next(events), resolved)
            (self.path.parent / 'restarted.jsonl').write_text('{}\n')
            with self.assertRaises(StopIteration):
                next(events)
        resolve.assert_called_once()

    def test_watch_follows_session_created_before_metadata_changes(self):
        from concurrent.futures import ThreadPoolExecutor, TimeoutError
        from threading import Event

        self.write(self.event('old conversation'))
        incoming, writer = os.pipe()
        self.addCleanup(os.close, incoming)
        self.addCleanup(os.close, writer)
        stdin = types.SimpleNamespace(fileno=lambda: incoming)
        old = ('claude', 'sid', self.path, None)
        replacement = self.path.with_name('new-session.jsonl')
        discovered = Event()
        metadata_ready = Event()

        def resolve(query, source, owner):
            discovered.set()
            return ('claude', 'new-session', replacement, None) if metadata_ready.is_set() else old

        with patch.object(reader.sys, 'stdin', stdin), patch.object(reader, 'resolve_response_path', side_effect=resolve):
            events = reader.watch_changes(self.query, old, reader.READER_SOURCE, True)
            self.assertEqual(next(events), old)
            with ThreadPoolExecutor(max_workers=1) as pool:
                following = pool.submit(next, events)
                replacement.touch()
                created_seen = discovered.wait(2)
                metadata_ready.set()
                replacement.write_text(json.dumps(self.event('reply after session restart')) + '\n')
                try:
                    current = following.result(timeout=2)
                except TimeoutError:
                    with self.path.open('a') as output:
                        output.write('\n')
                    current = following.result(timeout=2)
            events.close()
        self.assertTrue(created_seen, 'new session creation must trigger discovery')
        self.assertEqual(current[2], replacement, 'reply stayed on old session when metadata followed file creation')
        wire = reader.response(dict(self.query, sid='new-session', follow=False), resolved=current)
        self.assertIn('reply after session restart', wire)

    def test_watch_follows_completed_session_when_metadata_arrives_last(self):
        from concurrent.futures import ThreadPoolExecutor, TimeoutError
        from threading import Event

        self.write(self.event('old conversation'))
        metadata = self.home / '.claude/sessions/4242.json'
        metadata.parent.mkdir()
        metadata.write_text(json.dumps(dict(sessionId='sid')))
        incoming, writer = os.pipe()
        self.addCleanup(os.close, incoming)
        self.addCleanup(os.close, writer)
        stdin = types.SimpleNamespace(fileno=lambda: incoming)
        old = ('claude', 'sid', self.path, None)
        replacement = self.path.with_name('completed-session.jsonl')
        discovered = Event()

        discoveries = []

        def resolve(query, source, owner):
            sid = json.loads(metadata.read_text())['sessionId']
            discoveries.append(sid)
            if len(discoveries) >= 3:
                discovered.set()
            return ('claude', sid, replacement, None) if sid != 'sid' else old

        with patch.object(reader.sys, 'stdin', stdin), patch.object(reader, 'resolve_response_path', side_effect=resolve):
            events = reader.watch_changes(self.query, old, reader.READER_SOURCE, True)
            self.assertEqual(next(events), old)
            with ThreadPoolExecutor(max_workers=1) as pool:
                following = pool.submit(next, events)
                replacement.write_text(json.dumps(self.event('completed reply before metadata')) + '\n')
                created_seen = discovered.wait(2)
                pending_metadata = metadata.with_suffix('.tmp')
                pending_metadata.write_text(json.dumps(dict(sessionId='completed-session')))
                pending_metadata.replace(metadata)
                try:
                    current = following.result(timeout=2)
                except TimeoutError:
                    with self.path.open('a') as output:
                        output.write('\n')
                    current = following.result(timeout=2)
            events.close()
        self.assertTrue(created_seen)
        self.assertEqual(current[2], replacement, 'completed reply stayed stale after session metadata changed')

    def test_reader_fixture_repairs_are_checked_without_a_production_change(self):
        repo = self.home / 'admission'
        repo.mkdir()

        def git(*args):
            return subprocess.check_output(['git', '-C', str(repo), *args], text=True,
                                           stderr=subprocess.PIPE).strip()

        git('init', '-q')
        git('config', 'user.name', 'Test')
        git('config', 'user.email', 'test@example.invalid')
        (repo / 'tools').mkdir()
        fixture = repo / 'tools/metadata-test.py'
        fixture.write_text('import unittest\nunittest.main()\n')
        git('add', '.')
        git('commit', '-qm', 'initial fixture')
        base = git('rev-parse', 'HEAD')
        fixture.write_text('import unittest\nclass MetadataTest(unittest.TestCase):\n'
                           '    def test_complete_metadata(self): self.assertEqual(1, 1)\nunittest.main()\n')
        git('add', '.')
        git('commit', '-qm', 'repair fixture')
        head = git('rev-parse', 'HEAD')
        env = dict(os.environ)
        env.pop('GITHUB_HEAD_REF', None)
        env.pop('GITHUB_REF_NAME', None)
        command = ['node', str(ROOT / 'tools/check-red-run.mjs'), base, head]
        result = subprocess.run(command, cwd=repo, env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('TEST_ONLY_PROVEN 1 changed suites', result.stdout)
        fixture.write_text(fixture.read_text().replace('self.assertEqual(1, 1)', 'self.assertEqual(1, 2)'))
        git('add', '.')
        git('commit', '-qm', 'broken fixture')
        command[-1] = git('rev-parse', 'HEAD')
        result = subprocess.run(command, cwd=repo, env=env, capture_output=True, text=True, timeout=30)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('does not pass on PR source', result.stderr)

    def test_watch_completed_session_metadata_save_is_atomic(self):
        write_text = Path.write_text
        metadata = self.home / '.claude/sessions/4242.json'
        partial_saves = []

        def partial_write(path, text, *args, **kwargs):
            if path.parent == metadata.parent and 'completed-session' in text:
                with path.open('w') as output:
                    output.write(text[:1])
                    output.flush()
                    partial_saves.append(json.loads(metadata.read_text())['sessionId'])
                    output.write(text[1:])
                return len(text)
            return write_text(path, text, *args, **kwargs)

        with patch.object(Path, 'write_text', partial_write):
            self.test_watch_follows_completed_session_when_metadata_arrives_last()
        self.assertEqual(partial_saves, ['sid'])
        self.assertEqual(json.loads(metadata.read_text())['sessionId'], 'completed-session')

    def test_watch_delegates_pane_identity_to_the_transcript_owner_once(self):
        self.assertTrue(hasattr(reader, 'watch'), 'reader must provide a persistent watch')
        owner = ('secondary', Path('/home/secondary'))
        query = dict(self.query, cwd='/home/secondary/project', follow=True)
        class Input(io.BytesIO):
            def close(self):
                self.payload = self.getvalue()
                super().close()
        incoming = types.SimpleNamespace(buffer=io.BytesIO(json.dumps(query).encode() + b'\n'), fileno=lambda: 55)
        child = types.SimpleNamespace(stdin=Input(), wait=lambda: 0)
        with patch.object(reader, 'conversation_owner', return_value=owner), \
                patch.object(reader, 'tmux_owner_process', return_value=(99, owner)) as discover, \
                patch.object(reader.subprocess, 'Popen', return_value=child) as start, \
                patch.object(reader.sys, 'stdin', incoming), patch.object(reader.os, 'read', return_value=b''):
            reader.watch(reader.READER_SOURCE)
        discover.assert_called_once_with('claude', query['tmux'], reader.READER_SOURCE)
        command = start.call_args.args[0]
        self.assertEqual(command[:6], ['sudo', '-n', '-H', '-u', 'secondary', '--'])
        self.assertEqual(command[-2:], ['--watch', '--owner'])
        supplied, forwarded = child.stdin.payload.splitlines()
        self.assertEqual(base64.b64decode(supplied).decode(), reader.READER_SOURCE)
        self.assertEqual(json.loads(forwarded), dict(query, discovery_pid=99))

    def test_IGSH_1064_watch_discovers_owner_without_a_remote_cwd(self):
        owner = ('secondary', Path('/home/secondary'))
        query = dict(self.query, cwd='', follow=True)
        class Input(io.BytesIO):
            def close(self):
                self.payload = self.getvalue()
                super().close()
        incoming = types.SimpleNamespace(buffer=io.BytesIO(json.dumps(query).encode() + b'\n'), fileno=lambda: 55)
        child = types.SimpleNamespace(stdin=Input(), wait=lambda: 0)
        output = io.StringIO()
        with patch.object(reader, 'tmux_owner_process', return_value=(99, owner)), \
                patch.object(reader.subprocess, 'Popen', return_value=child) as start, \
                patch.object(reader.sys, 'stdin', incoming), patch.object(reader.sys, 'stdout', output), \
                patch.object(reader.os, 'read', return_value=b''), \
                patch.object(reader, 'resolve_response_path', return_value=('claude', '', None, reader.missing_response(query, 'session transcript not found'))):
            reader.watch(reader.READER_SOURCE)
        self.assertTrue(start.called, 'watch must delegate to discovered owner instead of returning one missing frame')
        supplied, forwarded = child.stdin.payload.splitlines()
        self.assertEqual(base64.b64decode(supplied).decode(), reader.READER_SOURCE)
        self.assertEqual(json.loads(forwarded), dict(query, discovery_pid=99))
        self.assertEqual(output.getvalue(), '')

    @unittest.skipUnless(os.name == 'posix' and shutil.which('tmux'), 'requires isolated Linux tmux')
    def test_watch_reuses_control_capture_without_spawning_tmux_per_update(self):
        real_tmux = shutil.which('tmux')
        socket = 'push-' + self.home.name
        subprocess.run([real_tmux, '-L', socket, '-f', '/dev/null', 'new-session', '-d', '-s', 'term-1', 'cat'], check=True)
        self.addCleanup(lambda: subprocess.run([real_tmux, '-L', socket, 'kill-server'], capture_output=True))
        log = self.home / 'tmux-calls.jsonl'
        tools = self.home / 'bin'
        tools.mkdir()
        wrapper = tools / 'tmux'
        wrapper.write_text('#!' + sys.executable + '\nimport json,os,sys\n' +
                           'with open(' + repr(str(log)) + ', "a") as log: log.write(json.dumps(sys.argv[1:]) + "\\n")\n' +
                           'os.execv(' + repr(real_tmux) + ', [' + repr(real_tmux) + ', "-L", ' + repr(socket) + '] + sys.argv[1:])\n')
        wrapper.chmod(0o755)
        self.query.update(follow=False, live=True)
        self.write(self.event('control first'))
        with patch.dict(os.environ, PATH=str(tools) + os.pathsep + os.environ['PATH']):
            server = self.start_server('--watch')
        first = self.server_request(server, self.query)
        self.assertIn('control first', first['wire'])
        with self.path.open('a') as output:
            output.write(json.dumps(self.event('control second')) + '\n')
        with selectors.DefaultSelector() as selector:
            selector.register(server.stdout, selectors.EVENT_READ)
            self.assertTrue(selector.select(timeout=5), 'control append did not push')
        second = json.loads(server.stdout.readline())
        self.assertIn('control second', second['wire'])
        server.stdin.close()
        self.assertEqual(server.wait(timeout=5), 0, server.stderr.read())
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        self.assertEqual(sum(call[:1] == ['-C'] for call in calls), 1)
        self.assertFalse(any(call[:1] == ['capture-pane'] for call in calls), calls)

    @unittest.skipUnless(os.name == 'posix' and shutil.which('tmux'), 'requires isolated Linux tmux')
    def test_watch_appends_do_not_recapture_screen(self):
        self.assertTrue(hasattr(reader, 'watch_changes'), 'reader must provide an event watch')
        tmux = shutil.which('tmux')
        socket = 'delta-' + self.home.name
        subprocess.run([tmux, '-L', socket, '-f', '/dev/null', 'new-session', '-d', '-s', 'term-1', 'cat'], check=True)
        self.addCleanup(lambda: subprocess.run([tmux, '-L', socket, 'kill-server'], capture_output=True))
        self.write(self.event('original reply'))
        self.query.update(live=True)
        calls = []
        popen = subprocess.Popen

        class Input:
            def __init__(self, stream):
                self.stream = stream

            def write(self, data):
                calls.append(data)
                return self.stream.write(data)

            def __getattr__(self, name):
                return getattr(self.stream, name)

        def control(command, **kwargs):
            child = popen([tmux, '-L', socket, *command[1:]], **kwargs)
            child.stdin = Input(child.stdin)
            return child

        input_read, input_write = os.pipe()
        self.addCleanup(os.close, input_write)
        with os.fdopen(input_read, 'rb') as input_stream, patch.object(sys, 'stdin', input_stream), patch.object(reader.subprocess, 'Popen', side_effect=control):
            resolved = ('claude', 'sid', self.path, None)
            events = reader.watch_changes(self.query, resolved, reader.READER_SOURCE, False)
            try:
                next(events)
                original_screen = self.query['watch_screen']
                initial = len(calls)
                with self.path.open('a') as output:
                    output.write(json.dumps(self.event('only new reply')) + '\n')
                next(events)
                self.assertEqual(len(calls), initial, 'transcript append repeated terminal capture')
                self.assertEqual(self.query['watch_screen'], original_screen)
            finally:
                events.close()

    def test_delta_reads_stay_bounded_after_large_history(self):
        self.query.update(live=True, watch_screen='')
        with self.path.open('w') as output:
            for _ in range(10000):
                output.write(json.dumps(dict(type='progress', padding='x' * 1024)) + '\n')
            output.write(json.dumps(self.event('old reply')) + '\n')
        self.read()
        appended = (json.dumps(self.event('new reply')) + '\n').encode()
        with self.path.open('ab') as output:
            output.write(appended)
        opened = Path.open
        reads = []

        class Measured:
            def __init__(self, file):
                self.file = file

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return self.file.__exit__(*args)

            def read(self, count=-1):
                data = self.file.read(count)
                reads.append(len(data))
                return data

            def __getattr__(self, name):
                return getattr(self.file, name)

        def measured(path, *args, **kwargs):
            file = opened(path, *args, **kwargs)
            return Measured(file) if path == self.path and args == ('rb',) else file

        with patch.object(Path, 'open', measured):
            fields, rows = self.read()
            self.assertEqual(fields[13], 'delta')
            self.assertEqual([row['text'] for row in rows if row['role'] == 'assistant'], ['new reply'])
            self.assertLessEqual(sum(reads), len(appended) * 2 + 320, reads)
            reads.clear()
            fields, rows = self.read()
            self.assertEqual(fields[13], 'delta')
            self.assertFalse(any(row['role'] == 'assistant' for row in rows))
            self.assertLessEqual(sum(reads), 320, reads)

    def test_watch_times_reader_work_with_high_resolution_counter(self):
        self.assertTrue(hasattr(reader, 'watch_changes'), 'reader must provide an event watch')
        self.query.update(tmux='', live=False, follow=True)
        self.write(self.event('measured pushed reply'))
        resolved = reader.resolve_response_path(self.query, reader.READER_SOURCE, False)
        output = io.StringIO()
        request = io.TextIOWrapper(io.BytesIO((json.dumps(self.query) + '\n').encode()))
        with patch.object(sys, 'stdin', request), patch.object(sys, 'stdout', output), patch.object(reader, 'watch_changes', return_value=iter([resolved])), patch.object(time, 'perf_counter_ns', side_effect=[1_000_000, 2_345_000]), patch.object(time, 'monotonic_ns', side_effect=AssertionError('coarse Windows clock used')):
            reader.watch(reader.READER_SOURCE)
        frame = json.loads(output.getvalue())
        self.assertEqual(frame['read_us'], 1345)
        self.assertIn('measured pushed reply', frame['wire'])

    def test_watch_pushes_appends_without_requests_and_stays_idle(self):
        self.query.update(tmux='', live=False, follow=True)
        self.write(self.event('first pushed reply'))
        server = self.start_server('--watch')
        first = self.server_request(server, self.query)
        self.assertIn('first pushed reply', first['wire'])
        with selectors.DefaultSelector() as selector:
            selector.register(server.stdout, selectors.EVENT_READ)
            self.assertFalse(selector.select(timeout=0.1), 'idle watch reloaded transcript')
            with self.path.open('a') as output:
                output.write(json.dumps(self.event('second pushed reply')) + '\n')
            self.assertTrue(selector.select(timeout=5), 'append did not push a reply')
        second = json.loads(server.stdout.readline())
        self.assertEqual(second['id'], self.query['id'])
        self.assertIn('second pushed reply', second['wire'])
        self.assertNotIn('first pushed reply', second['wire'])
        with self.path.open('a') as output:
            output.write(json.dumps(self.event('third pushed reply')) + '\n')
        with selectors.DefaultSelector() as selector:
            selector.register(server.stdout, selectors.EVENT_READ)
            self.assertTrue(selector.select(timeout=5), 'second append did not push')
        third = json.loads(server.stdout.readline())
        self.assertIn('third pushed reply', third['wire'])
        self.assertNotIn('second pushed reply', third['wire'])
        server.stdin.close()
        self.assertEqual(server.wait(timeout=5), 0)
        self.assertEqual(server.stderr.read(), b'')

    def test_watch_follows_replaced_transcript_and_ignores_other_files(self):
        self.query.update(tmux='', live=False)
        self.write(self.event('old content'))
        server = self.start_server('--watch')
        self.server_request(server, self.query)
        (self.path.parent / 'unrelated.jsonl').write_text('ignored\n')
        with selectors.DefaultSelector() as selector:
            selector.register(server.stdout, selectors.EVENT_READ)
            self.assertFalse(selector.select(timeout=0.1), 'unrelated file woke transcript')
            replacement = self.path.with_suffix('.tmp')
            replacement.write_text(json.dumps(self.event('replacement content')) + '\n')
            replacement.replace(self.path)
            self.assertTrue(selector.select(timeout=5), 'replacement did not push')
        reply = json.loads(server.stdout.readline())
        self.assertIn('replacement content', reply['wire'])
        self.assertNotIn('old content', reply['wire'])

    def test_serve_multiple_requests_observe_appends_and_eof_in_one_process(self):
        self.write(self.event('first reply'))
        server = self.start_server()
        first = self.server_request(server, self.query)
        self.assertEqual(first['id'], 7)
        self.assertIn('first reply', first['wire'])
        fields = first['wire'].splitlines()[0].split('\t')
        self.query.update(id=8, cached_sid=fields[4], inode=int(fields[7]), offset=int(fields[6]))
        with self.path.open('a') as output:
            output.write(json.dumps(self.event('second reply')) + '\n')
        second = self.server_request(server, self.query)
        self.assertEqual(second['id'], 8)
        self.assertIn('second reply', second['wire'])
        self.assertNotIn('first reply', second['wire'])
        self.assertIsNone(server.poll())
        server.stdin.close()
        self.assertEqual(server.wait(timeout=5), 0)
        self.assertEqual(server.stderr.read(), b'')

    def test_serve_malformed_requests_do_not_poison_next_request(self):
        self.write(self.event('after malformed'))
        server = self.start_server()
        for malformed in [b'not json', b'null', b'[]', b'{}', b'{"id":9}',
                          b'{"id":true}', b'{"id":-1}', b'{"id":"bad"}']:
            reply = self.server_request(server, malformed)
            self.assertIn('\thistory\terror\t', reply['wire'])
            self.assertEqual(reply['id'], 9 if malformed == b'{"id":9}' else 0)
        self.assertIn('after malformed', self.server_request(server, self.query)['wire'])
        self.assertIsNone(server.poll())

    def test_serve_large_transcript_and_oversized_older_reply_remain_framed(self):
        giant = 'large \" \\ \n 雪 ' * 120000
        self.write(self.event(giant), *(self.event('new ' + str(i) + 'x' * 4000) for i in range(300)))
        server = self.start_server()
        reply = self.server_request(server, self.query)
        fields = reply['wire'].splitlines()[0].split('\t')
        self.assertIn('new 299', reply['wire'])
        self.query.update(id=8, cached_sid=fields[4], inode=int(fields[7]), offset=int(fields[6]),
                          before=int(fields[14]))
        saw_giant = False
        for rid in range(8, 16):
            self.query['id'] = rid
            reply = self.server_request(server, self.query)
            self.assertEqual(reply['id'], rid)
            rows = reply['wire'].splitlines()
            messages = [json.loads(row) for row in rows[1:-1]]
            saw_giant = any(message['text'] == giant for message in messages)
            self.query['before'] = int(rows[0].split('\t')[14])
            if saw_giant:
                self.assertGreater(len(reply['wire']), 1024 * 1024)
                break
        self.assertTrue(saw_giant)
        self.query.update(id=99, before=0)
        self.assertEqual(self.server_request(server, self.query)['id'], 99)

    def test_embedded_stdin_transaction_without_remote_helper_install(self):
        with patch.dict('os.environ', {'HOME': str(self.home)}):
            self.write(self.event('stdin history'))
            encoded = base64.b64encode(json.dumps(self.query).encode()).decode()
            p = subprocess.run([sys.executable, '-', encoded], input=(ROOT / 'src/chat_history_reader.py').read_text(), text=True, capture_output=True, timeout=5)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn('stdin history', p.stdout)
        self.assertTrue(p.stdout.endswith('@@MP_END@@\n'))

    def test_corrupt_line_keeps_other_messages(self):
        corrupt = b'{"type":"assistant","message":{"ro\n'

        def texts(rows):
            return [row['text'] for row in rows if row['role'] in ('user', 'assistant')]

        self.write(self.event('before'))
        with self.path.open('ab') as output:
            output.write(corrupt)
            output.write((json.dumps(self.event('after')) + '\n').encode())
        fields, rows = self.read()
        self.assertEqual(texts(rows), ['before', 'after'])
        self.assertEqual(self.query['offset'], self.path.stat().st_size)

        self.write(self.event('first'))
        self.read()
        with self.path.open('ab') as output:
            output.write(corrupt)
            output.write((json.dumps(self.event('second')) + '\n').encode())
        fields, rows = self.read()
        self.assertEqual(fields[13], 'delta')
        self.assertEqual(texts(rows), ['second'])
        self.assertEqual(self.query['offset'], self.path.stat().st_size)

        question = dict(type='user', message=dict(role='user', content='QUESTION-690'))
        with self.path.open('wb') as output:
            output.write((json.dumps(question) + '\n').encode())
            output.write(corrupt)
            for index in range(5):
                output.write((json.dumps(self.event('answer-%d ' % index + 'x' * 150000)) + '\n').encode())
        self.query.update(cached_sid='', inode=0, offset=0)
        fields, rows = self.read()
        self.assertIn('QUESTION-690', [text[:12] for text in texts(rows)])
        self.assertIn('answer-4', [text[:8] for text in texts(rows)])


class IGSH412ResumedSessionTest(unittest.TestCase):
    def test_igsh412_resumed_process_shapes(self):
        resumed = '11111111-1111-4111-8111-111111111111'
        duplicated = '22222222-2222-4222-8222-222222222222'
        shapes = [
            ('projects-4', resumed, {10: (1, ['bash']), 11: (10, [
                'claude', '--resume', resumed, '--dangerously-skip-permissions', '--effort', 'medium'])}),
            ('term-1bc197', duplicated, {10: (1, [
                'claude', '--dangerously-skip-permissions', '--resume', duplicated,
                '--dangerously-skip-permissions', '--effort', 'medium'])}),
            ('grandchild', resumed, {10: (1, ['bash']), 11: (10, ['bash']), 12: (11, [
                'claude', '--resume', resumed, '--dangerously-skip-permissions'])}),
        ]
        for session, sid, table in shapes:
            for scenario in ('stale-map', 'no-map', 'new-map', 'new-open', 'missing-resume', 'no-map-missing'):
                with self.subTest(session=session, scenario=scenario), tempfile.TemporaryDirectory() as directory:
                    home = Path(directory)
                    folder = home / '.claude/projects/-work-original'
                    folder.mkdir(parents=True)
                    resumed_path = folder / (sid + '.jsonl')
                    resumed_path.write_text('{}\n')
                    neighbour = home / '.claude/projects/-work-current/neighbour.jsonl'
                    neighbour.parent.mkdir(parents=True)
                    neighbour.write_text('{}\n')
                    pid = reader.agent_pid_for_panes([10], table, 'claude')
                    metadata = home / '.claude/sessions' / f'{pid}.json'
                    metadata.parent.mkdir(parents=True)
                    if scenario not in ('no-map', 'no-map-missing'):
                        metadata.write_text(json.dumps(dict(sessionId='fresh', cwd='/work/current')))
                    expected = (sid, resumed_path)
                    opened = None
                    if scenario in ('new-map', 'new-open'):
                        new_path = folder / 'fresh.jsonl'
                        new_path.write_text('{}\n')
                        expected = ('fresh', new_path)
                        if scenario == 'new-open':
                            opened = new_path
                    if scenario in ('missing-resume', 'no-map-missing'):
                        resumed_path.unlink()
                        expected = None
                    original_read_bytes = Path.read_bytes
                    def proc_cmdline(path):
                        if str(path) == f'/proc/{pid}/cmdline':
                            return ('\0'.join(table[pid][1]) + '\0').encode()
                        return original_read_bytes(path)
                    with patch.object(reader, 'HOME', home), patch.dict(os.environ, {'CLAUDE_CONFIG_DIR': ''}), \
                            patch.object(reader, 'process_table', return_value=table), \
                            patch.object(reader, 'read_process_cwd', return_value='/work/current'), \
                            patch.object(reader, 'open_claude_transcript', return_value=opened), \
                            patch.object(Path, 'read_bytes', proc_cmdline), \
                            patch.object(reader.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '10\n', '')):
                        self.assertEqual(reader.resolve_tmux_agent('claude', session, pid), expected)
                        self.assertEqual(reader.resolve_tmux_agent('claude', session), expected)


class SharedQueueDraftTest(unittest.TestCase):
    setUp = HistoryReaderTest.setUp
    event = HistoryReaderTest.event
    write = HistoryReaderTest.write
    read = HistoryReaderTest.read

    def op(self, operation, content=None, **extra):
        row = dict(type='queue-operation', operation=operation, timestamp='2026-10-23T10:00:00.000Z', sessionId='sid')
        if content is not None:
            row['content'] = content
        row.update(extra)
        return row

    def live(self):
        self.query.update(live=True)
        _fields, rows = self.read()
        status = [row for row in rows if row['role'] == 'status']
        return status[0] if status else None

    def receipt_event(self, text, sid='sid'):
        return dict(type='attachment', timestamp='2026-10-23T10:00:01.000Z', sessionId=sid,
                    attachment=dict(type='queued_command', prompt=text))

    def delivered(self, key, text, event_index=None, inode=None, digest=None, sid='sid'):
        lines = self.path.read_bytes().splitlines(keepends=True)
        inode = self.path.stat().st_ino if inode is None else inode
        with reader.held_queue_db('term-1') as db:
            db.execute("INSERT INTO messages(key,sid,text,queued_at,state,origin,source_inode,created) VALUES(?,?,?,1,'delivered',0,?,?)",
                       (key, 'sid', text, inode, int(key.split('-')[-1])))
            if event_index is not None:
                raw = lines[event_index]
                offset = sum(map(len, lines[:event_index]))
                db.execute('INSERT INTO held_receipts(sid,inode,offset,key,digest) VALUES(?,?,?,?,?)',
                           (sid, inode, offset, key, digest or hashlib.sha256(raw).hexdigest()))

    def projected_sent(self):
        with self.path.open('rb') as source, patch.object(reader, 'box_draft', return_value=None):
            return reader.live_fields(source, self.path.stat().st_size, 'term-1', sid='sid')['sent']

    def test_consumed_receipt_recorded_queue_stays_ambiguous_on_refresh(self):
        alpha, beta = 'shell queued alpha edited', 'chat queued beta'
        self.write(self.op('enqueue', 'shell queued alpha'), self.op('enqueue', beta),
                   self.op('popAll'), self.op('enqueue', alpha), self.op('enqueue', beta),
                   self.op('remove', alpha), self.receipt_event(alpha),
                   self.op('dequeue'), self.receipt_event(beta))
        self.delivered('desktop-1', beta, 8)
        for _ in range(3):
            self.assertEqual(self.projected_sent(), [alpha, beta, beta])
        reader.QUEUE_CACHE.clear()
        self.assertEqual(self.projected_sent(), [alpha, beta, beta])

    def test_unique_native_and_held_receipt_merge(self):
        self.write(self.op('enqueue', 'beta'), self.op('dequeue'), self.receipt_event('beta'))
        self.delivered('desktop-1', 'beta', 2)
        self.assertEqual(self.projected_sent(), ['beta'])
        reader.QUEUE_CACHE.clear()
        self.assertEqual(self.projected_sent(), ['beta'])

    def test_orphan_receipt_never_claims_new_occurrence(self):
        for eviction in (False, True):
            for resume in (False, True):
                for claim in ('first', 'second'):
                    with self.subTest(eviction=eviction, resume=resume, claim=claim):
                        reader.QUEUE_CACHE.clear()
                        with reader.held_queue_db('term-1') as db:
                            db.execute('DELETE FROM held_receipts')
                            db.execute('DELETE FROM messages')
                        if eviction:
                            prefix = [self.op('enqueue', 'same')]
                            prefix += [self.op('enqueue', f'filler-{i}') for i in range(reader.QUEUE_SHOWN)]
                            prefix += [self.op('dequeue', 'same')]
                            prefix += [self.op('dequeue', f'filler-{i}') for i in range(reader.QUEUE_SHOWN)]
                        else:
                            prefix = [self.op('enqueue', 'same'), self.op('popAll')]
                        prefix += [self.op('enqueue', 'same'), self.receipt_event('same')]
                        suffix = [self.op('dequeue', 'same'), self.receipt_event('same'),
                                  self.event('tail ' + 'x' * 128)]
                        events = prefix + suffix
                        for i, event in enumerate(events):
                            event['timestamp'] = f'2026-10-23T10:{i // 60:02d}:{i % 60:02d}.000Z'
                        self.write(*(prefix if resume else events))
                        if resume:
                            self.projected_sent()
                            with self.path.open('a') as output:
                                output.write(''.join(json.dumps(event) + '\n' for event in suffix))
                        self.delivered('desktop-1', 'same', len(prefix) - 1 if claim == 'first' else len(prefix) + 1)
                        expected_count = 2 if claim == 'first' else 1
                        self.assertEqual(self.projected_sent().count('same'), expected_count)
                        self.assertEqual(self.projected_sent().count('same'), expected_count)
                        reader.QUEUE_CACHE.clear()
                        self.assertEqual(self.projected_sent().count('same'), expected_count)
                        if claim == 'second':
                            data = self.path.read_bytes()
                            self.path.write_bytes(data.replace(b'"prompt": "same"', b'"prompt": "else"', 2))
                            self.assertEqual(self.projected_sent().count('same'), 2)

    def test_consumed_receipt_keeps_idle_and_different_occurrences(self):
        self.write(self.op('enqueue', 'same'), self.op('enqueue', 'other'),
                   self.op('dequeue'), self.receipt_event('same'), self.op('enqueue', 'same'),
                   self.op('dequeue'), self.receipt_event('other'),
                   self.op('dequeue'), self.receipt_event('same'), self.receipt_event('idle'))
        self.delivered('desktop-1', 'same', 8)
        self.delivered('desktop-2', 'idle', 9)
        self.assertEqual(self.projected_sent(), ['same', 'other', 'same', 'idle'])
        reader.QUEUE_CACHE.clear()
        self.assertEqual(self.projected_sent(), ['same', 'other', 'same', 'idle'])

    def test_removed_without_consumption_does_not_merge(self):
        self.write(self.op('enqueue', 'same'), self.op('remove', 'same'),
                   dict(self.receipt_event('same'), timestamp='2026-10-23T09:59:59.000Z'))
        self.delivered('desktop-1', 'same', 2)
        self.assertEqual(self.projected_sent(), ['same', 'same'])

    def test_invalid_receipts_never_collapse(self):
        for invalid in ('digest', 'inode', 'receipt-sid', 'event-sid', 'partial', 'legacy'):
            with self.subTest(invalid=invalid):
                reader.QUEUE_CACHE.clear()
                self.write(self.op('enqueue', 'same'), self.op('dequeue'),
                           self.receipt_event('same', 'wrong' if invalid == 'event-sid' else 'sid'))
                if invalid == 'partial':
                    data = self.path.read_bytes()
                    self.path.write_bytes(data[:-1])
                self.delivered('desktop-1', 'same', None if invalid == 'legacy' else 2,
                               inode=self.path.stat().st_ino + 1 if invalid == 'inode' else None,
                               digest='0' * 64 if invalid == 'digest' else None,
                               sid='wrong' if invalid == 'receipt-sid' else 'sid')
                self.assertEqual(self.projected_sent(), ['same', 'same'])
                with reader.held_queue_db('term-1') as db:
                    db.execute('DELETE FROM held_receipts')
                    db.execute('DELETE FROM messages')

    def test_cached_receipt_is_revalidated_even_when_tail_unchanged(self):
        self.write(self.op('enqueue', 'same'), self.op('dequeue'), self.receipt_event('same'),
                   self.event('tail ' + 'x' * 128))
        self.delivered('desktop-1', 'same', 2)
        self.assertEqual(self.projected_sent(), ['same'])
        data = self.path.read_bytes().replace(b'"prompt": "same"', b'"prompt": "else"')
        self.path.write_bytes(data)
        self.assertEqual(self.projected_sent(), ['same', 'same'])

    def test_old_cache_shape_rebuilds_before_merging(self):
        self.write(self.op('enqueue', 'same'), self.op('dequeue'), self.receipt_event('same'))
        self.delivered('desktop-1', 'same', 2)
        self.assertEqual(self.projected_sent(), ['same'])
        key = (str(self.path.resolve()), self.path.stat().st_ino)
        reader.QUEUE_CACHE[key] = reader.QUEUE_CACHE[key][:5]
        self.assertEqual(self.projected_sent(), ['same'])

    def test_replaced_or_truncated_source_never_collapses(self):
        self.write(self.op('enqueue', 'same'), self.op('dequeue'), self.receipt_event('same'))
        self.delivered('desktop-1', 'same', 2)
        with self.path.open('rb') as source, patch.object(reader, 'box_draft', return_value=None):
            size = self.path.stat().st_size
            self.assertEqual(reader.live_fields(source, size, 'term-1', sid='sid')['sent'], ['same'])
            replacement = self.path.with_suffix('.new')
            replacement.write_bytes(self.path.read_bytes())
            os.replace(replacement, self.path)
            self.assertEqual(reader.live_fields(source, size, 'term-1', sid='sid')['sent'], ['same', 'same'])
        self.path.write_bytes(self.path.read_bytes().splitlines(keepends=True)[0] +
                              self.path.read_bytes().splitlines(keepends=True)[1])
        self.assertEqual(self.projected_sent(), ['same', 'same'])

    def test_many_deliveries_keep_recent_bounded_candidates(self):
        count = reader.QUEUE_SHOWN * 2
        events = [self.op('enqueue', f'native-{i}') for i in range(count)]
        events.append(self.op('enqueue', 'still queued'))
        offsets = {}
        for i in range(count):
            events.append(self.op('dequeue', f'native-{i}'))
            offsets[i] = len(events)
            events.append(self.receipt_event(f'native-{i}'))
        self.write(*events)
        with reader.held_queue_db('term-1') as db:
            db.executemany("INSERT INTO messages(key,sid,text,queued_at,state,origin,source_inode,created) VALUES(?,?,?,1,'delivered',0,?,?)",
                           [(f'old-{i}', 'sid', f'old-{i}', self.path.stat().st_ino, i)
                            for i in range(2000)])
            db.executemany('INSERT INTO held_receipts(sid,inode,offset,key,digest) VALUES(?,?,?,?,?)',
                           [('sid', self.path.stat().st_ino, 1000000 + i, f'old-{i}', 'old')
                            for i in range(2000)])
        for i in range(count - 4, count):
            self.delivered(f'desktop-{3000 + i}', f'native-{i}', offsets[i])
        for i in range(4):
            self.delivered(f'desktop-{4000 + i}', f'idle-{i}')

        original_db = reader.held_queue_db
        steps = []
        def measured_db(tmux):
            db = original_db(tmux)
            db.set_progress_handler(lambda: steps.append(1) or 0, 100)
            return db

        expected = ([f'native-{i}' for i in range(count - 4, count)] +
                    [f'idle-{i}' for i in range(4)])
        with patch.object(reader, 'held_queue_db', side_effect=measured_db):
            self.assertEqual(self.projected_sent(), expected)
        self.assertLess(len(steps), 200)
        cache = reader.QUEUE_CACHE[(str(self.path.resolve()), self.path.stat().st_ino)]
        self.assertLessEqual(len(cache[1][1]), reader.QUEUE_SHOWN)
        self.assertLessEqual(len(cache[5]), reader.QUEUE_SHOWN)
        self.assertLessEqual(len(cache[6]), reader.QUEUE_SHOWN)
        with self.path.open('ab') as source:
            source.write((json.dumps(self.event('new tail')) + '\n').encode())
        self.assertEqual(self.projected_sent(), expected)
        reader.QUEUE_CACHE.clear()
        self.assertEqual(self.projected_sent(), expected)

    def test_simultaneous_identical_candidates_remain_distinct(self):
        self.write(self.op('enqueue', 'same'), self.op('enqueue', 'same'),
                   self.op('dequeue'), self.receipt_event('same'))
        self.delivered('desktop-1', 'same', 3)
        self.assertEqual(self.projected_sent(), ['same', 'same'])

    def test_held_same_text_at_another_offset_remains_distinct(self):
        self.write(self.op('enqueue', 'same'), self.op('dequeue'), self.receipt_event('same'),
                   self.receipt_event('same'))
        self.delivered('desktop-1', 'same', 3)
        self.assertEqual(self.projected_sent(), ['same', 'same'])

    def test_IGSH_835_recorded_transcript_clears_queue_on_next_refresh(self):
        fixture = ROOT / 'tools/fixtures/IGSH-835/recorded-transcript.jsonl'
        events = [json.loads(line) for line in fixture.read_text(encoding='utf-8').splitlines()]
        self.write(events[0])
        with patch.object(reader, 'box_draft', return_value=None):
            self.assertEqual(self.live()['queue'], [events[0]['content']])
            with self.path.open('a', encoding='utf-8') as output:
                output.write(json.dumps(events[1]) + '\n')
            status = self.live()
            self.assertEqual(status['queue'], [])
            self.assertEqual(status['sent'], [events[0]['content']])
            self.assertEqual(self.live()['queue'], [])
            reader.QUEUE_CACHE.clear()
            self.assertEqual(self.live()['queue'], [])

    def test_IGSH_835_identical_sends_need_distinct_occurrences(self):
        fixture = ROOT / 'tools/fixtures/IGSH-835/identical-sends.jsonl'
        events = [json.loads(line) for line in fixture.read_text(encoding='utf-8').splitlines()]
        self.write(*events[:2])
        with patch.object(reader, 'box_draft', return_value=None):
            self.assertEqual(self.live()['queue'], ['same message', 'same message'])
            with self.path.open('a', encoding='utf-8') as output:
                output.write(''.join(json.dumps(event) + '\n' for event in events[2:4]))
            status = self.live()
            self.assertEqual(status['queue'], ['same message'])
            self.assertEqual(status['sent'], ['same message'])
            self.assertEqual(self.live()['queue'], ['same message'])
            reader.QUEUE_CACHE.clear()
            self.assertEqual(self.live()['queue'], ['same message'])
            with self.path.open('a', encoding='utf-8') as output:
                output.write(json.dumps(events[4]) + '\n')
            status = self.live()
            self.assertEqual(status['queue'], [])
            self.assertEqual(status['sent'], ['same message', 'same message'])

    def test_IGSH_835_old_partial_and_assistant_text_do_not_confirm(self):
        stamp = '2026-10-25T12:00:00.000Z'
        user = dict(type='user', timestamp=stamp, message=dict(role='user', content='exact text'))
        enqueue = self.op('enqueue', 'exact text', timestamp=stamp)
        cases = [dict(user, timestamp='2026-10-25T11:59:59.999Z'),
                 dict(user, timestamp='invalid'),
                 dict(user, message=dict(role='user', content='exact text extra')),
                 dict(user, message=dict(role='user', content=' exact text')),
                 dict(user, type='assistant', message=dict(role='assistant', content='exact text')),
                 dict(user, isSidechain=True), dict(user, isMeta=True),
                 dict(user, isCompactSummary=True), dict(user, isVisibleInTranscriptOnly=True)]
        with patch.object(reader, 'box_draft', return_value=None):
            for event in cases:
                with self.subTest(event=event):
                    reader.QUEUE_CACHE.clear()
                    self.write(enqueue, event)
                    self.assertEqual(self.live()['queue'], ['exact text'])
            reader.QUEUE_CACHE.clear()
            self.write(dict(enqueue, timestamp='invalid'), user)
            self.assertEqual(self.live()['queue'], ['exact text'])

    def test_IGSH_835_attachment_and_timezone_equality_confirm(self):
        self.write(self.op('enqueue', 'hello', timestamp='2026-10-25T12:00:00Z'),
                   dict(type='attachment', timestamp='2026-10-25T14:00:00+02:00',
                        attachment=dict(type='queued_command', prompt='hello')))
        with patch.object(reader, 'box_draft', return_value=None):
            self.assertEqual(self.live()['queue'], [])

    def test_IGSH_835_explicit_removal_and_transcript_do_not_confirm_second_send(self):
        stamp = '2026-10-25T12:00:00Z'
        user = dict(type='user', timestamp=stamp, message=dict(role='user', content='same'))
        with patch.object(reader, 'box_draft', return_value=None):
            for operation in ('dequeue', 'remove', 'popAll'):
                with self.subTest(operation=operation):
                    reader.QUEUE_CACHE.clear()
                    self.write(self.op('enqueue', 'same', timestamp=stamp),
                               self.op(operation, 'same', timestamp=stamp),
                               self.op('enqueue', 'same', timestamp=stamp))
                    self.assertEqual(self.live()['queue'], ['same'])
                    with self.path.open('a', encoding='utf-8') as output:
                        output.write(json.dumps(user) + '\n')
                    self.assertEqual(self.live()['queue'], ['same'])
                    with self.path.open('a', encoding='utf-8') as output:
                        output.write(json.dumps(user) + '\n')
                    self.assertEqual(self.live()['queue'], [])

    def test_IGSH_835_transcript_before_explicit_removal_preserves_second_send(self):
        stamp = '2026-10-25T12:00:00Z'
        user = dict(type='user', timestamp=stamp, message=dict(role='user', content='same'))
        self.write(self.op('enqueue', 'same', timestamp=stamp),
                   self.op('enqueue', 'same', timestamp=stamp), user)
        with patch.object(reader, 'box_draft', return_value=None):
            self.assertEqual(self.live()['queue'], ['same'])
            with self.path.open('a', encoding='utf-8') as output:
                output.write(json.dumps(self.op('remove', 'same', timestamp=stamp)) + '\n')
            status = self.live()
            self.assertEqual(status['queue'], ['same'])
            self.assertEqual(status['sent'], ['same'])
            reader.QUEUE_CACHE.clear()
            self.assertEqual(self.live()['queue'], ['same'])
            with self.path.open('a', encoding='utf-8') as output:
                output.write(json.dumps(user) + '\n')
            self.assertEqual(self.live()['queue'], [])

    def test_IGSH_835_partial_transcript_record_waits_for_complete_line(self):
        stamp = '2026-10-25T12:00:00Z'
        self.write(self.op('enqueue', 'hello', timestamp=stamp))
        event = json.dumps(dict(type='user', timestamp=stamp, message=dict(role='user', content='hello')))
        with patch.object(reader, 'box_draft', return_value=None):
            self.assertEqual(self.live()['queue'], ['hello'])
            with self.path.open('a', encoding='utf-8') as output:
                output.write(event[:20])
            self.assertEqual(self.live()['queue'], ['hello'])
            with self.path.open('a', encoding='utf-8') as output:
                output.write(event[20:] + '\n')
            self.assertEqual(self.live()['queue'], [])

    def test_IGSH_722_delivered_fixture_leaves_queue(self):
        fixture = ROOT / 'tools/fixtures/IGSH-722/queue-ops.jsonl'
        events = [json.loads(line) for line in fixture.read_text(encoding='utf-8').splitlines()]
        delivered = next(i for i, event in enumerate(events)
                         if event.get('operation') == 'remove' and 'RUNNER 8' in event.get('content', ''))
        self.write(*events[:delivered + 1])
        with self.path.open('rb') as source:
            queue, sent = reader.queue_replay(source, self.path.stat().st_size)
            self.assertEqual(queue, [])
            self.assertTrue(any('from-name="RUNNER 8"' in text for text in sent))
            with patch.object(reader, 'box_draft', return_value=None):
                fields = reader.live_fields(source, self.path.stat().st_size, 'term-1')
            self.assertEqual(fields['queue'], [])
            self.assertNotIn('<cross-session-message', json.dumps(fields['queue']))
            self.assertIn(dict(text='RUNNER 8: status update body for the test.', sender='RUNNER 8', injected=True), fields['sent'])
        with fixture.open('rb') as source, patch.object(reader, 'box_draft', return_value=None):
            self.assertEqual(reader.queue_replay(source, fixture.stat().st_size)[0], [])
            fields = reader.live_fields(source, fixture.stat().st_size, 'term-1')
            self.assertEqual(fields['queue'], [])
            self.assertNotIn('<cross-session-message', json.dumps(fields['queue']))

    def test_IGSH_722_remove_matches_envelope_without_hop_chain(self):
        queued = '<cross-session-message from="hidden" hop-chain="relay" from-name="RUNNER 8" from-mode="bypass">\nReady &amp; waiting\n</cross-session-message>'
        removed = '<cross-session-message from-name="RUNNER 8">Ready & waiting</cross-session-message>'
        for operation in ('remove', 'dequeue'):
            with self.subTest(operation=operation):
                state = ([], [])
                reader.queue_apply(state, self.op('enqueue', queued))
                reader.queue_apply(state, self.op(operation, removed, reason='absorbed_mid_turn'))
                self.assertEqual(state, ([], [queued]))

    def test_IGSH_722_remove_keeps_plain_text_exact_and_contentless_dequeue(self):
        state = ([], [])
        for text in ('hello ', 'hello', 'other'):
            reader.queue_apply(state, self.op('enqueue', text))
        reader.queue_apply(state, self.op('remove', ' hello'))
        self.assertEqual(state, (['hello ', 'hello', 'other'], []))
        reader.queue_apply(state, self.op('remove', 'hello'))
        self.assertEqual(state, (['hello ', 'other'], ['hello']))
        reader.queue_apply(state, self.op('dequeue'))
        self.assertEqual(state, (['other'], ['hello', 'hello ']))

    def test_IGSH_722_envelope_key_preserves_sender_and_full_body(self):
        for kind in ('cross-session-message', 'task-notification'):
            with self.subTest(kind=kind):
                prefix = 'x' * (reader.QUEUE_TEXT + 1)
                queued = f'<{kind} from-name="RUNNER &amp; 8" hop-chain="relay">{prefix} &amp; ready</{kind}>'
                state = ([], [])
                reader.queue_apply(state, self.op('enqueue', queued))
                reader.queue_apply(state, self.op('remove', f'<{kind} from-name="other">{prefix} &amp; ready</{kind}>'))
                reader.queue_apply(state, self.op('remove', f'<{kind} from-name="RUNNER &amp; 8">{prefix} different</{kind}>'))
                reader.queue_apply(state, self.op('remove', prefix + ' & ready'))
                self.assertEqual(state, ([queued], []))
                reader.queue_apply(state, self.op('remove', f'<{kind} from-name="RUNNER &#38; 8"> \n{prefix} & ready\n </{kind}>'))
                self.assertEqual(state, ([], [queued]))
        queued = '<cross-session-message>hello</cross-session-message>'
        state = ([queued], [])
        reader.queue_apply(state, self.op('remove', '<cross-session-message from-name="another session">hello</cross-session-message>'))
        self.assertEqual(state, ([queued], []))
        reader.queue_apply(state, self.op('remove', queued))
        self.assertEqual(state, ([], [queued]))

    def test_IGSH_722_queued_envelopes_are_unwrapped_before_clipping(self):
        body = 'RUNNER 5: 7686 update &amp; <summary>ready</summary> ' + 'x' * 700
        envelope = '<cross-session-message from="uds:/run/user/1000/cc-socks/92156.sock" hop-chain="relay" from-name="RUNNER 5" from-mode="bypass">' + body + '</cross-session-message>'
        self.write(self.op('enqueue', envelope), self.op('enqueue', 'mine'))
        with patch.object(reader, 'box_draft', return_value=''):
            status = self.live()
            expected = dict(text=('RUNNER 5: 7686 update & ready ' + 'x' * 700)[:reader.QUEUE_TEXT], sender='RUNNER 5', injected=True)
            self.assertEqual(status['queue'], [expected, 'mine'])
            for hidden in ('<cross-session-message', 'uds:', 'hop-chain', '<summary>', '&amp;'):
                self.assertNotIn(hidden, json.dumps(status['queue']))
            with self.path.open('a') as output:
                output.write(json.dumps(self.op('remove', envelope)) + '\n')
            status = self.live()
            self.assertEqual(status['sent'], [expected])
            self.assertEqual(status['queue'], ['mine'])

    def test_IGSH_722_queue_sender_fallback_tasks_and_entities(self):
        samples = [
            ('<cross-session-message from="hidden">hello</cross-session-message>', 'another session', 'hello'),
            ('<cross-session-message from-name="  ">hello</cross-session-message>', 'another session', 'hello'),
            ('<cross-session-message from-name="RUNNER &amp; &#53;">&lt;ok&gt; &#x1F680;</cross-session-message>', 'RUNNER & 5', '<ok> 🚀'),
            ('<task-notification from-name="ignored"><summary>done &amp; ready</summary></task-notification>', 'Task', 'done & ready'),
            ('<cross-session-message from="' + 's' * 700 + '" from-name="RUNNER 5">body</cross-session-message>', 'RUNNER 5', 'body'),
        ]
        for envelope, sender, text in samples:
            with self.subTest(ticket='IGSH-722', sender=sender):
                self.write(self.op('enqueue', envelope))
                with patch.object(reader, 'box_draft', return_value=None):
                    self.assertEqual(self.live()['queue'], [dict(text=text, sender=sender, injected=True)])
                self.query.update(cached_sid='', inode=0, offset=0)

    def test_IGSH_722_queue_keeps_non_envelopes_as_plain_strings(self):
        samples = ['hello', 'Please read <cross-session-message>example</cross-session-message>',
                   '<cross-session-message>one</cross-session-message> and reply',
                   '<cross-session-message from-name=R>bad</cross-session-message>']
        self.write(*(self.op('enqueue', text) for text in samples))
        with patch.object(reader, 'box_draft', return_value=None):
            self.assertEqual(self.live()['queue'], samples)

    def test_IGSH_682_queue_is_replayed_in_file_order(self):
        self.write(self.event('hi'), self.op('enqueue', 'first'), self.op('enqueue', 'second'), self.op('enqueue', 'third'))
        with patch.object(reader, 'box_draft', return_value=''):
            self.assertEqual(self.live()['queue'], ['first', 'second', 'third'])
            self.write(self.event('hi'), self.op('enqueue', 'first'), self.op('enqueue', 'second'), self.op('enqueue', 'third'),
                       self.op('dequeue'), self.op('remove', 'third', reason='absorbed_mid_turn'))
            self.query.update(cached_sid='', inode=0, offset=0)
            status = self.live()
            self.assertEqual(status['queue'], ['second'])
            self.assertEqual(status['sent'], ['first', 'third'])

    def test_IGSH_682_pop_all_clears_queue_and_sent_and_new_batch_resets_sent(self):
        self.write(self.op('enqueue', 'a'), self.op('enqueue', 'b'), self.op('popAll', 'a\nb'))
        with patch.object(reader, 'box_draft', return_value=''):
            status = self.live()
            self.assertEqual((status['queue'], status['sent']), ([], []))
            self.write(self.op('enqueue', 'a'), self.op('dequeue'), self.op('enqueue', 'b'))
            self.query.update(cached_sid='', inode=0, offset=0)
            status = self.live()
            self.assertEqual((status['queue'], status['sent']), (['b'], []))

    def test_IGSH_682_queue_is_incremental_and_ignores_sidechains_and_notifications(self):
        self.write(self.op('enqueue', 'a'))
        with patch.object(reader, 'box_draft', return_value=None):
            self.assertEqual(self.live()['queue'], ['a'])
            with self.path.open('a') as output:
                output.write(json.dumps(self.op('enqueue', 'side', isSidechain=True)) + '\n')
                output.write(json.dumps(self.op('enqueue', '<task-notification>done</task-notification>')) + '\n')
                output.write(json.dumps(self.op('enqueue', 'b')) + '\n')
            status = self.live()
            self.assertEqual(status['queue'], ['a', 'b'])
            self.assertNotIn('draft', status)

    def test_IGSH_682_live_rows_are_opt_in_and_never_appear_for_older_pages(self):
        self.write(self.event('hi'), self.op('enqueue', 'a'))
        with patch.object(reader, 'box_draft', return_value='typed'):
            _fields, rows = self.read()
            self.assertFalse([row for row in rows if row['role'] == 'status'])
            self.query.update(live=True, cached_sid='', inode=0, offset=0)
            status = self.live()
            self.assertEqual((status['queue'], status['draft']), (['a'], 'typed'))

    def test_IGSH_682_dim_suggestion_is_never_draft_text(self):
        dim = '\x1b[2m'
        reset = '\x1b[0m'
        rule = '─' * 20
        cases = [
            (f'❯ {dim}Try asking about the next step{reset}\n{rule}\n', ''),
            (f'❯ \x1b[7mT{reset}{dim}ry asking{reset}\n{rule}\n', ''),
            (f'❯ \x1b[7mT\x1b[27m\x1b[90mry asking\x1b[39m\n{rule}\n', ''),
            (f'❯ \x1b[38;5;244mgrey words{reset}\n{rule}\n', ''),
            (f'❯ \x1b[38;2;153;153;153mgrey words{reset}\n{rule}\n', ''),
            (f'❯ real draft\x1b[7m {reset}\n{rule}\n', 'real draft'),
            ('❯ real draft\n' + rule + '\n', 'real draft'),
            (f'❯ first line\n  second line{dim} hint{reset}\n{rule}\n', 'first line\nsecond line'),
            (f'old output\n❯ {dim}old{reset}\n{rule}\n❯ typed now\n{rule}\n', 'typed now'),
            (f'❯ \x1b[1mbold\x1b[22m {dim}ghost{reset}\n{rule}\n', 'bold'),
            (f'❯ \n{rule}\n', ''),
        ]
        for shown, expected in cases:
            self.assertEqual(reader.box_draft_text(shown), expected, repr(shown))
        self.assertIsNone(reader.box_draft_text('no prompt here\n'))

    def test_IGSH_682_box_draft_asks_tmux_for_escapes_without_wrapping(self):
        calls = []

        def run(argv, **kwargs):
            calls.append(argv)
            return subprocess.CompletedProcess(argv, 0, '❯ hello\n' + '─' * 5 + '\n', '')
        with patch.object(reader.subprocess, 'run', side_effect=run):
            self.assertEqual(reader.box_draft('=term-1:'), 'hello')
        self.assertEqual(calls, [['tmux', 'capture-pane', '-p', '-e', '-J', '-t', '=term-1:']])

    def test_IGSH_682_draft_op_clears_then_types_without_enter(self):
        calls = []

        def run(argv, **kwargs):
            calls.append(argv)
            return subprocess.CompletedProcess(argv, 0, b'', b'')
        with patch.object(reader, 'box_draft', return_value='old text'), patch.object(reader, 'prompt_box_text', side_effect=['old text', '']), patch.object(reader.subprocess, 'run', side_effect=run):
            wire = reader.draft_response(dict(op='draft', id=4, tmux='term-1', text='new text'))
        self.assertTrue(wire.startswith('@mp\t4\tdraft\tok\t-\t0\n'))
        self.assertEqual(calls, [['tmux', 'send-keys', '-t', '=term-1:', 'C-u'], ['tmux', 'send-keys', '-t', '=term-1:', '-l', '--', 'new text']])
        calls.clear()
        with patch.object(reader, 'box_draft', return_value='same'), patch.object(reader.subprocess, 'run', side_effect=run):
            reader.draft_response(dict(op='draft', id=4, tmux='term-1', text='same'))
        self.assertEqual(calls, [])
        with patch.object(reader, 'box_draft', return_value='gone'), patch.object(reader, 'prompt_box_text', side_effect=['gone', '']), patch.object(reader.subprocess, 'run', side_effect=run):
            reader.draft_response(dict(op='draft', id=4, tmux='term-1', text=''))
        self.assertEqual(calls, [['tmux', 'send-keys', '-t', '=term-1:', 'C-u']])
        for name, text in (('bad name', 'x'), ('term-1', 'x\x00')):
            self.assertIn('\terror\t', reader.draft_response(dict(op='draft', id=4, tmux=name, text=text)))

    def test_IGSH_720_draft_clears_rows_missing_from_first_capture(self):
        box = 'old first\nold second'
        captures = 0
        calls = []

        def run(argv, **kwargs):
            nonlocal box, captures
            calls.append(argv)
            shown = ''
            if 'capture-pane' in argv:
                captures += 1
                visible = box.split('\n')[-1] if captures == 1 else box
                shown = '❯ ' + visible.replace('\n', '\n  ') + '\n' + '─' * 20 + '\n'
            elif 'send-keys' in argv:
                if argv[-1] == 'C-u':
                    box = box.rsplit('\n', 1)[0] if '\n' in box else ''
                elif '-l' in argv:
                    box += argv[-1]
            return subprocess.CompletedProcess(argv, 0, shown, '')

        with patch.object(reader.subprocess, 'run', side_effect=run):
            wire = reader.draft_response(dict(op='draft', id=720, tmux='term-1', text='new draft'))
        self.assertIn('\tdraft\tok\t-\t0\n', wire)
        self.assertEqual(box, 'new draft')
        self.assertEqual(sum('-l' in call for call in calls), 1)
        self.assertFalse(any(call[-1] == 'Enter' for call in calls))

    def test_IGSH_720_draft_does_not_type_when_clear_stalls(self):
        calls = []

        def run(argv, **kwargs):
            calls.append(argv)
            shown = '❯ stuck draft\n' + '─' * 20 + '\n' if 'capture-pane' in argv else ''
            return subprocess.CompletedProcess(argv, 0, shown, '')

        with patch.object(reader.subprocess, 'run', side_effect=run):
            wire = reader.draft_response(dict(op='draft', id=720, tmux='term-1', text='new draft'))
        self.assertIn('\tdraft\terror\tbox_not_clear\t0\n', wire)
        self.assertEqual([call[-1] for call in calls if 'send-keys' in call], ['C-u', 'C-u', 'C-k'])

    def test_IGSH_682_edit_queued_presses_only_up(self):
        calls = []
        with patch.object(reader.subprocess, 'run', side_effect=lambda argv, **kwargs: calls.append(argv) or subprocess.CompletedProcess(argv, 0, b'', b'')):
            wire = reader.stop_response(dict(op='edit_queued', id=5, tmux='term-1'))
        self.assertTrue(wire.startswith('@mp\t5\tedit_queued\tok\t-\t0\n'))
        self.assertEqual(calls, [['tmux', 'send-keys', '-t', '=term-1:', 'Up']])

    def test_IGSH_682_serve_routes_draft_and_edit_queued(self):
        source = (ROOT / 'src/chat_history_reader.py').read_text(encoding='utf-8')
        self.assertIn('query.get("op") == "draft"', source)
        self.assertIn('"send_queued", "edit_queued"', source)



class HeldQueueTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(callable(getattr(reader, 'enqueue_response', None)), 'safe held-send boundary is missing')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.path = self.home / 'session.jsonl'
        self.path.write_text('')
        self.addCleanup(patch.stopall)
        patch.object(reader, 'HOME', self.home).start()
        patch.object(reader, 'resolve_tmux_agent', return_value=('sid', self.path)).start()
        self.send = patch.object(reader, 'send_response', return_value='@mp\t1\tsend\tok\t-\t0\n@@MP_END@@\n').start()
        self.real_ready = reader.held_queue_ready
        self.ready = patch.object(reader, 'held_queue_ready', return_value=False).start()

    def enqueue(self, text, stamp):
        return reader.enqueue_response(dict(id=1, tmux='term-1', text=text, queued_at=stamp, message_id=f'message-{stamp}'))

    def cancel(self, text, stamp):
        return reader.cancel_queued_response(dict(id=2, tmux='term-1', text=text, queued_at=stamp, message_id=f'message-{stamp}'))

    def test_handoff_holds_writer_lock_after_durable_submission(self):
        self.enqueue('first', 1)
        self.enqueue('next', 2)
        self.ready.return_value = True
        db_path = reader.held_queue_path('term-1')

        def handoff(_query):
            with sqlite3.connect(db_path, timeout=0) as other:
                self.assertEqual(other.execute('SELECT state FROM messages ORDER BY created').fetchall(),
                                 [('submitted',), ('queued',)])
                with self.assertRaisesRegex(sqlite3.OperationalError, 'locked'):
                    other.execute('BEGIN IMMEDIATE')
            return '@mp\t1\tsend\tok\t-\t0\n@@MP_END@@\n'

        self.send.side_effect = handoff
        reader.drain_held_queue('term-1', 'sid', self.path)
        with sqlite3.connect(db_path, timeout=0) as other:
            other.execute('BEGIN IMMEDIATE')
            self.assertEqual(other.execute('SELECT state FROM messages ORDER BY created').fetchall(),
                             [('submitted',), ('queued',)])
        self.send.assert_called_once()

    def test_handoff_subprocess_failure_keeps_submission_and_blocks_next(self):
        self.enqueue('first', 1)
        self.enqueue('next', 2)
        self.ready.return_value = True
        self.send.side_effect = subprocess.TimeoutExpired(['tmux'], 5)
        reader.drain_held_queue('term-1', 'sid', self.path)
        with sqlite3.connect(reader.held_queue_path('term-1'), timeout=0) as other:
            other.execute('BEGIN IMMEDIATE')
            self.assertEqual(other.execute('SELECT state,receipt FROM messages ORDER BY created').fetchall(),
                             [('submitted', 'Terminal handoff interrupted; receipt not confirmed'),
                              ('queued', '')])
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.send.assert_called_once()

    def test_handoff_rollback_keeps_submission_and_blocks_next(self):
        self.enqueue('first', 1)
        self.enqueue('next', 2)
        self.ready.return_value = True
        self.send.side_effect = RuntimeError('handoff interrupted')
        with self.assertRaisesRegex(RuntimeError, 'handoff interrupted'):
            reader.drain_held_queue('term-1', 'sid', self.path)
        with sqlite3.connect(reader.held_queue_path('term-1'), timeout=0) as other:
            other.execute('BEGIN IMMEDIATE')
            self.assertEqual(other.execute('SELECT state FROM messages ORDER BY created').fetchall(),
                             [('submitted',), ('queued',)])
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.send.assert_called_once()

    def test_busy_empty_handoff_waits_for_receipt_and_never_replays(self):
        self.path.write_text(json.dumps(dict(type='assistant', message=dict(stop_reason=None))) + '\n')
        with patch.object(reader, 'start_held_worker'), \
                patch.object(reader.subprocess, 'run', return_value=types.SimpleNamespace(stdout='esc to interrupt\n❯ \n────\n')):
            self.enqueue('target', 1)
            with reader.held_queue_db('term-1') as db:
                self.assertEqual(db.execute('SELECT state FROM messages').fetchone(), ('queued',))
            self.ready.side_effect = self.real_ready
            self.send.side_effect = subprocess.TimeoutExpired(['tmux'], 5)
            query = dict(id=7, tmux='term-1', offset=0, cached_sid='', inode=0)
            reader.response(query, resolved=('claude', 'sid', self.path, None))
            reader.drain_held_queue('term-1', 'sid', self.path)
            self.enqueue('target', 1)
            with reader.held_queue_db('term-1') as db:
                self.assertEqual(db.execute('SELECT state FROM messages').fetchall(), [('submitted',)])
            self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], [])
            self.send.assert_called_once()
            with self.path.open('a') as source:
                source.write(json.dumps(dict(type='attachment', attachment=dict(type='queued_command', prompt='target'), timestamp='receipt')) + '\n')
            reader.drain_held_queue('term-1', 'sid', self.path)
            self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], ['target'])
            self.send.assert_called_once()

    def test_busy_guards_and_idle_completion_through_enqueue(self):
        cases = [
            ('esc to interrupt\n❯ unfinished text\n────\n', 'unfinished text'),
            ('esc to interrupt\nedit queued messages\n❯ \n────\n', ''),
            ('esc to interrupt\nselect a queued message\n❯ \n────\n', ''),
            ('esc to interrupt\nenter to select\n❯ \n────\n', ''),
            ('esc to interrupt\nesc to cancel\n❯ \n────\n', ''),
            ('esc to interrupt\nDo you want to proceed?\n❯ Allow once\n  Allow always\nEnter to select · Esc to cancel\n────\n', 'Allow once\nAllow always\nEnter to select · Esc to cancel'),
            ('esc to interrupt\nno prompt\n', None),
            ('❯ \n────\n', ''),
        ]
        self.path.write_text(json.dumps(dict(type='assistant', message=dict(stop_reason=None))) + '\n')
        self.ready.side_effect = self.real_ready
        with patch.object(reader, 'start_held_worker'), patch.object(reader.subprocess, 'run') as capture:
            for stamp, (shown, draft) in enumerate(cases, 1):
                with self.subTest(shown=shown):
                    capture.return_value = types.SimpleNamespace(stdout=shown)
                    self.assertEqual(reader.box_draft_text(shown), draft)
                    self.enqueue('target', stamp)
                    reader.drain_held_queue('term-1', 'sid', self.path)
                    self.assertEqual(reader.box_draft_text(capture.return_value.stdout), draft)
                    self.send.assert_not_called()
                    self.assertIn('\tcancel_queued\tok\t', self.cancel('target', stamp))

    def test_busy_empty_hands_off_with_permission_words_in_conversation(self):
        self.path.write_text(json.dumps(dict(type='assistant', message=dict(stop_reason=None))) + '\n')
        self.ready.side_effect = self.real_ready
        with patch.object(reader, 'start_held_worker'), \
                patch.object(reader.subprocess, 'run', return_value=types.SimpleNamespace(stdout='The documentation says "do you want to proceed", "allow once", and "allow always".\nesc to interrupt\n❯ \n────\n')):
            wire = self.enqueue('target', 1)
        self.assertIn('"state":"submitted"', wire)
        self.send.assert_called_once()

    def test_ctrl_c_busy_empty_hands_off(self):
        self.path.write_text(json.dumps(dict(type='user', message=dict(content='earlier'))) + '\n')
        self.ready.side_effect = self.real_ready
        with patch.object(reader, 'start_held_worker'), \
                patch.object(reader.subprocess, 'run', return_value=types.SimpleNamespace(stdout='ctrl+c to interrupt\n❯ \n────\n')):
            wire = self.enqueue('target', 1)
        self.assertIn('"state":"submitted"', wire)
        self.send.assert_called_once()
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], [])

    def test_cancel_before_handoff_never_reaches_terminal(self):
        self.assertIn('\tsend\tok\t', self.enqueue('target', 1))
        self.assertIn('\tcancel_queued\tok\t', self.cancel('target', 1))
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.send.assert_not_called()
        self.assertIn('\tcancel_queued\tok\t', self.cancel('target', 1))

    def test_cancel_preserves_other_messages_and_order(self):
        for stamp, text in enumerate(['first', 'target', 'third'], 1):
            self.enqueue(text, stamp)
        self.cancel('target', 2)
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual([call.args[0]['text'] for call in self.send.call_args_list], ['first'])
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['queue'], ['third'])

    def test_after_handoff_cancel_refused_even_without_receipt(self):
        self.ready.return_value = True
        self.enqueue('target', 1)
        self.assertIn('\tcancel_queued\terror\tnot_queued\t', self.cancel('target', 1))
        self.send.assert_called_once()

    def test_terminal_failure_does_not_lose_unrelated_messages(self):
        self.enqueue('first', 1)
        self.enqueue('third', 3)
        self.ready.return_value = True
        self.send.side_effect = subprocess.TimeoutExpired(['tmux'], 5)
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['queue'], ['third'])
        self.assertIn('\tcancel_queued\terror\tnot_queued\t', self.cancel('first', 1))
        self.assertIn('\tcancel_queued\tok\t', self.cancel('third', 3))

    def test_duplicate_text_cancel_identifies_only_requested_message(self):
        self.enqueue('same', 1)
        self.enqueue('same', 2)
        self.cancel('same', 2)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['queue'], ['same'])
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.send.assert_called_once()

    def test_enqueue_retry_and_cancel_tombstone_are_idempotent(self):
        self.enqueue('target', 1)
        self.enqueue('target', 1)
        self.cancel('target', 1)
        self.enqueue('target', 1)
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.send.assert_not_called()

    def test_new_session_never_receives_previous_sessions_queue(self):
        self.enqueue('target', 1)
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'other-sid', self.path)
        self.send.assert_not_called()

    def test_real_receipt_required_and_unrelated_answer_not_receipt(self):
        self.ready.return_value = True
        self.enqueue('target', 1)
        self.path.write_text(json.dumps(dict(type='assistant', message=dict(role='assistant', content=[dict(type='text', text='answer')], stop_reason='end_turn'))) + '\n')
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], [])
        with self.path.open('a') as source:
            source.write(json.dumps(dict(type='user', message=dict(role='user', content='target'), timestamp='2026-10-28T20:00:00Z')) + '\n')
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], ['target'])
        self.assertIn('\tcancel_queued\terror\tnot_queued\t', self.cancel('target', 1))



    def test_large_preceding_record_does_not_block_exact_receipt(self):
        self.enqueue('target', 1)
        self.enqueue('next', 2)
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        preceding = json.dumps(dict(type='assistant', message=dict(role='assistant', content='x' * (reader.TAIL_BYTES + 1)))) + '\n'
        wrong = json.dumps(dict(type='user', message=dict(role='user', content='target extra'))) + '\n'
        receipt = json.dumps(dict(type='user', message=dict(role='user', content='target'), timestamp='2026-10-28T20:00:00Z')) + '\n'
        self.path.write_text(preceding + wrong + receipt)
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], [])
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], ['target'])
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual([call.args[0]['text'] for call in self.send.call_args_list], ['target', 'next'])

    def test_large_exact_user_receipt_unblocks_queue_once(self):
        text = 'x' * (reader.TAIL_BYTES + 1)
        self.enqueue(text, 1)
        self.enqueue('next', 2)
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.path.write_text(json.dumps(dict(type='user', message=dict(role='user', content=text), timestamp='2026-10-28T20:00:00Z')) + '\n')
        reader.drain_held_queue('term-1', 'sid', self.path)
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], [text])
        self.assertEqual([call.args[0]['text'] for call in self.send.call_args_list], [text, 'next'])

    def test_incomplete_large_receipt_retains_anchor_until_completed(self):
        text = 'x' * (reader.TAIL_BYTES + 1)
        self.enqueue(text, 1)
        self.enqueue('next', 2)
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        inode = self.path.stat().st_ino
        record = json.dumps(dict(type='user', message=dict(role='user', content=text), timestamp='2026-10-28T20:00:00Z'))
        self.path.write_text(record)
        reader.drain_held_queue('term-1', 'other-sid', self.path)
        self.assertFalse(reader.drain_held_queue('term-1', 'sid', self.path))
        with reader.held_queue_db('term-1') as db:
            self.assertEqual(db.execute("SELECT sid,source_inode,scan,state FROM messages WHERE key='message-1'").fetchone(), ('sid', inode, 0, 'submitted'))
        self.send.assert_called_once()
        with self.path.open('a') as source:
            source.write('\n')
        reader.drain_held_queue('term-1', 'sid', self.path)
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], [text])
        self.assertEqual([call.args[0]['text'] for call in self.send.call_args_list], [text, 'next'])

    def test_cancel_wins_race_before_submission_claim(self):
        self.enqueue('target', 1)
        def cancel_before_claim(*args):
            self.assertIn('\tcancel_queued\tok\t', self.cancel('target', 1))
            return True
        self.ready.side_effect = cancel_before_claim
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.send.assert_not_called()

    def test_submission_wins_race_before_terminal_receipt(self):
        import threading
        entered, release = threading.Event(), threading.Event()
        self.ready.return_value = True
        errors = []
        def handing_off(q):
            entered.set()
            if not release.wait(5):
                raise AssertionError('test did not release terminal handoff')
            return '@mp\t0\tsend\tok\t-\t0\n@@MP_END@@\n'
        self.send.side_effect = handing_off
        def enqueue():
            try:
                self.enqueue('target', 1)
            except BaseException as error:
                errors.append(error)
        cancel_started = threading.Event()
        cancel_results = []
        def cancel():
            try:
                cancel_started.set()
                cancel_results.append(self.cancel('target', 1))
            except BaseException as error:
                errors.append(error)
        worker = threading.Thread(target=enqueue)
        canceller = threading.Thread(target=cancel)
        worker.start()
        try:
            self.assertTrue(entered.wait(5))
            canceller.start()
            self.assertTrue(cancel_started.wait(5))
        finally:
            release.set()
            worker.join(5)
            if canceller.ident is not None:
                canceller.join(5)
        self.assertFalse(worker.is_alive())
        self.assertFalse(canceller.is_alive())
        self.assertEqual(errors, [])
        self.assertEqual(len(cancel_results), 1)
        self.assertIn('\tcancel_queued\terror\tnot_queued\t', cancel_results[0])
        self.send.assert_called_once()


    def test_queue_removal_not_receipt_but_queued_command_is(self):
        self.ready.return_value = True
        self.enqueue('target', 1)
        self.path.write_text(json.dumps(dict(type='queue-operation', operation='remove', content='target')) + '\n')
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], [])
        with self.path.open('a') as source:
            source.write(json.dumps(dict(type='attachment', attachment=dict(type='queued_command', prompt='target'), timestamp='2026-10-28T20:00:00Z')) + '\n')
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], ['target'])


    def test_recovery_ignores_legacy_cursor_before_first_claim(self):
        import sqlite3
        self.ready.return_value = True
        self.enqueue('head ', 1)
        self.ready.return_value = False
        self.path.write_text(json.dumps(dict(type='user', message=dict(role='user', content='head'), timestamp='receipt')) + '\n')
        with sqlite3.connect(reader.held_queue_path('term-1')) as db:
            db.execute('UPDATE messages SET recovery_scan=NULL, scan=?', (self.path.stat().st_size + 100,))
            db.execute('PRAGMA user_version=1')
        reader.drain_held_queue('term-1', 'sid', self.path)
        with reader.held_queue_db('term-1') as db:
            self.assertEqual(db.execute("SELECT state,scan,recovery_scan FROM messages WHERE key='message-1'").fetchone(),
                             ('delivered', self.path.stat().st_size, self.path.stat().st_size))
        self.send.assert_called_once()

    def test_recovery_ignores_legacy_cursor_between_slices(self):
        self.ready.return_value = True
        self.enqueue('head ', 1)
        self.ready.return_value = False
        padding = (json.dumps(dict(type='system', message='padding')) + '\n') * 12
        self.path.write_text(padding + json.dumps(dict(type='user', message=dict(role='user', content='head'), timestamp='receipt')) + '\n')
        with patch.object(reader, 'TAIL_BYTES', 128):
            for index in range(20):
                with reader.held_queue_db('term-1') as db:
                    db.execute('UPDATE messages SET scan=? WHERE state IN ("queued", "submitted")', (self.path.stat().st_size + 100,))
                progressing = reader.drain_held_queue('term-1', 'sid', self.path)
                if index == 0:
                    self.assertTrue(progressing)
        with reader.held_queue_db('term-1') as db:
            self.assertEqual(db.execute("SELECT state FROM messages WHERE key='message-1'").fetchone(), ('delivered',))
        self.send.assert_called_once()

    def test_legacy_insert_and_newer_schema_version(self):
        import sqlite3
        self.enqueue('first', 1)
        with sqlite3.connect(reader.held_queue_path('term-1')) as db:
            db.execute('PRAGMA user_version=42')
            db.execute("INSERT INTO messages(key,sid,text,queued_at,state,scan,origin,source_inode,created) VALUES (?,?,?,?,?,?,?,?,?)",
                       ('legacy', 'sid', 'second', 2, 'queued', 999, 0, self.path.stat().st_ino, 2))
        with reader.held_queue_db('term-1') as db:
            self.assertEqual(db.execute('PRAGMA user_version').fetchone(), (42,))
            self.assertEqual(db.execute("SELECT scan,recovery_scan FROM messages WHERE key='legacy'").fetchone(), (999, 0))

    def test_consumer_status_wire_and_foreground_recovery_with_worker_lock(self):
        import fcntl
        import hashlib
        self.ready.return_value = True
        self.enqueue('accepted ', 1)
        self.ready.return_value = False
        self.enqueue('waiting', 2)
        self.path.write_text(json.dumps(dict(type='user', message=dict(role='user', content='accepted'), timestamp='receipt')) + '\n')
        lock_path = reader.held_queue_path('term-1').with_suffix('.' + hashlib.sha256(b'sid').hexdigest() + '.worker')
        query = dict(id=7, tmux='term-1', offset=0, cached_sid='', inode=0,
                     held=True, delivery_ids=['message-1', 'message-2', 'unknown'])
        with lock_path.open('a') as lock, patch.object(reader, 'resolve_response_path', return_value=('claude', 'sid', self.path, None)):
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            wire = reader.response(query, source='pass')
        lines = wire.splitlines()
        self.assertEqual(len(lines[0].split('\t')), 15)
        status = next(json.loads(line) for line in lines[1:-1] if json.loads(line).get('role') == 'status')
        self.assertEqual(status['deliveries'], [dict(id='message-1', state='delivered'), dict(id='message-2', state='queued')])
        self.assertEqual(status['held'], [dict(id='message-2', text='waiting', queued_at=2, state='queued', offset=0, inode=self.path.stat().st_ino, sid='sid')])
        self.send.assert_called_once()
        with patch.object(reader, 'resolve_response_path', return_value=('claude', 'other-sid', self.path, None)):
            other = reader.response(query)
        status = next(json.loads(line) for line in other.splitlines()[1:-1] if json.loads(line).get('role') == 'status')
        self.assertEqual(status['deliveries'], [])
        self.assertEqual(status['held'], [])

    def test_watch_finishes_recovery_at_cached_eof_with_one_event(self):
        import fcntl
        import hashlib
        self.enqueue('head', 1)
        self.enqueue('accepted', 2)
        with reader.held_queue_db('term-1') as db:
            db.execute("UPDATE messages SET state='submitted' WHERE key='message-1'")
        padding = (json.dumps(dict(type='system', message='padding')) + '\n') * 12
        self.path.write_text(
            json.dumps(dict(type='user', message=dict(role='user', content='head'), timestamp='head')) + '\n'
            + padding
            + json.dumps(dict(type='user', message=dict(role='user', content='accepted'), timestamp='accepted')) + '\n')
        info = self.path.stat()
        resolved = ('claude', 'sid', self.path, None)
        query = dict(id=7, tmux='term-1', cwd='', offset=info.st_size, cached_sid='sid', inode=info.st_ino,
                     held=True, delivery_ids=['message-1', 'message-2'], _held_queue_progress=True)
        request = io.TextIOWrapper(io.BytesIO((json.dumps(query) + '\n').encode()))
        output = io.StringIO()
        lock_path = reader.held_queue_path('term-1').with_suffix('.' + hashlib.sha256(b'sid').hexdigest() + '.worker')
        events = []

        def one_event(*args):
            events.append('initial')
            yield resolved

        real_response = reader.response
        calls = []

        def bounded_response(*args):
            calls.append(1)
            self.assertLess(len(calls), 40, 'recovery failed to stop')
            return real_response(*args)

        with lock_path.open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with patch.object(sys, 'stdin', request), patch.object(sys, 'stdout', output), \
                    patch.object(reader, 'conversation_owner', return_value=None), \
                    patch.object(reader, 'resolve_response_path', return_value=resolved), \
                    patch.object(reader, 'watch_changes', side_effect=one_event), \
                    patch.object(reader, 'response', side_effect=bounded_response), \
                    patch.object(reader, 'TAIL_BYTES', 128), patch.object(reader.time, 'sleep'), \
                    patch.object(reader.subprocess, 'Popen', side_effect=AssertionError('worker started')):
                reader.watch('source', as_owner=True)
        frames = [json.loads(line)['wire'] for line in output.getvalue().splitlines()]
        self.assertEqual(events, ['initial'])
        self.assertGreater(len(calls), 2)
        self.assertTrue(all(wire.splitlines()[0].split('\t')[8] == '0' for wire in frames))
        status = next(json.loads(line) for line in frames[-1].splitlines()[1:-1] if json.loads(line).get('role') == 'status')
        self.assertEqual(status['deliveries'], [dict(id='message-1', state='delivered'), dict(id='message-2', state='delivered')])
        self.assertEqual(status['held'], [])
        with reader.held_queue_db('term-1') as db:
            self.assertEqual(db.execute('SELECT key,state FROM messages ORDER BY created').fetchall(),
                             [('message-1', 'delivered'), ('message-2', 'delivered')])
            self.assertEqual(db.execute('SELECT count(*) FROM held_receipts').fetchone(), (2,))
        self.send.assert_not_called()

    def test_deferred_receipt_survives_old_worker_cursor_overwrite(self):
        self.ready.return_value = True
        self.enqueue('accepted ', 1)
        self.ready.return_value = False
        self.enqueue('later', 2)
        receipt = json.dumps(dict(type='attachment', attachment=dict(type='queued_command', prompt='accepted'), timestamp='first')) + '\n'
        padding = (json.dumps(dict(type='system', message='padding')) + '\n') * 12
        with self.path.open('a') as source:
            source.write(receipt + padding)
        with patch.object(reader, 'TAIL_BYTES', 128):
            reader.drain_held_queue('term-1', 'sid', self.path)
            with reader.held_queue_db('term-1') as db:
                self.assertEqual(db.execute("SELECT state FROM messages WHERE key='message-1'").fetchone()[0], 'submitted')
                db.execute("UPDATE messages SET scan=? WHERE key='message-1'", (self.path.stat().st_size,))
            for _ in range(10):
                reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], ['accepted '])
        self.send.assert_called_once()

    def test_changed_claim_does_not_acknowledge(self):
        self.ready.return_value = True
        self.enqueue('accepted ', 1)
        self.ready.return_value = False
        self.enqueue('later', 2)
        receipt = json.dumps(dict(type='user', message=dict(role='user', content='accepted'), timestamp='first')) + '\n'
        with self.path.open('a') as source:
            source.write(receipt + (json.dumps(dict(type='system', message='padding')) + '\n') * 12)
        with patch.object(reader, 'TAIL_BYTES', 128):
            reader.drain_held_queue('term-1', 'sid', self.path)
            with self.path.open('r+') as source:
                source.write(receipt.replace('first', 'other', 1))
            for _ in range(10):
                reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], [])
        self.send.assert_called_once()

    def test_old_repeat_receipt_before_origin_does_not_block_new_send(self):
        self.enqueue('yes', 1)
        with self.path.open('a') as source:
            source.write(json.dumps(dict(type='user', message=dict(role='user', content='yes'), timestamp='old')) + '\n')
        with reader.held_queue_db('term-1') as db:
            db.execute("UPDATE messages SET state='delivered',receipt='old' WHERE key='message-1'")
        self.enqueue('yes', 2)
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual([call.args[0]['text'] for call in self.send.call_args_list], ['yes'])
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], ['yes'])

    def test_trimmed_head_waits_for_three_queued_rows_before_handoff(self):
        self.ready.return_value = True
        self.enqueue('head ', 1)
        self.ready.return_value = False
        self.enqueue('first missing', 2)
        self.enqueue('second missing', 3)
        self.enqueue('third accepted', 4)
        head = json.dumps(dict(type='attachment', attachment=dict(type='queued_command', prompt='head'), timestamp='head')) + '\n'
        padding = (json.dumps(dict(type='system', message='padding')) + '\n') * 12
        third = json.dumps(dict(type='user', message=dict(role='user', content='third accepted'), timestamp='third')) + '\n'
        with self.path.open('a') as source:
            source.write(head + padding + third)
        with reader.held_queue_db('term-1') as db:
            db.execute("UPDATE messages SET scan=? WHERE key='message-1'", (self.path.stat().st_size,))
            db.execute('PRAGMA user_version=0')
        self.send.reset_mock()
        self.ready.return_value = True

        def verify_before_send(request):
            db = sqlite3.connect(reader.held_queue_path('term-1').as_uri() + '?mode=ro', uri=True, timeout=0)
            try:
                states = dict(db.execute('SELECT key,state FROM messages'))
            finally:
                db.close()
            self.assertEqual(states['message-1'], 'delivered')
            self.assertEqual(states['message-4'], 'delivered')
            self.assertEqual(states['message-3'], 'queued')
            return '@mp\t1\tsend\tok\t-\t0\n@@MP_END@@\n'

        self.send.side_effect = verify_before_send
        with patch.object(reader, 'TAIL_BYTES', 128):
            reader.drain_held_queue('term-1', 'sid', self.path)
            self.send.assert_not_called()
            with reader.held_queue_db('term-1') as db:
                self.assertEqual(db.execute("SELECT state FROM messages WHERE key='message-1'").fetchone()[0], 'submitted')
                self.assertEqual(db.execute("SELECT key FROM held_receipts WHERE key='message-1'").fetchone(), ('message-1',))
                db.execute("UPDATE messages SET scan=? WHERE key='message-1'", (self.path.stat().st_size,))
            for _ in range(20):
                reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual([call.args[0]['text'] for call in self.send.call_args_list], ['first missing'])
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], ['head ', 'third accepted'])
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['queue'], ['second missing'])

    def test_legacy_delivered_acceptance_does_not_acknowledge_later_copy(self):
        self.enqueue('same ', 1)
        acceptance = json.dumps(dict(type='attachment', attachment=dict(type='queued_command', prompt='same'), timestamp='first')) + '\n'
        with self.path.open('a') as source:
            source.write(acceptance)
        with reader.held_queue_db('term-1') as db:
            db.execute("UPDATE messages SET state='delivered',receipt='first' WHERE key='message-1'")
            db.execute('PRAGMA user_version=0')
        self.enqueue('same', 2)
        with self.path.open('a') as source:
            source.write(json.dumps(dict(type='user', message=dict(role='user', content='same'), timestamp='later')) + '\n')
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], ['same '])
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['queue'], ['same'])
        self.send.assert_not_called()

    def test_upgrade_recovers_trimmed_receipt_and_reconciles_later_queue(self):
        self.ready.return_value = True
        self.enqueue('accepted ', 1)
        self.ready.return_value = False
        self.enqueue('missing', 2)
        self.enqueue('already', 3)
        with self.path.open('a') as source:
            source.write(json.dumps(dict(type='attachment', attachment=dict(type='queued_command', prompt='accepted'), timestamp='first')) + '\n')
            source.write(json.dumps(dict(type='user', message=dict(role='user', content='already'), timestamp='third')) + '\n')
        with reader.held_queue_db('term-1') as db:
            db.execute("UPDATE messages SET scan=? WHERE key='message-1'", (self.path.stat().st_size,))
            db.execute('PRAGMA user_version=0')
        self.send.reset_mock()
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], ['accepted ', 'already'])
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['queue'], ['missing'])
        self.send.assert_not_called()
        self.ready.return_value = True
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual([call.args[0]['text'] for call in self.send.call_args_list], ['missing'])

    def test_reconciliation_respects_origin_identity_and_exact_interior(self):
        self.path.write_text(json.dumps(dict(type='user', message=dict(role='user', content='before'))) + '\n')
        self.enqueue('before', 1)
        self.enqueue('a b ', 2)
        with self.path.open('a') as source:
            source.write(json.dumps(dict(type='user', message=dict(role='user', content='a  b'))) + '\n')
        reader.drain_held_queue('term-1', 'other-sid', self.path)
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], [])
        replacement = self.home / 'replacement.jsonl'
        replacement.write_text(json.dumps(dict(type='user', message=dict(role='user', content='before'))) + '\n')
        with patch.object(reader, 'resolve_tmux_agent', return_value=('sid', replacement)):
            self.ready.return_value = True
            reader.drain_held_queue('term-1', 'sid', replacement)
        self.send.assert_not_called()
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], [])

    def test_interior_whitespace_is_not_normalized(self):
        self.enqueue('spaces  inside', 1)
        self.enqueue('tab\tinside', 2)
        self.enqueue('line\ninside', 3)
        with self.path.open('a') as source:
            for text in ('spaces inside', 'tab inside', 'line inside'):
                source.write(json.dumps(dict(type='user', message=dict(role='user', content=text))) + '\n')
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], [])
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['queue'],
                         ['spaces  inside', 'tab\tinside', 'line\ninside'])
        self.send.assert_not_called()

    def test_one_receipt_cannot_acknowledge_two_identical_rows(self):
        self.enqueue('same', 1)
        self.enqueue('same', 2)
        self.path.write_text(json.dumps(dict(type='user', message=dict(role='user', content='same'))) + '\n')
        reader.drain_held_queue('term-1', 'sid', self.path)
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['sent'], ['same'])
        self.assertEqual(reader.held_queue_fields('term-1', 'sid')['queue'], ['same'])

    def test_offline_reconciliation_cannot_borrow_a_matching_message(self):
        self.enqueue('target', 1)
        self.path.write_text(json.dumps(dict(type='user', message=dict(role='user', content='target'), timestamp='2026-10-28T20:00:00Z')) + '\n')
        query = dict(id=7, tmux='term-1', texts=['target'], message_ids=['message-1'], after_ms=0)
        with patch.object(reader, 'resolve_response_path', return_value=('claude', 'sid', self.path, None)):
            wire = reader.settle_response(query)
            self.assertEqual(json.loads(wire.splitlines()[1])['delivered_ms'], 0)
            self.cancel('target', 1)
            wire = reader.settle_response(query)
            self.assertEqual(json.loads(wire.splitlines()[1])['delivered_ms'], 0)
        self.send.assert_not_called()

    def test_settle_without_a_start_time_never_sweeps_other_transcripts(self):
        self.path.write_text('')
        row = json.dumps(dict(type='user', message=dict(role='user', content='elsewhere'), timestamp='2026-10-28T20:00:00Z')) + '\n'
        for n in range(6):
            (self.path.parent / f'other-{n}.jsonl').write_text(row)
        query = dict(id=7, tmux='term-1', texts=['elsewhere'], message_ids=[''], after_ms=0)
        opened = []
        real_open = Path.open
        def tracking_open(path, *args, **kwargs):
            opened.append(path.name)
            return real_open(path, *args, **kwargs)
        with patch.object(reader, 'resolve_response_path', return_value=('claude', 'sid', self.path, None)), patch.object(Path, 'open', tracking_open):
            wire = reader.settle_response(query)
            self.assertEqual(json.loads(wire.splitlines()[1])['delivered_ms'], 0)
            self.assertFalse([name for name in opened if name.startswith('other-')])
            opened.clear()
            wire = reader.settle_response(dict(query, after_ms=1))
            self.assertLessEqual(len([name for name in opened if name.startswith('other-')]), reader.SETTLE_SIBLINGS_MAX)


    def test_worker_lock_never_strands_a_new_session(self):
        import fcntl
        import hashlib
        self.enqueue('old target', 1)
        lock_path = reader.held_queue_path('term-1').with_suffix('.' + hashlib.sha256(b'sid').hexdigest() + '.worker')
        with lock_path.open('a') as lock, reader.held_queue_path('term-1').with_suffix('.worker').open('a') as shared, patch.object(reader.subprocess, 'Popen') as spawn:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.flock(shared, fcntl.LOCK_EX | fcntl.LOCK_NB)
            reader.start_held_worker('term-1', 'sid', self.path, 'pass')
            spawn.assert_not_called()
            with patch.object(reader, 'resolve_tmux_agent', return_value=('new-sid', self.path)):
                self.enqueue('new target', 2)
            reader.start_held_worker('term-1', 'new-sid', self.path, 'pass')
            spawn.assert_called_once()
            self.assertEqual(spawn.call_args.args[0][-2], 'new-sid')

    def test_held_send_ignores_a_dim_prompt_suggestion(self):
        shown = 'Claude Code\nReady\n❯ \x1b[2mTry a suggestion\x1b[0m\n' + '─' * 40 + '\n'
        with patch.object(reader.subprocess, 'run', return_value=types.SimpleNamespace(stdout=shown, returncode=0)):
            self.assertEqual(reader.prompt_box_text('=term-1:'), '')




class IGSH_1266_SharedMessageHostTest(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.path = self.home / 'sid.jsonl'
        self.path.write_text('')
        self.base = dict(message_protocol=1, provider='claude', sid='sid', tmux='fixture')
        self.sources = {name: base64.b64encode((ROOT / 'src/chat_connectors' / (name + '.py')).read_bytes()).decode()
                        for name in ('claude', 'codex')}
        self.addCleanup(patch.stopall)
        patch.object(reader, 'HOME', self.home).start()
        patch.object(reader, 'CONNECTOR_SOURCES', self.sources, create=True).start()
        patch.object(reader, 'resolve_tmux_agent', return_value=('sid', self.path)).start()
        self.send = patch.object(reader, 'send_response_preserve_draft', return_value={'status': 'sent'}).start()

    def request(self, op, **values):
        return reader.message_store_request(dict(self.base, op='message_' + op, **values))

    def submit(self, token='message_fixture_0001', text='synthetic prompt'):
        return self.request('submit', message_id=token, text=text)['message']

    def status(self, token='message_fixture_0001'):
        return self.request('status', message_id=token)['message']

    def append(self, event):
        with self.path.open('a') as output:
            output.write(json.dumps(event) + '\n')

    def poll(self):
        reader.message_poll('fixture', 'claude', 'sid')

    def accept(self, text='synthetic prompt'):
        self.append(dict(type='queue-operation', operation='enqueue', content=text, sessionId='sid'))
        self.poll()

    def test_submit_replay_has_one_handoff_and_global_id(self):
        first = self.submit()
        self.assertEqual(self.submit()['attempt'], first['attempt'])
        self.request('dispatch')
        self.submit()
        self.request('dispatch')
        self.assertEqual(self.send.call_count, 1)
        with self.assertRaises(ValueError):
            self.submit(text='different synthetic prompt')
        self.assertEqual(self.status()['state'], 'sent')

    def test_global_id_replay_across_tmux_locators(self):
        first = self.submit()
        self.request('dispatch')
        sent = self.status()
        other = dict(self.base, tmux='fixture-other')
        replay = reader.message_store_request(dict(other, op='message_submit', message_id=first['id'], text=first['text']))['message']
        reader.message_store_request(dict(other, op='message_dispatch'))
        observed = reader.message_store_request(dict(other, op='message_status', message_id=first['id']))['message']
        self.assertEqual((replay['attempt'], replay['state']), (sent['attempt'], sent['state']))
        self.assertEqual((observed['attempt'], observed['state']), (sent['attempt'], sent['state']))
        self.assertEqual(self.send.call_count, 1)

    def test_legacy_watcher_tracks_shared_acceptance(self):
        from contextlib import closing
        token = 'test-1'
        request = dict(id=1, tmux='fixture', message_id=token, text='synthetic prompt', queued_at=1)
        self.assertIn('\tsend\tok\t', reader.enqueue_response(request))
        identity = reader.legacy_message_id(token)
        self.assertEqual(self.status(identity)['state'], 'queued')
        with closing(reader.held_queue_db('fixture')) as db:
            self.assertEqual(db.execute('SELECT sid,state FROM messages WHERE key=?', (token,)).fetchone(), ('sid', 'submitted'))
            with db:
                db.execute("UPDATE messages SET state='delivered',receipt='old-worker' WHERE key=?", (token,))
            self.assertEqual(db.execute('SELECT state FROM messages WHERE key=?', (token,)).fetchone(), ('submitted',))
        self.request('dispatch')
        self.assertEqual(self.status(identity)['state'], 'sent')
        with closing(reader.held_queue_db('fixture')) as db:
            self.assertEqual(db.execute('SELECT state FROM messages WHERE key=?', (token,)).fetchone(), ('submitted',))
        reader.legacy_projection('fixture', 'sid', self.path, [token])
        self.accept()
        self.assertEqual(self.status(identity)['state'], 'accepted')
        with closing(reader.held_queue_db('fixture')) as db:
            self.assertEqual(db.execute('SELECT sid,state FROM messages WHERE key=?', (token,)).fetchone(), ('sid', 'delivered'))
            with db:
                db.execute("UPDATE messages SET state='cancelled',receipt='old-worker' WHERE key=?", (token,))
            self.assertEqual(db.execute('SELECT state FROM messages WHERE key=?', (token,)).fetchone(), ('delivered',))
            self.assertEqual(db.execute('SELECT COUNT(*) FROM held_receipts WHERE key=?', (token,)).fetchone()[0], 0)
        self.assertEqual(self.send.call_count, 1)

    def test_legacy_replay_updates_original_watcher_across_locators(self):
        from contextlib import closing
        token = 'test-2'
        request = dict(id=1, tmux='fixture', message_id=token, text='synthetic prompt', queued_at=1)
        self.assertIn('\tsend\tok\t', reader.enqueue_response(request))
        identity = reader.legacy_message_id(token)
        initial = self.status(identity)
        self.request('dispatch')
        other = dict(request, tmux='fixture-other')
        self.assertIn('\tsend\tok\t', reader.enqueue_response(other))
        self.assertEqual(self.status(identity)['attempt'], initial['attempt'])
        self.accept()
        self.assertEqual(self.status(identity)['state'], 'accepted')
        self.assertIn('\tsend\tok\t', reader.enqueue_response(other))
        self.request('snapshot')
        self.request('dispatch')
        for locator in ('fixture', 'fixture-other'):
            with closing(reader.held_queue_db(locator)) as db:
                self.assertEqual(db.execute('SELECT sid,state FROM messages WHERE key=?', (token,)).fetchone(), ('sid', 'delivered'))
                self.assertEqual(db.execute('SELECT COUNT(*) FROM held_receipts WHERE key=?', (token,)).fetchone()[0], 0)
        with closing(reader.message_store_db('fixture')) as db:
            self.assertEqual(db.execute('SELECT sid,id FROM legacy_bindings WHERE token=?', (token,)).fetchone(), ('sid', identity))
            self.assertEqual(db.execute('SELECT id,attempt,state FROM shared_messages').fetchall(), [(identity, initial['attempt'], 'accepted')])
        with patch.object(reader, 'resolve_tmux_agent', return_value=('other-sid', self.path)):
            with self.assertRaises(ValueError):
                reader.enqueue_response(dict(other, tmux='fixture-conflict'))
        self.assertEqual(self.send.call_count, 1)

    def test_busy_native_acceptance_does_not_expire(self):
        self.submit()
        self.request('dispatch')
        self.accept()
        with patch.object(reader.time, 'time', return_value=time.time() + 60):
            self.assertEqual(self.status()['state'], 'accepted')
        self.assertEqual(self.send.call_count, 1)

    def test_late_native_acceptance_wins_after_timeout(self):
        self.submit()
        self.request('dispatch')
        with patch.object(reader.time, 'time', return_value=time.time() + 60):
            self.assertEqual(self.status()['state'], 'failed')
        self.accept()
        self.assertEqual(self.status()['state'], 'accepted')
        self.assertEqual(self.send.call_count, 1)

    def test_retry_replay_keeps_new_attempt_and_one_handoff(self):
        first = self.submit()
        self.request('dispatch')
        with patch.object(reader.time, 'time', return_value=time.time() + 60):
            self.assertEqual(self.status()['state'], 'failed')
        retry = self.request('retry', message_id=first['id'], retry_id='retry_fixture_0001')['message']
        replay = self.request('retry', message_id=first['id'], retry_id='retry_fixture_0001')['message']
        self.assertNotEqual(retry['attempt'], first['attempt'])
        self.assertEqual(replay['attempt'], retry['attempt'])
        self.request('dispatch')
        self.request('dispatch')
        self.assertEqual(self.send.call_count, 2)
        self.assertEqual(self.status()['state'], 'sent')

    def test_reset_preserves_accepted_and_native_history(self):
        self.submit()
        self.request('dispatch')
        self.accept()
        self.append(dict(type='user', sessionId='sid', message=dict(role='user', content='synthetic prompt')))
        self.poll()
        self.submit('message_fixture_0002', 'pending prompt')
        before = self.path.read_text()
        reset = self.request('reset', reset_id='reset_fixture_0001')
        self.assertEqual(self.request('reset', reset_id='reset_fixture_0001')['epoch'], reset['epoch'])
        self.assertIn(self.status()['state'], ('accepted', 'started'))
        self.assertEqual(self.status('message_fixture_0002')['state'], 'cancelled')
        self.assertEqual(self.path.read_text(), before)
        self.assertTrue(any('native_message' in item for item in self.request('snapshot')['items']))
        self.request('dispatch')
        self.assertEqual(self.send.call_count, 1)

    def test_old_poll_callback_cannot_resurrect_reset_message(self):
        self.submit()
        self.request('dispatch')
        self.append(dict(type='queue-operation', operation='enqueue', content='synthetic prompt', sessionId='sid'))
        original = reader.load_line
        def reset_during_poll(line):
            result = original(line)
            self.request('reset', reset_id='reset_fixture_0001')
            return result
        with patch.object(reader, 'load_line', side_effect=reset_during_poll):
            reader.message_poll_locked('fixture', 'claude', 'sid')
        self.assertEqual(self.status()['state'], 'cancelled')
        self.request('dispatch')
        self.assertEqual(self.send.call_count, 1)

    def test_snapshot_pages_are_frozen_and_events_catch_up(self):
        for index in range(130):
            self.submit('message_fixture_%04d' % index, 'prompt %d' % index)
        page = self.request('snapshot')
        self.assertIsNotNone(page['next_cursor'])
        self.submit('message_fixture_late', 'late prompt')
        tail = self.request('snapshot', snapshot_id=page['snapshot_id'], cursor=page['next_cursor'])
        self.assertEqual(page['seq'], tail['seq'])
        ids = [item['message']['id'] for item in page['items'] + tail['items'] if 'message' in item]
        self.assertEqual(len(ids), 130)
        self.assertNotIn('message_fixture_late', ids)
        events = self.request('events', after=page['seq'])['events']
        self.assertTrue(any(event['event'].get('id') == 'message_fixture_late' for event in events))

    def test_event_and_status_reads_restart_only_observable_work(self):
        import fcntl
        def serve_one(op, **values):
            request = dict(self.base, id=1, op='message_' + op, **values)
            incoming = types.SimpleNamespace(buffer=io.BytesIO((json.dumps(request) + '\n').encode()))
            output = io.StringIO()
            with patch.object(reader.sys, 'stdin', incoming), patch.object(reader.sys, 'stdout', output):
                reader.serve('pass')
            return json.loads(output.getvalue())
        def child(*args, **kwargs):
            return types.SimpleNamespace(stdin=io.BytesIO())
        with patch.object(reader.subprocess, 'Popen', side_effect=child) as spawn:
            self.assertEqual(serve_one('events', after=0)['result']['events'], [])
            self.assertIsNone(serve_one('status', message_id='message_fixture_0001')['result']['message'])
            self.assertEqual(spawn.call_count, 0)
            self.submit()
            self.assertTrue(serve_one('events', after=0)['result']['events'])
            self.assertEqual(serve_one('status', message_id='message_fixture_0001')['result']['message']['state'], 'queued')
            self.assertEqual(spawn.call_count, 2)
            path = reader.message_store_path().with_suffix('.' + hashlib.sha256(b'claude:sid').hexdigest() + '.message-worker')
            with path.open('a') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                serve_one('events', after=0)
                serve_one('status', message_id='message_fixture_0001')
            self.assertEqual(spawn.call_count, 2)
            self.assertEqual(self.send.call_count, 0)

    def test_duplicate_worker_returns_without_blocking(self):
        import fcntl
        self.submit()
        with patch.object(fcntl, 'flock', side_effect=BlockingIOError) as lock, \
                patch.object(reader, 'message_poll') as poll:
            reader.message_worker('fixture', 'claude', 'sid')
        self.assertEqual(lock.call_args.args[1], fcntl.LOCK_EX | fcntl.LOCK_NB)
        poll.assert_not_called()
        self.assertEqual(self.status()['state'], 'queued')
        self.assertEqual(self.send.call_count, 0)

    def test_native_history_continues_after_started(self):
        self.submit()
        self.request('dispatch')
        self.accept()
        self.append(dict(type='user', sessionId='sid', message=dict(role='user', content='synthetic prompt')))
        self.poll()
        self.assertEqual(self.status()['state'], 'started')
        snapshot = self.request('snapshot')
        self.append(dict(type='assistant', sessionId='sid', message=dict(role='assistant', content=[dict(type='text', text='synthetic reply')])))
        self.poll()
        events = self.request('events', after=snapshot['seq'])['events']
        self.assertTrue(any(event['event'].get('native_message', {}).get('text') == 'synthetic reply' for event in events))
        self.assertEqual(self.send.call_count, 1)


class IGSH_1266_PreserveDraftHostTest(unittest.TestCase):
    def test_preserve_draft_handoff_boundaries(self):
        request = dict(tmux='fixture', sid='sid', text='synthetic prompt')
        for draft, fail, expected in (('unfinished synthetic draft', False, 'not_sent'),
                                      ('', False, 'sent'), ('', True, 'uncertain')):
            with self.subTest(draft=draft, fail=fail):
                def run(command, **kwargs):
                    if fail and 'paste-buffer' in command:
                        raise subprocess.TimeoutExpired(command, 5)
                    return types.SimpleNamespace(returncode=0, stdout='')
                with patch.object(reader, 'resolve_tmux_agent', return_value=('sid', '/synthetic/sid.jsonl')), \
                        patch.object(reader, 'box_draft', return_value=draft), \
                        patch.object(reader.subprocess, 'run', side_effect=run) as calls:
                    result = reader.send_response_preserve_draft(request)
                self.assertEqual(result['status'], expected)
                commands = [call.args[0] for call in calls.call_args_list]
                handoffs = [command for command in commands if 'paste-buffer' in command]
                self.assertEqual(len(handoffs), 0 if draft else 1)
                self.assertEqual(sum(command.count('Enter') for command in commands), 0 if draft else 1)
                self.assertFalse(any('C-u' in command or 'C-k' in command or '-l' in command for command in commands))
                self.assertFalse(any('send-keys' in command and 'paste-buffer' not in command for command in commands))
                self.assertFalse(any('delete-buffer' in command and 'send-keys' in command for command in commands))


    def test_own_mirrored_draft_is_cleared_and_sent(self):
        request = dict(tmux='fixture', sid='sid', text='https://example.com/a.png This happens on every message.')
        boxes = iter(['https://example.com/a.png This happens', ''])
        def run(command, **kwargs):
            return types.SimpleNamespace(returncode=0, stdout='')
        with patch.object(reader, 'resolve_tmux_agent', return_value=('sid', '/synthetic/sid.jsonl')), \
                patch.object(reader, 'box_draft', return_value='https://example.com/a.png This\nhappens'), \
                patch.object(reader, 'prompt_box_text', side_effect=lambda target: next(boxes)), \
                patch.object(reader.subprocess, 'run', side_effect=run) as calls:
            result = reader.send_response_preserve_draft(request)
        self.assertEqual(result['status'], 'sent')
        commands = [call.args[0] for call in calls.call_args_list]
        self.assertEqual(len([command for command in commands if 'paste-buffer' in command]), 1)
        self.assertTrue(any('C-u' in command for command in commands))

if __name__ == '__main__':
    unittest.main()
