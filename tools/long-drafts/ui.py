#!/usr/bin/env python3
import argparse
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import sys

import xml.etree.ElementTree as ET
from PIL import Image

APP = 'dev.multiprompt.companion'
ADB = os.environ.get('ADB') or 'adb'
CHUNK_CHARS = 100
GRID = (16, 8)
CELL_TOLERANCE = 10
DIFFERENT_CELLS = 8
MIDDLE_BAND = (0.1, 0.9)

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


def pending(args):
    path = Path(args.xml) if args.xml else dump_ui(args.dump)
    root = ET.parse(path).getroot()
    observed = composer(app_nodes(root)).get('text') or ''
    expected = Path(args.expected).read_text()
    if observed == expected:
        payload = {'ok': True, 'field_chars': len(observed), 'expected_chars': len(expected), 'next': ''}
    elif expected.startswith(observed):
        missing = expected[len(observed):]
        payload = {'ok': True, 'field_chars': len(observed), 'expected_chars': len(expected),
                   'missing_chars': len(missing), 'next': typed(missing[:args.chunk])}
    else:
        raise SystemExit(f'LONG_DRAFTS_FIELD_REPLACED: the composer holds {len(observed)} characters that are not the start of the {len(expected)} character draft')
    print(json.dumps(payload))
    return payload


def typed(text):
    return text.replace(' ', '%s')


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
    fill = commands.add_parser('pending')
    fill.add_argument('--expected', required=True)
    fill.add_argument('--chunk', type=int, default=CHUNK_CHARS)
    fill.add_argument('--xml')
    fill.add_argument('--dump', default='current.xml')
    fill.set_defaults(run=pending)
    args = parser.parse_args()
    args.run(args)


if __name__ == '__main__':
    main()
