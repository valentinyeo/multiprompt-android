import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

here = Path(__file__).resolve().parent
sys.path.insert(0, str(here))
import report

root = here.parents[1]


def sample(screen='chat', total=400, janky=40, percent=10.0, p95=20, scroll=5000):
    return {
        'screen': screen,
        'swipes': 12,
        'scrollMs': scroll,
        'totalFrames': total,
        'jankyFrames': janky,
        'jankyPercent': percent,
        'p50Ms': 8,
        'p90Ms': 16,
        'p95Ms': p95,
        'p99Ms': 33,
    }


class ReportTest(unittest.TestCase):
    def write(self, directory, name, value):
        path = Path(directory) / name
        path.write_text(json.dumps(value))
        return path

    def test_a_smooth_scroll_passes_and_reports_its_numbers(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write(directory, 'chat.json', sample())
            self.write(directory, 'inbox.json', sample('inbox', total=300, janky=30, percent=10.0, p95=24))
            samples = report.load(directory)
        text, verdict = report.report(samples)
        self.assertEqual([], verdict)
        self.assertIn('SCROLL_FRAME_TIME_OK', text)
        self.assertIn('| chat | 400 | 40 | 10.00 |', text)
        self.assertIn('| inbox | 300 | 30 | 10.00 |', text)

    def test_a_frozen_scroll_fails_on_janky_frames(self):
        text, verdict = report.report([sample(percent=91.5)])
        self.assertEqual(['chat: 91.50% janky frames, limit 60.00%'], verdict)
        self.assertIn('SCROLL_FRAME_TIME_FAILED', text)

    def test_a_frozen_scroll_fails_on_the_95th_percentile_frame(self):
        _, verdict = report.report([sample(percent=5.0, p95=480)])
        self.assertEqual(['chat: 95th percentile frame 480ms, limit 120ms'], verdict)

    def test_limits_come_from_the_environment(self):
        result = subprocess.run(
            [sys.executable, '-B', str(here / 'report.py'), '/nonexistent'],
            capture_output=True, text=True,
        )
        self.assertNotEqual(0, result.returncode)
        with tempfile.TemporaryDirectory() as directory:
            self.write(directory, 'chat.json', sample(percent=61.0))
            result = subprocess.run(
                [sys.executable, '-B', str(here / 'report.py'), directory],
                capture_output=True, text=True, env={'SCROLL_JANK_MAX_PERCENT': '70', 'PATH': '/usr/bin:/bin'},
            )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('SCROLL_FRAME_TIME_OK', result.stdout)

    def test_a_sample_without_scroll_frames_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            self.write(directory, 'chat.json', sample(total=2, janky=2))
            with self.assertRaises(ValueError):
                report.load(directory)

    def test_a_sample_missing_a_field_is_rejected(self):
        broken = sample()
        del broken['p95Ms']
        with tempfile.TemporaryDirectory() as directory:
            self.write(directory, 'chat.json', broken)
            with self.assertRaises(ValueError):
                report.load(directory)

    def test_an_empty_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                report.load(directory)


if __name__ == '__main__':
    unittest.main()
