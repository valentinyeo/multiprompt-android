#!/usr/bin/env python3
import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
AVATAR = 'Robot '


def row_bounds(node, parents):
    row, disabled = node, node.get('enabled') == 'false'
    while row is not None and row.get('clickable') != 'true':
        row = parents.get(row)
        if row is not None and row.get('enabled') == 'false':
            disabled = True
    if row is None:
        row = node
    if disabled:
        return None
    bounds = BOUNDS.fullmatch(row.get('bounds') or '')
    if not bounds:
        return None
    top, bottom = int(bounds.group(2)), int(bounds.group(4))
    if bottom <= top:
        return None
    return dict(bounds=row.get('bounds'), top=top)


def scan(xml_path, session):
    root = ET.parse(xml_path).getroot()
    parents = {child: parent for parent in root.iter('node') for child in parent}
    wanted = AVATAR + session
    matches, others = [], []
    for node in root.iter('node'):
        description = (node.get('content-desc') or '').strip()
        if not description.startswith(AVATAR.strip()):
            continue
        found = row_bounds(node, parents)
        if found is None:
            continue
        entry = dict(session=session, description=description, **found)
        if description == wanted:
            matches.append(entry)
        else:
            others.append(entry)
    return matches, others


def session_rows(xml_path, session):
    return scan(xml_path, session)[0]


def main(argv):
    parser = argparse.ArgumentParser(description='Count enabled inbox rows for one tmux session name.')
    parser.add_argument('xml')
    parser.add_argument('session')
    arguments = parser.parse_args(argv[1:])
    matches, others = scan(arguments.xml, arguments.session)
    if len(matches) != 1:
        print(f'FRESH_START_INBOX_ROW_COUNT: expected exactly one row for {arguments.session}, got {len(matches)}', file=sys.stderr)
        for row in matches:
            print(row, file=sys.stderr)
        return 1
    if others:
        print(f'FRESH_START_INBOX_EXTRA_ROWS: expected only {arguments.session}, also saw {others}', file=sys.stderr)
        return 1
    print(json.dumps(matches[0]))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
