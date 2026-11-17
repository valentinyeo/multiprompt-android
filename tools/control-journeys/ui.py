#!/usr/bin/env python3
import json
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET


def check(xml, screenshot, phase, value=''):
    image = Path(screenshot).read_bytes()
    assert image[:8] == b'\x89PNG\r\n\x1a\n' and len(image) > 24, 'Missing screenshot'
    width, height = struct.unpack('>II', image[16:24])
    assert width >= 100 and height >= 100, 'Empty screenshot'
    nodes = [n for n in ET.parse(xml).getroot().iter('node')
             if n.get('package') == 'dev.multiprompt.companion.debug']
    texts = [n.get('text', '') for n in nodes]
    descriptions = [n.get('content-desc', '') for n in nodes]
    assert nodes, 'Missing app UI'

    def visible(node):
        bounds = list(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
        return (len(bounds) == 4 and 0 <= bounds[0] < bounds[2] <= width
                and 0 <= bounds[1] < bounds[3] <= height)

    if phase in ('face', 'colour', 'auto'):
        assert 'Bot look' in texts
        label = {'face': f'Face {value}', 'colour': f'Colour {value}', 'auto': 'Auto'}[phase]
        matches = [n for n in nodes if n.get('content-desc') == label or n.get('text') == label]
        assert len(matches) == 1 and visible(matches[0]), f'Choice not visible: {label}'
        assert matches[0].get('selected') == 'true', f'Choice not selected: {label}'
        prefix = 'Face ' if phase == 'face' else 'Colour '
        assert not any(n is not matches[0] and n.get('selected') == 'true'
                       and (n.get('content-desc', '').startswith(prefix)
                            or (phase != 'face' and n.get('text') == 'Auto')) for n in nodes), 'Multiple selections'
    elif phase == 'scroll-top':
        assert 'Bot look' in texts and 'Face 1' in descriptions
        assert not any(n.get('text') == 'Auto' and visible(n) for n in nodes), 'Bottom row already visible'
    elif phase == 'scroll-bottom':
        assert 'Bot look' in texts
        assert any(n.get('text') == 'Auto' and visible(n) for n in nodes), 'Bottom row not revealed'
        assert not any(n.get('content-desc') == 'Face 1' and visible(n) for n in nodes), 'Menu did not scroll'
    elif phase == 'rename-draft':
        assert 'Rename session' in texts
        assert any(n.get('class') == 'android.widget.EditText' and n.get('text') == value for n in nodes), 'Wrong rename draft'
        assert 'Cancel' in texts and 'Rename' in texts
    elif phase == 'display':
        assert value in texts and 'Use default font size' in texts
    elif phase == 'reader':
        assert 'Session actions' in descriptions and 'Bot look' not in texts
        assert 'Rename session' not in texts and value in texts
    else:
        raise AssertionError(f'Unknown state: {phase}')
    return dict(phase=phase, value=value, screenshot=Path(screenshot).name,
                texts=[t for t in texts if t], selected=[n.get('content-desc') or n.get('text')
                                                        for n in nodes if n.get('selected') == 'true'])


if __name__ == '__main__':
    print(json.dumps(check(*sys.argv[1:]), sort_keys=True))
