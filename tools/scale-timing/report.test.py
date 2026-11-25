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
        dict(event='open', stream='tmux', t=1001.9),
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
        self.assertIn('| main | scale | launch-inbox | 10000 | 5500 | 50 | 40 | 2 | 1800 | 900 | 40 | 1 | 4500 | 4500 | 1 | 0 |', text)

    def test_switch_table_names_the_action_that_ate_the_time(self):
        switched = dict(STEP, name='switch-five', detail=dict(chats=[
            dict(session='live-scale-01', bytes=54_532_789, opened=True, back_ms=900.0, inbox_ms=61_000.0,
                 tap_ms=1_200.0, marker_ms=2_400.0, switch_ms=65_500.0)]))
        text = report.render([report.enrich(dict(timing(), steps=[switched]), relay_lines())])
        self.assertIn('| head | scale | live-scale-01 | 54532789 | 900 | 61000 | 1200 | 2400 | 65500 |', text)

    def test_render_names_measurements_that_lost_their_reader_log(self):
        kept = report.enrich(timing(seed='small', ref='head'), relay_lines())
        lost = report.enrich(timing(seed='scale', ref='head'), '')
        text = report.render([kept, lost])
        self.assertIn('No reader log was retained for head scale', text)
        self.assertNotIn('No reader log was retained for head small', text)

    def test_render_orders_columns_by_ref_then_seed(self):
        text = report.render([report.enrich(timing(seed='scale', ref='9374e19f'), relay_lines()),
                              report.enrich(timing(seed='small', ref='head'), relay_lines())])
        header = next(line for line in text.splitlines() if 'wall / cpu ms' in line)
        self.assertLess(header.index('9374e19f scale'), header.index('head small'))


class BreakdownTest(unittest.TestCase):
    def test_switch_phase_names_driver_wait_and_app_cpu(self):
        switched = dict(STEP, name='switch-five', detail=dict(chats=[dict(
            session='live-scale-01', opened=True,
            phases=[dict(name='tap the Inbox row', wall_ms=4252.0, driver_ms=4100.0, wait_ms=152.0, cpu_ms=310.0,
                         threads=[dict(tid='2062', name='main', ms=300.0)]),
                    dict(name='wait for the newest row', wall_ms=2314.0, driver_ms=1500.0, wait_ms=814.0, cpu_ms=410.0,
                         threads=[dict(tid='2183', name='RenderThread', ms=380.0)])])]))
        text = report.render([report.enrich(dict(timing(), steps=[switched]), relay_lines())])
        self.assertIn('## Wall, driver and app CPU per switch phase', text)
        self.assertIn('| head | scale | live-scale-01 | tap the Inbox row | 4252 | 4100 | 152 | 310 | main 300 |', text)
        self.assertIn('| head | scale | live-scale-01 | wait for the newest row | 2314 | 1500 | 814 | 410 | RenderThread 380 |', text)

    def test_launch_stages_show_where_the_wall_clock_went(self):
        launched = dict(STEP, name='launch-inbox', detail=dict(am_start='TotalTime: 3400', stages=[
            dict(name='process start', at_ms=120.0, stage_ms=120.0, driver_ms=110.0),
            dict(name='first frame', at_ms=3400.0, stage_ms=3280.0, driver_ms=0.0)]))
        text = report.render([report.enrich(dict(timing(), steps=[launched]), relay_lines())])
        self.assertIn('## Cold launch stages', text)
        self.assertIn('| head | scale | process start | 120 | 120 | 110 |', text)
        self.assertIn('| head | scale | first frame | 3400 | 3280 | 0 |', text)

    def test_driver_probe_shows_how_many_dumps_a_tap_costs(self):
        probed = dict(STEP, name='driver-alone', detail=dict(
            dump=dict(label='uiautomator dump (driver)', ms=1300.0), read=dict(label='mpa read', ms=1450.0),
            wait_present=dict(label='mpa read --wait-text', ms=1500.0), input=dict(label='input tap', ms=120.0),
            tap=dict(label='mpa tap', ms=3120.0)))
        text = report.render([report.enrich(dict(timing(), steps=[probed]), relay_lines())])
        self.assertIn('## The driver on its own', text)
        self.assertIn('| head | scale | uiautomator dump (driver) | 1300 |', text)
        self.assertIn('| head | scale | resolved tap minus the raw input, in dumps | 2.3 |', text)

    def test_idle_inbox_shows_what_the_app_costs_with_nothing_happening(self):
        idle = dict(STEP, name='idle-inbox', wall_ms=21000.0, cpu_ms=4200.0, detail=dict(seconds=20))
        text = report.render([report.enrich(dict(timing(), steps=[idle]), relay_lines())])
        self.assertIn('## An idle Inbox', text)
        self.assertIn('| head | scale | 21000 | 4200 | 400 | 1 | 0 |', text)

    def test_a_timing_without_breakdowns_renders_no_breakdown_sections(self):
        text = report.render([report.enrich(timing(), relay_lines())])
        self.assertNotIn('## Cold launch stages', text)
        self.assertNotIn('## The driver on its own', text)
        self.assertNotIn('## An idle Inbox', text)


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
