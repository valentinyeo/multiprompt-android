import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / '.claude/skills/verify-multiprompt-android/scripts'
HERE = Path(__file__).resolve().parent
READER = ROOT / 'app/src/main/assets/chat_history_reader.py'
PRE_FIX_REVISION = 'af4d369923a28d27890d81fcb3335c181583f05d'


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


fixture = load('chat_shapes_fixture', HERE / 'fixture.py')
probe = load('chat_shapes_probe', HERE / 'probe.py')
repro = load('chat_shapes_repro', HERE / 'repro.py')


def resolved(payload):
    return {shape['shape']: shape for shape in payload['shapes']}


class ChatShapesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        self.manifest = fixture.create(self.home)

    def pinned_reader(self):
        source = subprocess.run(['git', '-C', str(ROOT), 'show', f'{PRE_FIX_REVISION}:app/src/main/assets/chat_history_reader.py'],
                                capture_output=True, text=True)
        self.assertEqual(source.returncode, 0, source.stderr)
        self.assertNotIn('hosted_claude_transcript_sid', source.stdout)
        path = self.home / 'pinned-reader.py'
        path.write_text(source.stdout)
        return path

    def test_seed_copies_real_shape_and_only_synthetic_text(self):
        self.assertEqual(['compacted', 'fresh', 'resumed'], [item['shape'] for item in self.manifest['shapes']])
        self.assertEqual(fixture.check(self.home), self.manifest)
        for item in self.manifest['shapes']:
            for key in ('transcript', 'own_transcript', 'neighbour_transcript'):
                path = Path(item[key])
                if not path.exists():
                    continue
                self.assertTrue(path.is_relative_to(self.home), path)
                self.assertLess(path.stat().st_size, 8192, path)
                for row in path.read_text().splitlines():
                    text = ''.join(part.get('text', '') for part in json.loads(row)['message']['content'])
                    self.assertTrue(text.endswith('_2715'), text)
        self.assertEqual(fixture.host_key('c02715c0-0000-4000-8000-000000002715'),
                         Path(self.manifest['shapes'][0]['config']).name.removesuffix('.config.json'))

    def test_current_reader_follows_the_live_session_for_every_shape(self):
        payload = probe.probe(READER, self.home)
        shapes = resolved(payload)
        print('CURRENT_READER_2715_GREEN ' + json.dumps({name: shape['transcript'] and shape['visible'][-1:] for name, shape in shapes.items()}),
              flush=True)
        self.assertEqual(fixture.shape('compacted')['native'] + '.jsonl', shapes['compacted']['transcript'])
        self.assertEqual('COMPACTED_AFTER_2715', shapes['compacted']['visible'][-1])
        self.assertNotIn('COMPACTED_BEFORE_2715', shapes['compacted']['visible'])
        self.assertEqual(fixture.shape('resumed')['native'] + '.jsonl', shapes['resumed']['transcript'])
        self.assertEqual('RESUMED_READY_2715', shapes['resumed']['visible'][-1])
        self.assertEqual(fixture.shape('fresh')['sid'] + '.jsonl', shapes['fresh']['transcript'])
        self.assertEqual('FRESH_READY_2715', shapes['fresh']['visible'][-1])
        self.assertNotIn('FRESH_NEIGHBOUR_2715', shapes['fresh']['visible'])
        for item in payload['shapes']:
            self.assertNotIn(item['stale'] or 'NONE', item['visible'])

    def test_reader_before_the_live_session_fix_reads_the_wrong_transcript(self):
        shapes = resolved(probe.probe(self.pinned_reader(), self.home))
        print('PRE_425_READER_2715 ' + json.dumps({name: shape['transcript'] and shape['visible'][-1:] for name, shape in shapes.items()}),
              flush=True)
        self.assertEqual(fixture.shape('compacted')['sid'] + '.jsonl', shapes['compacted']['transcript'])
        self.assertEqual('COMPACTED_BEFORE_2715', shapes['compacted']['visible'][-1])
        self.assertNotIn('COMPACTED_AFTER_2715', shapes['compacted']['visible'])
        self.assertIsNone(shapes['resumed']['transcript'])
        self.assertEqual([], shapes['resumed']['visible'])
        self.assertEqual('FRESH_READY_2715', shapes['fresh']['visible'][-1])

    def test_repro_is_red_on_the_pre_fix_reader_and_green_on_the_current_one(self):
        result = repro.run(self.pinned_reader(), READER)
        print(repro.report(result).strip(), flush=True)
        self.assertTrue(result['red'], result['preFix'])
        self.assertTrue(result['green'], result['current'])

    def test_seed_command_creates_the_shapes_as_supervised_live_chats(self):
        mpa = load('chat_shapes_mpa', SCRIPTS / 'mpa')
        shutil.copyfile(SCRIPTS / 'console.py', self.home / 'console.py')
        shutil.copyfile(HERE / 'fixture.py', self.home / 'chat-shapes-fixture.py')
        environment = dict(os.environ, GITHUB_ACTIONS='true', HOME=str(self.home))
        with patch.dict(os.environ, environment, clear=True):
            payload = mpa.seed('verify-session', True)
        self.assertEqual(['live-compacted-2715', 'live-fresh-2715', 'live-resumed-2715'], payload['shapes'])
        commands = [' '.join(str(part) for part in command) for command in payload['commands']]
        for shape in fixture.SHAPES:
            session = [command for command in commands if f'-s {shape["session"]} ' in command]
            self.assertEqual(1, len(session), commands)
            self.assertIn('exec sleep infinity', session[0])
            self.assertIn(str(self.home / shape['project']), session[0])
            self.assertTrue(any(f'-t ={shape["session"]}: @mp_host_sid {shape["sid"]}' in command for command in commands), session)
        socket = 'chat-shapes-' + str(os.getpid())
        tmux = shutil.which('tmux')
        binary = self.home / 'bin'
        binary.mkdir()
        wrapper = binary / 'tmux'
        wrapper.write_text('#!/usr/bin/env bash\nexec ' + tmux + ' -L ' + socket + ' "$@"\n')
        wrapper.chmod(0o755)
        environment = dict(os.environ, HOME=str(self.home), PATH=str(binary) + os.pathsep + os.environ['PATH'])
        environment.pop('TMUX', None)
        with patch.dict(os.environ, environment, clear=True):
            try:
                sessions = [shape['session'] for shape in fixture.SHAPES]
                for command in payload['commands'][1:]:
                    if command[0] != 'tmux' or not any(name in ' '.join(map(str, command)) for name in sessions):
                        continue
                    subprocess.run([str(part) for part in command], check=True, capture_output=True)
                for shape in fixture.SHAPES:
                    target = '=' + shape['session'] + ':'
                    self.assertEqual('sleep', subprocess.check_output(
                        ['tmux', 'display-message', '-p', '-t', target, '#{pane_current_command}'], text=True).strip())
                    self.assertEqual(str(self.home / shape['project']), subprocess.check_output(
                        ['tmux', 'display-message', '-p', '-t', target, '#{pane_current_path}'], text=True).strip())
                    self.assertEqual(shape['sid'], subprocess.check_output(
                        ['tmux', 'show-options', '-t', shape['session'], '-v', '@mp_host_sid'], text=True).strip())
                reader = load('chat_shapes_reader', READER)
                reader.HOME = self.home
                self.assertEqual(fixture.shape('compacted')['sid'],
                                 reader.host_sid_for_tmux('claude', 'live-compacted-2715'))
            finally:
                subprocess.run(['tmux', 'kill-server'], capture_output=True)


if __name__ == '__main__':
    unittest.main()
