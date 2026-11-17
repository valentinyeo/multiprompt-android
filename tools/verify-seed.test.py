import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / '.claude/skills/verify-multiprompt-android/scripts'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


seed = load('verify_seed', ROOT / 'tools/verify-seed.py')
long_chat = load('long_chat', ROOT / 'tools/long-chat/fixture.py')


class VerifySeedTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        _, self.transcript = long_chat.create(self.home)
        self.source = self.home / 'long-chat-source.json'
        self.source.write_text(json.dumps(dict(path=str(self.transcript), bytes=self.transcript.stat().st_size,
                                              rows=1882, sid=long_chat.SID, latest=long_chat.LATEST)))
        self.names = ['verify-session', 'cl-long-chat'] + seed.BULK_NAMES
        self.tmux = patch.object(seed.subprocess, 'check_output', side_effect=lambda *args, **kwargs: '\n'.join(self.names) + '\n')
        self.tmux.start()
        self.addCleanup(self.tmux.stop)
        self.evidence = self.home / 'seed-counts.json'
        self.evidence.write_text(json.dumps(seed.inventory(self.home)))

    def test_records_measured_counts_and_rechecks_the_evidence(self):
        counts = seed.check(self.home, self.evidence)
        self.assertEqual(42, counts['sessions'])
        self.assertEqual(40, counts['bulk_sessions'])
        self.assertEqual(sorted(self.names), counts['session_names'])
        self.assertGreater(counts['transcript']['bytes'], 18 * 1024 * 1024)
        self.assertEqual(1882, counts['transcript']['rows'])
        self.assertEqual(str(self.transcript), counts['transcript']['path'])

    def test_rejects_tiny_inbox_and_missing_named_seed(self):
        self.names[:] = ['verify-session']
        with self.assertRaisesRegex(AssertionError, 'SEED_INBOX_TOO_SMALL'):
            seed.check(self.home, self.evidence)
        self.names[:] = ['verify-session', 'cl-long-chat'] + seed.BULK_NAMES[:-1] + ['unrelated']
        with self.assertRaisesRegex(AssertionError, 'SEED_SESSIONS_MISSING'):
            seed.check(self.home, self.evidence)

    def test_rejects_small_or_missing_transcript_and_wrong_rows(self):
        self.transcript.unlink()
        with self.assertRaises(FileNotFoundError):
            seed.check(self.home, self.evidence)
        self.transcript.write_text('tiny\n')
        with self.assertRaisesRegex(AssertionError, 'SEED_TRANSCRIPT_TOO_SMALL'):
            seed.check(self.home, self.evidence)
        self.transcript.write_bytes(b'x' * (19 * 1024 * 1024))
        with self.assertRaisesRegex(AssertionError, 'SEED_TRANSCRIPT_ROWS'):
            seed.check(self.home, self.evidence)

    def test_rejects_missing_malformed_or_fabricated_evidence(self):
        self.evidence.unlink()
        with self.assertRaises(FileNotFoundError):
            seed.check(self.home, self.evidence)
        self.evidence.write_text('{')
        with self.assertRaises(json.JSONDecodeError):
            seed.check(self.home, self.evidence)
        self.evidence.write_text(json.dumps(dict(sessions=400, bulk_sessions=40)))
        with self.assertRaisesRegex(AssertionError, 'SEED_EVIDENCE_MISMATCH'):
            seed.check(self.home, self.evidence)

    def test_rejects_stale_counts_and_source_metadata(self):
        self.names.append('unexpected-session')
        with self.assertRaisesRegex(AssertionError, 'SEED_EVIDENCE_MISMATCH'):
            seed.check(self.home, self.evidence)
        source = json.loads(self.source.read_text())
        source['bytes'] += 1
        self.source.write_text(json.dumps(source))
        with self.assertRaisesRegex(AssertionError, 'SEED_SOURCE_MISMATCH'):
            seed.check(self.home, self.evidence)

    def test_deep_target_comes_from_the_recorded_first_page_not_session_order(self):
        page = self.home / '04-seed-deep-before.xml'
        page.write_bytes((ROOT / 'tools/fixtures/verify-seed/04-seed-deep-before.xml').read_bytes())
        chosen = seed.target(self.home)
        self.assertIn('row-27', chosen['initial_names'])
        self.assertEqual('cl-long-chat', chosen['name'])
        self.assertEqual('Robot cl-long-chat', chosen['description'])
        self.assertEqual('Message cl-long-chat', chosen['reader_label'])
        self.assertEqual(long_chat.LATEST, chosen['ready_text'])
        self.assertEqual(page.name, chosen['initial_xml'])
        self.names[:] = ['row-02', 'row-01'] + [name for name in self.names if name not in ('row-02', 'row-01')]
        counts = json.loads(self.evidence.read_text())
        counts['session_names'] = self.names
        self.evidence.write_text(json.dumps(counts))
        chosen = seed.target(self.home)
        self.assertEqual('row-02', chosen['name'])
        self.assertEqual('VERIFY_READY_528', chosen['ready_text'])

    def test_deep_target_fails_setup_when_every_seeded_name_is_in_the_dump(self):
        page = self.home / '04-seed-deep-before.xml'
        page.write_text('<hierarchy>' + ''.join(f'<node content-desc="Robot {name}"/>' for name in self.names) + '</hierarchy>')
        with self.assertRaisesRegex(ValueError, 'SEED_DEEP_SETUP_FAILED: every seeded session'):
            seed.target(self.home)
        page.unlink()
        with self.assertRaisesRegex(ValueError, 'SEED_DEEP_SETUP_FAILED: expected one initial inbox dump'):
            seed.target(self.home)

    def inbox_pages(self):
        (self.home / 'seed-check.json').write_text(self.evidence.read_text())
        pages = []
        for index in range(0, 42, 6):
            rows = ''.join(f'<node content-desc="Robot {name}" bounds="[20,{300 + row * 100}][100,{380 + row * 100}]"/>'
                           for row, name in enumerate(self.names[index:index + 6]))
            xml = ('<hierarchy><node bounds="[0,0][1080,1920]">'
                   '<node text="42 results across all splits" bounds="[0,100][1000,200]"/>'
                   '<node scrollable="true" bounds="[0,250][1080,1800]">' + rows + '</node></node></hierarchy>')
            page = self.home / f'{index:02d}-seed-inbox-{index}.xml'
            page.write_text(xml)
            pages.append(page)
        return pages

    def test_inbox_evidence_requires_every_seeded_name_on_visible_pages(self):
        pages = self.inbox_pages()
        pages[-1].unlink()
        with self.assertRaisesRegex(AssertionError, 'SEED_INBOX_ROWS_MISSING'):
            seed.inbox(self.home)
        self.inbox_pages()
        result = seed.inbox(self.home)
        self.assertEqual(42, result['sessions_visible'])
        self.assertEqual(sorted(self.names), result['session_names'])
        self.assertEqual(7, len(result['pages']))
        self.assertEqual('42 results across all splits', result['displayed_count'])

    def test_inbox_evidence_rejects_hidden_clipped_and_query_only_rows(self):
        for replacement in ['content-desc="Not a robot"', 'visible-to-user="false" content-desc="Robot verify-session"',
                            'text="verify-session"', 'content-desc="Robot verify-session-neighbor"']:
            pages = self.inbox_pages()
            pages[0].write_text(pages[0].read_text().replace('content-desc="Robot verify-session"', replacement))
            with self.subTest(replacement=replacement), self.assertRaisesRegex(AssertionError, 'SEED_INBOX_ROWS_MISSING'):
                seed.inbox(self.home)
        pages = self.inbox_pages()
        pages[0].write_text(pages[0].read_text().replace('[20,300][100,380]', '[20,240][100,380]'))
        with self.assertRaisesRegex(AssertionError, 'SEED_INBOX_ROWS_MISSING'):
            seed.inbox(self.home)

    def test_inbox_evidence_requires_the_displayed_count_and_successful_seed_check(self):
        pages = self.inbox_pages()
        for page in pages:
            page.write_text(page.read_text().replace('42 results across all splits', '1 result across all splits'))
        with self.assertRaisesRegex(AssertionError, 'SEED_INBOX_COUNT_NOT_VISIBLE'):
            seed.inbox(self.home)
        self.inbox_pages()
        (self.home / 'seed-check.json').write_text('{}')
        with self.assertRaisesRegex(AssertionError, 'SEED_CHECK_EVIDENCE_MISMATCH'):
            seed.inbox(self.home)

    def test_driver_checks_private_runner_evidence_as_the_fixture_user(self):
        self.home.chmod(0o755)
        private = self.home / 'runner-private'
        private.mkdir(mode=0o700)
        evidence = private / 'seed-counts.json'
        evidence.write_text(self.evidence.read_text())
        denied = subprocess.run(['sudo', '-u', 'nobody', 'cat', str(evidence)], capture_output=True, text=True)
        self.assertNotEqual(0, denied.returncode)
        self.assertIn('Permission denied', denied.stderr)
        (self.home / 'verify-seed.py').write_bytes((ROOT / 'tools/verify-seed.py').read_bytes())
        binary = self.home / 'bin'
        binary.mkdir()
        tmux = binary / 'tmux'
        tmux.write_text('#!/bin/sh\nprintf "%s\\n" ' + shlex.join(self.names) + '\n')
        tmux.chmod(0o755)
        command = next(line for line in (SCRIPTS / 'drive.sh').read_text().splitlines() if 'verify-seed.py check' in line)
        command = command.replace('-u verifyssh', '-u nobody').replace('/home/verifyssh', str(self.home))
        command = command.replace('env HOME=', 'env PATH=' + shlex.quote(str(binary) + os.pathsep + os.environ['PATH']) + ' HOME=')
        result = subprocess.run(['bash', '-euc', command], env=dict(os.environ, EVIDENCE=str(private)), capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(42, json.loads(result.stdout)['sessions'])

    def test_shared_seed_starts_real_sessions_and_a_discoverable_long_chat(self):
        self.tmux.stop()
        socket = 'verify-seed-' + uuid.uuid4().hex
        binary = self.home / 'bin'
        binary.mkdir()
        tmux = subprocess.check_output(['which', 'tmux'], text=True).strip()
        wrapper = binary / 'tmux'
        wrapper.write_text('#!/usr/bin/env bash\nexec ' + shlex.join([tmux, '-L', socket]) + ' "$@"\n')
        wrapper.chmod(0o755)
        (self.home / 'console.py').write_bytes((SCRIPTS / 'console.py').read_bytes())
        (self.home / 'long-chat-fixture.py').write_bytes((ROOT / 'tools/long-chat/fixture.py').read_bytes())
        (self.home / 'long-chat-bin').mkdir()
        (self.home / 'long-chat-bin/claude').symlink_to(sys.executable)
        self.source.unlink()
        self.transcript.unlink()
        script = (SCRIPTS / 'seed.sh').read_text().replace('/home/verifyssh', str(self.home))
        guard = next(line for line in script.splitlines() if line.startswith('[['))
        script = script.replace(guard, '[[ "$GITHUB_ACTIONS" == true && "$HOME" == ' + shlex.quote(str(self.home)) + ' ]]')
        env = dict(os.environ, HOME=str(self.home), GITHUB_ACTIONS='true', PATH=str(binary) + os.pathsep + os.environ['PATH'])
        env.pop('TMUX', None)
        with patch.dict(os.environ, env, clear=True):
            try:
                subprocess.run(['tmux', 'new-session', '-d', '-s', 'verify-session', 'sleep 300'], check=True)
                subprocess.run(['bash', '-euc', script], check=True, capture_output=True, text=True, timeout=45)
                command = [sys.executable, '-I', str(ROOT / 'tools/verify-seed.py')]
                recorded = subprocess.check_output(command + ['record'], text=True)
                self.evidence.write_text(recorded)
                counts = json.loads(subprocess.check_output(command + ['check', str(self.evidence)], text=True))
                self.assertEqual(json.loads(recorded), counts)
                self.assertEqual(42, counts['sessions'])
                self.assertEqual(40, len((self.home / 'pins-names.txt').read_text().splitlines()))
                for name in seed.BULK_NAMES:
                    self.assertEqual(name, subprocess.check_output(['tmux', 'display-message', '-p', '-t', f'={name}:', '#{window_name}'], text=True).strip())
                    self.assertEqual('off', subprocess.check_output(['tmux', 'show-window-options', '-t', f'={name}:', '-v', 'automatic-rename'], text=True).strip())
                self.assertEqual('cl-long-chat|claude', subprocess.check_output(['tmux', 'display-message', '-p', '-t', '=cl-long-chat:', '#{window_name}|#{pane_current_command}'], text=True).strip())
                reader = load('seed_reader', ROOT / 'app/src/main/assets/chat_history_reader.py')
                with patch.object(reader, 'HOME', self.home):
                    sid, transcript = reader.resolve_tmux_agent('claude', 'cl-long-chat')
                self.assertEqual(long_chat.SID, sid)
                self.assertEqual(Path(counts['transcript']['path']), transcript)
            finally:
                subprocess.run(['tmux', 'kill-server'], capture_output=True)


if __name__ == '__main__':
    unittest.main()
