#!/usr/bin/env python3
import argparse
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fixture
import probe

ROOT = Path(__file__).resolve().parents[2]
CURRENT_READER = ROOT / 'app/src/main/assets/chat_history_reader.py'

EXPECTED = {
    'pre-fix': {
        'compacted': ['COMPACTED_BEFORE_2715'],
        'fresh': ['FRESH_READY_2715'],
        'resumed': [],
    },
    'current': {
        'compacted': ['COMPACTED_AFTER_2715'],
        'fresh': ['FRESH_READY_2715'],
        'resumed': ['RESUMED_READY_2715'],
    },
}


def observed(source, home):
    payload = probe.probe(source, home)
    return {item['shape']: dict(transcript=item['transcript'], visible=item['visible'][-1:], all_visible=item['visible'])
            for item in payload['shapes']}


def run(pre_fix, current=CURRENT_READER):
    with tempfile.TemporaryDirectory() as directory:
        home = Path(directory)
        fixture.create(home)
        before = observed(pre_fix, home)
        after = observed(current, home)
    red = all(before[shape]['visible'] == markers for shape, markers in EXPECTED['pre-fix'].items())
    green = all(
        ('RESUMED_READY_2715' in after['resumed']['all_visible'] and
         after['resumed']['all_visible'] and fixture.CODE_BLOCK_FIRST in after['resumed']['all_visible'][-1])
        if shape == 'resumed'
        else after[shape]['visible'] == markers
        for shape, markers in EXPECTED['current'].items()
    )
    return {'preFix': before, 'current': after, 'red': red, 'green': green}


def report(result):
    return '\n'.join([
        'PRE_425_READER_2715_RED ' + json.dumps(result['preFix'], separators=(',', ':')),
        'CURRENT_READER_2715_GREEN ' + json.dumps(result['current'], separators=(',', ':')),
    ]) + '\n'


def main(argv):
    parser = argparse.ArgumentParser(description='Read one seeded chat shape with the reader from before the live-session fix and with the current reader.')
    parser.add_argument('--pre-fix-reader', required=True, help='chat_history_reader.py from before PR 425')
    parser.add_argument('--current-reader', default=str(CURRENT_READER), help='chat_history_reader.py under test')
    parser.add_argument('--evidence', help='Also write the two result lines to this file')
    arguments = parser.parse_args(argv[1:])
    result = run(Path(arguments.pre_fix_reader), Path(arguments.current_reader))
    text = report(result)
    sys.stdout.write(text)
    if arguments.evidence:
        Path(arguments.evidence).write_text(text)
    if not (result['red'] and result['green']):
        print(f'CHAT_SHAPES_REPRO_MISMATCH red={result["red"]} green={result["green"]}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
