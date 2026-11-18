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
    parents = {child: parent for parent in nodes for child in parent}

    def selected(node):
        while node.get('clickable') != 'true' and node in parents:
            node = parents[node]
        return (node.get('selected') == 'true'
                or (node.get('checkable') == 'true' and node.get('checked') == 'true'))

    texts = [n.get('text', '') for n in nodes]
    descriptions = [n.get('content-desc', '') for n in nodes]
    assert nodes or phase == 'surface', 'Missing app UI'

    def visible(node):
        bounds = list(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
        return (len(bounds) == 4 and 0 <= bounds[0] < bounds[2] <= width
                and 0 <= bounds[1] < bounds[3] <= height)

    if phase == 'surface':
        expected = json.loads(value)
        root = ET.parse(xml).getroot()
        active = list(root)[-1]
        nodes = list(active.iter('node'))
        parents = {child: parent for parent in nodes for child in parent}

        def shown(node):
            if not visible(node):
                return False
            current = node
            while current is not None:
                if current.get('visible-to-user') == 'false':
                    return False
                if current.get('scrollable') == 'true':
                    a, b, c, d = map(int, re.findall(r'-?\d+', current.get('bounds', '')))
                    x, y, right, bottom = map(int, re.findall(r'-?\d+', node.get('bounds', '')))
                    if not (a <= x < right <= c and b <= y < bottom <= d):
                        return False
                current = parents.get(current)
            return True

        for attribute, key in [('text', 'text'), ('content-desc', 'desc')]:
            for label in expected.get(key, []):
                assert any(n.get(attribute) == label and shown(n) for n in nodes), f'Missing visible {key}: {label}'
        for label in expected.get('absent', []):
            assert not any(n.get('text') == label or n.get('content-desc') == label for n in nodes), f'Unexpected surface: {label}'
        if 'field' in expected:
            fields = [n for n in nodes if n.get('class') == 'android.widget.EditText' and shown(n)]
            assert len(fields) == 1 and fields[0].get('text') == expected['field'], 'Wrong editor value'
        for label, marked in expected.get('marked', {}).items():
            matches = [n for n in nodes if n.get('text') == label and shown(n)]
            assert len(matches) == 1, f'Missing marked row: {label}'
            node = matches[0]
            while node.get('clickable') != 'true' and node in parents:
                node = parents[node]
            assert node.get('clickable') == 'true', f'Missing row action: {label}'
            assert any(n.get('text') == '✓' for n in node.iter('node')) == marked, f'Wrong checkmark: {label}'
        for label in expected.get('disabled', []):
            matches = [n for n in nodes if n.get('text') == label and shown(n)]
            assert len(matches) == 1, f'Missing disabled row: {label}'
            node = matches[0]
            while node.get('enabled') != 'false' and node in parents:
                node = parents[node]
            assert node.get('enabled') == 'false', f'Row is enabled: {label}'
        return dict(phase=phase, expected=expected, xml=Path(xml).name,
                    screenshot=Path(screenshot).name)
    if phase in ('face', 'colour', 'auto'):
        assert 'Bot look' in texts
        label = {'face': f'Face {value}', 'colour': f'Colour {value}', 'auto': 'Auto'}[phase]
        matches = [n for n in nodes if n.get('content-desc') == label or n.get('text') == label]
        assert len(matches) == 1 and visible(matches[0]), f'Choice not visible: {label}'
        assert selected(matches[0]), f'Choice not selected: {label}'
        prefix = 'Face ' if phase == 'face' else 'Colour '
        assert not any(n is not matches[0] and selected(n)
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
                                                        for n in nodes if selected(n)
                                                        and (n.get('content-desc') or n.get('text'))])


if __name__ == '__main__':
    print(json.dumps(check(*sys.argv[1:]), sort_keys=True))
