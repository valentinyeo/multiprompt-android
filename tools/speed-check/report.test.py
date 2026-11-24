import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

here = Path(__file__).resolve().parent
sys.path.insert(0, str(here))
import report

MUBO_298_BASE = 'CHAT_LIST_REMEASURES idle=0\nCHAT_LIST_REMEASURES live=5\n'
MUBO_298_HEAD = 'CHAT_LIST_REMEASURES idle=5\nCHAT_LIST_REMEASURES live=5\n'


class VerdictTest(unittest.TestCase):
    def judge(self, base_text, head_text, base_status=0, head_status=0):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / 'base.txt'
            head = Path(tmp) / 'head.txt'
            base.write_text(base_text)
            head.write_text(head_text)
            out = Path(tmp) / 'out'
            result = subprocess.run([
                sys.executable, str(here / 'report.py'),
                '--base', str(base), '--head', str(head),
                '--base-status', str(base_status), '--head-status', str(head_status),
                '--out', str(out),
            ], capture_output=True, text=True)
            markdown = (out / 'report.md').read_text() if (out / 'report.md').exists() else ''
            return result, markdown

    def test_replays_the_mubo_298_slowdown_as_slow(self):
        result, markdown = self.judge(MUBO_298_BASE, MUBO_298_HEAD, head_status=1)
        self.assertEqual(3, result.returncode, result.stdout)
        self.assertIn('SPEED_CHECK_SLOW', result.stdout)
        self.assertIn('remeasured the chat list 5 times', result.stdout)
        self.assertIn('SPEED_CHECK_SLOW', markdown)

    def test_passes_when_head_matches_base(self):
        result, _ = self.judge(MUBO_298_BASE, MUBO_298_BASE)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn('SPEED_CHECK_OK', result.stdout)

    def test_passes_when_head_removes_a_slow_recomposition(self):
        result, _ = self.judge(MUBO_298_HEAD, MUBO_298_BASE, base_status=1)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn('SPEED_CHECK_OK', result.stdout)

    def test_fails_when_one_reply_costs_more_than_the_margin(self):
        head = 'CHAT_LIST_REMEASURES idle=0\nCHAT_LIST_REMEASURES live=9\n'
        result, _ = self.judge(MUBO_298_BASE, head)
        self.assertEqual(3, result.returncode, result.stdout)
        self.assertIn('the budget is 8.25', result.stdout)

    def test_allows_a_reply_cost_inside_the_margin(self):
        head = 'CHAT_LIST_REMEASURES idle=0\nCHAT_LIST_REMEASURES live=8\n'
        result, _ = self.judge(MUBO_298_BASE, head)
        self.assertEqual(0, result.returncode, result.stdout)

    def test_is_never_green_without_a_head_reading(self):
        result, _ = self.judge(MUBO_298_BASE, '', head_status=1)
        self.assertEqual(4, result.returncode, result.stdout)
        self.assertIn('SPEED_CHECK_INCOMPLETE', result.stdout)

    def test_is_never_green_when_the_head_probe_fails(self):
        result, _ = self.judge(MUBO_298_BASE, MUBO_298_BASE, head_status=1)
        self.assertEqual(3, result.returncode, result.stdout)
        self.assertIn('the head probe test failed', result.stdout)

    def test_reads_the_largest_reading_when_a_file_repeats_one(self):
        text = 'CHAT_LIST_REMEASURES idle=0\nCHAT_LIST_REMEASURES idle=3\nCHAT_LIST_REMEASURES live=5\n'
        self.assertEqual({'idle': 3, 'live': 5}, report.parse(text))

    def test_ignores_lines_it_did_not_write(self):
        self.assertEqual({}, report.parse('CHAT_LIST_REMEASURES idle=oops\nremeasures live=4\n'))


if __name__ == '__main__':
    unittest.main()
