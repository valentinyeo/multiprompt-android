#!/usr/bin/env python3
import argparse
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import time

import xml.etree.ElementTree as ET
from PIL import Image

APP = 'dev.multiprompt.companion'
ADB = os.environ.get('ADB') or 'adb'
GRID = (16, 8)
CELL_TOLERANCE = 10
DIFFERENT_CELLS = 8
MIDDLE_BAND = (0.1, 0.9)
SEGMENT_CHARS = 500
SEGMENT_FLOOR = 125
TAIL_CHARS = 4
SETTLE_READS = 3
FILL_ROUNDS = 40
TYPED_CAP = 6
DELETE_BATCH = 20

SKILL_PILLS = Path(__file__).resolve().parents[1] / 'skill-pills' / 'ui.py'
_spec = importlib.util.spec_from_file_location('skill_pills_ui', SKILL_PILLS)
skill_pills = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(skill_pills)


def bounds(node):
    box = [int(value) for value in re.findall(r'-?\d+', node.get('bounds', ''))]
    assert len(box) == 4 and box[0] < box[2] and box[1] < box[3], f'bad bounds {node.get("bounds")}'
    return box


def window_nodes(root):
    nodes = [node for node in root if node.tag == 'node' and node.get('visible-to-user') != 'false']
    assert nodes, 'the active window is missing'
    return nodes[-1]


def app_nodes(root):
    nodes = [node for node in root.iter('node') if (node.get('package') or '').startswith(APP)]
    assert nodes, 'the multiprompt app is not on screen'
    return nodes


def composer(nodes):
    editors = [node for node in nodes if node.get('class') == 'android.widget.EditText'
               and node.get('visible-to-user') != 'false']
    assert len(editors) == 1, f'expected one composer, found {len(editors)}'
    return editors[0]


def described(nodes, description):
    found = [node for node in nodes if node.get('content-desc') == description]
    assert len(found) == 1, f'expected one {description!r} control, found {len(found)}'
    return found[0]


def signature(image, box):
    left, top, right, bottom = box
    crop = image.crop((left, top, right, bottom)).convert('L')
    return list(crop.resize(GRID, Image.BOX).tobytes())


def ink(image, box):
    left, top, right, bottom = box
    data = list(image.crop((left, top, right, bottom)).convert('L').tobytes())
    background = max(set(data), key=data.count)
    return sum(1 for value in data if abs(value - background) > 30) / len(data)


def geometry(root, image):
    nodes = app_nodes(root)
    field_node = composer(nodes)
    field = bounds(field_node)
    attach = bounds(described(nodes, 'Attach'))
    send = bounds(described(nodes, 'Send prompt'))
    window = bounds(window_nodes(root))
    assert window[0] <= field[0] and field[2] <= window[2], f'the composer leaves the window: field={field} window={window}'
    assert window[1] <= field[1] and field[3] <= window[3], f'the composer leaves the window: field={field} window={window}'
    assert field[0] >= attach[2] - 4, f'the text runs under the attach button: field={field} attach={attach}'
    assert field[2] <= send[0] + 4, f'the text runs under the send button: field={field} send={send}'
    crop = [field[0] + 3, field[1] + 3, field[2] - 3, field[3] - 3]
    return {
        'field': field,
        'attach': attach,
        'send': send,
        'window': window,
        'width': field[2] - field[0],
        'height': field[3] - field[1],
        'text': field_node.get('text') or '',
        'ink': round(ink(image, crop), 4),
        'signature': signature(image, crop),
        'pill_outline': bool(skill_pills.outlines(image, crop, False)),
    }


def dump_ui(destination):
    output = subprocess.run(
        [ADB, 'exec-out', 'sh', '-c', 'rm -f /sdcard/verify-ui.xml; uiautomator dump /sdcard/verify-ui.xml >/dev/null 2>&1; cat /sdcard/verify-ui.xml 2>/dev/null; exit 0'],
        capture_output=True, text=True, timeout=120).stdout
    assert '<hierarchy' in output, 'the UI dump returned no hierarchy'
    Path(destination).write_text(output)
    return Path(destination)


