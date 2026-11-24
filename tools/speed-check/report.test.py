import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

here = Path(__file__).resolve().parent
sys.path.insert(0, str(here))
import report

START_OK = 'Starting: Intent { cmp=dev.multiprompt.companion/.MainActivity }\nStatus: ok\nLaunchState: COLD\nActivity: dev.multiprompt.companion/.MainActivity\nTotalTime: 1234\nWaitTime: 1250\nComplete\n'

FRAMES_OK = '''Applications Graphics Acceleration Info:
Uptime: 123456 Realtime: 123456

** Graphics info for pid 4242 [dev.multiprompt.companion.debug] **

Stats since: 90000000000ns
Total frames rendered: 240
Janky frames: 12 (5.00%)
Janky frames (legacy): 30 (12.50%)
50th percentile: 5ms
90th percentile: 11ms
95th percentile: 24ms
99th percentile: 40ms
'''

FAKE_ADB = r'''#!/usr/bin/env bash
set -u
state_dir=$FAKE_DIR
read_side() { cat "$state_dir/side" 2>/dev/null || echo base; }
read_round() { cat "$state_dir/round" 2>/dev/null || echo 0; }
frame_line() {
    local side=$1 metric=$2 jitter
    jitter=$(read_round)
    if [ "${FAKE_SLOW:-1}" = 1 ] && [ "$side" = head ]; then
        case "$metric" in
            open) printf 'Total frames rendered: 200\nJanky frames: 18 (9.00%%)\n90th percentile: %sms\n' "$((45 + jitter))" ;;
            scroll) printf 'Total frames rendered: 200\nJanky frames: 18 (9.00%%)\n90th percentile: %sms\n' "$((45 + jitter))" ;;
            chat) printf 'Total frames rendered: 200\nJanky frames: 18 (9.00%%)\n90th percentile: %sms\n' "$((70 + jitter))" ;;
        esac
    else
        case "$metric" in
            open) printf 'Total frames rendered: 200\nJanky frames: 4 (2.00%%)\n90th percentile: %sms\n' "$((10 + jitter))" ;;
            scroll) printf 'Total frames rendered: 200\nJanky frames: 4 (2.00%%)\n90th percentile: %sms\n' "$((14 + jitter))" ;;
            chat) printf 'Total frames rendered: 200\nJanky frames: 6 (3.00%%)\n90th percentile: %sms\n' "$((12 + jitter))" ;;
        esac
    fi
}
case "$*" in
  'get-state') echo device ;;
  uninstall*) echo Success ;;
  'install -g '*'test'*) echo Success ;;
  'install -g '*) case "$*" in *base*) echo base > "$state_dir/side" ;; *head*) echo head > "$state_dir/side" ;; esac; echo Success ;;
  'install -r -g '*) case "$*" in *test*) ;; *base*) echo base > "$state_dir/side" ;; *head*) echo head > "$state_dir/side" ;; esac; echo Success ;;
  'shell dumpsys gfxinfo '*reset*) echo '' ;;
  'shell dumpsys gfxinfo '*)
    seq=$(cat "$state_dir/seq" 2>/dev/null || echo 0)
    seq=$((seq + 1))
    echo "$seq" > "$state_dir/seq"
    case $((seq % 4)) in
      1) frame_line "$(read_side)" open ;;
      2) frame_line "$(read_side)" scroll ;;
      3|0) frame_line "$(read_side)" chat ;;
    esac ;;
  'shell am force-stop '*) ;;
  'shell am start-activity '*)
    side=$(read_side)
    round=$(read_round)
    echo 'Status: ok'
    echo 'LaunchState: COLD'
    if [ "$side" = head ] && [ "${FAKE_SLOW:-1}" = 1 ]; then total=$((1400 + round)); else total=$((900 + round)); fi
    echo "TotalTime: $total"
    echo Complete ;;
  'shell am instrument '*) echo 'OK (1 test)' ;;
  'shell am start '*) ;;
  'shell getprop '*) echo "fake-${3}" ;;
  *) echo "unexpected adb call: $*" >&2; exit 9 ;;
esac
'''

FAKE_MPA = r'''#!/usr/bin/env python3
import json
import sys
print(json.dumps({"ok": True, "command": sys.argv[1] if len(sys.argv) > 1 else ""}))
'''

FAKE_SLEEP = '#!/usr/bin/env bash\nexit 0\n'


