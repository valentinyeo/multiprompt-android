#!/usr/bin/env python3
import argparse
import csv
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

PROBE_SNAPSHOTS = 50
PROBE_REPLIES = 5
WINDOW = 10


def versions(path):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return {}


def cpu_windows(path):
    samples = defaultdict(list)
    failures = []
    if not Path(path).is_file():
        return samples, failures
    with Path(path).open() as source:
        for row in csv.DictReader(source):
            if not row.get('ticks'):
                failures.append(row)
                continue
            samples[row['sha']].append(int(row['ticks']) * 10)
    return samples, failures


def counted(directory):
    found = {}
    for line in sorted(Path(directory).glob('counters-*.line')):
        text = line.read_text().strip()
        if not text.startswith('COUNTERS '):
            continue
        fields = dict(piece.split('=', 1) for piece in text.split()[1:])
        found[fields['sha']] = fields
    return found


def number(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def scaled(value, window):
    parsed = number(value)
    if parsed is None:
        return 'missing'
    return f'{parsed * WINDOW / window:.0f}'


def main(argv):
    parser = argparse.ArgumentParser(description='Report the MUBO-453 bisect readings.')
    parser.add_argument('--readings', default='bisect-evidence/bisect/readings.csv')
    parser.add_argument('--versions', default='.bisect/versions.json')
    parser.add_argument('--counters', default='bisect-evidence')
    parser.add_argument('--out', default='bisect-evidence/bisect')
    args = parser.parse_args(argv)
    names = versions(args.versions)
    samples, failures = cpu_windows(args.readings)
    counters = counted(args.counters)
    every = list(dict.fromkeys(list(samples) + list(counters)))
    lines = ['| version | sha | cpu_ms/10s | recompositions/10s | remeasures/10s | windows | idle | live | inbox_rows/50 | inbox_remeasures/50 |',
             '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for sha in every:
        windows = samples.get(sha) or []
        median = f'{statistics.median(windows):.0f}' if windows else 'missing'
        spread = f'{min(windows)}-{max(windows)}' if windows else '-'
        probe = counters.get(sha, {})
        lines.append('| {} | {} | {} | {} | {} | {} | {} | {} | {} | {} |'.format(
            names.get(sha, '?'), sha[:8], median,
            scaled(probe.get('inbox_rows'), PROBE_SNAPSHOTS),
            scaled(probe.get('live'), PROBE_REPLIES),
            spread, probe.get('idle', '-'), probe.get('live', '-'),
            probe.get('inbox_rows', '-'), probe.get('inbox_remeasures', '-')))
    lines.append('')
    lines.append(f'recompositions/10s = inbox row compositions over {PROBE_SNAPSHOTS} snapshots x 10/{PROBE_SNAPSHOTS}')
    lines.append(f'remeasures/10s = chat list remeasures for {PROBE_REPLIES} new replies x 10/{PROBE_REPLIES}')
    if failures:
        lines.append('')
        lines.append('windows without a reading: ' + json.dumps(failures))
    report = '\n'.join(lines) + '\n'
    target = Path(args.out)
    target.mkdir(parents=True, exist_ok=True)
    (target / 'table.md').write_text(report)
    sys.stdout.write(report)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
