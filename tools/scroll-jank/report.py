import json
import os
import sys
from pathlib import Path

MAX_JANKY_PERCENT = 60.0
MAX_P95_MS = 120


def load(directory):
    files = sorted(Path(directory).glob('*.json'))
    if not files:
        raise ValueError(f'no scroll frame samples in {directory}')
    samples = []
    for path in files:
        sample = json.loads(path.read_text())
        for field in ('screen', 'totalFrames', 'jankyFrames', 'jankyPercent', 'p50Ms', 'p90Ms', 'p95Ms', 'p99Ms', 'scrollMs'):
            if field not in sample:
                raise ValueError(f'{path.name} has no {field}')
        if sample['totalFrames'] < 60:
            raise ValueError(f"{path.name} rendered only {sample['totalFrames']} frames")
        samples.append(sample)
    return samples


def table(samples):
    lines = [
        '| Screen | Frames | Janky | Janky % | 50th ms | 90th ms | 95th ms | 99th ms | Scroll ms |',
        '| --- | --- | --- | --- | --- | --- | --- | --- | --- |',
    ]
    for sample in samples:
        lines.append('| {screen} | {totalFrames} | {jankyFrames} | {jankyPercent:.2f} | {p50Ms} | {p90Ms} | {p95Ms} | {p99Ms} | {scrollMs} |'.format(**sample))
    return '\n'.join(lines)


def failures(samples, max_percent=MAX_JANKY_PERCENT, max_p95=MAX_P95_MS):
    found = []
    for sample in samples:
        if sample['jankyPercent'] > max_percent:
            found.append(f"{sample['screen']}: {sample['jankyPercent']:.2f}% janky frames, limit {max_percent:.2f}%")
        if sample['p95Ms'] > max_p95:
            found.append(f"{sample['screen']}: 95th percentile frame {sample['p95Ms']}ms, limit {max_p95}ms")
    return found


def report(samples, max_percent=MAX_JANKY_PERCENT, max_p95=MAX_P95_MS):
    verdict = failures(samples, max_percent, max_p95)
    text = table(samples) + '\n\n'
    text += f'Limits: {max_percent:.2f}% janky frames, {max_p95}ms 95th percentile.\n\n'
    text += 'SCROLL_FRAME_TIME_FAILED: ' + '; '.join(verdict) + '\n' if verdict else 'SCROLL_FRAME_TIME_OK\n'
    return text, verdict


def main(argv):
    if len(argv) != 2:
        raise SystemExit('usage: report.py <sample directory>')
    max_percent = float(os.environ.get('SCROLL_JANK_MAX_PERCENT', MAX_JANKY_PERCENT))
    max_p95 = int(os.environ.get('SCROLL_JANK_MAX_P95_MS', MAX_P95_MS))
    text, verdict = report(load(argv[1]), max_percent, max_p95)
    sys.stdout.write(text)
    return 1 if verdict else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
