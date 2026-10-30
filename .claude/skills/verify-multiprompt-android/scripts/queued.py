#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET

xml, expected, name = sys.argv[1], int(sys.argv[2]), sys.argv[3]
root = ET.parse(xml).getroot()
parents = {child: parent for parent in root.iter() for child in parent}
labels = [n for n in root.iter('node') if n.get('text') == 'Queued']
assert len(labels) == expected, f'{name}: expected {expected} Queued labels, found {len(labels)}'
phases = {
    'QUEUE_WAITING': (('WAITING_ONE', 'Queued'), ('WAITING_TWO', 'Queued')),
    'QUEUE_SUBMITTED': (('WAITING_ONE', 'Sending'), ('WAITING_TWO', 'Queued')),
    'QUEUE_FIRST_DELIVERED': (('WAITING_ONE', 'Delivered'), ('WAITING_TWO', 'Sending')),
    'QUEUE_BOTH_DELIVERED': (('WAITING_ONE', 'Delivered'), ('WAITING_TWO', 'Delivered')),
    'FOLLOWUP_SUBMITTED': (('FOLLOWUP', 'Sending'),),
    'FOLLOWUP_DELIVERED': (('FOLLOWUP', 'Delivered'),),
    'UNCERTAIN_SUBMITTED': (('UNCERTAIN_ONE', 'Sending'), ('UNCERTAIN_TWO', 'Queued')),
}
if name in phases:
    pairs = phases[name]
    for body, state in pairs:
        matches = [n for n in root.iter('node') if n.get('text') == body]
        assert len(matches) == 1, f'{name}: {body} occurs {len(matches)} times'
        node = matches[0]
        while node in parents:
            node = parents[node]
            texts = [n.get('text') for n in node.iter('node')]
            if state in texts:
                assert not any(other in texts for other, _ in pairs if other != body), f'{name}: {state} not attached to {body}'
                assert not any(other in texts for other in ('Queued', 'Sending', 'Delivered') if other != state), f'{name}: conflicting state for {body}'
                break
        else:
            raise AssertionError(f'{name}: {body} lacks {state}')
print(f'QUEUE_UI_{name}: {expected} Queued')
