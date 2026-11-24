import csv
import json
import re
import statistics
import sys
from pathlib import Path

SIDES = ('base', 'head')
ROUNDS = 3
RULES = {
    'inbox_open_ms': {'percent': 25, 'floor': 150},
    'inbox_open_p90_ms': {'percent': 25, 'floor': 25},
    'inbox_scroll_janky_percent': {'percent': 25, 'floor': 4},
    'inbox_scroll_p90_ms': {'percent': 25, 'floor': 20},
    'chat_open_p90_ms': {'percent': 25, 'floor': 20},
}
LABELS = {
    'inbox_open_ms': 'Cold open of the app, first frame',
    'inbox_open_p90_ms': 'Inbox open, 90th percentile frame',
    'inbox_scroll_janky_percent': 'Fling scroll of the inbox, janky frames',
    'inbox_scroll_p90_ms': 'Fling scroll of the inbox, 90th percentile frame',
    'chat_open_p90_ms': 'Open the long chat, 90th percentile frame',
}
UNITS = {
    'inbox_open_ms': 'ms',
    'inbox_open_p90_ms': 'ms',
    'inbox_scroll_janky_percent': '%',
    'inbox_scroll_p90_ms': 'ms',
    'chat_open_p90_ms': 'ms',
}


def parse_cold_start(text):
    text = text.replace('\r', '')
    if not re.search(r'^Status:\s*ok\s*$', text, re.M):
        raise ValueError('am start did not report Status: ok')
    if not re.search(r'^LaunchState:\s*COLD\s*$', text, re.M):
        raise ValueError('am start did not report LaunchState: COLD')
    match = re.search(r'^TotalTime:\s*(\d+)\s*$', text, re.M)
    if not match or int(match.group(1)) <= 0:
        raise ValueError('am start did not report a positive TotalTime')
    return int(match.group(1))


def parse_frame_stats(text):
    text = text.replace('\r', '')
    frames = [int(value) for value in re.findall(r'^\s*Total frames rendered:\s*(\d+)\s*$', text, re.M)]
    if not frames:
        raise ValueError('gfxinfo reported no frame count')
    if sum(frames) <= 0:
        raise ValueError('gfxinfo reported no rendered frames')
    janky = [int(value) for value in re.findall(r'^\s*Janky frames:\s*(\d+)\s*\([0-9.]+%\)\s*$', text, re.M)]
    if len(janky) != len(frames):
        raise ValueError('gfxinfo janky frame summary is missing for a frame count')
    percentiles = [int(value) for value in re.findall(r'^\s*90th percentile:\s*(\d+)ms\s*$', text, re.M)]
    if not percentiles:
        raise ValueError('gfxinfo reported no 90th percentile frame')
    total = sum(frames)
    return {
        'total_frames': total,
        'janky_frames': sum(janky),
        'janky_percent': round(sum(janky) * 100.0 / total, 2),
        'p90_ms': max(percentiles),
    }


def read_samples(path):
    samples = {}
    with open(path, newline='') as handle:
        for row in csv.reader(handle):
            if not row:
                continue
            if len(row) != 4:
                raise ValueError(f'bad sample row: {row}')
            side, round_number, metric, value = (item.strip() for item in row)
            if side not in SIDES:
                raise ValueError(f'unknown side: {side}')
            if metric not in RULES:
                raise ValueError(f'unknown metric: {metric}')
            if not round_number.isdigit():
                raise ValueError(f'bad round: {round_number}')
            samples.setdefault(metric, {}).setdefault(side, []).append(float(value))
    return samples


