#!/usr/bin/env python3
import json
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET


def check(xml, screenshot, phase):
    image = Path(screenshot).read_bytes()
    assert image[:8] == b'\x89PNG\r\n\x1a\n', 'Missing PNG capture'
    width, height = struct.unpack('>II', image[16:24])
    assert width >= 100 and height >= 100, 'Empty screenshot'
    root = ET.parse(xml).getroot()
    nodes = [n for n in root.iter('node') if n.get('package') == 'dev.multiprompt.companion.debug']
    assert nodes, 'Claude chat UI is missing'
    texts = [n.get('text', '') for n in nodes]
    descriptions = [n.get('content-desc', '') for n in nodes]
    editors = [n for n in nodes if n.get('class') == 'android.widget.EditText']
    assert len(editors) == 1, 'Missing or ambiguous composer'
    composer = editors[0].get('text', '')
    assert 'Session actions' in descriptions, 'Chat is not open'
    result = dict(phase=phase, screenshot=Path(screenshot).name, composer=composer)
    if phase == 'ready':
        assert 'Native session skills are ready.' in texts
    elif phase == 'query':
        assert composer == '/cl', f'Wrong query: {composer!r}'
        parents = {child: parent for parent in root.iter() for child in parent}
        rows = []
        for node in nodes:
            label = node.get('text', '')
            if not label.startswith('/') or node in editors:
                continue
            action = node
            while action.get('clickable') != 'true' and action in parents:
                action = parents[action]
            assert action.get('clickable') == 'true' and action.get('enabled') == 'true', label
            bounds = list(map(int, re.findall(r'-?\d+', action.get('bounds', ''))))
            assert len(bounds) == 4, label
            x1, y1, x2, y2 = bounds
            assert 0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height, f'Off-screen command: {label}'
            rows.append((y1, label))
        order = [label for _, label in sorted(rows)]
        assert order[:3] == ['/clear', '/client', '/aaa-paper'], f'Wrong name-first ranking: {order}'
        assert order.count('/clear') == 1, 'Duplicate /clear row'
        assert any(t.startswith('Start a new session with empty context') for t in texts), 'Built-in description replaced by duplicate skill'
        result['order'] = order
    elif phase == 'selected':
        assert composer == '/clear ', f'Wrong selected command: {composer!r}'
        assert '/client' not in texts and '/aaa-paper' not in texts, 'Picker stayed open'
    elif phase == 'sent':
        assert composer in ('', 'Message verify-session'), f'Sent command stayed in composer: {composer!r}'
        assert any('Received /clear. Claude' in t for t in texts), 'Host reply is missing'
        assert not any('[mp:' in t for t in texts), 'Delivery metadata became a chat turn'
        result['reply'] = 'Received /clear. Claude'
    else:
        raise AssertionError(f'Unknown phase: {phase}')
    return result


def check_receipts(path):
    rows = Path(path).read_text().splitlines()
    assert len(rows) in (1, 2) and rows[0] == 'VERIFY_RECEIVED:/clear', rows
    tag = None
    if len(rows) == 2:
        match = re.fullmatch(r'VERIFY_RECEIVED:\[mp:([0-9a-f]{16})\]', rows[1])
        assert match, f'Unexpected extra host input: {rows[1]!r}'
        tag = match[1]
    return dict(command='/clear', command_count=1, delivery_tag=tag, raw_receipts=Path(path).name)


if __name__ == '__main__':
    result = check_receipts(sys.argv[2]) if sys.argv[1] == 'receipts' else check(*sys.argv[1:])
    print(json.dumps(result, indent=2) + '\n', end='')
