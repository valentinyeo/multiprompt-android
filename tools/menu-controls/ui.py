import json
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET


def state(xml):
    root = ET.parse(xml).getroot()
    windows = list(root)
    assert windows, 'Missing active window'
    window = windows[-1]
    parents = {child: parent for parent in window.iter() for child in parent}

    def chain(node):
        yield node
        while node in parents:
            node = parents[node]
            yield node

    def rect(node):
        values = tuple(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
        assert len(values) == 4, node.attrib
        return values

    def visible(node):
        x1, y1, x2, y2 = rect(window)
        for ancestor in chain(node):
            if ancestor.get('visible-to-user') == 'false':
                return False
            if ancestor.get('scrollable') == 'true':
                a, b, c, d = rect(ancestor)
                x1, y1, x2, y2 = max(x1, a), max(y1, b), min(x2, c), min(y2, d)
        a, b, c, d = rect(node)
        return x1 <= a < c <= x2 and y1 <= b < d <= y2

    return list(window.iter('node')), chain, visible


def check(xml, png, mode, value, before=None):
    image = Path(png).read_bytes()
    assert image[:8] == b'\x89PNG\r\n\x1a\n' and len(image) > 100, 'Missing screenshot'
    assert min(struct.unpack('>II', image[16:24])) >= 100, 'Invalid screenshot'
    nodes, chain, visible = state(xml)
    if mode == 'current':
        matches = [n for n in nodes if n.get('text', '').startswith('Current: ')]
        assert len(matches) == 1 and visible(matches[0]), 'Missing current model'
        assert value in matches[0].get('text'), matches[0].attrib
        assert any(n.get('enabled') == 'false' for n in chain(matches[0])), 'Current model must be disabled'
    elif mode == 'reply':
        matches = [n for n in nodes if n.get('text') == value and visible(n)]
        assert len(matches) == 1, 'Expected visible host reply'
    elif mode == 'draft':
        matches = [n for n in nodes if n.get('class') == 'android.widget.EditText' and n.get('text') == value]
        assert len(matches) == 1 and visible(matches[0]), 'Draft changed or hidden'
        assert not any(n.get('text', '').startswith(('Current: ', 'Set effort: ')) for n in nodes), 'Menu stayed open'
    else:
        assert mode in ('visible', 'scroll')
        matches = [n for n in nodes if n.get('text') == value and visible(n)]
        assert len(matches) == 1, f'Expected visible row: {value}'
        assert all(n.get('enabled') != 'false' for n in chain(matches[0])), 'Row is disabled'
        clickable = next((n for n in chain(matches[0]) if n.get('clickable') == 'true'), None)
        assert clickable is not None and visible(clickable), 'Row action is clipped'
        if mode == 'scroll':
            old_nodes, _, old_visible = state(before)
            assert not any(n.get('text') == value and old_visible(n) for n in old_nodes), 'Scroll revealed no new row'
    return dict(mode=mode, text=value, xml=Path(xml).name, screenshot=Path(png).name)


if __name__ == '__main__':
    if sys.argv[2] == 'swipe':
        nodes, _, visible = state(sys.argv[1])
        scrolls = [n for n in nodes if n.get('scrollable') == 'true' and visible(n)]
        assert len(scrolls) == 1, 'Expected one active menu scroll'
        x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', scrolls[0].get('bounds', '')))
        print((x1 + x2) // 2, y2 - (y2 - y1) // 5, (x1 + x2) // 2, y1 + (y2 - y1) // 5)
    else:
        print(json.dumps(check(*sys.argv[1:])))