def typed(text):
    return text.replace(' ', '%s')


def advance(observed, expected, limit=None):
    keep = 0
    while keep < len(observed) and keep < len(expected) and observed[keep] == expected[keep]:
        keep += 1
    want = len(expected) if limit is None else min(len(expected), keep + limit)
    ok = observed == expected
    return {'ok': ok, 'field_chars': len(observed), 'expected_chars': len(expected), 'keep': keep,
            'delete': len(observed) - keep, 'retype': '' if ok else typed(expected[keep:want]), 'want': want}


def step(args):
    path = Path(args.xml) if args.xml else dump_ui(args.dump)
    root = ET.parse(path).getroot()
    observed = composer(app_nodes(root)).get('text') or ''
    payload = advance(observed, Path(args.expected).read_text()[:args.want])
    print(json.dumps(payload))
    return payload


def field_text(dump):
    root = ET.parse(dump_ui(dump)).getroot()
    return composer(app_nodes(root)).get('text') or ''


def key_deletes(count, adb=ADB):
    while count > 0:
        take = min(count, DELETE_BATCH)
        subprocess.run([adb, 'shell', 'input', 'keyevent', *(['KEYCODE_DEL'] * take)], check=True)
        count -= take
        time.sleep(0.3)


def type_keys(text, adb=ADB):
    if not text:
        return
    subprocess.run([adb, 'shell', 'input', 'text', text], check=True)


def settle(read, observed, reads=SETTLE_READS, pause=0.6):
    for _ in range(reads):
        time.sleep(pause)
        fresh = read()
        if fresh == observed:
            return fresh
        observed = fresh
    return observed


