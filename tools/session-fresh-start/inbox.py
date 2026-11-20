#!/usr/bin/env python3
import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET


def session_rows(xml_path, session):
    root = ET.parse(xml_path).getroot()
    matches = []
    for node in root.iter('node'):
        text = (node.get('text') or '').strip()
        if text != session:
            continue
        bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds') or '')
        if not bounds:
            continue
        top, bottom = int(bounds[2]), int(bounds[4])
        if bottom <= top:
            continue
        matches.append(dict(text=text, bounds=node.get('bounds'), top=top))
    return matches


def main(argv):
    parser = argparse.ArgumentParser(description='Count enabled inbox rows for one tmux session name.')
    parser.add_argument('xml')
    parser.add_argument('session')
    arguments = parser.parse_args(argv[1:])
    matches = session_rows(arguments.xml, arguments.session)
    if len(matches) != 1:
        print(f'FRESH_START_INBOX_ROW_COUNT: expected exactly one row for {arguments.session}, got {len(matches)}', file=sys.stderr)
        for row in matches:
            print(row, file=sys.stderr)
        return 1
    print(json.dumps(matches[0]))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))