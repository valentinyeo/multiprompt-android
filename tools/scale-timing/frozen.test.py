import argparse
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import frozen
import stream
from runtime import MeasurementError

PACKAGE = 'dev.multiprompt.companion.debug'


def samples():
    return [dict(scenario=scenario, repetition=index, valid=True,
                 metrics={metric: index * 10 for metric in frozen.METRICS})
            for scenario in frozen.SCENARIOS for index in range(1, 6)]


def results():
    return frozen.aggregate(samples(), 'a' * 40, 'scale', 'frozen-hash')


class StatisticsTest(unittest.TestCase):
    def test_median_and_full_spread_do_not_depend_on_order(self):
        self.assertEqual(frozen.spread([90, 10, 30, 20, 40]), dict(median=30, min=10, max=90))
        self.assertEqual(frozen.spread([4, 1, 3, 2]), dict(median=2.5, min=1, max=4))

    def test_missing_data_is_not_zero(self):
        for values in ([], [None, 1]):
            with self.assertRaisesRegex(ValueError, 'MISSING_SAMPLE'):
                frozen.spread(values)

    def test_every_scenario_needs_five_valid_samples(self):
        for changed in (samples()[:-1], samples() + [samples()[0]]):
            with self.assertRaisesRegex(ValueError, 'INVALID_SAMPLES'):
                frozen.aggregate(changed, 'sha', 'small', 'hash')
        changed = samples()
        changed[0]['valid'] = False
        with self.assertRaisesRegex(ValueError, 'INVALID_SAMPLES'):
            frozen.aggregate(changed, 'sha', 'small', 'hash')

    def test_stable_scenario_metric_and_statistic_order(self):
        result = results()
        self.assertEqual(tuple(result['scenarios']), frozen.SCENARIOS)
        for row in result['scenarios'].values():
            self.assertEqual(tuple(row), frozen.METRICS)
            self.assertEqual(tuple(row['app_cpu_ms']), ('median', 'min', 'max'))
            self.assertEqual(row['app_cpu_ms'], dict(median=30, min=10, max=50))
        self.assertEqual(json.dumps(results()), json.dumps(result))

    def test_compare_requires_nonoverlapping_ranges(self):
        base = dict(median=100, min=90, max=110)
        self.assertEqual(frozen.verdict(base, dict(median=80, min=70, max=89)), 'better')
        self.assertEqual(frozen.verdict(base, dict(median=120, min=111, max=130)), 'worse')
        for low, high in ((80, 90), (110, 120), (95, 105), (1, 200)):
            self.assertEqual(frozen.verdict(base, dict(median=(low + high) / 2, min=low, max=high)), 'within noise')

    def test_compare_covers_all_metrics_and_allows_different_app_commits(self):
        base = results()
        candidate = copy.deepcopy(base)
        candidate['app_sha'] = 'b' * 40
        candidate['scenarios']['chat_switch']['app_cpu_ms'] = dict(median=1, min=0, max=2)
        compared = frozen.compare(base, candidate)
        self.assertEqual(compared['chat_switch']['app_cpu_ms']['verdict'], 'better')
        self.assertEqual(compared['cold_start']['errors']['verdict'], 'within noise')
        self.assertEqual(tuple(compared), frozen.SCENARIOS)

    def test_compare_refuses_different_harness_seed_schema_or_repeat_count(self):
        for key in ('schema', 'harness', 'seed', 'repeats'):
            candidate = results()
            candidate[key] = 'different'
            with self.assertRaisesRegex(ValueError, 'INCOMPATIBLE: ' + key):
                frozen.compare(results(), candidate)

    def test_markdown_has_five_rows_median_and_range_and_secondary_label(self):
        text = frozen.table(results())
        for scenario in frozen.SCENARIOS:
            self.assertIn('| ' + scenario + ' |', text)
        self.assertIn('30 [10, 50]', text)
        self.assertIn('frames (secondary)', text)

    def test_report_without_results_cannot_pass_through_tee(self):
        workflow = Path(frozen.__file__).resolve().parents[2] / '.github/workflows/scale-timing.yml'
        self.assertIn('defaults:\n  run:\n    shell: bash\n', workflow.read_text())
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c',
                                     '"$1" "$2" report --root "$3" --out "$3/report.md" | tee "$3/summary"',
                                     'benchmark-report', sys.executable, frozen.__file__, directory],
                                    capture_output=True, text=True, timeout=30)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('BENCHMARK_RESULTS_MISSING', result.stderr)

    def test_compare_script_entry_point(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'results.json'
            path.write_text(json.dumps(results()))
            result = subprocess.run([sys.executable, str(Path(frozen.__file__)), 'compare', str(path), str(path)],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout)['idle_inbox']['main_thread_ms']['verdict'], 'within noise')


