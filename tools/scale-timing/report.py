#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import parsers

STEPS = ('launch-inbox', 'open-largest', 'ten-flings', 'switch-five')
STEP_TITLES = dict(zip(STEPS, ('cold launch to Inbox', 'open the largest chat to its newest row',
                               '10 flings in that chat', 'switch through 5 big chats')))


def load(path):
    return json.loads(Path(path).read_text())


def enrich(timing, relay_text=''):
    events = parsers.relay_events(relay_text)
    steps = []
    for step in timing.get('steps', []):
        calls = parsers.calls_in_window(events, step['started_unix'], step['ended_unix'])
        steps.append(dict(step,
                          history=parsers.call_totals([call for call in calls if call.get('stream') == 'history']),
                          sync=parsers.call_totals([call for call in calls if call.get('stream') == 'tmux']),
                          all_calls=parsers.call_totals(calls)))
    enriched = dict(timing, steps=steps, relay_events=len(events))
    return enriched


def relay_text(path):
    try:
        return path.read_text(errors='replace')
    except OSError:
        return ''


def load_measured(directory):
    directory = Path(directory)
    found = []
    for path in sorted(directory.rglob('timing.json')):
        found.append(enrich(load(path), relay_text(path.parent / 'relay.jsonl')))
    return found


def label(timing):
    return (timing.get('ref') or 'head', timing.get('seed') or 'seed')


def millis(value):
    return '%d' % round(value)


def number(value):
    return '%d' % round(value)


def detail_table(timings):
    lines = ['| ref | seed | step | wall ms | cpu ms | janky % | frames | history calls | history bytes | bytes per history call | history ms (median) | sync calls | sync bytes | bytes per sync call |',
             '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for timing in sorted(timings, key=lambda item: (label(item)[0], label(item)[1])):
        ref, seed = label(timing)
        largest = (timing.get('targets') or {}).get('largest') or {}
        for step in timing.get('steps', []):
            gfx = step.get('gfx') or {}
            history = step.get('history') or {}
            sync = step.get('sync') or {}
            lines.append('| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |' % (
                ref, seed, step['name'], millis(step.get('wall_ms', 0)), millis(step.get('cpu_ms', 0)),
                '%.0f' % gfx.get('janky_percent', 0), number(gfx.get('frames', 0)),
                number(history.get('calls', 0)), number(history.get('bytes', 0)), number(history.get('mean_bytes', 0)),
                millis(history.get('median_ms', 0)), number(sync.get('calls', 0)), number(sync.get('bytes', 0)),
                number(sync.get('mean_bytes', 0))))
        if largest:
            cells = [ref, seed, 'largest chat', '%s bytes, %s rows, marker %s' % (
                number(largest.get('bytes', 0)), number(largest.get('rows', 0)), largest.get('marker', ''))]
            lines.append('| ' + ' | '.join(cells + [''] * 10) + ' |')
    return lines


def switch_table(timings):
    lines = ['| ref | seed | chat | transcript bytes | back ms | inbox ms | tap ms | marker ms | whole switch ms |',
             '| --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for timing in sorted(timings, key=lambda item: (label(item)[0], label(item)[1])):
        ref, seed = label(timing)
        for step in timing.get('steps', []):
            if step.get('name') != 'switch-five':
                continue
            for chat in (step.get('detail') or {}).get('chats') or []:
                lines.append('| %s | %s | %s | %s | %s | %s | %s | %s | %s |' % (
                    ref, seed, chat.get('session', ''), number(chat.get('bytes', 0)),
                    millis(chat.get('back_ms', 0)), millis(chat.get('inbox_ms', 0)), millis(chat.get('tap_ms', 0)),
                    millis(chat.get('marker_ms', 0)), millis(chat.get('switch_ms', 0))))
    return lines


def comparison_table(timings):
    columns = []
    for timing in timings:
        key = label(timing)
        if key not in columns:
            columns.append(key)
    columns.sort()
    header = '| step | ' + ' | '.join('%s %s wall / cpu ms' % key for key in columns) + ' |'
    divider = '| --- |' + ' --- |' * len(columns)
    cells = {key: {step['name']: step for step in timing.get('steps', [])} for timing in timings for key in [label(timing)]}
    lines = [header, divider]
    for name in STEPS:
        row = ['| %s |' % STEP_TITLES.get(name, name)]
        for key in columns:
            step = cells.get(key, {}).get(name)
            row.append(' |' if step is None else ' %s / %s |' % (millis(step.get('wall_ms', 0)), millis(step.get('cpu_ms', 0))))
        lines.append(''.join(row))
    return lines


def render(timings):
    lines = ['# Phone timing at fixture and owner scale', '',
             'Wall clock and app CPU time per step, recorded on the GitHub emulator. Frame counts are a'
             ' secondary reading only: this emulator draws about 4 fps for every build.', '']
    lines.extend(comparison_table(timings))
    lines.extend(['', '## Every step with its reader traffic', ''])
    lines.extend(detail_table(timings))
    lines.extend(['', '## Where one switch goes', ''])
    lines.extend(switch_table(timings))
    lines.extend(['', 'History calls are the reader channel (`--serve`) requests the phone made inside the step window,'
                       ' counted by the fixture host relay from its own protocol log. Sync calls are the tmux listing'
                       ' commands made inside the same window.'])
    missing = ['%s %s' % label(timing) for timing in timings if not timing.get('relay_events')]
    if missing:
        lines.extend(['', 'No reader log was retained for %s, so its byte and call columns are blank, not zero.'
                           % ', '.join(missing)])
    lines.append('')
    return '\n'.join(lines)


def main(argv):
    parser = argparse.ArgumentParser(description='Render the phone timing table from the retained measurements.')
    parser.add_argument('--root', required=True)
    parser.add_argument('--out', required=True)
    arguments = parser.parse_args(argv[1:])
    timings = load_measured(arguments.root)
    if not timings:
        print('NO_TIMING_MEASUREMENTS: nothing under ' + arguments.root, file=sys.stderr)
        return 1
    out = Path(arguments.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    text = render(timings)
    out.write_text(text)
    (out.parent / 'timings.json').write_text(json.dumps(timings, indent=2) + '\n')
    print(text)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