def filling(expected, read, type_keys_fn, erase, segment=SEGMENT_CHARS, tail=TAIL_CHARS,
            rounds=FILL_ROUNDS, typed_cap=TYPED_CAP, pause=0.6, report=None):
    report = report or (lambda item: None)
    body = expected[:max(0, len(expected) - tail)]
    observed = read()
    typed_chars = 0
    attempts = 0
    limit = segment
    best = -1
    stalls = 0
    while observed != body and attempts < rounds and typed_chars <= len(expected) * typed_cap:
        attempts += 1
        before = best
        plan = advance(observed, body, limit)
        if plan['delete'] > 0:
            erase(plan['delete'])
        if plan['retype']:
            type_keys_fn(plan['retype'])
            typed_chars += len(plan['retype'])
        observed = settle(read, observed, pause=pause)
        current = advance(observed, body, limit)
        if plan['retype'] and current['keep'] <= before:
            if current['delete'] > 0:
                erase(current['delete'])
            if current['retype']:
                type_keys_fn(current['retype'])
                typed_chars += len(current['retype'])
            observed = settle(read, observed, pause=pause)
            current = advance(observed, body, limit)
        if current['keep'] <= before:
            stalls += 1
        else:
            stalls = 0
        best = max(best, current['keep'])
        if stalls >= 2 and limit > SEGMENT_FLOOR:
            limit = max(SEGMENT_FLOOR, limit // 2)
            stalls = 0
        report({'fill_round': attempts, 'fill_field_chars': len(observed), 'fill_keep': current['keep'],
                'fill_typed_chars': typed_chars, 'fill_segment': limit, 'fill_ok': observed == body})
    for character in expected[len(body):]:
        key = typed(character)
        type_keys_fn(key)
        typed_chars += len(key)
    observed = settle(read, observed, pause=pause)
    return {'ok': observed == expected, 'fill_rounds': attempts, 'fill_typed_chars': typed_chars,
            'field_chars': len(observed), 'expected_chars': len(expected)}


def capture(args):
    root = ET.parse(args.xml).getroot()
    image = Image.open(args.png).convert('RGB')
    expected = Path(args.expected).read_text()
    measured = geometry(root, image)
    observed = measured.pop('text')
    cell = measured.pop('signature')
    key = f'{args.length}-{args.phase}'
    record = {'length': args.length, 'phase': args.phase, 'xml': Path(args.xml).name, 'png': Path(args.png).name,
              'text_chars': len(observed), 'expected_chars': len(expected)}
    if args.insert:
        offset = -1
        for start in range(0, len(observed) - len(args.insert) + 1):
            if observed[start:start + len(args.insert)] == args.insert and observed[:start] + observed[start + len(args.insert):] == expected:
                assert offset < 0, f'{args.insert} appears twice in the middle edit'
                offset = start
        assert offset >= 0, f'the composer holds neither the draft nor one middle edit: {len(observed)} characters'
        edited = expected[:offset] + args.insert + expected[offset:]
        Path(args.expected).with_name(Path(args.expected).stem + '-edited.txt').write_text(edited)
        record.update(insert_offset=offset, insert=args.insert)
    else:
        assert observed == expected, f'the composer holds {len(observed)} characters of {len(expected)}'
    if args.pill != 'any':
        expected_pill = args.pill == 'yes'
        assert measured['pill_outline'] == expected_pill, (
            f'the composer pill outline is {measured["pill_outline"]}, expected {expected_pill}; '
            f'lines={measured["height"]} width={measured["width"]}')
    assert measured['ink'] >= 0.02, f'the composer draws no text: ink={measured["ink"]}'
    record.update(measured)
    path = Path(args.state)
    state = json.loads(path.read_text()) if path.exists() else {'captures': {}, 'lengths': {}}
    state['captures'][key] = {'signature': cell, 'text_chars': record['text_chars'],
                              'insert_offset': record.get('insert_offset'), 'expected_chars': len(expected)}
    if args.phase == 'end':
        state['lengths'][str(args.length)] = {name: record[name] for name in ('width', 'height', 'field', 'send', 'attach')}
    path.write_text(json.dumps(state, indent=2) + '\n')
    path.with_name(f'long-drafts-{key}.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps({'ok': True, 'capture': key, 'text_chars': record['text_chars'], 'width': record['width'],
                      'height': record['height'], 'pill_outline': record['pill_outline'],
                      'insert_offset': record.get('insert_offset')}))


def moved(left, right):
    return sum(1 for a, b in zip(left, right) if abs(a - b) > CELL_TOLERANCE)


def equal(left, right):
    return moved(left, right) < DIFFERENT_CELLS


def different(left, right):
    return moved(left, right) >= DIFFERENT_CELLS