class ParsingTest(unittest.TestCase):
    def test_frames_are_strictly_above_16_not_the_janky_counter(self):
        self.assertEqual(frozen.frames_over_16('Janky frames: 900\nHISTOGRAM: 5ms=2 16ms=3 17ms=5 20ms=8\n'), 13)
        with self.assertRaisesRegex(MeasurementError, 'GFX_HISTOGRAM_MISSING'):
            frozen.frames_over_16('')

    def test_lists_count_commands_not_reply_lines_or_other_tmux_calls(self):
        events = [dict(event='open', stream='tmux', list_sessions=True, t=10),
                  dict(event='open', stream='tmux', list_sessions=False, t=11),
                  dict(event='call', stream='tmux', bytes=100, t=12),
                  dict(event='call', stream='history', bytes=300, t=20),
                  dict(event='call', stream='tmux', bytes=999, t=9.999),
                  dict(event='close', code=3, t=21), dict(event='call', bytes=999, t=30.001)]
        found = frozen.traffic(events, 10, 30)
        self.assertEqual(found, dict(list_sessions=1, bytes=400, relay_failures=1,
                                     list_sessions_per_minute=3, bytes_per_minute=1200))

    def test_errors_include_app_pids_from_cold_start_and_ignore_other_apps(self):
        log = f'''100.1 500 500 I ActivityManager: Start proc 123:{PACKAGE}/u0a123 for activity
100.2 123 123 E AndroidRuntime: FATAL EXCEPTION: main
100.3 123 123 E App: broken
100.4 999 999 E Other: unrelated
100.5 500 500 E ActivityManager: ANR in {PACKAGE}
100.6 123 123 I App: okay
'''
        self.assertEqual(frozen.errors(log, PACKAGE, [], failed=True),
                         dict(crashes=1, anrs=1, failed_steps=1, app_logcat_e=2))

    def test_crashed_cold_process_is_owned_even_when_only_event_log_has_its_pid(self):
        log = f'''100.1 500 500 I am_proc_start: [0,123,10148,{PACKAGE},next-top-activity,MainActivity]
100.2 123 123 E AndroidRuntime: FATAL EXCEPTION: main
'''
        self.assertEqual(frozen.errors(log, PACKAGE, [])['app_logcat_e'], 1)
        self.assertEqual(frozen.errors(log, PACKAGE, [])['crashes'], 1)

    def test_fingerprint_covers_real_harness_inputs(self):
        self.assertRegex(frozen.fingerprint(), r'^[0-9a-f]{64}$')

    def test_truncated_cpu_stats_are_not_a_fast_zero(self):
        responses = [subprocess.CompletedProcess([], 0, '123\n'),
                     subprocess.CompletedProcess([], 0, '123 (main) S 1\n123 (main) S 1\n')]
        with patch.object(frozen, 'adb', side_effect=responses):
            with self.assertRaisesRegex(MeasurementError, 'CPU_UNREADABLE'):
                frozen.snapshot(PACKAGE)

    def test_snapshot_reads_process_and_main_not_driver_or_other_threads(self):
        raw = '123 (main) S 1 1 1 0 -1 0 0 0 0 0 55 12 0 0\n123 (main) S 1 1 1 0 -1 0 0 0 0 0 20 3 0 0\n'
        responses = [subprocess.CompletedProcess([], 0, '123\n'), subprocess.CompletedProcess([], 0, raw)]
        with patch.object(frozen, 'adb', side_effect=responses) as adb:
            self.assertEqual(frozen.snapshot(PACKAGE), dict(pid='123', app=67, main=23))
            self.assertIn('/proc/123/task/123/stat', adb.call_args.args[1])

    def test_missing_live_process_is_a_failure_but_cold_start_can_begin_at_zero(self):
        with patch.object(frozen, 'adb', return_value=subprocess.CompletedProcess([], 1, '')):
            self.assertEqual(frozen.snapshot(PACKAGE, absent=True), dict(pid=None, app=0, main=0))
            with self.assertRaisesRegex(MeasurementError, 'PID_MISSING'):
                frozen.snapshot(PACKAGE)


