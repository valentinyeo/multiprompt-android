import base64
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'src/chat_history_reader.py').read_text()
GENERATION = int(re.search(r'^RUNTIME_GENERATION = (\d+)$', SOURCE, re.MULTILINE).group(1))
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
        return SOURCE.replace(f'RUNTIME_GENERATION = {GENERATION}\n', f'RUNTIME_GENERATION = {GENERATION + 1}\n', 1).replace('END = "@@MP_END@@"', 'END = "@@MP_NEW_END@@"', 1)

    def test_selection_and_idempotence_across_bootstraps(self):
        first = self.info()
        active = Path(self.home.name) / '.multiprompt-runtime' / 'active.json'
        before = active.stat().st_mtime_ns
        self.assertEqual(self.info(), first)
        self.assertEqual(active.stat().st_mtime_ns, before)
        newer = self.info(self.newer())
        self.assertEqual(newer['generation'], GENERATION + 1)
        self.assertNotEqual(first['sha256'], newer['sha256'])
        self.assertEqual(self.info(), newer)
        query = {'id': 9, 'cwd': '', 'sid': 'missing', 'tmux': '', 'codex': False}
        request = base64.b64encode(json.dumps(query).encode()).decode()
        result = subprocess.run([sys.executable, '-', request], input=SOURCE, text=True, capture_output=True, env=self.env, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, '@mp\t9\thistory\tmissing\t-\t0\tsession transcript not found\n@@MP_NEW_END@@\n')
        self.assertEqual(json.loads(active.read_text()), newer)

    def test_conflict_and_corruption_fail_closed(self):
        first = self.info()
        conflict = SOURCE.replace('PAGE_BYTES = 350000', 'PAGE_BYTES = 350001', 1)
        result = self.invoke(conflict)
        self.assertIn('history\\terror', result.stdout)
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
        self.assertEqual(self.info(self.newer())['generation'], GENERATION + 1)
        active = Path(self.home.name) / '.multiprompt-runtime' / 'active.json'
        active.write_text('null')
        self.assertIn('history\\terror', self.invoke().stdout)
        self.assertEqual(active.read_text(), 'null')

    def test_concurrent_generations_cannot_downgrade(self):
        processes = []
        for source in (SOURCE, self.newer(), SOURCE, self.newer()):
            process = subprocess.Popen([sys.executable, '-u', '-c', BOOTSTRAP, '--serve'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=self.env)
            process.stdin.write(base64.b64encode(source.encode()).decode() + '\n' + json.dumps({'id': 7, 'op': 'engine_info'}) + '\n')
            process.stdin.flush()
            processes.append(process)
        for process in processes:
            output, error = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 0, error)
            self.assertIn(json.loads(output)['engine_info']['generation'], (GENERATION, GENERATION + 1))
        self.assertEqual(self.info()['generation'], GENERATION + 1)

    def test_import_does_not_install(self):
        code = "import importlib.util,sys; s=importlib.util.spec_from_file_location('reader',sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); assert m.READER_SOURCE"
        result = subprocess.run([sys.executable, '-c', code, str(ROOT / 'src/chat_history_reader.py')], env=self.env, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((Path(self.home.name) / '.multiprompt-runtime').exists())

    def test_watch_uses_selected_engine(self):
        self.info(self.newer())
        result = self.invoke(mode='--watch', request={'id': 8, 'cwd': '', 'sid': 'missing', 'tmux': '', 'codex': False})
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


if __name__ == '__main__':
    unittest.main()
