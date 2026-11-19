#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

SESSION = 'live-long-pane-2840'
CYCLES = 1475
TAIL = 'LONG_PANE_TAIL_2840'
DIVIDER = '──────────'
FILE = 'long-pane-2840.txt'
MIN_LINES = 2950


def pane_text(cycles=CYCLES):
    lines = []
    for index in range(1, cycles + 1):
        lines.append(f'⏺ behind={index} age={index}m tag=v0.1.{index}')
        lines.append(f'behind={index} age={index}m tag=v0.1.{index}')
    lines.extend([DIVIDER, TAIL])
    return '\n'.join(lines) + '\n'


def summary():
    text = pane_text()
    return {'session': SESSION, 'cycles': CYCLES, 'lines': len(text.splitlines()), 'bytes': len(text.encode()), 'tail': TAIL}


def seed(home):
    path = Path(home) / FILE
    path.write_text(pane_text())
    return {**summary(), 'path': str(path)}


def check(home):
    path = Path(home) / FILE
    lines = path.read_text().splitlines()
    payload = {**summary(), 'path': str(path), 'read_lines': len(lines), 'tail_present': lines[-1:] == [TAIL]}
    problems = []
    if payload['read_lines'] < MIN_LINES:
        problems.append(f'the pane holds {payload["read_lines"]} lines, fewer than {MIN_LINES}')
    if not payload['tail_present']:
        problems.append('the pane does not end with its tail marker')
    if problems:
        raise SystemExit('LONG_PANE_FIXTURE_INVALID: ' + '; '.join(problems))
    return payload


def main():
    parser = argparse.ArgumentParser(description='Synthetic long terminal pane for the legacy reader proof.')
    parser.add_argument('command', choices=['plan', 'seed', 'check'])
    parser.add_argument('--home', default=None)
    arguments = parser.parse_args()
    home = arguments.home or str(Path.home())
    payload = {'plan': summary, 'seed': lambda: seed(home), 'check': lambda: check(home)}[arguments.command]()
    print(json.dumps(payload, indent=1, sort_keys=True))


if __name__ == '__main__':
    main()