class RunTest(unittest.TestCase):
    def test_each_cold_start_reenters_the_debug_gate_and_requires_inbox(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            chat = dict(session='largest', marker='latest', bytes=100, transcript='unused')
            (root / 'targets.json').write_text(json.dumps(dict(chats=[chat, chat])))
            (root / 'host-fingerprint.txt').write_text('fingerprint')
            args = argparse.Namespace(out=str(root / 'out'), targets=str(root / 'targets.json'),
                                      package=PACKAGE, evidence=str(root), fixture=str(root),
                                      expected_version='version', app_sha='sha', seed='small')

            def sample(scenario, repetition, work, *_):
                if scenario == 'cold_start':
                    self.assertEqual(work(), dict(inbox='shown'))
                return dict(scenario=scenario, valid=True, metrics={metric: 0 for metric in frozen.METRICS})

            with patch.dict(frozen.os.environ, GITHUB_ACTIONS='true'), \
                    patch.object(frozen, 'sample', side_effect=sample), \
                    patch.object(frozen, 'adb'), patch.object(frozen, 'mpa') as mpa, \
                    patch.object(frozen.subprocess, 'run'), \
                    patch.object(frozen.measure, 'sign_in'), patch.object(frozen.measure, 'copy_relay'), \
                    patch.object(frozen.measure, 'clk_tck', return_value=100), \
                    patch.object(frozen.measure.probe, 'install_sampler'), \
                    patch.object(frozen.measure, 'open_chat'), \
                    patch.object(frozen.measure, 'poll', return_value=dict(texts=['Use without account (debug)'])), \
                    patch.object(frozen.measure, 'wait_inbox') as inbox:
                frozen.run(args)
            self.assertEqual(sum(call.args == ('tap', 'text', 'Use without account (debug)')
                                 for call in mpa.call_args_list), 5)
            self.assertEqual(inbox.call_count, 10)
            self.assertEqual(json.loads((root / 'out/results.json').read_text())['repeats'], 5)


class StreamTest(unittest.TestCase):
    def test_real_appended_rows_receipt_and_exact_restore(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'chat.jsonl'
            original = b'{"sessionId":"native-session","message":{"content":[]}}\n'
            path.write_bytes(original)
            with patch.object(stream.time, 'sleep'):
                receipt = stream.stream(path, 2)
            appended = path.read_bytes()[len(original):]
            rows = [json.loads(line) for line in appended.splitlines()]
            self.assertEqual(receipt['bytes'], len(appended))
            self.assertEqual(receipt['rows'], 40)
            self.assertEqual(receipt['marker'], 'FROZEN_STREAM_2_039')
            self.assertEqual(len({row['uuid'] for row in rows}), 40)
            self.assertEqual({row['sessionId'] for row in rows}, {'native-session'})
            self.assertEqual({row['message']['role'] for row in rows}, {'assistant'})
            self.assertIn(receipt['marker'], rows[-1]['message']['content'][0]['text'])
            with self.assertRaisesRegex(ValueError, 'NOT_RESTORED'):
                stream.stream(path, 3)
            stream.restore(path)
            self.assertEqual(path.read_bytes(), original)


if __name__ == '__main__':
    unittest.main()