def verify(args):
    state = json.loads(Path(args.state).read_text())
    captures = state['captures']
    lengths = sorted({int(key) for key in state['lengths']})
    errors = []
    for length in lengths:
        for phase in ('end', 'top', 'bottom', 'top-again', 'middle'):
            if f'{length}-{phase}' not in captures:
                errors.append(f'{length}: missing capture {phase}')
        if errors:
            continue
        top, bottom = captures[f'{length}-top']['signature'], captures[f'{length}-bottom']['signature']
        again = captures[f'{length}-top-again']['signature']
        middle = captures[f'{length}-middle']['signature']
        end = captures[f'{length}-end']['signature']
        if not equal(top, again):
            errors.append(f'{length}: returning to the top did not restore the same composer text view')
        if length >= 1000 and not different(top, bottom):
            errors.append(f'{length}: the top and bottom of the composer draw the same text')
        if length >= 1000 and not different(middle, end):
            errors.append(f'{length}: a middle edit did not scroll the composer away from the end')
        if length <= 100 and not equal(top, bottom):
            errors.append(f'{length}: a draft that fits scrolled inside the composer')
        if f'{length}-bottom-again' in captures and not equal(bottom, captures[f'{length}-bottom-again']['signature']):
            errors.append(f'{length}: the composer did not stop at the bottom of the draft')
        offset = captures[f'{length}-middle'].get('insert_offset')
        chars = captures[f'{length}-middle']['expected_chars']
        if offset is None or chars is None:
            errors.append(f'{length}: the middle edit recorded no insertion offset')
        elif not chars * MIDDLE_BAND[0] <= offset <= chars * MIDDLE_BAND[1]:
            errors.append(f'{length}: the edit landed at {offset} of {chars}, outside the middle of the draft')
    if f'{lengths[-1]}-reopened' not in captures:
        errors.append(f'{lengths[-1]}: the reopened chat did not show the edited draft')
    widths = [state['lengths'][str(length)]['width'] for length in lengths]
    heights = [state['lengths'][str(length)]['height'] for length in lengths]
    if max(widths) - min(widths) > 1:
        errors.append(f'the composer field widened with the draft: widths={widths}')
    if len(lengths) >= 2 and heights[0] >= heights[1]:
        errors.append(f'the composer field did not grow with the draft: heights={heights}')
    if len(lengths) >= 3 and abs(heights[-1] - heights[-2]) > 2:
        errors.append(f'the composer field did not stop at its line cap: heights={heights}')
    result = {'ok': not errors, 'lengths': lengths, 'widths': widths, 'heights': heights, 'errors': errors,
              'captures': sorted(f'{length}-{phase}' for length in lengths
                                 for phase in ('end', 'top', 'top-again', 'bottom', 'bottom-again', 'middle', 'reopened')
                                 if f'{length}-{phase}' in captures)}
    print(json.dumps(result, indent=2))
    Path(args.state).with_name('long-drafts-result.json').write_text(json.dumps(result, indent=2) + '\n')
    if errors:
        raise SystemExit('LONG_DRAFTS_JOURNEY_FAILED: ' + '; '.join(errors))


def fill_command(args):
    expected = Path(args.expected).read_text()
    payload = filling(expected, lambda: field_text(args.dump), type_keys, key_deletes,
                      segment=args.segment, tail=args.tail, rounds=args.rounds,
                      report=lambda item: print(json.dumps(item)))
    print(json.dumps(payload))
    if not payload['ok']:
        raise SystemExit(f'LONG_DRAFTS_FILL_FAILED: the composer holds {payload["field_chars"]} characters of '
                         f'{payload["expected_chars"]} after {payload["fill_rounds"]} rounds and '
                         f'{payload["fill_typed_chars"]} typed characters')


def main():
    parser = argparse.ArgumentParser(description='Assert the long-draft composer geometry, text and scrolling.')
    commands = parser.add_subparsers(dest='command', required=True)
    take = commands.add_parser('capture')
    take.add_argument('--xml', required=True)
    take.add_argument('--png', required=True)
    take.add_argument('--length', type=int, required=True)
    take.add_argument('--phase', required=True)
    take.add_argument('--expected', required=True)
    take.add_argument('--state', required=True)
    take.add_argument('--insert', default='')
    take.add_argument('--pill', choices=['yes', 'no', 'any'], default='no')
    take.set_defaults(run=capture)
    check = commands.add_parser('verify')
    check.add_argument('--state', required=True)
    check.set_defaults(run=verify)
    check_fill = commands.add_parser('step')
    check_fill.add_argument('--expected', required=True)
    check_fill.add_argument('--want', type=int, required=True)
    check_fill.add_argument('--xml')
    check_fill.add_argument('--dump', default='current.xml')
    check_fill.set_defaults(run=step)
    write = commands.add_parser('fill')
    write.add_argument('--expected', required=True)
    write.add_argument('--dump', default='current.xml')
    write.add_argument('--segment', type=int, default=SEGMENT_CHARS)
    write.add_argument('--tail', type=int, default=TAIL_CHARS)
    write.add_argument('--rounds', type=int, default=FILL_ROUNDS)
    write.set_defaults(run=fill_command)
    args = parser.parse_args()
    args.run(args)


if __name__ == '__main__':
    main()
