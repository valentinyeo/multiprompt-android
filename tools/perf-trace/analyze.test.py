import importlib.util
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).parent))
import analyze


class Fixture:
    def __init__(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
            CREATE TABLE process(upid, pid, name, cmdline);
            CREATE TABLE thread(utid, tid, upid);
            CREATE TABLE actual_frame_timeline_slice(id, ts, dur, upid, jank_type);
            CREATE TABLE thread_track(id, utid);
            CREATE TABLE slice(id, parent_id, name, ts, dur, track_id);
            CREATE TABLE thread_state(utid, ts, dur, state, io_wait, blocked_function);
            CREATE TABLE perf_sample(id, ts, utid, callsite_id, unwind_error);
            CREATE TABLE stack_profile_callsite(id, parent_id, frame_id);
            CREATE TABLE stack_profile_frame(id, name, deobfuscated_name, symbol_set_id);
            CREATE TABLE stack_profile_symbol(symbol_set_id, name);
            CREATE TABLE stats(name, idx, severity, value);
            INSERT INTO process VALUES (1, 10, 'dev.multiprompt.companion', NULL), (2, 20, 'other.app', NULL);
            INSERT INTO thread VALUES (1, 10, 1), (2, 11, 1), (3, 20, 2);
            INSERT INTO actual_frame_timeline_slice VALUES
                (1, 0, 20000000, 1, 'App Deadline Missed'),
                (2, 10000000, 30000000, 1, 'App Deadline Missed, Buffer Stuffing'),
                (3, 0, 50000000, 2, 'App Deadline Missed'),
                (4, 0, 90000000, 1, 'None'), (5, 0, -1, 1, 'App Deadline Missed');
            INSERT INTO thread_track VALUES (1, 1), (2, 2), (3, 3);
            INSERT INTO slice VALUES
                (1, NULL, 'recompose', 0, 50000000, 1),
                (2, 1, 'ChatHistory.projectMessages', 5000000, 40000000, 1),
                (3, NULL, 'worker', 0, 80000000, 2),
                (4, NULL, 'other', 0, 80000000, 3);
            INSERT INTO thread_state VALUES
                (1, 0, 15000000, 'Running', 0, NULL),
                (1, 15000000, 30000000, 'S', 0, 'binder_thread_read'),
                (2, 0, 80000000, 'Running', 0, NULL);
            INSERT INTO stack_profile_callsite VALUES (1, NULL, 1), (2, 1, 2), (3, NULL, 3);
            INSERT INTO stack_profile_frame VALUES
                (1, 'connectbot.sshlib', NULL, NULL), (2, 'json.parse', NULL, NULL),
                (3, 'Compose.draw', NULL, NULL);
            INSERT INTO perf_sample VALUES
                (1, 5000000, 1, 2, NULL), (2, 15000000, 1, 2, NULL),
                (3, 45000000, 1, 3, NULL), (4, 0, 2, 2, NULL);
        ''')

    def query(self, sql):
        result = []
        for statement in sql.replace('CREATE PERFETTO TABLE', 'CREATE TABLE').split(';'):
            if statement.strip():
                result = [SimpleNamespace(**dict(row)) for row in self.db.execute(statement)]
        return result


class AnalyzeTest(unittest.TestCase):
    def test_overlap_main_thread_and_stacks(self):
        report = analyze.analyze(Fixture(), 'dev.multiprompt.companion')
        self.assertEqual(report['janky_frames']['count'], 2)
        self.assertEqual(report['janky_frames']['duration_ms'], {'p50': 25, 'p90': 29, 'p99': 29.9, 'max': 30})
        self.assertEqual(report['janky_frames']['jank_types']['App Deadline Missed'], 2)
        self.assertEqual([(s['name'], s['overlap_ms']) for s in report['main_slices_during_jank']],
                         [('recompose', 40), ('ChatHistory.projectMessages', 35)])
        self.assertEqual(sum(s['total_ms'] for s in report['main_thread_states_during_jank']), 40)
        self.assertEqual(report['main_thread_samples']['count'], 3)
        self.assertEqual(report['top_cpu_sampled_functions'][0], {'name': 'json.parse', 'samples': 2, 'jank_samples': 2})
        self.assertEqual(report['matching_main_thread_stacks']['ssh']['sample_stacks'][0]['samples'], 2)
        self.assertEqual(report['matching_main_thread_stacks']['parsing']['slices'][0]['jank_overlap_ms'], 35)
        self.assertIn('p50=25.000', analyze.markdown(report))

    def test_missing_data_is_not_a_clean_bill_of_health(self):
        fixture = Fixture()
        fixture.db.executescript('DELETE FROM process; DELETE FROM stats;')
        report = analyze.analyze(fixture, 'missing.app')
        self.assertIsNone(report['janky_frames']['duration_ms']['p99'])
        self.assertEqual(len(report['warnings']), 4)
        self.assertIn('unavailable, not zero', analyze.markdown(report))

    def test_capture_contract_with_synthetic_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            adb = base / 'adb'
            adb.write_text('''#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$CAPTURE_CALLS"
case "$*" in
  'shell dumpsys package '*) printf 'Package [dev.multiprompt.companion]\\nversionName=synthetic-only\\n' ;;
  'shell perfetto '*) cat > "$SYNTHETIC_CONFIG" ;;
  'pull '*) printf 'synthetic fixture only' > "$3" ;;
  *) printf 'synthetic fixture only\\n' ;;
esac
''')
            adb.chmod(0o700)
            out = base / 'capture'
            env = dict(os.environ, PATH=f'{base}:{os.environ["PATH"]}', OUT=str(out), SECS='30',
                       CAPTURE_CALLS=str(base / 'calls'), SYNTHETIC_CONFIG=str(base / 'config'))
            script = Path(__file__).with_name('capture.sh')
            result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((out / 'capture-exit.txt').read_text(), '0\n')
            calls = (base / 'calls').read_text()
            self.assertNotIn('kill-server', calls)
            self.assertIn('gfxinfo dev.multiprompt.companion reset', calls)
            self.assertIn('gfxinfo dev.multiprompt.companion framestats', calls)
            config = (base / 'config').read_text()
            self.assertIn('duration_ms: 30000', config)
            self.assertIn('target_cmdline: "dev.multiprompt.companion"', config)
            self.assertIn('android.surfaceflinger.frametimeline', config)
            if importlib.util.find_spec('perfetto'):
                from google.protobuf.text_format import Parse
                from perfetto.protos.perfetto.trace.perfetto_trace_pb2 import TraceConfig
                self.assertEqual(Parse(config, TraceConfig()).duration_ms, 30000)
            result = subprocess.run(['bash', str(script)], env=env, capture_output=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            env['OUT'] = str(Path(__file__).parent / 'must-not-exist')
            result = subprocess.run(['bash', str(script)], env=env, capture_output=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(Path(env['OUT']).exists())

    @unittest.skipUnless(importlib.util.find_spec('perfetto'), 'optional real TraceProcessor smoke')
    def test_real_trace_processor_without_phone_data(self):
        from perfetto.trace_processor import TraceProcessor
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory) / 'synthetic.systrace'
            trace.write_text('# tracer: nop\n\n app-123 (  123) [000] ...1 1.000000: tracing_mark_write: B|123|test\n app-123 (  123) [000] ...1 1.001000: tracing_mark_write: E|123\n')
            with TraceProcessor(trace=str(trace)) as tp:
                report = analyze.analyze(tp, 'dev.multiprompt.companion')
            self.assertEqual(report['janky_frames']['count'], 0)
            self.assertTrue(report['warnings'])


if __name__ == '__main__':
    unittest.main()
