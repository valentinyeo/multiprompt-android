#!/usr/bin/env python3
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image


def bounds(node):
    box = list(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
    assert len(box) == 4 and box[0] < box[2] and box[1] < box[3], box
    return box


def covered(boxes, x, y):
    return any(a <= x <= c and b <= y <= d for a, b, c, d in boxes)


def outlines(image, box, inset, exclude=()):
    x1, y1, x2, y2 = box
    assert 0 <= x1 < x2 <= image.width and 0 <= y1 < y2 <= image.height, 'Pill is off screen'
    margin = max(3, (y2 - y1) // 10) if inset else 0
    lines = []
    for y in range(y1 + margin, y2 - margin):
        start = None
        for x in range(x1 + margin, x2 - margin + 1):
            pixel = image.getpixel((x, y)) if x < x2 - margin else None
            grey = pixel is not None and all(abs(channel - 46) <= 2 for channel in pixel) and not covered(exclude, x, y)
            if grey and start is None:
                start = x
            if not grey and start is not None:
                if x - start >= 24:
                    lines.append((start, x, y))
                start = None
    pairs = [(top, bottom) for top in lines for bottom in lines
             if 12 <= bottom[2] - top[2] <= y2 - y1
             and abs(top[0] - bottom[0]) <= 3 and abs(top[1] - bottom[1]) <= 3]
    return pairs


def picker_swipe(xml):
    root = ET.parse(xml).getroot()
    parents = {child: parent for parent in root.iter() for child in parent}
    windows = [node for node in root if node.tag == 'node' and node.get('visible-to-user') != 'false']
    assert windows, 'Active window is missing'
    window = windows[-1]
    labels = [node for node in window.iter('node') if node.get('text', '').startswith('/pill-guide-')
              and node.get('package') == 'dev.multiprompt.companion.debug']
    assert labels, 'Visible skill guide is missing'
    node = labels[0]
    while node is not window and node.get('scrollable') != 'true':
        assert node.get('enabled') != 'false', 'Picker is disabled'
        node = parents[node]
    assert node is not window and node.get('class') == 'android.widget.ScrollView', 'Skill picker scroll area is missing'
    x1, y1, x2, y2 = bounds(node)
    wx1, wy1, wx2, wy2 = bounds(window)
    assert wx1 <= x1 < x2 <= wx2 and wy1 <= y1 < y2 <= wy2, 'Skill picker is clipped'
    assert node.get('enabled') != 'false' and y2 - y1 >= 20
    return (x1 + x2) // 2, y2 - (y2 - y1) // 5, (x1 + x2) // 2, y1 + (y2 - y1) // 5


def check(xml, screenshot, phase, text):
    nodes = [node for node in ET.parse(xml).getroot().iter('node')
             if node.get('package') == 'dev.multiprompt.companion.debug']
    assert nodes, 'Chat UI is missing'
    image = Image.open(screenshot).convert('RGB')
    buttons = [bounds(node) for node in nodes if node.get('class') == 'android.widget.Button']
    editors = [node for node in nodes if node.get('class') == 'android.widget.EditText']
    assert len(editors) == 1, 'Expected one composer'
    editor = editors[0]
    result = dict(screenshot=Path(screenshot).name, text=text)
    if phase in ('sent', 'sent-removed'):
        assert editor.get('text') in ('', 'Message verify-session'), 'Send did not clear the composer'
        messages = [node for node in nodes if node.get('class') != 'android.widget.EditText'
                    and node.get('text') == text]
        assert messages, 'Exact sent message is missing'
        if phase == 'sent':
            pills = [child for message in messages for child in message.iter('node')
                     if child.get('text') == '/review-native']
            assert len(pills) == 1, 'Sent message must contain one inline skill pill'
            text_bounds = bounds(pills[0])
            message_bounds = bounds(next(message for message in messages if pills[0] in message.iter('node')))
            height = text_bounds[3] - text_bounds[1]
            pad_x, pad_y = (height + 1) // 2, (height + 15) // 16
            pill_bounds = [max(message_bounds[0], text_bounds[0] - pad_x),
                           max(message_bounds[1], text_bounds[1] - pad_y),
                           min(message_bounds[2], text_bounds[2] + pad_x),
                           min(message_bounds[3], text_bounds[3] + pad_y)]
            assert outlines(image, pill_bounds, False, buttons), 'Sent skill pill outline is missing'
            result.update(sent_pill='/review-native', sent_pill_bounds=pill_bounds)
    else:
        assert editor.get('text') == text, ('Wrong composer text', editor.get('text'), text)
        if phase in ('picker', 'scrolled'):
            label = '/pill-guide-01' if phase == 'picker' else '/review-native'
            assert any(node.get('text') == label for node in nodes), 'Skill picker is missing'
        else:
            pairs = outlines(image, bounds(editor), True, buttons)
            expected = phase in ('inserted', 'removable')
            assert bool(pairs) == expected, 'Composer skill pill outline is missing or survived removal'
            result['composer_pill'] = expected
    return result


if __name__ == '__main__':
    if sys.argv[1] == 'scroll':
        print(*picker_swipe(Path(sys.argv[2])))
        raise SystemExit(0)
    xml, screenshot, phase, text, output = sys.argv[1:]
    result = check(Path(xml), Path(screenshot), phase, text)
    path = Path(output)
    states = json.loads(path.read_text()) if path.exists() else {}
    states[phase] = result
    path.write_text(json.dumps(states, indent=2) + '\n')
    print(json.dumps(result, sort_keys=True))
