import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import parsers

PROC_STAT = '1234 (dev.multiprompt.compa) S 1 1234 1234 0 -1 4194624 12345 0 0 0 550 120 0 0 20 0 25 0 123 4567890 1234 18446744073709551615'

GFXINFO = '''** Graphics info for pid 1234 [dev.multiprompt.companion.debug] **

Total frames rendered: 40
Janky frames: 20 (50.00%)
Janky frames (legacy): 20 (50.00%)
50th percentile: 120ms
90th percentile: 400ms
95th percentile: 500ms
99th percentile: 700ms
Number Missed Vsync: 3
Number High input latency: 0
Number Slow UI thread: 4
Number Slow bitmap uploads: 0
Number Slow issue draw commands: 5
'''


class ProcStatTest(unittest.TestCase):
    def test_reads_utime_and_stime(self):
        found = parsers.parse_proc_stat(PROC_STAT)
        self.assertEqual(found['utime'], 550)
        self.assertEqual(found['stime'], 120)
        self.assertEqual(found['total'], 670)

    def test_process_name_with_spaces_and_parentheses(self):
        text = '7 (multi (prompt)) R 1 7 7 0 -1 0 0 0 0 0 30 4 0 0 20 0 1 0 10 0 0'
        self.assertEqual(parsers.parse_proc_stat(text)['total'], 34)

    def test_empty_pid_reads_zero(self):
        self.assertEqual(parsers.parse_proc_stat('')['total'], 0)


class GfxInfoTest(unittest.TestCase):
    def test_reads_frames_jank_and_percentiles(self):
        found = parsers.parse_gfxinfo(GFXINFO)
        self.assertEqual(found['frames'], 40)
        self.assertEqual(found['janky'], 20)
        self.assertEqual(found['janky_percent'], 50.0)
        self.assertEqual((found['p50_ms'], found['p90_ms'], found['p95_ms'], found['p99_ms']), (120, 400, 500, 700))
        self.assertEqual((found['missed_vsync'], found['slow_ui'], found['slow_draw']), (3, 4, 5))

    def test_missing_dump_reads_zero(self):
        found = parsers.parse_gfxinfo('')
        self.assertEqual((found['frames'], found['janky'], found['p95_ms']), (0, 0, 0))


class ScrollableTest(unittest.TestCase):
    nodes = [
        dict(bounds='[0,100][1080,1500]', scrollable=False),
        dict(bounds='[0,200][1080,600]', scrollable=True),
        dict(bounds='[0,700][1080,2100]', scrollable=True),
        dict(bounds='', scrollable=True),
    ]

    def test_picks_the_largest_scrollable_container(self):
        self.assertEqual(parsers.biggest_scrollable(self.nodes), [0, 700, 1080, 2100])

    def test_no_container_returns_none(self):
        self.assertIsNone(parsers.biggest_scrollable([dict(bounds='[0,0][10,10]', scrollable=False)]))

    def test_fling_points_stay_inside_the_container(self):
        points = parsers.fling_points([0, 700, 1080, 2100])
        self.assertEqual(points[0], points[2])
        self.assertEqual(points[0], 540)
        self.assertGreater(points[1], points[3])
        for value in (points[1], points[3]):
            self.assertGreater(value, 700)
            self.assertLess(value, 2100)


class ThreadSampleTest(unittest.TestCase):
    def test_reads_tid_ticks_and_name(self):
        found = parsers.parse_thread_sample('2062 470 main\n2183 90 RenderThread\nNO_PID\n')
        self.assertEqual(sorted(found), ['2062', '2183'])
        self.assertEqual(found['2183'], dict(ticks=90, name='RenderThread'))

    def test_sample_without_a_name_is_kept(self):
        self.assertEqual(parsers.parse_thread_sample('2062 470\nNO_PID\n'), {'2062': dict(ticks=470, name='')})

    def test_deltas_ignore_a_thread_that_did_not_move_and_a_thread_that_appeared(self):
        before = parsers.parse_thread_sample('1 100 main\n2 50 worker\n')
        after = parsers.parse_thread_sample('1 140 main\n2 50 worker\n3 900 new\n')
        deltas = parsers.thread_deltas(before, after, 100)
        self.assertEqual(deltas, [dict(tid='1', name='main', ticks=40, ms=400.0)])
        self.assertEqual(parsers.thread_total(deltas), 400.0)


