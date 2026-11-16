#!/usr/bin/env python3
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET

PHASES = {
    'QUEUE_WAITING': (('WAITING_ONE', 'queued'), ('WAITING_TWO', 'queued')),
    'QUEUE_SUBMITTED': (('WAITING_ONE', 'sent'), ('WAITING_TWO', 'queued')),
    'QUEUE_FIRST_DELIVERED': (('WAITING_ONE', 'started'), ('WAITING_TWO', 'sent')),
    'QUEUE_ANSWERED': (('WAITING_ONE', 'started'), ('WAITING_TWO', 'started')),
    'QUEUE_BOTH_DELIVERED': (('WAITING_ONE', 'started'), ('WAITING_TWO', 'started')),
    'FOLLOWUP_SUBMITTED': (('FOLLOWUP', 'sent'),),
    'FOLLOWUP_DELIVERED': (('FOLLOWUP', 'started'),),
    'UNCERTAIN_SUBMITTED': (('UNCERTAIN_ONE', 'sent'), ('UNCERTAIN_TWO', 'queued')),
}


def verify(root, expected, name, evidence=None):
    texts = [node.get('text') for node in root.iter('node')]
    if evidence is None:
        assert texts.count('Queued') == expected, f'{name}: expected {expected} Queued labels'
        return
    pairs = PHASES[name]
    assert [(row['text'], row['state']) for row in evidence] == list(pairs), f'{name}: canonical queue state mismatch'
    assert sum(state == 'queued' for _, state in pairs) == expected
    assert not any(label in texts for label in ('Queued', 'Sending', 'Delivered', 'Not sent')), f'{name}: unexpected delivery label'
    for body, state in pairs:
        assert texts.count(body) == 1, f'{name}: {body} must stay visible exactly once while {state}'
    result = dict(phase=name, queued=expected, ids=[row['id'] for row in evidence], visible=[body for body, _ in pairs])
    if name == 'QUEUE_ANSWERED':
        assert texts.count('Both messages received.') == 1, 'QUEUE_ANSWERED: the provider answer must be visible exactly once'
        result['answer'] = 'Both messages received.'
    return result


if __name__ == '__main__':
    xml, expected, name = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    evidence = json.load(open(sys.argv[4])) if len(sys.argv) > 4 else None
    result = verify(ET.parse(xml).getroot(), expected, name, evidence)
    if len(sys.argv) > 5:
        output = Path(sys.argv[5])
        snapshot = output.with_suffix('.xml')
        shutil.copyfile(xml, snapshot)
        result['xml'] = snapshot.name
        output.write_text(json.dumps(result) + '\n')
    print('QUEUE_UI_' + name, json.dumps(result))
