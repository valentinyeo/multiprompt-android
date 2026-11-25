#!/usr/bin/env python3
import json
import math
from pathlib import Path
import statistics
import sys


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def mean(values):
    return sum(values) / len(values) if values else 0.0


def report(path):
    rows = json.loads(Path(path).read_text())
    print(f'## Typing measurement: {path}\n')
    print('| Phase | Trial | Keys | Frame p50/p95 ms | Main p50/p95 ms | Allocated p50 bytes |')
    print('| --- | --- | --- | --- | --- | --- |')
    for phase in ('type', 'edit', 'idle'):
        for trial in sorted({row['trial'] for row in rows}):
            keys = [row for row in rows if row['phase'] == phase and row['trial'] == trial]
            frames = [ms for row in keys for ms in row['frames_ms']]
            cpu = [row['main_ms'] for row in keys]
            print(f'| {phase} | {trial} | {len(keys)} | {percentile(frames, .5)}/{percentile(frames, .95)} '
                  f'| {percentile(cpu, .5):.2f}/{percentile(cpu, .95):.2f} '
                  f'| {statistics.median(row["allocated_bytes"] for row in keys):.0f} |')
    active = [row for row in rows if row['phase'] != 'idle']
    names = sorted({name for row in active for name in row['compositions']})
    print('\nCompositions per key:\n')
    print('| Composable | Compositions/key | Keys touching it |')
    print('| --- | ---: | ---: |')
    for name in names:
        values = [row['compositions'].get(name, 0) for row in active]
        print(f'| {name} | {mean(values):.3f} | {sum(1 for value in values if value)} of {len(values)} |')
    layout_names = sorted({name for row in active for name in row.get('layouts', {})})
    print('\nLayout passes per key:\n')
    print('| Component | Layouts/key | Keys touching it |')
    print('| --- | ---: | ---: |')
    for name in layout_names:
        values = [row.get('layouts', {}).get(name, 0) for row in active]
        print(f'| {name} | {mean(values):.3f} | {sum(1 for value in values if value)} of {len(values)} |')
    insets = [row.get('ime_inset_px', 0) for row in active]
    print(f'\nIME inset per key: {sorted({value for value in insets})} px, '
          f'{sum(1 for value in insets if value)} of {len(insets)} keys measured with the keyboard shown.')
    print('Measured path CPU ms/key: ' + json.dumps({name: mean([row['cpu_ns'].get(name, 0) for row in active]) / 1e6
          for name in sorted({name for row in active for name in row['cpu_ns']})}))
    if len(active) != 350 or any(not row['frames_ms'] for row in active):
        raise SystemExit('TYPING_MEASUREMENT_INCOMPLETE: require 350 keys and at least one drawn frame per key')


if __name__ == '__main__':
    for path in sys.argv[1:]:
        report(path)
