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
    proc_samples = defaultdict(list)
    anrs = defaultdict(int)
    skipped = defaultdict(list)
    pairs = defaultdict(lambda: defaultdict(list))
    failures = []
    if not Path(path).is_file():
        return samples, proc_samples, anrs, skipped, pairs, failures
    with Path(path).open() as source:
        for row in csv.DictReader(source):
            if not row.get('ticks'):
                failures.append(row)
                continue
            ms = int(row['ticks']) * 10
            samples[row['sha']].append(ms)
            if row.get('proc_ticks'):
                proc_samples[row['sha']].append(int(row['proc_ticks']) * 10)
            if row.get('anr'):
                anrs[row['sha']] += int(row['anr'])
            if row.get('skipped_frames'):
                skipped[row['sha']].append(int(row['skipped_frames']))
            pairs[row['pair']][(row['side'], row['sha'])].append(ms)
    return samples, proc_samples, anrs, skipped, pairs, failures


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
    samples, proc_samples, anrs, skipped, pairs, failures = cpu_windows(args.readings)
    counters = counted(args.counters)
    every = list(dict.fromkeys(list(samples) + list(counters)))
    lines = ['| version | sha | cpu_ms/10s | proc_ms/10s | anr | skipped | recompositions/10s | remeasures/10s | windows |',
             '| --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for sha in every:
        windows = samples.get(sha) or []
        pwindows = proc_samples.get(sha) or []
        swindows = skipped.get(sha) or []
        median = f'{statistics.median(windows):.0f}' if windows else 'missing'
        pmedian = f'{statistics.median(pwindows):.0f}' if pwindows else '-'
        smedian = f'{statistics.median(swindows):.0f}' if swindows else '-'
        spread = f'{min(windows)}-{max(windows)}' if windows else '-'
        probe = counters.get(sha, {})
        lines.append('| {} | {} | {} | {} | {} | {} | {} | {} | {} |'.format(
            names.get(sha, '?'), sha[:8], median, pmedian, anrs.get(sha, 0), smedian,
            scaled(probe.get('inbox_rows'), PROBE_SNAPSHOTS),
            scaled(probe.get('live'), PROBE_REPLIES),
            spread))
    if pairs:
        lines.append('')
        lines.append('| pair | base | base_ms | suspect | suspect_ms | delta_ms |')
        lines.append('| --- | --- | --- | --- | --- | --- |')
        for pair_id in sorted(pairs, key=lambda x: int(x) if str(x).isdigit() else 0):
            entries = pairs[pair_id]
            base_item = next(((sha, vals) for (side, sha), vals in entries.items() if side == 'base'), None)
            suspect_item = next(((sha, vals) for (side, sha), vals in entries.items() if side == 'suspect'), None)
            if base_item and suspect_item:
                bsha, bvals = base_item
                ssha, svals = suspect_item
                bmed = statistics.median(bvals)
                smed = statistics.median(svals)
                lines.append('| {} | {} {} | {:.0f} | {} {} | {:.0f} | {:+.0f} |'.format(
                    pair_id, names.get(bsha, '?'), bsha[:8], bmed,
                    names.get(ssha, '?'), ssha[:8], smed, smed - bmed))
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
