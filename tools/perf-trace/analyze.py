#!/usr/bin/env python3
import argparse
import importlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys


PATTERNS = {
    'ssh': r'sshlib|connectbot|SessionReaderConnection|SshRepository',
    'parsing': r'TmuxParser|ChatHistory|MessageIdentity|json',
    'compose': r'compose|recompos|layout|draw|measure',
}


def rows(tp, sql):
    return [vars(row) for row in tp.query(sql)]


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    low, high = math.floor(index), math.ceil(index)
    return (ordered[low] + (ordered[high] - ordered[low]) * (index - low)) / 1e6


def frame_summary(frames):
    durations = [frame['dur'] for frame in frames]
    types = {}
    for frame in frames:
        for kind in frame['jank_type'].split(','):
            kind = kind.strip()
            types[kind] = types.get(kind, 0) + 1
    return {
        'count': len(frames),
        'duration_ms': {name: percentile(durations, fraction) for name, fraction in
                        [('p50', .5), ('p90', .9), ('p99', .99), ('max', 1)]},
        'jank_types': types,
    }


def analyze(tp, package):
    if not re.fullmatch(r'[a-zA-Z0-9_.]+', package):
        raise ValueError('Invalid package name')
    tp.query(f"""
        CREATE PERFETTO TABLE perf_main AS
        SELECT t.utid, t.tid, p.upid, p.pid, p.name
        FROM thread t JOIN process p USING (upid)
        WHERE t.tid = p.pid AND (p.name = '{package}' OR p.cmdline = '{package}');
        CREATE PERFETTO TABLE perf_jank AS
        SELECT f.* FROM actual_frame_timeline_slice f
        WHERE f.upid IN (SELECT upid FROM perf_main) AND f.dur > 0
          AND f.jank_type IS NOT NULL AND lower(f.jank_type) NOT IN ('none', 'unknown', '');
        CREATE PERFETTO TABLE perf_windows AS
        WITH previous AS (
          SELECT ts, ts + dur AS end_ts,
            MAX(ts + dur) OVER (ORDER BY ts, id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS previous_end
          FROM perf_jank
        ), groups AS (
          SELECT *, SUM(CASE WHEN previous_end IS NULL OR ts > previous_end THEN 1 ELSE 0 END)
            OVER (ORDER BY ts, end_ts) AS group_id FROM previous
        )
        SELECT MIN(ts) AS ts, MAX(end_ts) AS end_ts FROM groups GROUP BY group_id;
        CREATE PERFETTO TABLE perf_slices AS
        SELECT s.id, s.parent_id, s.name, s.ts, s.dur
        FROM slice s JOIN thread_track tt ON s.track_id = tt.id
        WHERE tt.utid IN (SELECT utid FROM perf_main) AND s.dur > 0;
    """)
    main = rows(tp, 'SELECT * FROM perf_main')
    frames = rows(tp, 'SELECT id, ts, dur, jank_type FROM perf_jank ORDER BY ts')
    all_frames = rows(tp, '''SELECT COUNT(*) AS count, SUM(dur <= 0) AS unfinished,
        SUM(jank_type IS NULL OR lower(jank_type) IN ('unknown', '')) AS unknown_jank
        FROM actual_frame_timeline_slice WHERE upid IN (SELECT upid FROM perf_main)''')[0]
    slices = rows(tp, '''SELECT s.name, COUNT(DISTINCT s.id) AS occurrences,
        SUM(MIN(s.ts + s.dur, w.end_ts) - MAX(s.ts, w.ts)) / 1e6 AS overlap_ms,
        MAX(s.dur) / 1e6 AS longest_full_slice_ms
        FROM perf_slices s JOIN perf_windows w ON s.ts < w.end_ts AND s.ts + s.dur > w.ts
        GROUP BY s.name ORDER BY overlap_ms DESC''')
    states = rows(tp, '''SELECT state, io_wait, blocked_function,
        SUM(dur) / 1e6 AS total_ms FROM thread_state
        WHERE utid IN (SELECT utid FROM perf_main) AND dur > 0
        GROUP BY state, io_wait, blocked_function ORDER BY total_ms DESC''')
    jank_states = rows(tp, '''SELECT s.state, s.io_wait, s.blocked_function,
        SUM(MIN(s.ts + s.dur, w.end_ts) - MAX(s.ts, w.ts)) / 1e6 AS total_ms
        FROM thread_state s JOIN perf_windows w ON s.ts < w.end_ts AND s.ts + s.dur > w.ts
        WHERE s.utid IN (SELECT utid FROM perf_main) AND s.dur > 0
        GROUP BY s.state, s.io_wait, s.blocked_function ORDER BY total_ms DESC''')
    slice_stacks = rows(tp, '''WITH RECURSIVE lineage(id, parent_id, stack) AS (
        SELECT id, parent_id, name FROM perf_slices
        UNION ALL
        SELECT l.id, s.parent_id, s.name || ' > ' || l.stack
        FROM lineage l JOIN slice s ON s.id = l.parent_id
        )
        SELECT s.id, s.ts, s.dur / 1e6 AS duration_ms, s.name, l.stack,
          (SELECT COALESCE(SUM(MIN(s.ts + s.dur, w.end_ts) - MAX(s.ts, w.ts)), 0) / 1e6
           FROM perf_windows w WHERE s.ts < w.end_ts AND s.ts + s.dur > w.ts) AS jank_overlap_ms
        FROM lineage l JOIN perf_slices s USING (id) WHERE l.parent_id IS NULL''')
    sample_counts = rows(tp, '''SELECT COUNT(*) AS count,
        SUM(callsite_id IS NULL) AS missing_stack, SUM(unwind_error IS NOT NULL) AS unwind_errors
        FROM perf_sample WHERE utid IN (SELECT utid FROM perf_main)''')[0]
    tp.query('''CREATE PERFETTO TABLE perf_sample_counts AS
        SELECT callsite_id, COUNT(*) AS samples,
          SUM(EXISTS(SELECT 1 FROM perf_windows w WHERE p.ts >= w.ts AND p.ts < w.end_ts)) AS jank_samples
        FROM perf_sample p WHERE utid IN (SELECT utid FROM perf_main) AND callsite_id IS NOT NULL
        GROUP BY callsite_id;
        CREATE PERFETTO TABLE perf_functions AS
        SELECT f.id, COALESCE(f.deobfuscated_name,
          (SELECT GROUP_CONCAT(name, ' > ') FROM stack_profile_symbol WHERE symbol_set_id = f.symbol_set_id),
          f.name, '[unsymbolized]') AS name FROM stack_profile_frame f;
    ''')
    samples = rows(tp, '''WITH RECURSIVE stacks(leaf, parent_id, stack) AS (
        SELECT c.id, c.parent_id, f.name
        FROM stack_profile_callsite c JOIN perf_functions f ON f.id = c.frame_id
        WHERE c.id IN (SELECT callsite_id FROM perf_sample_counts)
        UNION ALL
        SELECT s.leaf, c.parent_id, f.name || ' > ' || s.stack
        FROM stacks s JOIN stack_profile_callsite c ON c.id = s.parent_id
        JOIN perf_functions f ON f.id = c.frame_id
        ) SELECT s.stack, n.samples, n.jank_samples FROM stacks s
        JOIN perf_sample_counts n ON n.callsite_id = s.leaf WHERE s.parent_id IS NULL
        ORDER BY n.samples DESC''')
    functions = rows(tp, '''SELECT f.name, SUM(n.samples) AS samples, SUM(n.jank_samples) AS jank_samples
        FROM perf_sample_counts n JOIN stack_profile_callsite c ON c.id = n.callsite_id
        JOIN perf_functions f ON f.id = c.frame_id
        GROUP BY f.name ORDER BY samples DESC''')
    matching = {}
    for category, pattern in PATTERNS.items():
        matching[category] = {
            'slices': sorted([item for item in slice_stacks if re.search(pattern, item['stack'], re.I)],
                             key=lambda item: item['duration_ms'], reverse=True),
            'sample_stacks': [item for item in samples if re.search(pattern, item['stack'], re.I)],
        }
    issues = rows(tp, "SELECT name, idx, severity, value FROM stats WHERE value > 0 AND severity IN ('error', 'data_loss')")
    warnings = []
    for condition, message in [
        (not main, 'No matching app main thread; package may not have run inside the trace.'),
        (not all_frames['count'], 'No app FrameTimeline records; jank is unavailable, not zero.'),
        (all_frames['unfinished'], 'Incomplete FrameTimeline records excluded from jank durations.'),
        (all_frames['unknown_jank'], 'Unknown FrameTimeline jank classifications are not counted as janky.'),
        (not states, 'No main-thread scheduler states; Running/blocked split unavailable.'),
        (not sample_counts['count'], 'No main-thread CPU samples; check profileable/debuggable permissions and perfetto.log.'),
        (sample_counts['missing_stack'] or sample_counts['unwind_errors'], 'Missing stacks or unwind errors; sampled functions are incomplete.'),
        (issues, 'Trace reports errors/data loss; inspect trace_issues before trusting durations.'),
    ]:
        if condition:
            warnings.append(message)
    return {
        'package': package, 'main_threads': main, 'app_frame_records': all_frames,
        'janky_frames': frame_summary(frames), 'janky_intervals': frames,
        'main_slices_during_jank': slices, 'main_thread_states': states,
        'main_thread_states_during_jank': jank_states,
        'main_thread_samples': sample_counts, 'top_cpu_sampled_functions': functions,
        'matching_main_thread_stacks': matching, 'trace_issues': issues, 'warnings': warnings,
        'limitations': [
            'Frame counts are app layer-frame records, not unique display refreshes; Unknown jank is excluded.',
            'Slice totals are inclusive wall time clipped to the union of janky intervals, not CPU time; nested slices overlap.',
            'Scheduler states: Running is CPU, R/R+ runnable, S sleeping, D uninterruptible; io_wait and blocked_function are evidence, not a binder attribution.',
            'Sample counts are not milliseconds. Top functions are exclusive leaf samples; category matches use full stacks.',
            'Java/Kotlin names may be absent in release/native unwinds; no matching symbol does not exclude work.',
            'This instrumented baseline cannot establish a speedup or causal blame. Inspect build, warmup, actions, thermal drift and sample counts.',
        ],
    }


