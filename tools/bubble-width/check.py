import json
from pathlib import Path
import sys
from PIL import Image


def measure(directory, phase):
    directory = Path(directory)
    data = json.loads((directory / 'nodes.json').read_text())
    image = Image.open(directory / 'chat.png').convert('RGB')
    assert image.width == data['screenWidth'] == 1080
    density = data['density']
    widths = {}
    edges = {}
    for text, bounds in data['text'].items():
        role = 'agent' if text.startswith('Agent:') or text == 'Ready' else 'user'
        size = 'short' if text in ('Ready', 'Thanks') else 'long'
        y = (bounds['top'] + bounds['bottom']) // 2
        assert 0 < y < image.height
        center = (bounds['left'] + bounds['right']) // 2
        left, right = center, center
        while left > 0 and image.getpixel((left - 1, y)) != (16, 16, 16):
            left -= 1
        while right < image.width and image.getpixel((right, y)) != (16, 16, 16):
            right += 1
        assert left < bounds['left'] < bounds['right'] < right, (text, left, right, bounds)
        widths[role + '-' + size] = right - left
        edges[role + '-' + size] = [left, right]
    chat = image.width - 2 * round(12 * density)
    for role in ('agent', 'user'):
        expected = (0.85 if role == 'agent' else 0.7) if phase == 'before' else 0.9
        assert abs(widths[role + '-long'] / chat - expected) < 0.006, ('WIDTH_MAXIMUM_MISMATCH', role, widths, chat)
        if phase == 'before' and role == 'agent':
            assert widths[role + '-short'] == widths[role + '-long']
        else:
            assert widths[role + '-short'] < widths[role + '-long'] / 2
        edge = 0 if role == 'agent' else 1
        assert abs(edges[role + '-long'][edge] - edges[role + '-short'][edge]) <= 2
    result = dict(phase=phase, chatWidth=chat, widths=widths, edges=edges)
    (directory / 'measurements.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result))
    print('ORIGINAL_WIDTH_REJECTED' if phase == 'before' else 'CHAT_BUBBLE_WIDTH_OK')


if __name__ == '__main__':
    measure(sys.argv[1], sys.argv[2])
