#!/usr/bin/env python3
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageStat

PACKAGE = 'dev.multiprompt.companion.debug'


def bounds(node):
    values = list(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
    assert len(values) == 4, 'Missing UI bounds'
    return values


def table(root):
    matches = [node for node in root.iter('node') if node.get('package') == PACKAGE
               and node.get('class') == 'android.widget.HorizontalScrollView'
               and any(re.fullmatch(r'R\d{2}-C\d{2} (orchard|harvest)', child.get('text', '')) for child in node.iter('node'))]
    assert len(matches) == 1, 'Missing or ambiguous table scroll container'
    return matches[0]


def viewport(root):
    parents = {child: parent for parent in root.iter() for child in parent}
    node = table(root)
    while node in parents:
        node = parents[node]
        if node.get('scrollable') == 'true' and node.get('class') != 'android.widget.HorizontalScrollView':
            return node
    raise AssertionError('Table has no vertical scroll container')


def visible(root, text):
    parents = {child: parent for parent in root.iter() for child in parent}
    matches = [node for node in root.iter('node') if node.get('text') == text and node.get('package') == PACKAGE]
    for node in matches:
        x1, y1, x2, y2 = bounds(node)
        if not (0 <= x1 < x2 and 0 <= y1 < y2):
            continue
        parent = node
        clipped = False
        while parent in parents:
            parent = parents[parent]
            if parent.get('bounds'):
                a, b, c, d = bounds(parent)
                if not (a <= x1 < x2 <= c and b <= y1 < y2 <= d):
                    clipped = True
        if not clipped:
            return [x1, y1, x2, y2]
    raise AssertionError(f'Text is not fully visible: {text}')


def swipe(root, axis):
    assert axis in ('older', 'sideways', 'down'), 'Unknown scroll direction'
    node = viewport(root)
    x1, y1, x2, y2 = bounds(node)
    assert x2 - x1 > 100 and y2 - y1 > 100, 'Scroll viewport is too small'
    if axis != 'older':
        assert any(child.get('content-desc') == 'Close' for child in root.iter('node')), 'Expanded table is not open'
        a, b, c, d = bounds(table(root))
        x1, y1, x2, y2 = max(x1, a), max(y1, b), min(x2, c), min(y2, d)
    dx, dy = (x2 - x1) // 5, (y2 - y1) // 5
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    if axis == 'sideways':
        return [x2 - dx, y, x1 + dx, y]
    if axis == 'down':
        return [x, y2 - dy, x, y1 + dy]
    return [x, y1 + dy, x, y2 - dy]


def check(xml, screenshot, state, target, hidden=''):
    root = ET.parse(xml).getroot()
    nodes = [node for node in root.iter('node') if node.get('package') == PACKAGE]
    assert nodes, 'Chat UI is missing'
    texts = [node.get('text') for node in nodes]
    descriptions = [node.get('content-desc') for node in nodes]
    image = Image.open(screenshot).convert('RGB')
    assert image.width >= 100 and image.height >= 100, 'Invalid screenshot'
    scroll = table(root)
    if state in ('inline', 'closed'):
        assert 'Close' not in descriptions, 'Expanded sheet remains open'
        assert 'Message verify-session' in texts and 'Session actions' in descriptions, 'Chat did not return'
        assert 'Expand table' in descriptions, 'Inline expand action is missing'
    else:
        assert state in ('open', 'sideways', 'down')
        assert 'Garden harvest table' in texts, 'Table title is missing'
        assert 'Close' in descriptions and 'Expand table' not in descriptions, 'Expanded table is not open'
        area = bounds(viewport(root))
        assert area[2] - area[0] >= image.width * 0.8, 'Table did not expand to full width'
        assert area[3] - area[1] >= image.height * 0.5, 'Expanded table is too short'
    assert any(node.get('text') == target for node in scroll.iter('node')), 'Target is outside the table'
    cell = visible(root, target)
    x1, y1, x2, y2 = cell
    assert x2 <= image.width and y2 <= image.height, 'Cell is outside the screenshot'
    extrema = ImageStat.Stat(image.crop(tuple(cell)).convert('L')).extrema[0]
    assert extrema[1] - extrema[0] >= 35, 'Visible cell pixels are blank'
    if hidden:
        try:
            visible(root, hidden)
        except AssertionError:
            pass
        else:
            raise AssertionError(f'Expected cell to be off screen: {hidden}')
    return dict(state=state, target=target, bounds=cell, hidden=hidden, screenshot=Path(screenshot).name)


if __name__ == '__main__':
    if sys.argv[1] in ('visible', 'swipe'):
        mode, xml, value = sys.argv[1:]
        root = ET.parse(xml).getroot()
        print(' '.join(map(str, visible(root, value) if mode == 'visible' else swipe(root, value))))
    else:
        print(json.dumps(check(*sys.argv[1:]), sort_keys=True))
