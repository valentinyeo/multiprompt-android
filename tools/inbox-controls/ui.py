#!/usr/bin/env python3
import json
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET


def check(xml, screenshot, expected):
    image = Path(screenshot).read_bytes()
    assert image[:8] == b'\x89PNG\r\n\x1a\n' and len(image) > 24, 'Missing screenshot'
    width, height = struct.unpack('>II', image[16:24])
    assert width >= 100 and height >= 100, 'Empty screenshot'
    root = ET.parse(xml).getroot()
    parents = {child: parent for parent in root.iter() for child in parent}
    nodes = [n for n in root.iter('node') if n.get('package') == 'dev.multiprompt.companion.debug']
    assert nodes, 'Missing app UI'

    def bounds(node):
        values = list(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
        return values if len(values) == 4 else [0, 0, 0, 0]

    def visible(node):
        left, top, right, bottom = bounds(node)
        if not (0 <= left < right <= width and 0 <= top < bottom <= height):
            return False
        parent = parents.get(node)
        while parent is not None:
            if parent.get('scrollable') == 'true':
                x1, y1, x2, y2 = bounds(parent)
                if not (x1 <= left < right <= x2 and y1 <= top < bottom <= y2):
                    return False
            parent = parents.get(parent)
        return True

    def matches(label, attribute='text'):
        return [n for n in nodes if n.get(attribute) == label and visible(n)]

    def choice(label):
        found = matches(label)
        assert len(found) == 1, f'Missing or ambiguous choice: {label}'
        node = found[0]
        while (node.get('clickable') != 'true' and node.get('checkable') != 'true'
               and node.get('selected') != 'true' and node in parents):
            node = parents[node]
        return node

    for label in expected.get('text', []):
        assert matches(label), f'Missing visible text: {label}'
    for label in expected.get('desc', []):
        assert matches(label, 'content-desc'), f'Missing visible description: {label}'
    for label in expected.get('absent', []):
        assert not matches(label), f'Unexpected visible text: {label}'
    for label in expected.get('absent_desc', []):
        assert not matches(label, 'content-desc'), f'Unexpected visible description: {label}'
    for label in expected.get('selected', []):
        node = choice(label)
        assert node.get('selected') == 'true' or node.get('checked') == 'true', f'Choice not selected: {label}'
    if label := expected.get('folder'):
        node = choice(label)
        assert node.get('selected') == 'true' or node.get('checked') == 'true', f'Folder not selected: {label}'
        assert visible(node), f'Folder pill clipped: {label}'
        left, top, right, bottom = bounds(node)
        text_left, _, text_right, _ = bounds(matches(label)[0])
        density = (bottom - top) / 48
        assert text_left - left >= 23 * density and right - text_right >= 23 * density, f'Folder pill padding clipped: {label}'
        viewport = parents.get(node)
        while viewport is not None and viewport.get('scrollable') != 'true':
            viewport = parents.get(viewport)
        assert viewport is not None, 'Missing folder scroll viewport'
        x1, _, x2, _ = bounds(viewport)
        assert left - x1 >= 11 * density and x2 - right >= 11 * density, f'Folder pill inside edge fade: {label}'
        from PIL import Image
        pixels = Image.open(screenshot).convert('RGB')
        center_y = (top + bottom) // 2
        center = pixels.getpixel(((left + right) // 2, top + max(2, int(3 * density))))
        assert all(abs(a - b) <= 5 for point in [(left + int(3 * density), center_y), (right - int(3 * density), center_y)]
                   for a, b in zip(pixels.getpixel(point), center)), 'Selected pill is faded or clipped'
    return dict(xml=Path(xml).name, screenshot=Path(screenshot).name, asserted=expected,
                texts=[n.get('text') for n in nodes if n.get('text') and visible(n)])


def reminder(preferences, before, after, timezone, preset):
    from datetime import datetime, timedelta
    from zoneinfo import ZoneInfo
    values = [int(n.get('value')) for n in ET.parse(preferences).getroot()
              if n.get('name', '').startswith('archive_until::') and n.get('name', '').endswith('::verify-session')]
    assert len(values) == 1, 'Missing or duplicate persisted reminder'
    due = values[0]
    start, end = int(before), int(after)
    assert 0 <= end - start < 180, 'Unbounded reminder action'
    if preset.isdigit():
        assert start + int(preset) <= due <= end + int(preset), 'Wrong relative reminder time'
    else:
        targets = set()
        for epoch in (start, end):
            now = datetime.fromtimestamp(epoch, ZoneInfo(timezone))
            if preset == 'later':
                target = now.replace(hour=17, minute=0, second=0, microsecond=0)
                if target <= now:
                    target = target.replace(hour=21)
                if target <= now:
                    target = (now + timedelta(days=1)).replace(hour=9, minute=0, second=0, microsecond=0)
            else:
                target = (now + timedelta(days=int(preset[-1]))).replace(hour=9, minute=0, second=0, microsecond=0)
            targets.add(int(target.timestamp()))
        assert due in targets, 'Wrong calendar reminder time'
    return dict(preset=preset, before=start, after=end, due=due, timezone=timezone)


if __name__ == '__main__':
    if sys.argv[1] == 'reminder':
        result = reminder(*sys.argv[2:])
    else:
        result = check(sys.argv[1], sys.argv[2], json.loads(sys.argv[3]))
    print(json.dumps(result, sort_keys=True))
