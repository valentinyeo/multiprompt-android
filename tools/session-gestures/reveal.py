import re


def list_bounds(root):
    nodes = [node for node in root.iter('node') if node.get('scrollable') == 'true']
    if len(nodes) != 1:
        raise AssertionError(f'Expected one scrollable list, found {len(nodes)}')
    numbers = list(map(int, re.findall(r'\d+', nodes[0].get('bounds', ''))))
    if len(numbers) != 4 or numbers[2] <= numbers[0] or numbers[3] <= numbers[1]:
        raise AssertionError('Empty scrollable bounds')
    return tuple(numbers)


def visible(root, text):
    return any(node.get('text') == text for node in root.iter('node'))


def reveal(dump, scroll_up, text, limit=12):
    root = dump()
    for attempt in range(limit + 1):
        if visible(root, text):
            return root
        if attempt == limit:
            break
        scroll_up(list_bounds(root))
        root = dump()
    raise AssertionError(f'Missing {text} after {limit} scrolls')
