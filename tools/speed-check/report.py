import argparse
import json
import re
import sys
from pathlib import Path

READINGS = ('idle', 'live', 'inbox_rows', 'inbox_remeasures')
LIVE_PERCENT = 25
LIVE_FLOOR = 2
IDLE_LIMIT = 0
INBOX_LIMIT = 0
LINE = re.compile(r'^CHAT_LIST_REMEASURES (idle|live)=(\d+)\s*$', re.M)
SNAPSHOT = re.compile(r'^CHAT_LIST_SNAPSHOTS row=(\S+) snapshots=\d+ recompositions=(\d+) remeasures=(\d+)\s*$', re.M)
INBOX_ROW = 'SessionCard'
NOUNS = {
    'idle': 'remeasure',
    'live': 'remeasure',
    'inbox_rows': 'inbox row recomposition',
    'inbox_remeasures': 'inbox remeasure',
}
LABELS = {
    'idle': 'Lazy-list remeasures after five recompositions that bring no new reply',
    'live': 'Lazy-list remeasures for five incoming replies at the bottom of the chat',
    'inbox_rows': 'Inbox row recompositions from fifty reader snapshots that change no row',
    'inbox_remeasures': 'Inbox lazy-list remeasures from fifty reader snapshots that change no row',
}
SHORT = {
    'idle': 'chat remeasures with no new reply',
    'live': 'chat remeasures for five replies',
    'inbox_rows': 'inbox row recompositions',
    'inbox_remeasures': 'inbox remeasures',
}
BUDGETS = {
    'idle': str(IDLE_LIMIT),
    'live': f'{LIVE_PERCENT}% plus {LIVE_FLOOR} remeasures over base',
    'inbox_rows': str(INBOX_LIMIT),
    'inbox_remeasures': str(INBOX_LIMIT),
}


def parse(text):
    text = text.replace('\r', '')
    readings = {}
    for reading, value in LINE.findall(text):
        readings[reading] = max(readings.get(reading, 0), int(value))
    for row, recompositions, remeasures in SNAPSHOT.findall(text):
        if row != INBOX_ROW:
            continue
        readings['inbox_rows'] = max(readings.get('inbox_rows', 0), int(recompositions))
        readings['inbox_remeasures'] = max(readings.get('inbox_remeasures', 0), int(remeasures))
    return readings


def read_file(path):
    try:
        return parse(Path(path).read_text())
    except FileNotFoundError:
        return {}


def compare(base, head, head_status=0):
    missing = [reading for reading in READINGS if head.get(reading) is None]
    if missing:
        nouns = ' or '.join(dict.fromkeys(NOUNS[reading] for reading in missing))
        return 'INCOMPLETE', [f'the head build reported no {nouns} count']
    reasons = []
    if head['idle'] > IDLE_LIMIT:
        reasons.append(f"head remeasured the chat list {head['idle']} times from five recompositions that "
                       f'brought no new reply; the budget is {IDLE_LIMIT}')
    if head['inbox_rows'] > INBOX_LIMIT:
        reasons.append(f"head recomposed inbox rows {head['inbox_rows']} times from fifty reader snapshots "
                       f'that changed no row; the budget is {INBOX_LIMIT}')
    if head['inbox_remeasures'] > INBOX_LIMIT:
        reasons.append(f"head remeasured the inbox list {head['inbox_remeasures']} times from fifty reader "
                       f'snapshots that changed no row; the budget is {INBOX_LIMIT}')
    if base.get('live') is not None and base['live'] > 0:
        budget = base['live'] * (100 + LIVE_PERCENT) / 100 + LIVE_FLOOR
        if head['live'] > budget:
            reasons.append(f"head needs {head['live']} remeasures for five incoming replies against "
                           f"{base['live']} on the base revision; the budget is {budget:g}")
    if reasons:
        return 'SLOW', reasons
    if head_status != 0:
        return 'SLOW', [f'the head probe test failed with exit {head_status}']
    return 'OK', []


def notes(base, head, base_status, head_status):
    lines = []
    for reading in READINGS:
        for side, readings in (('base', base), ('head', head)):
            value = readings.get(reading)
            lines.append(f"{side} {SHORT[reading]}: {'missing' if value is None else value}")
    if base_status != 0:
        lines.append(f'the base probe test failed with exit {base_status}; head is judged on its own budget')
    if head_status != 0:
        lines.append(f'the head probe test failed with exit {head_status}')
    return lines


def render(outcome, reasons, base, head):
    rows = ['| Reading | Base | Head | Budget |', '|---|---|---|---|']
    for reading in READINGS:
        base_value = base.get(reading, 'missing')
        head_value = head.get(reading, 'missing')
        rows.append(f'| {LABELS[reading]} | {base_value} | {head_value} | {BUDGETS[reading]} |')
    markdown = [
        f'## SPEED_CHECK_{outcome}',
        '',
        '\n'.join(f'- {reason}' for reason in reasons) or 'Head stays inside the chat list and inbox budget of the base revision.',
        '',
        *rows,
        '',
        'Counted with `Snapshot.registerApplyObserver` on the lazy list layout info of the real `ChatHistoryList`',
        'driven by a 120-reply chat, and with a Compose tracer on the real `SessionCard` rows of an 80-row inbox',
        'driven by 50 reader snapshots, both in Robolectric tests. No emulator, no frame timing, no noise.',
        '',
    ]
    return '\n'.join(markdown) + '\n'


def main(argv):
    parser = argparse.ArgumentParser(description='Judge the chat list and inbox budget of base against head')
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
    outcome, reasons = compare(base, head, args.head_status)
    code = {'OK': 0, 'SLOW': 3, 'INCOMPLETE': 4}[outcome]
    detail = notes(base, head, args.base_status, args.head_status)
    markdown = render(outcome, reasons, base, head)
    (out / 'report.md').write_text(markdown)
    (out / 'info.txt').write_text('\n'.join(detail) + '\n')
    (out / 'report.json').write_text(json.dumps({
        'outcome': outcome, 'reasons': reasons, 'base': base, 'head': head,
        'base_status': args.base_status, 'head_status': args.head_status,
        'idle_limit': IDLE_LIMIT, 'inbox_limit': INBOX_LIMIT,
        'live_percent': LIVE_PERCENT, 'live_floor': LIVE_FLOOR,
    }, indent=2) + '\n')
    print(markdown, end='')
    print('\n'.join(detail))
    summary = '; '.join(reasons) or 'head is inside the remeasure budget of the base revision'
    print(f'SPEED_CHECK_{outcome}: {summary}')
    return code


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