class ParserTest(unittest.TestCase):
    def test_cold_start_reads_a_cold_launch(self):
        self.assertEqual(report.parse_cold_start(START_OK), 1234)

    def test_cold_start_rejects_warm_failed_and_missing_times(self):
        with self.assertRaises(ValueError):
            report.parse_cold_start(START_OK.replace('COLD', 'WARM'))
        with self.assertRaises(ValueError):
            report.parse_cold_start(START_OK.replace('Status: ok', 'Status: timeout'))
        with self.assertRaises(ValueError):
            report.parse_cold_start(START_OK.replace('TotalTime: 1234\n', ''))

    def test_frame_stats_read_the_jank_and_the_ninetieth_percentile(self):
        stats = report.parse_frame_stats(FRAMES_OK)
        self.assertEqual(stats['total_frames'], 240)
        self.assertEqual(stats['janky_frames'], 12)
        self.assertEqual(stats['janky_percent'], 5.0)
        self.assertEqual(stats['p90_ms'], 11)

    def test_frame_stats_reject_a_dump_without_frames(self):
        with self.assertRaises(ValueError):
            report.parse_frame_stats(FRAMES_OK.replace('Total frames rendered: 240\n', ''))
        with self.assertRaises(ValueError):
            report.parse_frame_stats(FRAMES_OK.replace('Janky frames: 12 (5.00%)\n', ''))
        with self.assertRaises(ValueError):
            report.parse_frame_stats(FRAMES_OK.replace('90th percentile: 11ms\n', ''))

    def test_frame_stats_sum_windows_and_take_the_worst_ninetieth_percentile(self):
        doubled = FRAMES_OK + 'Total frames rendered: 60\nJanky frames: 18 (30.00%)\n90th percentile: 25ms\n'
        stats = report.parse_frame_stats(doubled)
        self.assertEqual(stats['total_frames'], 300)
        self.assertEqual(stats['janky_frames'], 30)
        self.assertEqual(stats['janky_percent'], 10.0)
        self.assertEqual(stats['p90_ms'], 25)


class CompareTest(unittest.TestCase):
    def samples(self, **values):
        document = {}
        for side in ('base', 'head'):
            for metric, pair in values.items():
                document.setdefault(metric, {})[side] = list(pair[0 if side == 'base' else 1])
        return document

    def test_equal_sides_pass(self):
        result = report.compare(self.samples(
            inbox_open_ms=([900, 901, 902], [900, 901, 902]),
            inbox_open_p90_ms=([10, 10, 11], [10, 11, 11]),
            inbox_scroll_janky_percent=([2, 2, 2], [2, 2, 2]),
            inbox_scroll_p90_ms=([14, 14, 15], [14, 15, 15]),
            chat_open_p90_ms=([12, 12, 13], [12, 12, 13]),
        ))
        self.assertTrue(result['passed'], result['errors'])
        self.assertEqual(result['failures'], [])

    def test_the_planted_slow_down_fails_every_timed_journey(self):
        result = report.compare(self.samples(
            inbox_open_ms=([900, 901, 902], [1400, 1401, 1402]),
            inbox_open_p90_ms=([10, 10, 11], [45, 46, 47]),
            inbox_scroll_janky_percent=([2, 2, 2], [9, 9, 9]),
            inbox_scroll_p90_ms=([14, 14, 15], [45, 45, 46]),
            chat_open_p90_ms=([12, 12, 13], [70, 70, 71]),
        ))
        self.assertFalse(result['passed'])
        self.assertEqual(
            [entry['metric'] for entry in result['failures']],
            ['inbox_open_ms', 'inbox_open_p90_ms', 'inbox_scroll_janky_percent', 'inbox_scroll_p90_ms', 'chat_open_p90_ms'],
        )

    def test_a_change_inside_the_absolute_floor_is_noise(self):
        result = report.compare(self.samples(
            inbox_open_ms=([900, 900, 900], [1000, 1000, 1000]),
            inbox_open_p90_ms=([10, 10, 10], [30, 30, 30]),
            inbox_scroll_janky_percent=([2, 2, 2], [5, 5, 5]),
            inbox_scroll_p90_ms=([14, 14, 14], [16, 16, 16]),
            chat_open_p90_ms=([12, 12, 12], [14, 14, 14]),
        ))
        self.assertTrue(result['passed'], result['failures'])

    def test_missing_rounds_are_incomplete_not_green(self):
        result = report.compare(self.samples(
            inbox_open_ms=([900, 900, 900], [900, 900]),
            inbox_open_p90_ms=([10, 10, 10], [10, 10, 10]),
            inbox_scroll_janky_percent=([2, 2, 2], [2, 2, 2]),
            inbox_scroll_p90_ms=([14, 14, 14], [14, 14, 14]),
            chat_open_p90_ms=([12, 12, 12], [12, 12, 12]),
        ))
        self.assertFalse(result['passed'])
        self.assertEqual(result['errors'], ['inbox_open_ms has 3 base and 2 head samples, need 3 of each'])

    def test_the_report_names_the_journeys_and_the_result(self):
        result = report.compare(self.samples(
            inbox_open_ms=([900, 900, 900], [1500, 1500, 1500]),
            inbox_open_p90_ms=([10, 10, 10], [10, 10, 10]),
            inbox_scroll_janky_percent=([2, 2, 2], [2, 2, 2]),
            inbox_scroll_p90_ms=([14, 14, 14], [14, 14, 14]),
            chat_open_p90_ms=([12, 12, 12], [12, 12, 12]),
        ))
        text = report.markdown(result)
        self.assertIn('| Cold open of the app, first frame |', text)
        self.assertIn('| Open the long chat, 90th percentile frame |', text)
        self.assertIn('900ms to 1500ms', text)
        self.assertIn('Result: SLOWER', text)


