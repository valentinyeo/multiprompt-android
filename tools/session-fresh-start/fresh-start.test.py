import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
READER = ROOT / 'app/src/main/assets/chat_history_reader.py'
sys.path.insert(0, str(HERE))

import inbox as inbox_tool
import fixture


class FreshStartFixtureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.home = Path(self.directory.name)
        reader = self.home / 'session-fresh-start-reader.py'
        reader.write_bytes(READER.read_bytes())
        os.environ['GITHUB_ACTIONS'] = 'true'
        fixture.HOME = self.home
        fixture.READER = reader
        fixture.CWD = self.home / 'fresh-start-work'

    def test_fresh_start_chooses_native_after_and_hides_old_stale(self):
        payload = fixture.seed_before(self.home)
        fixture.write_json(self.home / 'session-fresh-start.json', payload)
        with patch.object(subprocess, 'run', return_value=None):
            fixture.apply_fresh_start(self.home)
        visible = fixture.visible_transcript(self.home)['visible']
        self.assertNotIn(fixture.MARKERS['old'], visible)
        self.assertEqual(['HANDOVER_3000', 'FRESH_REPLY_3000'], visible[-2:])

    def test_inbox_counter_requires_exactly_one_row(self):
        xml = self.home / 'inbox.xml'
        session = 'live-fresh-start-3000'
        xml.write_text(f'''<hierarchy>
  <node text="{session}" bounds="[40,400][900,520]" class="android.widget.TextView" />
  <node text="{session}" bounds="[40,900][900,1020]" class="android.widget.TextView" />
</hierarchy>''')
        self.assertEqual(2, len(inbox_tool.session_rows(xml, session)))


if __name__ == '__main__':
    unittest.main()