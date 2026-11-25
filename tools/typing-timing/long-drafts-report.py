#!/usr/bin/env python3
import json
import math
from pathlib import Path
import statistics
import sys

PHASES = ('type', 'edit')
KEYS = {'type': 10, 'edit': 8}
TRIALS = 5


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else 0.0


def spread(values):
    return f'{statistics.median(values):.2f} ({min(values):.2f}-{max(values):.2f})'


def main():
    rows = json.loads(Path(sys.argv[1]).read_text())
    lengths = sorted({row['length'] for row in rows})
    print('| Draft | Phase | Keys/trial | Layout ms/key p50 | Layout p95 ms | Main ms/key p50 | Main p95 ms | Skill spans ms/key | Draft save ms/key |')
    print('| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
    for length in lengths:
        for phase in PHASES:
            trials = sorted({row['trial'] for row in rows if row['length'] == length and row['phase'] == phase})
            layout50, layout95, main50, main95, spans, saves = [], [], [], [], [], []
            for trial in trials:
                keys = [row for row in rows if row['length'] == length and row['phase'] == phase and row['trial'] == trial]
                layout = [row['cpu_ns'].get('composerTextLayout', 0) / 1e6 for row in keys]
                if not layout:
                    raise SystemExit(f'LONG_DRAFT_MEASUREMENT_INCOMPLETE: no layout probe for {length} {phase} trial {trial}')
                layout50.append(statistics.median(layout))
                layout95.append(percentile(layout, .95))
                main50.append(statistics.median([row['main_ms'] for row in keys]))
                main95.append(percentile([row['main_ms'] for row in keys], .95))
                spans.append(statistics.median([row['cpu_ns'].get('skillSpans', 0) / 1e6 for row in keys]))
                saves.append(statistics.median([row['cpu_ns'].get('draftSave', 0) / 1e6 for row in keys]))
            print(f'| {length} | {phase} | {KEYS[phase]} | {spread(layout50)} | {spread(layout95)} | {spread(main50)} '
                  f'| {spread(main95)} | {statistics.median(spans):.2f} | {statistics.median(saves):.2f} |')
    print('\nLayout ms/key is one Compose TextMeasurer layout of the draft at the composer field width, '
          'its 16/24 sp style and its six-line cap, timed with the `Debug.threadCpuTimeNanos` probe from PR #618. '
          'It is not the composer\'s own internal layout pass; Main ms/key is the in-app cost of the same key.')
    active = [row for row in rows if row.get('frames_ms')]
    total = len(lengths) * len(PHASES) * sum(KEYS.values()) * TRIALS
    if len(active) != total:
        raise SystemExit(f'LONG_DRAFT_MEASUREMENT_INCOMPLETE: {len(active)} keys with a drawn frame of {total}')
    for phase in PHASES:
        typed = {row['length'] for row in rows if row['phase'] == phase}
        if typed != set(lengths):
            raise SystemExit(f'LONG_DRAFT_MEASUREMENT_INCOMPLETE: {phase} covers {sorted(typed)}')


if __name__ == '__main__':
    main()
