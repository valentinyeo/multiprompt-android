#!/usr/bin/env python3
import json
import math
from pathlib import Path
import statistics
import sys


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def report(path):
    rows = json.loads(Path(path).read_text())
    print(f'## Typing measurement: {path}\n')
    print('| Phase | Trial | Keys | Frame p50/p95 ms | Main p50/p95 ms | List compositions/key | Allocated p50 bytes |')
    print('| --- | --- | --- | --- | --- | --- | --- |')
    for phase in ('type', 'edit', 'idle'):
        for trial in sorted({row['trial'] for row in rows}):
            keys = [row for row in rows if row['phase'] == phase and row['trial'] == trial]
            frames = [ms for row in keys for ms in row['frames_ms']]
            cpu = [row['main_ms'] for row in keys]
            compositions = sum(row['compositions'].get('ChatHistoryList', 0) for row in keys) / len(keys)
            print(f'| {phase} | {trial} | {len(keys)} | {percentile(frames, .5)}/{percentile(frames, .95)} '
                  f'| {percentile(cpu, .5):.2f}/{percentile(cpu, .95):.2f} | {compositions:.2f} '
                  f'| {statistics.median(row["allocated_bytes"] for row in keys):.0f} |')
    active = [row for row in rows if row['phase'] != 'idle']
    print('\nCompositions/key: ' + json.dumps({name: sum(row['compositions'].get(name, 0) for row in active) / len(active)
          for name in sorted({name for row in active for name in row['compositions']})}))
    print('Measured path CPU ms/key: ' + json.dumps({name: sum(row['cpu_ns'].get(name, 0) for row in active) / len(active) / 1e6
          for name in sorted({name for row in active for name in row['cpu_ns']})}))
    if len(active) != 350 or any(not row['frames_ms'] for row in active):
        raise SystemExit('TYPING_MEASUREMENT_INCOMPLETE: require 350 keys and at least one drawn frame per key')


if __name__ == '__main__':
    for path in sys.argv[1:]:
        report(path)
