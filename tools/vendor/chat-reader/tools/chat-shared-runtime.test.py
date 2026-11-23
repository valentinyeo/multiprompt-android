import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'src/chat_history_reader.py').read_text()
READER = {'__name__': 'reader_runtime_test'}
exec(compile(SOURCE, '<runtime-test>', 'exec'), READER)
GENERATION = READER['RUNTIME_GENERATION']
BOOTSTRAP = 'import base64,sys;exec(base64.b64decode(sys.stdin.buffer.readline()))'


class RuntimeTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.TemporaryDirectory()
        self.addCleanup(self.home.cleanup)
        self.env = dict(os.environ, HOME=self.home.name, PYTHONDONTWRITEBYTECODE='1')

    def invoke(self, source=SOURCE, client='desktop', mode='--serve', request=None):
        request = request or {'id': 7, 'op': 'engine_info'}
        payload = json.dumps(request) + '\n'
        if client == 'desktop':
            args = [sys.executable, '-u', '-c', BOOTSTRAP, mode]
            payload = base64.b64encode(source.encode()).decode() + '\n' + payload
        else:
            args = [sys.executable, '-', mode]
            payload = source
        result = subprocess.run(args, input=payload, text=True, capture_output=True, env=self.env, timeout=10)
        return result

    def info(self, source=SOURCE):
        result = self.invoke(source)
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)['engine_info']

    def newer(self):
        return SOURCE.replace('END = "@@MP_END@@"', 'END = "@@MP_NEW_END@@"', 1)

    def legacy(self):
        return (ROOT / 'tools/fixtures/SOFT-2524/legacy-reader.py').read_text()

    def test_install_newer_then_older_leaves_runtime_unchanged(self):
        first = self.info(self.bundle(self.newer()))
        runtime = Path(self.home.name) / '.multiprompt-runtime'
        snapshot = {path.name: (os.readlink(path) if path.is_symlink() else path.read_bytes())
                    for path in runtime.iterdir() if path.name != 'lock'}
        self.assertEqual(self.info(self.legacy()), first)
        for path in runtime.iterdir():
            if path.name == 'lock':
                continue
            if path.is_symlink():
                self.assertEqual(os.readlink(path), snapshot[path.name], path.name)
            else:
                self.assertEqual(path.read_bytes(), snapshot[path.name], path.name)
        for name, _file in self.RUNNABLE_TOOLS:
            self.assertTrue(os.access(runtime / name, os.X_OK), name)

    def test_install_older_then_newer_upgrades(self):
        old = self.info(self.legacy())
        upgraded = self.info(self.bundle(self.newer()))
        self.assertGreater(upgraded['generation'], old['generation'])
        self.assertEqual(self.info(self.bundle(self.newer())), upgraded)

    def test_equal_generation_install_is_idempotent(self):
        info = self.info(self.bundle())
        active = Path(self.home.name) / '.multiprompt-runtime' / 'active.json'
        before = active.stat().st_mtime_ns
        self.assertEqual(self.info(self.bundle()), info)
        self.assertEqual(active.stat().st_mtime_ns, before)

    def test_selection_and_idempotence_across_bootstraps(self):
        first = self.info()
        active = Path(self.home.name) / '.multiprompt-runtime' / 'active.json'
        before = active.stat().st_mtime_ns
        self.assertEqual(self.info(), first)
        self.assertEqual(active.stat().st_mtime_ns, before)
        newer = self.info(self.newer())
        self.assertNotEqual(newer['generation'], GENERATION)
        self.assertNotEqual(first['sha256'], newer['sha256'])
        self.assertEqual(self.info(self.newer()), newer)
        query = {'id': 9, 'cwd': '', 'sid': 'missing', 'tmux': '', 'codex': False}
        request = base64.b64encode(json.dumps(query).encode()).decode()
        result = subprocess.run([sys.executable, '-', request], input=self.newer(), text=True, capture_output=True, env=self.env, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, '@mp\t9\thistory\tmissing\t-\t0\tsession transcript not found\n@@MP_NEW_END@@\n')
        self.assertEqual(json.loads(active.read_text()), newer)

    def test_different_source_replaces_the_stale_selection_without_a_bump(self):
        first = self.info()
        conflict = SOURCE.replace('PAGE_BYTES = 350000', 'PAGE_BYTES = 350001', 1)
        replaced = self.info(conflict)
        self.assertNotEqual(replaced['generation'], GENERATION)
        self.assertNotEqual(replaced['sha256'], first['sha256'])
        self.assertEqual(self.info(conflict), replaced)
        self.assertEqual(self.info()['sha256'], first['sha256'])

    def test_corruption_fails_closed(self):
        first = self.info()
        path = Path(self.home.name) / '.multiprompt-runtime' / (first['sha256'] + '.py')
        path.write_text('corrupt')
        self.assertIn('history\\terror', self.invoke().stdout)

    def test_unsafe_selection_fails_closed(self):
        self.info()
        active = Path(self.home.name) / '.multiprompt-runtime' / 'active.json'
        active.unlink()
        active.symlink_to('/dev/null')
        self.assertIn('history\\terror', self.invoke().stdout)

    def test_boolean_protocol_fails_closed(self):
        self.info()
        active = Path(self.home.name) / '.multiprompt-runtime' / 'active.json'
        metadata = json.loads(active.read_text())
        metadata['protocol'] = True
        active.write_text(json.dumps(metadata))
        self.assertIn('history\\terror', self.invoke().stdout)

    def test_null_selection_cannot_downgrade(self):
        self.assertNotEqual(self.info(self.newer())['generation'], GENERATION)
        active = Path(self.home.name) / '.multiprompt-runtime' / 'active.json'
        active.write_text('null')
        self.assertIn('history\\terror', self.invoke().stdout)
        self.assertEqual(active.read_text(), 'null')

    def test_thread_pool_executor_loads_before_retire_thread(self):
        prelude = SOURCE.split('def serve(', 1)[0]
        serve_head = SOURCE.split('def serve(', 1)[1].split('chats = {}', 1)[0]
        self.assertIn('from concurrent.futures import ThreadPoolExecutor', prelude)
        self.assertNotIn('from concurrent.futures import ThreadPoolExecutor', serve_head)

    def test_concurrent_fingerprints_publish_a_consistent_selection(self):
        versions = {self.info()['generation'], self.info(self.newer())['generation']}
        for attempt in range(50):
            with self.subTest(attempt=attempt):
                processes = []
                for source in (SOURCE, self.newer(), SOURCE, self.newer()):
                    process = subprocess.Popen([sys.executable, '-u', '-c', BOOTSTRAP, '--serve'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=self.env)
                    process.stdin.write(base64.b64encode(source.encode()).decode() + '\n' + json.dumps({'id': 7, 'op': 'engine_info'}) + '\n')
                    process.stdin.flush()
                    processes.append(process)
                for process in processes:
                    output, error = process.communicate(timeout=10)
                    self.assertEqual(process.returncode, 0, error)
                    self.assertTrue(output.strip(), 'superseded reader dropped an open reply before ThreadPoolExecutor load')
                    self.assertIn(json.loads(output)['engine_info']['generation'], versions)
                active = Path(self.home.name) / '.multiprompt-runtime/active.json'
                self.assertIn(json.loads(active.read_text())['generation'], versions)

    def test_mp_send_ships_inside_the_runtime(self):
        body = "#!/usr/bin/env python3\nprint('mp-send')\n"
        source = "MP_SEND_SOURCE = '%s'\n%s" % (base64.b64encode(body.encode()).decode(), SOURCE)
        self.info(source)
        published = Path(self.home.name) / '.multiprompt-runtime' / 'mp-send'
        self.assertTrue(published.is_symlink())
        self.assertEqual(published.read_text(), body)
        self.assertTrue(os.access(published, os.X_OK))

    def test_zsb_ships_inside_the_runtime_as_an_executable_copy(self):
        body = "#!/bin/sh\necho zsb\n"
        source = "ZSB_SOURCE = '%s'\n%s" % (base64.b64encode(body.encode()).decode(), SOURCE)
        self.info(source)
        published = Path(self.home.name) / '.multiprompt-runtime' / 'mp-zsb'
        self.assertTrue(published.is_symlink())
        self.assertEqual(published.read_text(), body)
        self.assertTrue(os.access(published, os.X_OK))
        self.assertFalse((Path(self.home.name) / '.multiprompt-runtime' / 'zsb').exists())

    def test_reader_tool_is_a_symlink(self):
        info = self.info()
        reader = Path(self.home.name) / '.multiprompt-runtime' / 'reader.py'
        self.assertTrue(reader.is_symlink())
        self.assertEqual(os.readlink(reader), info['sha256'] + '.py')

    def test_hand_copied_reader_is_repaired(self):
        info = self.info()
        runtime = Path(self.home.name) / '.multiprompt-runtime'
        reader = runtime / 'reader.py'
        reader.unlink()
        reader.write_text('broken hand copy')
        self.assertFalse(reader.is_symlink())
        self.assertEqual(self.info()['sha256'], info['sha256'])
        self.assertTrue(reader.is_symlink())
        self.assertEqual(reader.read_text(), (runtime / (info['sha256'] + '.py')).read_text())

    def bundle(self, reader=SOURCE):
        sources = (
            ('MP_SEND_SOURCE', 'src/mp_send.py'),
            ('FEEDBACK_SOURCE', 'src/multibot_feedback.py'),
            ('MP_FRESH_SOURCE', 'src/mp_fresh.py'),
            ('MP_HELPER_SOURCE', 'src/mp_helper.py'),
            ('MP_SHELL_SOURCE', 'src/mp_shell.py'),
            ('MP_SESSIONS_SOURCE', 'src/mp_sessions.py'),
            ('MP_LAYOUT_SOURCE', 'src/mp_layout.mjs'),
            ('ZSB_SOURCE', 'src/mp_zsb.sh'),
        )
        prelude = ''.join("%s = '%s'\n" % (name, base64.b64encode((ROOT / file).read_bytes()).decode()) for name, file in sources if (ROOT / file).is_file())
        return prelude + reader

    def test_mp_fresh_ships_inside_the_runtime_as_an_executable_copy(self):
        self.info(self.bundle())
        published = Path(self.home.name) / '.multiprompt-runtime' / 'mp-fresh'
        self.assertTrue(published.is_symlink())
        self.assertTrue(os.access(published, os.X_OK))

    RUNNABLE_TOOLS = (
        ('mp-send', 'src/mp_send.py'),
        ('multibot-feedback', 'src/multibot_feedback.py'),
        ('mp-helper', 'src/mp_helper.py'),
        ('mp-shell', 'src/mp_shell.py'),
        ('mp-sessions', 'src/mp_sessions.py'),
        ('mp-fresh', 'src/mp_fresh.py'),
        ('mp-zsb', 'src/mp_zsb.sh'),
        ('mp-layout', 'src/mp_layout.mjs'),
    )

    def test_every_installed_chat_tool_runs(self):
        self.info(self.bundle())
        runtime = Path(self.home.name) / '.multiprompt-runtime'
        for name, file in self.RUNNABLE_TOOLS:
            with self.subTest(tool=name):
                link = runtime / name
                self.assertTrue(link.is_symlink(), name)
                target = runtime / os.readlink(link)
                self.assertEqual(target.parent, runtime, name)
                self.assertEqual(target.read_text(), (ROOT / file).read_text(), name)
                self.assertTrue(os.access(target, os.X_OK), name + ' is not executable')
                self.assertTrue(os.access(link, os.X_OK), name + ' does not resolve to an executable file')

    def test_install_removes_leftover_temporary_files(self):
        runtime = Path(self.home.name) / '.multiprompt-runtime'
        runtime.mkdir(mode=0o700)
        (runtime / '.tmp-leftover').write_text('stale')
        self.info(self.bundle())
        self.assertEqual([path.name for path in runtime.iterdir() if path.name.startswith('.tmp-')], [])

    def test_mp_sessions_and_mp_layout_ship_inside_the_runtime(self):
        self.info(self.bundle())
        runtime = Path(self.home.name) / '.multiprompt-runtime'
        for name, file in (('mp-sessions', 'src/mp_sessions.py'), ('mp-layout', 'src/mp_layout.mjs')):
            published = runtime / name
            self.assertTrue(published.is_symlink(), name)
            self.assertEqual(published.read_text(), (ROOT / file).read_text(), name)
            self.assertTrue(os.access(published, os.X_OK), name)
        self.assertTrue(os.readlink(runtime / 'mp-layout').endswith('.mjs'), 'node must see the module extension')

    def test_published_mp_sessions_lists_chats_from_the_runtime_copy(self):
        self.info(self.bundle())
        sessions = Path(self.home.name) / '.multiprompt-runtime' / 'mp-sessions'
        self.assertTrue(sessions.is_file(), 'mp-sessions must be published')
        listed = subprocess.run([str(sessions)], text=True, capture_output=True, env=self.env, timeout=30)
        self.assertEqual(listed.returncode, 0, listed.stderr)
        self.assertIn('STATE', listed.stdout)

    def test_published_mp_layout_answers_help_from_the_runtime_copy(self):
        self.info(self.bundle())
        layout = Path(self.home.name) / '.multiprompt-runtime' / 'mp-layout'
        self.assertTrue(layout.is_file(), 'mp-layout must be published')
        usage = subprocess.run([str(layout)], text=True, capture_output=True, env=self.env, timeout=30)
        self.assertIn('usage: mp-layout', usage.stdout)

    def test_mp_send_is_not_published_without_a_bundle(self):
        self.info()
        runtime = Path(self.home.name) / '.multiprompt-runtime'
        self.assertFalse((runtime / 'mp-send').exists())
        self.assertFalse((runtime / 'mp-sessions').exists())
        self.assertFalse((runtime / 'mp-layout').exists())

    def test_import_does_not_install(self):
        code = "import importlib.util,sys; s=importlib.util.spec_from_file_location('reader',sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); assert m.READER_SOURCE"
        result = subprocess.run([sys.executable, '-c', code, str(ROOT / 'src/chat_history_reader.py')], env=self.env, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((Path(self.home.name) / '.multiprompt-runtime').exists())

    def test_watch_uses_selected_engine(self):
        self.info(self.newer())
        result = self.invoke(self.newer(), mode='--watch', request={'id': 8, 'cwd': '', 'sid': 'missing', 'tmux': '', 'codex': False})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), {'id': 8, 'wire': '@mp\t8\thistory\tmissing\t-\t0\tsession transcript not found\n@@MP_NEW_END@@\n'})

    def test_selected_source_reaches_worker(self):
        newer = self.info(self.newer())
        code = "import base64,sys;s=base64.b64decode(sys.stdin.buffer.readline()).decode();exec(compile(s,'<held-queue-worker>','exec'),dict(__name__='__main__',READER_SOURCE=s))"
        active = Path(self.home.name) / '.multiprompt-runtime'
        payload = (active / (newer['sha256'] + '.py')).read_text()
        source = 'ENGINE_INFO = ' + repr(newer) + '\n' + payload
        result = subprocess.run([sys.executable, '-c', code, '--serve'], input=base64.b64encode(source.encode()).decode() + '\n' + json.dumps({'id': 3, 'op': 'engine_info'}) + '\n', text=True, capture_output=True, env=self.env, timeout=10)
        self.assertEqual(json.loads(result.stdout)['engine_info'], newer)


class ReaderRetireTest(unittest.TestCase):
    def test_superseded_reader_finishes_open_requests_before_exit(self):
        exits = []
        exited = threading.Event()

        def fake_exit(code):
            exits.append(code)
            exited.set()
            raise SystemExit(code)

        spec = importlib.util.spec_from_file_location('reader_retire', ROOT / 'src/chat_history_reader.py')
        reader = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(reader)
        work = getattr(reader, 'serve_work', lambda delta: None)
        if hasattr(reader, 'SERVE_WORK'):
            reader.SERVE_WORK.update(busy=0, last=time.monotonic())
        with patch.object(reader, 'superseded_engine', return_value=True), patch.object(reader.os, '_exit', fake_exit):
            work(1)
            thread = threading.Thread(target=reader.serve_retire, args=(0.02,), daemon=True)
            thread.start()
            exited.wait(0.3)
            self.assertEqual(exits, [], 'reader retired while a request was still open')
            work(-1)
            self.assertTrue(exited.wait(2), 'idle superseded reader never retired')
            thread.join(1)
        self.assertEqual(exits, [0])


if __name__ == '__main__':
    unittest.main()
