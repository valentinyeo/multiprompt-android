import csv
import json
import re
import statistics
import sys
from pathlib import Path

MODES = ('without', 'with')
PROFILE_STATUS = 'speed-profile'


def parse_total_time(text):
    text = text.replace('\r', '')
    if not re.search(r'^Status:\s*ok\s*$', text, re.M):
        raise ValueError('am start did not report Status: ok')
    if not re.search(r'^LaunchState:\s*COLD\s*$', text, re.M):
        raise ValueError('am start did not report LaunchState: COLD')
    match = re.search(r'^TotalTime:\s*(\d+)\s*$', text, re.M)
    if not match or int(match.group(1)) <= 0:
        raise ValueError('am start did not report a positive TotalTime')
    return int(match.group(1))


def parse_dexopt_status(text):
    text = text.replace('\r', '')
    head, marker, tail = text.partition('Dexopt state:')
    if not marker:
        raise ValueError('dumpsys package has no Dexopt state section')
    match = re.search(r'\[status=([A-Za-z0-9_-]+)\]', tail)
    if not match:
        raise ValueError('Dexopt state section has no status')
    return match.group(1)


def parse_broadcast_result(text):
    match = re.search(r'result=(\d+)', text.replace('\r', ''))
    if not match:
        raise ValueError('broadcast reported no result code')
    return int(match.group(1))


def read_samples(path):
    samples = {mode: [] for mode in MODES}
    with open(path, newline='') as handle:
        for row in csv.reader(handle):
            if len(row) != 4:
                raise ValueError(f'bad sample row: {row}')
            mode, segment, index, total = row
            if mode not in samples:
                raise ValueError(f'unknown mode: {mode}')
            value = int(total)
            if value <= 0:
                raise ValueError(f'non-positive sample: {row}')
            samples[mode].append(value)
    return samples


def read_pairs(path):
    pairs = {}
    for line in Path(path).read_text().splitlines():
        if not line.strip():
            continue
        key, separator, value = line.partition('=')
        if not separator:
            raise ValueError(f'bad key=value line: {line}')
        pairs[key.strip()] = value.strip()
    return pairs


def describe(values):
    if not values:
        raise ValueError('no samples')
    return {
        'count': len(values),
        'median': statistics.median(values),
        'mean': round(statistics.fmean(values), 1),
        'min': min(values),
        'max': max(values),
        'values': list(values),
    }


def summarize(samples, expected_per_mode):
    result = {}
    for mode in MODES:
        values = samples.get(mode, [])
        if len(values) != expected_per_mode:
            raise ValueError(f'{mode}: expected {expected_per_mode} samples, found {len(values)}')
        result[mode] = describe(values)
    difference = result['without']['median'] - result['with']['median']
    result['difference_ms'] = difference
    result['difference_percent'] = round(100 * difference / result['without']['median'], 1)
    return result


def ms(value):
    return f'{value:.1f}'.rstrip('0').rstrip('.')


def verdict(summary):
    difference = summary['difference_ms']
    if difference > 0:
        return f'With the profile the app starts {ms(difference)} ms faster ({summary["difference_percent"]:g}%).'
    if difference < 0:
        return f'With the profile the app starts {ms(-difference)} ms slower ({-summary["difference_percent"]:g}%).'
    return 'No difference between the two medians.'


def markdown(summary, info, per_mode):
    lines = [
        '## Android start time with and without the startup profile',
        '',
        f'**{verdict(summary)}**',
        '',
        '| | Median (ms) | Mean (ms) | Min (ms) | Max (ms) | Samples |',
        '|---|---|---|---|---|---|',
    ]
    for mode, label in (('without', 'Without profile'), ('with', 'With profile')):
        row = summary[mode]
        lines.append(f'| {label} | {ms(row["median"])} | {ms(row["mean"])} | {row["min"]} | {row["max"]} | {row["count"]} |')
    lines += [
        '',
        f'Difference (without minus with): **{ms(summary["difference_ms"])} ms** ({summary["difference_percent"]:g}% of the without median). Positive means the profile is faster.',
        '',
        '| Setting | Value |',
        '|---|---|',
    ]
    for key in ('device', 'android', 'api', 'abi', 'variant', 'apk', 'without_status', 'with_status', 'with_install_result', 'order'):
        if key in info:
            lines.append(f'| {key.replace("_", " ")} | {info[key]} |')
    lines += [
        f'| samples per mode | {per_mode} |',
        f'| warm-up starts discarded per segment | {info.get("warmup", "")} |',
        '',
        'Each sample is the TotalTime of a cold start from `am start-activity -W -S`. The two modes alternate in segments (without, with, with, without), and each segment starts from a fresh install. Emulator times use software graphics and are only meant to compare the two modes, not to predict phone times.',
    ]
    return '\n'.join(lines) + '\n'


def write_report(samples_path, info_path, out_dir, per_mode):
    samples = read_samples(samples_path)
    info = read_pairs(info_path)
    summary = summarize(samples, per_mode)
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    document = {'summary': summary, 'info': info, 'samples_per_mode': per_mode}
    (out / 'report.json').write_text(json.dumps(document, indent=2) + '\n')
    text = markdown(summary, info, per_mode)
    (out / 'report.md').write_text(text)
    return text


def main(argv):
    if len(argv) == 3 and argv[1] == 'total-time':
        print(parse_total_time(Path(argv[2]).read_text()))
    elif len(argv) == 3 and argv[1] == 'dexopt-status':
        print(parse_dexopt_status(Path(argv[2]).read_text()))
    elif len(argv) == 3 and argv[1] == 'broadcast-result':
        print(parse_broadcast_result(Path(argv[2]).read_text()))
    elif len(argv) == 6 and argv[1] == 'report':
        sys.stdout.write(write_report(argv[2], argv[3], argv[4], int(argv[5])))
    else:
        raise SystemExit('usage: report.py total-time|dexopt-status|broadcast-result <file> | report <samples.csv> <info.txt> <out-dir> <samples-per-mode>')


if __name__ == '__main__':
    try:
        main(sys.argv)
    except (ValueError, OSError) as error:
        print(f'startup-time: {error}', file=sys.stderr)
        sys.exit(1)
