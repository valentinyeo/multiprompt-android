import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / ".claude/skills/verify-multiprompt-android/scripts"
DRIVER = SCRIPTS / "chat-search-drive.sh"

SHOT = textwrap.dedent('''
shot() {
    local frame
    step=$((step + 1))
    frame=$(python3 "$FIXTURE/frames.py" "$FIXTURE")
    printf '%s\\n' "$step" >> "$EVIDENCE/shots.txt"
    cp "$FIXTURE/$frame.xml" "$EVIDENCE/$(printf '%02d' "$step")-$1.xml"
    cp "$FIXTURE/$frame.png" "$EVIDENCE/$(printf '%02d' "$step")-$1.png"
    cp "$FIXTURE/$frame.xml" "$EVIDENCE/current.xml"
}
dump_ui() {
    cp "$FIXTURE/$(python3 "$FIXTURE/frames.py" "$FIXTURE").xml" "$EVIDENCE/current.xml"
}
wait_for_text() { :; }
sleep() { :; }
''')

FRAMES = textwrap.dedent('''
import json
import sys
from pathlib import Path

fixture = Path(sys.argv[1])
plan = json.loads((fixture / 'plan.json').read_text())
done = fixture / 'done.txt'
index = len(done.read_text().split()) if done.exists() else 0
name = plan[min(index, len(plan) - 1)]
with done.open('a') as handle:
    handle.write(f'{name} ')
print(name)
''')


def functions():
    source = DRIVER.read_text()
    start = source.index('search_target_bounds() {')
    end = source.index('tap_text verify-session', start)
    return source[start:end]


class SettleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.evidence = self.root / 'evidence'
        self.evidence.mkdir()
        self.fixture = self.root / 'fixture'
        self.fixture.mkdir()
        self.workspace = self.root / 'workspace'
        (self.workspace / 'tools/chat-search').mkdir(parents=True)
        for name in ('ui.py', 'bounds.py'):
            shutil.copy(ROOT / 'tools/chat-search' / name, self.workspace / 'tools/chat-search' / name)
        (self.fixture / 'frames.py').write_text(FRAMES)

    def frame(self, name, highlighted, top=100, target='orchard south'):
        root = ET.Element('hierarchy')
        for attributes in (
            {'text': 'orchard', 'class': 'android.widget.EditText'},
            {'text': '2 of 2'},
            {'content-desc': 'Close search'},
            {'content-desc': 'Previous match'},
            {'content-desc': 'Next match'},
            {'text': target, 'bounds': f'[20,{top}][180,{top + 30}]'},
        ):
            ET.SubElement(root, 'node', {'package': 'dev.multiprompt.companion.debug', **attributes})
        ET.ElementTree(root).write(self.fixture / f'{name}.xml')
        image = Image.new('RGB', (240, 400), (16, 16, 16))
        draw = ImageDraw.Draw(image)
        if highlighted:
            draw.rectangle((20, top, 109, top + 25), fill=(242, 242, 242))
        else:
            draw.text((20, top), target, fill=(242, 242, 242))
        image.save(self.fixture / f'{name}.png')

    def plan(self, names):
        (self.fixture / 'plan.json').write_text(json.dumps(names))

    def run_bash(self, body):
        stream = {
            'per_shot': 'search_state newest orchard "2 of 2" "orchard south"',
            'settle': 'search_settle_target "orchard south"',
        }[body]
        script = (
            textwrap.dedent(f'''
            set -euo pipefail
            cd {self.workspace}
            EVIDENCE={self.evidence} FIXTURE={self.fixture} package=dev.multiprompt.companion.debug step=0
            BOUNDS={self.workspace / 'tools/chat-search/bounds.py'}
            ''')
            + SHOT
            + functions()
            + textwrap.dedent(f'''
            {stream}
            ''')
        )
        return subprocess.run(['bash', '-c', script], capture_output=True, text=True)

    def test_retakes_the_shot_and_stops_on_the_first_disagreeing_then_agreeing_pair(self):
        self.frame('plain', highlighted=False)
        self.frame('settled', highlighted=True)
        self.plan(['plain', 'plain', 'plain', 'settled'])
        result = self.run_bash('per_shot')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.evidence / 'shots.txt').read_text().split(), ['1', '2'])
        self.assertIn('SEARCH_SHOT_RETAKEN', (self.evidence / 'search-settle.txt').read_text())
        self.assertNotIn('SEARCH_SHOT_UNSETTLED', result.stderr)
        self.assertIn('"highlight_fraction"', (self.evidence / 'search-newest.json').read_text())
        self.assertTrue((self.evidence / '02-search-newest.png').exists())

    def test_fails_after_the_last_try_when_the_highlight_is_really_missing(self):
        self.frame('plain', highlighted=False)
        self.plan(['plain'])
        result = self.run_bash('per_shot')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.evidence / 'shots.txt').read_text().split(), ['1', '2', '3', '4', '5'])
        self.assertIn('SEARCH_SHOT_UNSETTLED', result.stderr)
        self.assertIn('Active match highlight is missing', result.stderr)
        self.assertFalse((self.evidence / 'search-newest.json').exists())

    def test_waits_for_two_consecutive_dumps_with_the_same_bounds(self):
        for index in range(4):
            self.frame(f'step{index}', highlighted=True, top=300 - index * 50)
        self.plan(['step0', 'step1', 'step2', 'step2'])
        result = self.run_bash('settle')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('SEARCH_TARGET_SETTLED', (self.evidence / 'search-settle.txt').read_text())

    def test_reports_a_moving_target_instead_of_hanging(self):
        for index in range(8):
            self.frame(f'step{index}', highlighted=True, top=300 - index * 30)
        self.plan([f'step{index}' for index in range(8)])
        result = self.run_bash('settle')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('SEARCH_TARGET_MOVING', result.stderr)


if __name__ == '__main__':
    unittest.main()
