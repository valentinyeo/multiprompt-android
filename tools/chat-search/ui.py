#!/usr/bin/env python3
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image


def check(xml, screenshot, query, count, target=''):
    root = ET.parse(xml).getroot()
    nodes = [node for node in root.iter('node') if node.get('package') == 'dev.multiprompt.companion.debug']
    assert nodes, 'Chat UI is missing'
    texts = [node.get('text', '') for node in nodes]
    descriptions = [node.get('content-desc', '') for node in nodes]
    image = Image.open(screenshot).convert('RGB')
    assert image.width >= 100 and image.height >= 100
    if count == 'closed':
        assert 'Session actions' in descriptions
        assert 'Close search' not in descriptions
        assert not any(node.get('resource-id') == 'chat-search' for node in nodes)
        assert 'Message verify-session' in texts
    else:
        assert count in texts, f'Missing match count: {count}'
        assert all(label in descriptions for label in ('Close search', 'Previous match', 'Next match'))
        editors = [node for node in nodes if node.get('class') == 'android.widget.EditText']
        assert any(node.get('text') == query or (not query and node.get('text') == 'Search this chat') for node in editors), 'Wrong search query'
    result = dict(query=query, count=count, target=target)
    if target:
        matches = [node for node in nodes if node.get('text') == target]
        assert len(matches) == 1, f'Missing or ambiguous search destination: {target}'
        bounds = list(map(int, re.findall(r'-?\d+', matches[0].get('bounds', ''))))
        assert len(bounds) == 4
        x1, y1, x2, y2 = bounds
        assert 0 <= x1 < x2 <= image.width and 0 <= y1 < y2 <= image.height, 'Search destination is off screen'
        crop = image.crop(tuple(bounds))
        longest = 0
        bright = 0
        for y in range(crop.height):
            run = 0
            for x in range(crop.width):
                pixel = crop.getpixel((x, y))
                lit = all(abs(channel - 242) <= 3 for channel in pixel)
                bright += int(lit)
                run = run + 1 if lit else 0
                longest = max(longest, run)
        fraction = bright / (crop.width * crop.height)
        assert fraction > 0.22 and longest >= max(24, crop.width * 0.35), 'Active match highlight is missing'
        result.update(bounds=bounds, highlight_fraction=round(fraction, 3), highlight_run=longest)
    return result


if __name__ == '__main__':
    xml, screenshot, query, count, *target = sys.argv[1:]
    print(json.dumps(check(Path(xml), Path(screenshot), query, count, *target), sort_keys=True))
