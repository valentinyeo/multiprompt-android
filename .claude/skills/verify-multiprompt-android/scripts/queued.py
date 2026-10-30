#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET

xml, expected, name = sys.argv[1], int(sys.argv[2]), sys.argv[3]
root = ET.parse(xml).getroot()
parents = {child: parent for parent in root.iter() for child in parent}
labels = [n for n in root.iter('node') if n.get('text') == 'Queued']
assert len(labels) == expected, f'{name}: expected {expected} Queued labels, found {len(labels)}'
if name in ('QUEUE_WAITING', 'QUEUE_FIRST_DELIVERED', 'QUEUE_BOTH_DELIVERED'):
    states = {'QUEUE_WAITING': ('Queued', 'Queued'), 'QUEUE_FIRST_DELIVERED': ('Delivered', 'Queued'), 'QUEUE_BOTH_DELIVERED': ('Delivered', 'Delivered')}[name]
    for body, state in zip(('WAITING_ONE', 'WAITING_TWO'), states):
        matches = [n for n in root.iter('node') if n.get('text') == body]
        assert len(matches) == 1, f'{name}: {body} occurs {len(matches)} times'
        node = matches[0]
        while node in parents:
            node = parents[node]
            texts = [n.get('text') for n in node.iter('node')]
            if state in texts:
                assert not any(other in texts for other in ('WAITING_ONE', 'WAITING_TWO') if other != body), f'{name}: {state} not attached to {body}'
                break
        else:
            raise AssertionError(f'{name}: {body} lacks {state}')
print(f'QUEUE_UI_{name}: {expected} Queued')