def compare(samples, rounds=ROUNDS):
    metrics = []
    errors = []
    failures = []
    for metric in RULES:
        base = samples.get(metric, {}).get('base', [])
        head = samples.get(metric, {}).get('head', [])
        if len(base) < rounds or len(head) < rounds:
            errors.append(f'{metric} has {len(base)} base and {len(head)} head samples, need {rounds} of each')
            continue
        base_median = statistics.median(base)
        head_median = statistics.median(head)
        rule = RULES[metric]
        delta = head_median - base_median
        limit = base_median * (1 + rule['percent'] / 100.0)
        if base_median > 0:
            change = round((head_median - base_median) * 100.0 / base_median, 1)
        else:
            change = 0.0 if head_median <= 0 else 100.0
        worse = head_median > limit and delta > rule['floor']
        entry = {
            'metric': metric,
            'label': LABELS[metric],
            'unit': UNITS[metric],
            'base_median': base_median,
            'head_median': head_median,
            'change_percent': change,
            'allowed_percent': rule['percent'],
            'allowed_floor': rule['floor'],
            'base_samples': base,
            'head_samples': head,
            'worse': worse,
        }
        metrics.append(entry)
        if worse:
            failures.append(entry)
    return {'passed': not failures and not errors, 'metrics': metrics, 'failures': failures, 'errors': errors}


def markdown(result, rounds=ROUNDS):
    lines = [
        f'Base and head timed on the same emulator, {rounds} rounds each, median per metric.',
        '',
        '| Journey | Base | Head | Change | Fails when head is |',
        '|---|---|---|---|---|',
    ]
    for entry in result['metrics']:
        unit = entry['unit']
        lines.append(
            f"| {entry['label']} | {entry['base_median']:g}{unit} | {entry['head_median']:g}{unit} | "
            f"{entry['change_percent']:+.1f}% | over {entry['allowed_percent']}% and {entry['allowed_floor']:g}{unit} worse |"
        )
    if result['errors']:
        lines.extend(['', 'Missing measurements:'] + [f'- {error}' for error in result['errors']])
    if result['failures']:
        lines.extend(['', 'Slower than the base revision:'] + [
            f"- {entry['label']}: {entry['base_median']:g}{entry['unit']} to {entry['head_median']:g}{entry['unit']} ({entry['change_percent']:+.1f}%)"
            for entry in result['failures']
        ])
    lines.extend(['', f"Result: {'SLOWER' if result['failures'] else 'NO SLOWDOWN' if not result['errors'] else 'INCOMPLETE'}"])
    return '\n'.join(lines) + '\n'


def write_report(samples_path, json_path, markdown_path, rounds=ROUNDS):
    result = compare(read_samples(samples_path), rounds)
    Path(json_path).parent.mkdir(parents=True, exist_ok=True)
    Path(json_path).write_text(json.dumps({'rounds': rounds, 'rules': RULES, **result}, indent=2) + '\n')
    Path(markdown_path).write_text(markdown(result, rounds))
    return result


def main(argv):
    if len(argv) < 3:
        raise SystemExit('Usage: report.py cold-start FILE | frames FILE FIELD | report SAMPLES JSON MARKDOWN [ROUNDS]')
    command = argv[1]
    if command == 'cold-start':
        print(parse_cold_start(Path(argv[2]).read_text()))
        return 0
    if command == 'frames':
        fields = {'total-frames': 'total_frames', 'janky-percent': 'janky_percent', 'p90-ms': 'p90_ms'}
        if argv[3] not in fields:
            raise SystemExit(f'unknown frame field {argv[3]}')
        print(parse_frame_stats(Path(argv[2]).read_text())[fields[argv[3]]])
        return 0
    if command == 'report':
        rounds = int(argv[5]) if len(argv) > 5 else ROUNDS
        result = write_report(argv[2], argv[3], argv[4], rounds)
        sys.stdout.write(markdown(result, rounds))
        if result['errors']:
            print('SPEED_CHECK_INCOMPLETE', file=sys.stderr)
            return 4
        if result['failures']:
            print('SPEED_CHECK_SLOW', file=sys.stderr)
            return 3
        print('SPEED_CHECK_OK')
        return 0
    raise SystemExit(f'unknown command {command}')


if __name__ == '__main__':
    sys.exit(main(sys.argv))
