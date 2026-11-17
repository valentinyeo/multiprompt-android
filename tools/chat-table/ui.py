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


def tagged(root, tag):
    matches = [node for node in root.iter('node') if node.get('resource-id') == tag and node.get('package') == PACKAGE]
    assert len(matches) == 1, f'Missing or ambiguous {tag}'
    return matches[0]


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
    if axis == 'older':
        nodes = [node for node in root.iter('node') if node.get('package') == PACKAGE
                 and node.get('scrollable') == 'true' and node.get('class') != 'android.widget.HorizontalScrollView']
        assert len(nodes) == 1, 'Missing or ambiguous chat scroll container'
        node = nodes[0]
    else:
        node = tagged(root, 'table-sheet-scroll')
        assert node.get('scrollable') == 'true', 'Expanded table is not scrollable'
    x1, y1, x2, y2 = bounds(node)
    assert x2 - x1 > 100 and y2 - y1 > 100, 'Scroll viewport is too small'
    dx, dy = (x2 - x1) // 5, (y2 - y1) // 5
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    if axis == 'sideways':
        return [x2 - dx, y, x1 + dx, y]
    if axis == 'down':
        return [x, y2 - dy, x, y1 + dy]
    assert axis == 'older', 'Unknown scroll direction'
    return [x, y1 + dy, x, y2 - dy]


def check(xml, screenshot, state, target, hidden=''):
    root = ET.parse(xml).getroot()
    nodes = [node for node in root.iter('node') if node.get('package') == PACKAGE]
    assert nodes, 'Chat UI is missing'
    texts = [node.get('text') for node in nodes]
    tags = [node.get('resource-id') for node in nodes]
    descriptions = [node.get('content-desc') for node in nodes]
    image = Image.open(screenshot).convert('RGB')
    assert image.width >= 100 and image.height >= 100, 'Invalid screenshot'
    if state in ('inline', 'closed'):
        assert 'table-sheet' not in tags and 'table-close' not in tags, 'Expanded sheet remains open'
        assert 'Message verify-session' in texts and 'Session actions' in descriptions, 'Chat did not return'
        assert 'Expand table' in descriptions and 'md-table' in tags, 'Inline table is missing'
    else:
        assert state in ('open', 'sideways', 'down')
        assert 'Garden harvest table' in texts, 'Table title is missing'
        assert 'Close' in descriptions, 'Close action is missing'
        for tag in ('table-sheet', 'table-close', 'table-sheet-scroll', 'md-table-expanded'):
            tagged(root, tag)
        sheet = bounds(tagged(root, 'table-sheet'))
        assert sheet[2] - sheet[0] >= image.width * 0.8, 'Table did not expand to full width'
        assert sheet[3] - sheet[1] >= image.height * 0.5, 'Expanded table is too short'
        table = tagged(root, 'md-table-expanded')
        assert any(node.get('text') == target for node in table.iter('node')), 'Target is outside the expanded table'
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
