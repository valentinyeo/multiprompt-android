#!/usr/bin/env python3
import argparse
import json
import sys
from pathlib import Path

DEFAULT = '.bisect/rounds.json'


def pairs(path):
    data = json.loads(Path(path).read_text())
    return [(entry['base'], entry['suspect']) for entry in data['pairs']], data


def unique(items, reading):
    seen = []
    for pair in items:
        for sha in pair:
            if sha not in seen:
                seen.append(sha)
    return seen


def main(argv):
    parser = argparse.ArgumentParser(description='Read the bisect rounds file.')
    parser.add_argument('rounds', nargs='?', default=DEFAULT)
    parser.add_argument('--repeats', action='store_true')
    parser.add_argument('--pairs', action='store_true')
    parser.add_argument('--json', action='store_true')
    parser.add_argument('--field', default='window_s')
    args = parser.parse_args(argv)
    rounds, data = pairs(args.rounds)
    if args.repeats:
        print(int(data.get('repeats', 3)))
        return 0
    if args.field and not (args.pairs or args.json):
        pass
    if args.pairs:
        for base, suspect in rounds:
            print(base, suspect)
        return 0
    shas = unique(rounds, None)
    if args.json:
        print(json.dumps(shas))
        return 0
    for sha in shas:
        print(sha)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