LOGCAT = '''
1795580251.601  539  928 I ActivityTaskManager: START u0 {cmp=dev.multiprompt.companion.debug/dev.multiprompt.companion.MainActivity}
1795580251.632  539  575 I ActivityManager: Start proc 2062:dev.multiprompt.companion.debug/u0a148 for next-top-activity
1795580253.240  2062 2062 I MpaStartup: application-ready
1795580253.600  2062 2062 I MpaStartup: view-model-ready
1795580253.900  2062 2062 I MpaStartup: inbox-ready
1795580255.123  539  561 I ActivityTaskManager: Displayed dev.multiprompt.companion.debug/dev.multiprompt.companion.MainActivity for user 0: +3s572ms
1795580256.400  539  561 I ActivityTaskManager: Fully drawn dev.multiprompt.companion.debug/dev.multiprompt.companion.MainActivity for user 0: +4s799ms
1795580259.000  539  561 I ActivityTaskManager: Displayed com.other/.Main for user 0: +1s000ms
'''


class LaunchLogTest(unittest.TestCase):
    def test_reads_each_stage_once_for_the_app_package(self):
        marks = parsers.parse_launch_log(LOGCAT, 'dev.multiprompt.companion.debug')
        self.assertAlmostEqual(marks['process'], 1795580251.632, places=3)
        self.assertAlmostEqual(marks['application'], 1795580253.240, places=3)
        self.assertAlmostEqual(marks['inbox'], 1795580253.900, places=3)
        self.assertAlmostEqual(marks['displayed'], 1795580255.123, places=3)
        self.assertEqual(marks['displayed_reported_ms'], 3572)
        self.assertEqual(marks['fully_drawn_reported_ms'], 4799)

    def test_the_first_launch_wins_when_two_are_in_the_buffer(self):
        marks = parsers.parse_launch_log(LOGCAT + LOGCAT.replace('2062', '3062'), 'dev.multiprompt.companion.debug')
        self.assertAlmostEqual(marks['process'], 1795580251.632, places=3)
        self.assertAlmostEqual(marks['displayed'], 1795580255.123, places=3)

    def test_duration_reads_seconds_and_millis(self):
        self.assertEqual(parsers.duration_ms('Displayed x: +858ms'), 858)
        self.assertEqual(parsers.duration_ms('Displayed x: +3s154ms'), 3154)
        self.assertIsNone(parsers.duration_ms('Displayed x'))

    def test_relay_open_times_are_sorted_and_filtered(self):
        events = parsers.relay_events('{"event":"open","stream":"tmux","t":20}\n'
                                     '{"event":"open","stream":"history","t":10}\n'
                                     '{"event":"open","stream":"tmux","t":30}\n')
        self.assertEqual(parsers.relay_open_times(events, stream='tmux'), [20, 30])
        self.assertEqual(parsers.relay_open_times(events, stream='tmux', since=25), [30])


class RelayAttributionTest(unittest.TestCase):
    def test_window_includes_boundaries_and_skips_other_streams(self):
        events = parsers.relay_events('{"event":"call","stream":"history","bytes":10,"ms":5,"t":100.2}\n'
                                     '{"event":"call","stream":"tmux","bytes":99,"ms":1,"t":200}\n'
                                     'not json\n')
        calls = parsers.calls_in_window(events, 100.0, 110.0, stream='history')
        self.assertEqual(len(calls), 1)
        totals = parsers.call_totals(calls)
        self.assertEqual((totals['calls'], totals['bytes'], totals['median_ms']), (1, 10, 5))

    def test_median_uses_the_middle_of_an_odd_sample(self):
        self.assertEqual(parsers.median([3, 1, 2]), 2)
        self.assertEqual(parsers.median([]), 0)


if __name__ == '__main__':
    unittest.main()
