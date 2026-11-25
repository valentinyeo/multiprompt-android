import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import report

STEP = dict(name='launch-inbox', started_unix=1000.0, ended_unix=1010.0, wall_ms=10_000.0, cpu_ticks=550,
            cpu_ms=5_500.0, pids=['123'], gfx=dict(frames=40, janky=20, janky_percent=50.0, p50_ms=120, p90_ms=400,
                                                   p95_ms=500, p99_ms=700, missed_vsync=3, slow_ui=4, slow_draw=5),
            detail=dict())


def timing(seed='scale', ref='head'):
    return dict(seed=seed, ref=ref, package='dev.multiprompt.companion.debug', clk_tck=100,
                relay=True, targets=dict(largest=dict(session='live-scale-01', marker='SCALE_CHAT_LATEST_4704_01',
                                                      bytes=54_000_000, rows=30_000)),
                steps=[dict(STEP)])


def relay_lines(outside=True):
    lines = [
        dict(event='open', stream='history', t=950.0),
        dict(event='call', stream='history', call=1, request=1, bytes=900, request_bytes=300, ms=40.0, t=950.5),
        dict(event='call', stream='history', call=2, request=2, bytes=1100, request_bytes=320, ms=60.0, t=1005.0),
        dict(event='call', stream='history', call=3, request=3, bytes=700, request_bytes=310, ms=20.0, t=1008.0),
        dict(event='call', stream='tmux', call=1, request=1, bytes=4500, request_bytes=80, ms=15.0, t=1002.0),
        dict(event='call', stream='tmux', call=2, request=1, bytes=4100, request_bytes=80, ms=25.0, t=1030.0),
        dict(event='close', stream='history', calls=3, bytes=2700, ms=90.0, t=1030.0),
    ]
    return ''.join(json.dumps(line) + '\n' for line in lines)


class AttachTest(unittest.TestCase):
    def test_only_calls_inside_the_step_window_are_counted(self):
        enriched = report.enrich(timing(), relay_lines())
        step = enriched['steps'][0]
        self.assertEqual(step['history']['calls'], 2)
        self.assertEqual(step['history']['bytes'], 1800)
        self.assertEqual(step['history']['median_bytes'], 900)
        self.assertEqual(step['history']['median_ms'], 40)
        self.assertEqual(step['sync']['calls'], 1)
        self.assertEqual(step['sync']['bytes'], 4500)

    def test_missing_relay_log_leaves_zero_counts(self):
        enriched = report.enrich(timing(), '')
        step = enriched['steps'][0]
        self.assertEqual(step['history']['calls'], 0)
        self.assertEqual(step['history']['median_bytes'], 0)


class RenderTest(unittest.TestCase):
    def test_render_shows_wall_cpu_and_reader_traffic(self):
        small = report.enrich(timing(seed='small', ref='main'), relay_lines())
        scale = report.enrich(timing(seed='scale', ref='main'), relay_lines())
        text = report.render([small, scale])
        self.assertIn('| main scale wall / cpu ms | main small wall / cpu ms |', text)
        self.assertIn('10000 / 5500', text)
        self.assertIn('cold launch to Inbox', text)
        self.assertIn('54000000 bytes, 30000 rows', text)
        self.assertIn('| main | scale | launch-inbox | 10000 | 5500 | 50 | 40 | 2 | 900 | 40 | 1 | 4500 |', text)

    def test_render_orders_columns_by_ref_then_seed(self):
        text = report.render([report.enrich(timing(seed='scale', ref='9374e19f'), ''),
                              report.enrich(timing(seed='small', ref='head'), '')])
        header = next(line for line in text.splitlines() if 'wall / cpu ms' in line)
        self.assertLess(header.index('9374e19f scale'), header.index('head small'))


class MainTest(unittest.TestCase):
    def test_cli_writes_the_table_and_the_json(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'measurements'
            run = root / 'timing-scale-head'
            run.mkdir(parents=True)
            (run / 'timing.json').write_text(json.dumps(timing()))
            (run / 'relay.jsonl').write_text(relay_lines())
            out = Path(directory) / 'report.md'
            code = report.main(['report.py', '--root', str(root), '--out', str(out)])
            self.assertEqual(code, 0)
            self.assertTrue(out.is_file())
            self.assertIn('launch-inbox', out.read_text())
            self.assertTrue((out.parent / 'timings.json').is_file())

    def test_cli_fails_when_nothing_was_measured(self):
        with tempfile.TemporaryDirectory() as directory:
            code = report.main(['report.py', '--root', directory, '--out', str(Path(directory) / 'report.md')])
            self.assertEqual(code, 1)


if __name__ == '__main__':
    unittest.main()
