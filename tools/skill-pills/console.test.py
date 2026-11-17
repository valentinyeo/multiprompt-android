import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / '.claude/skills/verify-multiprompt-android/scripts/skills-console.py'


class SkillsConsoleTest(unittest.TestCase):
    def test_phone_metadata_is_recorded_raw_without_becoming_an_extra_chat_turn(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            receipts = home / 'receipts.txt'
            inputs = ['Please /review-native this', '[mp:0123456789abcdef]',
                      'Keep this', '[mp:fedcba9876543210]']
            run = subprocess.run([sys.executable, str(SCRIPT), str(receipts)],
                                 input='\n'.join(inputs) + '\n', capture_output=True, text=True,
                                 env={**os.environ, 'HOME': directory}, timeout=10)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertEqual(receipts.read_text().splitlines(), ['VERIFY_RECEIVED:' + text for text in inputs])
            transcript, = home.glob('.claude/projects/*/*.jsonl')
            turns = [json.loads(line) for line in transcript.read_text().splitlines()]
            self.assertEqual([turn['message']['content'] for turn in turns if turn['type'] == 'user'],
                             ['VERIFY_READY_528', inputs[0], inputs[2]])
            self.assertEqual(len([turn for turn in turns if turn['type'] == 'assistant']), 3)
            self.assertTrue((home / 'native-skills-work/.claude/skills/review-native/SKILL.md').is_file())


if __name__ == '__main__':
    unittest.main()
