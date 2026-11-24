#!/usr/bin/env python3
import hashlib
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
READER = ROOT / 'app/src/main/assets/chat_history_reader.py'
HERE = Path(__file__).resolve().parent


def load(name, path):
    loader = importlib.machinery.SourceFileLoader(name, str(path))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


fixture = load('real_chat_fixture', HERE / 'fixture.py')


def runtime(source, home):
    namespace = {'__name__': 'real_chat_probe'}
    exec(compile(Path(source).read_text(), str(source), 'exec'), namespace)
    reader = {}
    exec(namespace['READER_SOURCE'], reader)
    reader['HOME'] = Path(home)
    return reader


def visible(reader, query, resolved):
    agent, sid, path, delegated = resolved
    wire = reader['response'](dict(query, sid=sid, follow=False), None, False, resolved)
    texts = []
    for line in wire.splitlines()[1:]:
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if row.get('role') in ('user', 'assistant') and row.get('text'):
            texts.append(row['text'])
    return Path(path).name if path is not None else None, texts


class RealChatFixtureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        self.manifest = fixture.create(self.home)

    def query(self, **values):
        return dict(dict(id=1, provider='claude', codex=False, sid=fixture.CHAT, tmux=fixture.SESSION,
                         cwd=self.manifest['cwd'], follow=False, offset=0, before=0, cached_sid='', inode=0,
                         mtime=0, probe='', start=0), **values)

    def test_seed_is_real_size_tool_heavy_and_synthetic(self):
        measured = fixture.counts(self.manifest['transcript'])
        self.assertGreaterEqual(measured['bytes'], fixture.MIN_BYTES)
        self.assertLessEqual(measured['bytes'], fixture.MAX_BYTES)
        self.assertGreaterEqual(measured['rows'], fixture.MIN_ROWS)
        self.assertLessEqual(measured['rows'], fixture.MAX_ROWS)
        self.assertGreaterEqual(measured['tool_rows'] / measured['rows'], fixture.MIN_TOOL_SHARE)
        self.assertLessEqual(measured['tool_rows'] / measured['rows'], fixture.MAX_TOOL_SHARE)
        self.assertEqual(fixture.check(self.home), self.manifest)
        for text in fixture.screen_texts(self.manifest['transcript']):
            self.assertTrue('Synthetic' in text or '_2781' in text, text)

    def test_seed_restarts_and_dispatch_events(self):
        self.assertEqual([fixture.FIRST, fixture.SECOND, fixture.LIVE], self.manifest['init_sessions'])
        self.assertEqual(['real-queue-1', 'real-queue-2', 'real-queue-3'], self.manifest['accepted'])
        self.assertEqual(self.manifest['accepted'], self.manifest['delivered'])
        self.assertEqual(2, len(self.manifest['handovers']))
        self.assertEqual(fixture.STALE_FIRST, fixture.last_text(self.manifest['first_transcript']))
        self.assertEqual(fixture.STALE_SECOND, fixture.last_text(self.manifest['second_transcript']))

    def test_bundled_reader_shows_the_newest_reply_of_the_live_session(self):
        reader = runtime(READER, self.home)
        query = self.query()
        resolved = reader['resolve_response_path'](dict(query))
        transcript, texts = visible(reader, query, resolved)
        self.assertEqual(fixture.LIVE + '.jsonl', transcript)
        self.assertEqual(fixture.REPLY_NEWEST, texts[-1])
        self.assertIn(fixture.USER_NEWEST, texts)
        self.assertNotIn(fixture.STALE_FIRST, texts)
        self.assertNotIn(fixture.STALE_SECOND, texts)
        self.assertNotIn(fixture.NEIGHBOUR_TEXT, texts)

    def test_stale_cached_position_still_returns_the_newest_reply(self):
        reader = runtime(READER, self.home)
        older = Path(self.manifest['first_transcript'])
        offsets = fixture.row_offsets(older)
        query = self.query(cached_sid=fixture.FIRST, inode=older.stat().st_ino,
                           offset=offsets[-1][1], start=offsets[0][0], mtime=int(older.stat().st_mtime),
                           probe=hashlib.sha256(older.read_bytes()).hexdigest())
        resolved = reader['resolve_response_path'](dict(query))
        transcript, texts = visible(reader, query, resolved)
        self.assertEqual(fixture.LIVE + '.jsonl', transcript)
        self.assertEqual(fixture.REPLY_NEWEST, texts[-1])

    def test_appended_rows_arrive_on_the_next_live_page(self):
        path = Path(self.manifest['transcript'])
        end = fixture.row_offsets(path)[-1][1]
        info = path.stat()
        probe = hashlib.sha256(path.read_bytes()[max(0, end - 64):end]).hexdigest()
        fixture.append(self.home, 'user', fixture.USER_APPEND)
        fixture.append(self.home, 'assistant', fixture.REPLY_APPEND)
        reader = runtime(READER, self.home)
        query = self.query(cached_sid=fixture.LIVE, inode=info.st_ino, offset=end, mtime=info.st_mtime_ns, probe=probe)
        resolved = reader['resolve_response_path'](dict(query))
        transcript, texts = visible(reader, query, resolved)
        self.assertEqual(fixture.LIVE + '.jsonl', transcript)
        self.assertEqual([fixture.USER_APPEND, fixture.REPLY_APPEND], texts[-2:])

    def test_append_keeps_the_seed_check_green(self):
        fixture.append(self.home, 'user', fixture.USER_APPEND)
        manifest = fixture.check(self.home)
        self.assertEqual(fixture.USER_APPEND, fixture.last_text(manifest['transcript']))
        self.assertEqual([fixture.USER_APPEND], [row['text'] for row in manifest['appended']])
        fixture.append(self.home, 'assistant', fixture.REPLY_APPEND)
        self.assertEqual(fixture.REPLY_APPEND, fixture.last_text(fixture.check(self.home)['transcript']))
        for text in fixture.screen_texts(manifest['transcript']):
            self.assertTrue('Synthetic' in text or '_2781' in text, text)

    def test_append_command_writes_a_row_the_check_accepts(self):
        subprocess.run(['python3', '-B', str(HERE / 'fixture.py'), 'append', '--home', str(self.home),
                        '--role', 'assistant', '--text', fixture.REPLY_APPEND], check=True, capture_output=True, text=True)
        receipt = json.loads(subprocess.check_output(
            ['python3', '-B', str(HERE / 'fixture.py'), 'check', '--home', str(self.home)], text=True))
        self.assertEqual(fixture.REPLY_APPEND, fixture.last_text(receipt['transcript']))
        self.assertEqual('assistant', receipt['appended'][-1]['role'])

    def test_phone_cache_file_matches_the_row_contract(self):
        payload = fixture.cache(self.home, 'host-id', '10.0.2.2', '2222', 'verifyssh', 'CLAUDE',
                                fixture.SESSION, self.manifest['cwd'])
        key = '\u0000'.join(['host-id', '10.0.2.2', '2222', 'verifyssh', 'CLAUDE', fixture.SESSION, self.manifest['cwd']])
        self.assertEqual(hashlib.sha256(key.encode()).hexdigest() + '-' +
                         hashlib.sha256(fixture.FIRST.encode()).hexdigest() + '.jsonl', payload['name'])
        header = json.loads(payload['lines'][0])
        self.assertEqual(6, header['version'])
        self.assertEqual(fixture.FIRST, header['sid'])
        self.assertLessEqual(header['start'], header['offset'])
        rows = [json.loads(line) for line in payload['lines'][1:]]
        self.assertEqual(fixture.CACHE_ROWS, len(rows))
        self.assertEqual(sorted(rows, key=lambda row: row['offset']), rows)
        for row in rows:
            self.assertIn(row['role'], ('user', 'assistant'))
            self.assertTrue(row['text'])
            self.assertGreaterEqual(row['offset'], header['start'])
            self.assertGreater(row['end'], row['offset'])
            self.assertLessEqual(row['end'], header['offset'])
        self.assertEqual(rows[-1]['end'], header['offset'])
        self.assertEqual(fixture.STALE_FIRST, rows[-1]['text'])

    def test_cache_command_writes_the_same_file(self):
        output = self.home / 'cache.jsonl'
        subprocess.run(['python3', '-B', str(HERE / 'fixture.py'), 'cache', '--home', str(self.home),
                        '--host-id', 'host-id', '--hostname', '10.0.2.2', '--port', '2222',
                        '--username', 'verifyssh', '--tmux', fixture.SESSION, '--cwd', self.manifest['cwd'],
                        '--out', str(output)], check=True, capture_output=True, text=True)
        lines = output.read_text().splitlines()
        self.assertEqual(6, json.loads(lines[0])['version'])
        self.assertEqual(fixture.CACHE_ROWS, len(lines) - 1)


if __name__ == '__main__':
    unittest.main()
