import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parent / 'fixture.py'


def load():
    spec = importlib.util.spec_from_file_location('scale_chat_fixture', SOURCE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PlanTest(unittest.TestCase):
    def test_scale_plan_follows_the_owner_distribution(self):
        fixture = load()
        plan = fixture.plan()
        self.assertEqual(len(plan['chats']), 40)
        self.assertEqual(plan['transcript_target'], 160)
        sizes = sorted(chat['planned_bytes'] for chat in plan['chats'])
        self.assertEqual(sizes[-1], 54_000_000)
        self.assertEqual(max(chat['planned_rows'] for chat in plan['chats']), 30_000)
        self.assertGreaterEqual(sum(1 for size in sizes if size >= 10_000_000), 6)
        self.assertEqual(sizes[len(sizes) // 2], 1_300_000)
        self.assertEqual(len(set(plan['markers'])), 40)
        self.assertEqual(len({chat['session'] for chat in plan['chats']}), 40)
        self.assertEqual(len({chat['chat'] for chat in plan['chats']}), 40)

    def test_every_chat_has_a_distinct_sid(self):
        fixture = load()
        sids = [chat['sid'] for chat in fixture.plan()['chats']]
        self.assertEqual(len(set(sids)), 40)
        for sid in sids:
            self.assertRegex(sid, r'^[A-Za-z0-9_-]{1,80}$')


class RowsTest(unittest.TestCase):
    def chat(self):
        fixture = load()
        chat = fixture.entry(12)
        chat.update(planned_bytes=200_000, planned_rows=170)
        return fixture, chat

    def test_rows_cover_the_real_shapes_and_end_on_the_marker(self):
        fixture, chat = self.chat()
        rows = list(fixture.rows(chat))
        kinds = [row['message']['content'][0]['type'] for row in rows]
        self.assertGreaterEqual(len(rows), chat['planned_rows'])
        for kind in ('text', 'tool_use', 'tool_result'):
            self.assertIn(kind, kinds)
        self.assertGreater(kinds.count('tool_use'), len(rows) // 5)
        roles = {row['message']['role'] for row in rows}
        self.assertEqual(roles, {'user', 'assistant'})
        texts = [row['message']['content'][0].get('text', '') for row in rows]
        self.assertTrue(any('```kotlin' in text for text in texts))
        self.assertTrue(rows[-1]['message']['content'][0]['text'].startswith(chat['marker']))
        self.assertEqual(rows[-2]['message']['role'], 'user')
        for row in rows:
            self.assertEqual(len(row['uuid']), 36)
            self.assertEqual(row['sessionId'], chat['sid'])
        tools = {row['message']['content'][0]['name'] for row in rows if row['message']['content'][0]['type'] == 'tool_use'}
        self.assertEqual(tools, {'Bash', 'Read', 'Edit', 'Grep'})

    def test_serialized_bytes_land_inside_the_envelope(self):
        fixture, chat = self.chat()
        raw = ''.join(json.dumps(row) + '\n' for row in fixture.rows(chat))
        self.assertGreaterEqual(len(raw), 0.8 * chat['planned_bytes'])
        self.assertLessEqual(len(raw), 1.25 * chat['planned_bytes'])


class SeedTest(unittest.TestCase):
    def small(self):
        fixture = load()
        fixture.SCALE = 3
        fixture.TYPICAL = (150_000, 140)
        fixture.BIG = {1: (600_000, 500), 2: (200_000, 180), 3: (160_000, 150)}
        return fixture

    def test_seed_writes_transcripts_config_and_a_marker(self):
        fixture = self.small()
        with tempfile.TemporaryDirectory() as directory:
            manifest = fixture.create(Path(directory))
            for chat in manifest['chats']:
                live = Path(chat['transcript'])
                self.assertTrue(live.is_file())
                self.assertEqual(fixture.last_text(live).startswith(chat['marker']), True)
                self.assertGreaterEqual(chat['bytes'], 0.8 * chat['planned_bytes'])
                self.assertLessEqual(chat['bytes'], 1.25 * chat['planned_bytes'])
                self.assertEqual(len(list(Path(chat['folder']).glob('*.jsonl'))), 1 + fixture.STALE)
                config = json.loads(Path(chat['config']).read_text())
                self.assertEqual(config['sid'], chat['chat'])
                self.assertEqual(config['tmux'], chat['session'])
            largest = max(manifest['chats'], key=lambda chat: chat['bytes'])
            self.assertEqual(manifest['measured']['largest_marker'], largest['marker'])

    def test_check_rejects_a_truncated_transcript(self):
        fixture = self.small()
        with tempfile.TemporaryDirectory() as directory:
            manifest = fixture.create(Path(directory))
            fixture.check(Path(directory))
            live = Path(manifest['chats'][0]['transcript'])
            lines = live.read_bytes().splitlines()
            live.write_bytes(b'\n'.join(lines[:-1]) + b'\n')
            with self.assertRaises(AssertionError):
                fixture.check(Path(directory))


if __name__ == '__main__':
    unittest.main()