class ReplayTest(unittest.TestCase):
    def run_measure(self, **env):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / 'repo'
            (repo / 'tools/speed-check').mkdir(parents=True)
            for name in ('measure.sh', 'report.py'):
                shutil.copy(here / name, repo / 'tools/speed-check' / name)
            (repo / 'base.apk').write_text('base')
            (repo / 'head.apk').write_text('head')
            (repo / 'test.apk').write_text('test')
            fixture = Path(tmp) / 'fixture'
            fixture.mkdir()
            (fixture / 'user-key').write_text('fake-key')
            bin_dir = Path(tmp) / 'bin'
            bin_dir.mkdir()
            (bin_dir / 'adb').write_text(FAKE_ADB)
            (bin_dir / 'mpa-fake').write_text(FAKE_MPA)
            (bin_dir / 'sleep').write_text(FAKE_SLEEP)
            for tool in bin_dir.iterdir():
                tool.chmod(0o755)
            fake_dir = Path(tmp) / 'fake'
            fake_dir.mkdir()
            (fake_dir / 'side').write_text('base')
            full_env = dict(
                os.environ,
                PATH=f'{bin_dir}:{os.environ["PATH"]}',
                FAKE_DIR=str(fake_dir),
                FIXTURE=str(fixture),
                EVIDENCE=str(Path(tmp) / 'evidence'),
                EXPECTED_VERSION='0.1.2-speed.1-debug',
                SPEED_BASE_APK='base.apk',
                SPEED_HEAD_APK='head.apk',
                SPEED_TEST_APK='test.apk',
                SPEED_MPA=str(bin_dir / 'mpa-fake'),
                SPEED_ROUNDS='3',
            )
            full_env.update(env)
            result = subprocess.run(['bash', str(repo / 'tools/speed-check/measure.sh')], cwd=repo, env=full_env, capture_output=True, text=True)
            report_json = repo / 'build/speed-check/report.json'
            report_md = repo / 'build/speed-check/report.md'
            document = json.loads(report_json.read_text()) if report_json.exists() else None
            text = report_md.read_text() if report_md.exists() else None
            samples = (repo / 'build/speed-check/samples.csv').read_text()
            return result, text, document, samples

    def test_the_planted_slow_down_replay_fails_the_check(self):
        result, text, document, samples = self.run_measure()
        self.assertEqual(result.returncode, 3, result.stderr + result.stdout)
        self.assertIn('SPEED_CHECK_SLOW', result.stderr)
        self.assertIn('| Cold open of the app, first frame |', text)
        self.assertIn('Result: SLOWER', text)
        self.assertFalse(document['passed'])
        self.assertEqual(len(document['failures']), 5)
        self.assertEqual(len(samples.strip().splitlines()), 30)
        self.assertIn(',inbox_open_ms,', samples)
        self.assertIn(',chat_open_p90_ms,', samples)

    def test_the_same_measurement_passes_when_both_sides_are_equal(self):
        result, text, document, samples = self.run_measure(FAKE_SLOW='0')
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertIn('SPEED_CHECK_OK', result.stdout)
        self.assertIn('Result: NO SLOWDOWN', text)
        self.assertTrue(document['passed'])
        self.assertEqual(document['rounds'], 3)

    def test_four_rounds_take_four_samples_per_side(self):
        result, text, document, samples = self.run_measure(FAKE_SLOW='0', SPEED_ROUNDS='4')
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertEqual(document['rounds'], 4)
        self.assertEqual(len(samples.strip().splitlines()), 40)

    def test_rejects_bad_round_counts(self):
        for value in ('2', '6', 'x'):
            result, text, document, samples = self.run_measure(SPEED_ROUNDS=value)
            self.assertNotEqual(result.returncode, 0, value)
            self.assertIsNone(document)


if __name__ == '__main__':
    unittest.main()
