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

root = here.parents[1]

START_OK = 'Starting: Intent { cmp=dev.multiprompt.companion/.MainActivity }\nStatus: ok\nLaunchState: COLD\nActivity: dev.multiprompt.companion/.MainActivity\nTotalTime: 1234\nWaitTime: 1250\nComplete\n'

FAKE_ADB = r'''#!/usr/bin/env bash
set -u
state_dir=$FAKE_DIR
read_state() { cat "$state_dir/state" 2>/dev/null || echo none; }
case "$*" in
  'get-state') echo device ;;
  uninstall*) rm -f "$state_dir/state" "$state_dir/profile"; echo Success ;;
  'install -g '*) echo 'Performing Streamed Install'; echo verify > "$state_dir/state"; echo Success ;;
  'shell cmd package compile --reset '*) rm -f "$state_dir/profile"; echo verify > "$state_dir/state"; echo Success ;;
  'shell cmd package compile -f -m verify '*) echo verify > "$state_dir/state"; echo Success ;;
  'shell cmd package compile -f -m speed-profile '*)
    if [ -f "$state_dir/profile" ] && [ "${FAKE_COMPILE_KEEPS_VERIFY:-0}" != 1 ]; then echo speed-profile > "$state_dir/state"; else echo verify > "$state_dir/state"; fi
    echo Success ;;
  'shell am broadcast '*)
    if [ "${FAKE_BROADCAST_RESULT:-1}" = 1 ]; then touch "$state_dir/profile"; fi
    echo "Broadcast completed: result=${FAKE_BROADCAST_RESULT:-1}" ;;
  'shell dumpsys package '*)
    printf 'Package [dev.multiprompt.companion]\nDexopt state:\n  [dev.multiprompt.companion]\n    path: /data/app/base.apk\n      x86_64: [status=%s] [reason=cmdline] [primary-abi]\n' "$(read_state)" ;;
  'shell input keyevent '*) ;;
  'shell am start-activity '*)
    count=$(cat "$state_dir/count" 2>/dev/null || echo 0)
    count=$((count + 1))
    echo "$count" > "$state_dir/count"
    if [ "$(read_state)" = speed-profile ]; then total=$((800 + count % 5)); else total=$((1000 + count % 5)); fi
    echo 'Status: ok'
    echo 'LaunchState: COLD'
    if [ "${FAKE_NO_TOTAL:-0}" != 1 ]; then echo "TotalTime: $total"; fi
    echo Complete ;;
  'shell getprop '*) echo "fake-${3}" ;;
  *) echo "unexpected adb call: $*" >&2; exit 9 ;;
esac
'''


class ParserTest(unittest.TestCase):
    def test_total_time_reads_a_cold_start(self):
        self.assertEqual(report.parse_total_time(START_OK), 1234)

    def test_total_time_rejects_warm_failed_and_missing(self):
        with self.assertRaises(ValueError):
            report.parse_total_time(START_OK.replace('COLD', 'WARM'))
        with self.assertRaises(ValueError):
            report.parse_total_time(START_OK.replace('Status: ok', 'Status: timeout'))
        with self.assertRaises(ValueError):
            report.parse_total_time(START_OK.replace('TotalTime: 1234\n', ''))
        with self.assertRaises(ValueError):
            report.parse_total_time(START_OK.replace('1234', '0'))

    def test_dexopt_status_comes_from_the_dexopt_section(self):
        text = 'versionName=1 [status=wrong]\nDexopt state:\n  [pkg]\n    path: base.apk\n      arm64: [status=speed-profile] [reason=cmdline]\n'
        self.assertEqual(report.parse_dexopt_status(text), 'speed-profile')
        with self.assertRaises(ValueError):
            report.parse_dexopt_status('[status=verify]')
        with self.assertRaises(ValueError):
            report.parse_dexopt_status('Dexopt state:\n  nothing here')

    def test_broadcast_result(self):
        self.assertEqual(report.parse_broadcast_result('Broadcasting: Intent\nBroadcast completed: result=1\n'), 1)
        with self.assertRaises(ValueError):
            report.parse_broadcast_result('Broadcast completed\n')

    def test_summary_reports_median_and_difference(self):
        summary = report.summarize({'without': [1000, 1100, 1300], 'with': [800, 900, 1000]}, 3)
        self.assertEqual(summary['without']['median'], 1100)
        self.assertEqual(summary['with']['median'], 900)
        self.assertEqual(summary['difference_ms'], 200)
        self.assertEqual(summary['difference_percent'], 18.2)
        self.assertIn('200 ms faster', report.verdict(summary))

    def test_summary_names_a_slower_profile(self):
        summary = report.summarize({'without': [900, 900], 'with': [1000, 1000]}, 2)
        self.assertEqual(summary['difference_ms'], -100)
        self.assertIn('100 ms slower', report.verdict(summary))

    def test_summary_refuses_missing_samples(self):
        with self.assertRaises(ValueError):
            report.summarize({'without': [1000], 'with': []}, 1)
        with self.assertRaises(ValueError):
            report.summarize({'without': [1000, 1001], 'with': [900]}, 2)


