#!/usr/bin/env python3
import copy
import json
import re
import sys
import xml.etree.ElementTree as ET


def missing_remote(host, root):
    if host.get('provider') != 'claude' or host.get('sid') != 'a1b2c3d4-1008-4000-8000-000000001008':
        return False
    rows = host.get('rows')
    if not isinstance(rows, list) or len(rows) != 2 or not isinstance(host.get('epoch'), int):
        return False
    if not all(isinstance(row, dict) and row.get('state') == 'queued' and row.get('epoch') == host['epoch']
               and isinstance(row.get('id'), str) and row['id'].strip()
               and isinstance(row.get('attempt'), str) and row['attempt'].strip() for row in rows):
        return False
    if len({row['id'] for row in rows}) != 2:
        return False
    if len([row for row in rows if row.get('body') == 'SHARED_PHONE_SYNTHETIC']) != 1:
        return False
    if len([row for row in rows if row.get('body') == 'SHARED_DESKTOP_SYNTHETIC'
            and row['id'] == 'fixturedesktop000000000000000001']) != 1:
        return False

    frame = root.find('node')
    screen = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', frame.get('bounds', '')) if frame is not None else None
    if not screen or int(screen[3]) <= int(screen[1]) or int(screen[4]) <= int(screen[2]):
        return False
    nodes = []
    for node in root.iter('node'):
        if node.get('package') != 'dev.multiprompt.companion.debug':
            continue
        bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
        if (bounds and int(bounds[3]) > int(bounds[1]) and int(bounds[4]) > int(bounds[2])
                and int(bounds[1]) < int(screen[3]) and int(bounds[3]) > int(screen[1])
                and int(bounds[2]) < int(screen[4]) and int(bounds[4]) > int(screen[2])):
            nodes.append(node)
    texts = [node.get('text', '') for node in nodes]
    descriptions = [node.get('content-desc', '') for node in nodes]
    values = texts + descriptions
    return (any(node.get('text') == 'verify-session' and node.get('class') == 'android.widget.TextView' for node in nodes)
            and 'Session actions' in descriptions
            and 'SHARED_PHONE_SYNTHETIC' in texts
            and not any('SHARED_DESKTOP_SYNTHETIC' in value for value in values)
            and not any('Inbox' in value or 'disconnected' in value.lower() for value in values))


host = json.load(open(sys.argv[1]))
root = ET.parse(sys.argv[2]).getroot()
if not missing_remote(host, root):
    raise SystemExit('Shared remote visibility evidence rejected')
if sys.argv[3:] == ['--self-check']:
    no_second_row = copy.deepcopy(host)
    no_second_row['rows'] = [row for row in no_second_row['rows'] if row['id'] != 'fixturedesktop000000000000000001']
    inbox = copy.deepcopy(root)
    ET.SubElement(inbox, 'node', {'package': 'dev.multiprompt.companion.debug', 'text': 'Inbox', 'bounds': '[0,0][10,10]'})
    remote = copy.deepcopy(root)
    ET.SubElement(remote, 'node', {'package': 'dev.multiprompt.companion.debug', 'text': 'SHARED_DESKTOP_SYNTHETIC', 'bounds': '[0,0][10,10]'})
    if any((missing_remote(no_second_row, root), missing_remote(host, inbox), missing_remote(host, remote))):
        raise SystemExit('Shared remote visibility oracle mutation accepted')
    print('SHARED_REMOTE_ORACLE_CONTROLS_REJECTED')
print('SHARED_REMOTE_VISIBILITY_EVIDENCE_VERIFIED')
