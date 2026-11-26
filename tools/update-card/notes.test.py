#!/usr/bin/env python3
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/update-card'))

import notes


class NotesTest(unittest.TestCase):
    def test_offered_release_changes_are_plain_lines(self):
        manifest = {
            'versionName': '0.2.0-m3',
            'versionCode': 3,
            'notes': "- **MUBO-1** Chat view needs no <b>screen</b> text\n* `MUBO-2` Code font",
        }
        self.assertEqual(
            notes.changes(manifest['notes']),
            ['MUBO-1 Chat view needs no screen text', 'MUBO-2 Code font'],
        )

    def test_headers_and_ticket_only_filler_are_dropped(self):
        raw = "## What's new\n- MUBO-1 Real change\nMUBO-2\nRelease notes\n- MUBO-1 Real change"
        self.assertEqual(notes.changes(raw), ['MUBO-1 Real change'])

    def test_preview_stops_at_three_changes(self):
        raw = '\n'.join(f'- MUBO-{index} Change {index}' for index in range(1, 6))
        self.assertEqual(len(notes.changes(raw)), 5)
        self.assertEqual(notes.preview(raw), ['MUBO-1 Change 1', 'MUBO-2 Change 2', 'MUBO-3 Change 3'])

    def test_short_and_missing_notes(self):
        self.assertEqual(notes.preview('- MUBO-1 Only change'), ['MUBO-1 Only change'])
        self.assertEqual(notes.preview(''), [])
        self.assertEqual(notes.preview(None), [])

    def test_manifest_report_names_the_offered_release_only(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'
            manifest.write_text(json.dumps({
                'versionName': '0.2.0-m3',
                'versionCode': 3,
                'notes': '- MUBO-1 Offered change',
                'history': [{'versionName': '0.1.0-m3', 'notes': '- MUBO-9 Older change'}],
            }))
            output = subprocess.run(
                [sys.executable, '-B', str(ROOT / 'tools/update-card/notes.py'), str(manifest)],
                capture_output=True, text=True, check=True,
            ).stdout
        report = json.loads(output)
        self.assertEqual(report['lines'], ['MUBO-1 Offered change'])
        self.assertEqual(report['preview'], ['MUBO-1 Offered change'])
        self.assertNotIn('MUBO-9 Older change', output)


if __name__ == '__main__':
    unittest.main()