class MeasureScriptTest(unittest.TestCase):
    def run_measure(self, **env):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / 'repo'
            (repo / 'tools/startup-time').mkdir(parents=True)
            for name in ('measure.sh', 'report.py'):
                shutil.copy(here / name, repo / 'tools/startup-time' / name)
            (repo / 'tools/baseline-profile.mjs').write_text('process.exit(0)\n')
            (repo / 'fake.apk').write_text('apk')
            bin_dir = Path(tmp) / 'bin'
            bin_dir.mkdir()
            (bin_dir / 'adb').write_text(FAKE_ADB)
            (bin_dir / 'sleep').write_text('#!/usr/bin/env bash\nexit 0\n')
            for tool in bin_dir.iterdir():
                tool.chmod(0o755)
            fake_dir = Path(tmp) / 'fake'
            fake_dir.mkdir()
            full_env = dict(os.environ, PATH=f'{bin_dir}:{os.environ["PATH"]}', FAKE_DIR=str(fake_dir), STARTUP_APK='fake.apk', STARTUP_RUNS='4', STARTUP_WARMUP='1')
            full_env.update(env)
            result = subprocess.run(['bash', str(repo / 'tools/startup-time/measure.sh')], cwd=repo, env=full_env, capture_output=True, text=True)
            report_path = repo / 'build/startup-time/report.md'
            json_path = repo / 'build/startup-time/report.json'
            return result, report_path.read_text() if report_path.exists() else None, json.loads(json_path.read_text()) if json_path.exists() else None

    def test_happy_path_prints_both_times_and_the_difference(self):
        result, text, document = self.run_measure()
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertIn('STARTUP_TIME_REPORT_OK', result.stdout)
        self.assertEqual(document['summary']['without']['count'], 8)
        self.assertEqual(document['summary']['with']['count'], 8)
        self.assertEqual(document['summary']['difference_ms'], 200)
        self.assertIn('| Without profile |', text)
        self.assertIn('| With profile |', text)
        self.assertIn('200 ms faster', text)
        self.assertEqual(document['info']['with_status'], 'speed-profile')
        self.assertEqual(document['info']['without_status'], 'verify')

    def test_fails_when_the_profile_install_is_refused(self):
        result, text, document = self.run_measure(FAKE_BROADCAST_RESULT='3')
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(text)
        self.assertIn('Profile install result 3', result.stderr)

    def test_fails_when_the_profile_is_not_compiled(self):
        result, text, document = self.run_measure(FAKE_COMPILE_KEEPS_VERIFY='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(text)
        self.assertIn('expected speed-profile', result.stderr)

    def test_fails_when_a_start_reports_no_time(self):
        result, text, document = self.run_measure(FAKE_NO_TOTAL='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(text)

    def test_rejects_bad_run_counts(self):
        for value in ('2', '101', 'x'):
            result, text, document = self.run_measure(STARTUP_RUNS=value)
            self.assertNotEqual(result.returncode, 0, value)
            self.assertIsNone(text)


if __name__ == '__main__':
    unittest.main()
