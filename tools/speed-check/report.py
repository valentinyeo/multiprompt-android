import argparse
import json
import re
import sys
from pathlib import Path

READINGS = ('idle', 'live')
LIVE_PERCENT = 25
LIVE_FLOOR = 2
IDLE_LIMIT = 0
LINE = re.compile(r'^CHAT_LIST_REMEASURES (idle|live)=(\d+)\s*$', re.M)
LABELS = {
    'idle': 'Lazy-list remeasures after five recompositions that bring no new reply',
    'live': 'Lazy-list remeasures for five incoming replies at the bottom of the chat',
}


def parse(text):
    readings = {}
    for reading, value in LINE.findall(text.replace('\r', '')):
        readings[reading] = max(readings.get(reading, 0), int(value))
    return readings


def read_file(path):
    try:
        return parse(Path(path).read_text())
    except FileNotFoundError:
        return {}


def compare(base, head, head_status=0):
    missing = [reading for reading in READINGS if head.get(reading) is None]
    if missing:
        return 'INCOMPLETE', 'the head build reported no ' + ' or '.join(missing) + ' remeasure count'
    if head['idle'] > IDLE_LIMIT:
        return 'SLOW', (f"head remeasured the chat list {head['idle']} times from five recompositions that "
                        f'brought no new reply; the budget is {IDLE_LIMIT}')
    if base.get('live') is not None and base['live'] > 0:
        budget = base['live'] * (100 + LIVE_PERCENT) / 100 + LIVE_FLOOR
        if head['live'] > budget:
            return 'SLOW', (f"head needs {head['live']} remeasures for five incoming replies against "
                            f"{base['live']} on the base revision; the budget is {budget:g}")
    if head_status != 0:
        return 'SLOW', f'the head probe test failed with exit {head_status}'
    return 'OK', ''


def notes(base, head, base_status, head_status):
    lines = []
    for reading in READINGS:
        for side, readings in (('base', base), ('head', head)):
            value = readings.get(reading)
            lines.append(f"{side} {reading} remeasures: {'missing' if value is None else value}")
    if base_status != 0:
        lines.append(f'the base probe test failed with exit {base_status}; head is judged on its own budget')
    if head_status != 0:
        lines.append(f'the head probe test failed with exit {head_status}')
    return lines


def render(outcome, reason, base, head, base_status, head_status):
    rows = ['| Reading | Base | Head | Budget |', '|---|---|---|---|']
    for reading in READINGS:
        budget = IDLE_LIMIT if reading == 'idle' else f'{LIVE_PERCENT}% plus {LIVE_FLOOR} remeasures over base'
        base_value = base.get(reading, 'missing')
        head_value = head.get(reading, 'missing')
        rows.append(f'| {LABELS[reading]} | {base_value} | {head_value} | {budget} |')
    markdown = [
        f'## SPEED_CHECK_{outcome}',
        '',
        reason or 'Head stays inside the chat list remeasure budget of the base revision.',
        '',
        *rows,
        '',
        'Counted with `Snapshot.registerApplyObserver` on the lazy list layout info of the real `ChatHistoryList`',
        'driven by a 120-reply chat in a Robolectric test. No emulator, no frame timing, no noise.',
        '',
    ]
    return '\n'.join(markdown) + '\n'


def main(argv):
    parser = argparse.ArgumentParser(description='Judge the chat list remeasure budget of base against head')
    parser.add_argument('--base', required=True, help='probe reading file of the base revision')
    parser.add_argument('--head', required=True, help='probe reading file of the head revision')
    parser.add_argument('--base-status', type=int, default=0, help='exit status of the base probe test run')
    parser.add_argument('--head-status', type=int, default=0, help='exit status of the head probe test run')
    parser.add_argument('--out', required=True, help='directory for report.md, report.json and info.txt')
    args = parser.parse_args(argv)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    base = read_file(args.base)
    head = read_file(args.head)
    outcome, reason = compare(base, head, args.head_status)
    code = {'OK': 0, 'SLOW': 3, 'INCOMPLETE': 4}[outcome]
    detail = notes(base, head, args.base_status, args.head_status)
    markdown = render(outcome, reason, base, head, args.base_status, args.head_status)
    (out / 'report.md').write_text(markdown)
    (out / 'info.txt').write_text('\n'.join(detail) + '\n')
    (out / 'report.json').write_text(json.dumps({
        'outcome': outcome, 'reason': reason, 'base': base, 'head': head,
        'base_status': args.base_status, 'head_status': args.head_status,
        'idle_limit': IDLE_LIMIT, 'live_percent': LIVE_PERCENT, 'live_floor': LIVE_FLOOR,
    }, indent=2) + '\n')
    print(markdown, end='')
    print('\n'.join(detail))
    print(f'SPEED_CHECK_{outcome}: {reason or "head is inside the remeasure budget of the base revision"}')
    return code


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