def markdown(report):
    frames = report['janky_frames']
    durations = frames['duration_ms']
    lines = ['# Multi Bot trace', '', f"Package `{report['package']}`", '',
             f"Janky layer-frame records: {frames['count']} / {report['app_frame_records']['count']}.",
             'Frame duration ms: ' + ', '.join(f'{key}={value:.3f}' if value is not None else f'{key}=unavailable'
                                               for key, value in durations.items()),
             'Jank types: ' + json.dumps(frames['jank_types']), '', '## Main-thread slices during jank', '',
             '| Slice | Inclusive overlap ms | Longest full slice ms |', '| --- | ---: | ---: |']
    for item in report['main_slices_during_jank'][:15]:
        name = item['name'].replace('|', '/').replace('\n', ' ')
        lines.append(f"| {name} | {item['overlap_ms']:.3f} | {item['longest_full_slice_ms']:.3f} |")
    lines += ['', '## Main-thread scheduler time', '']
    for item in report['main_thread_states'][:15]:
        lines.append(f"- {item['state']}, io_wait={item['io_wait']}, {item['blocked_function'] or 'no blocked function'}: {item['total_ms']:.3f} ms")
    lines += ['', '## CPU sampling', '', f"Main-thread samples: {report['main_thread_samples']['count']}"]
    for item in report['top_cpu_sampled_functions'][:10]:
        lines.append(f"- {item['name']}: {item['samples']} leaf samples ({item['jank_samples']} during jank)")
    lines += ['', '## Stack matches', '']
    for category, matches in report['matching_main_thread_stacks'].items():
        lines.append(f"- {category}: {len(matches['slices'])} slices, {sum(item['samples'] for item in matches['sample_stacks'])} samples. Full stacks in JSON.")
    lines += ['', '## Warnings and limits', '']
    lines.extend('- ' + warning for warning in report['warnings'] + report['limitations'])
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('trace', type=Path)
    parser.add_argument('--package', default='dev.multiprompt.companion')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = args.out.resolve()
    if out == root or root in out.parents:
        parser.error('--out must be outside the repository')
    if not args.trace.is_file() or not args.trace.stat().st_size:
        parser.error('trace must be a non-empty file')
    try:
        api = importlib.import_module('perfetto.trace_processor')
    except ModuleNotFoundError:
        subprocess.run([sys.executable, '-m', 'pip', 'install', '--user', 'perfetto'], check=True)
        importlib.invalidate_caches()
        api = importlib.import_module('perfetto.trace_processor')
    with api.TraceProcessor(trace=str(args.trace)) as tp:
        report = analyze(tp, args.package)
    report['trace'] = str(args.trace.resolve())
    metadata = args.trace.parent / 'metadata.json'
    report['capture_metadata'] = json.loads(metadata.read_text()) if metadata.exists() else None
    out.mkdir(parents=True, exist_ok=True)
    (out / 'analysis.json').write_text(json.dumps(report, indent=2) + '\n')
    text = markdown(report)
    (out / 'summary.md').write_text(text)
    print(text)


if __name__ == '__main__':
    main()
