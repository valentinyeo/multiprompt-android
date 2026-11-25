#!/usr/bin/env python3
import json
import re
import sys

BULLET = re.compile(r'^\s*(?:[-\u2022+*]\s+|\d+[.)]\s+)')
HEADING = re.compile(r'^#{1,6}\s*')
HTML = re.compile(r'<[^>]*>')
EMPHASIS = re.compile(r'\*\*|__|`|~~')
SPACE = re.compile(r'\s+')
TICKET_ONLY = re.compile(r'(?:IGSH|HYCI|MUBO)-\d+')
HEADERS = {"what's new", "whats new", "release notes", "release history", "changes", "changelog"}


def plain(raw):
    line = SPACE.sub(' ', EMPHASIS.sub('', HEADING.sub('', BULLET.sub('', HTML.sub(' ', raw))))).strip()
    return '' if TICKET_ONLY.fullmatch(line) else line


def changes(notes):
    lines = []
    for raw in str(notes or '').splitlines():
        line = plain(raw)
        if line and line.lower() not in HEADERS and line not in lines:
            lines.append(line)
    return lines


def preview(notes, limit=3):
    return changes(notes)[:limit]


def main(argv):
    if len(argv) != 2:
        raise SystemExit('Usage: notes.py <update-manifest.json>')
    with open(argv[1], encoding='utf-8') as stream:
        manifest = json.load(stream)
    offered = changes(manifest.get('notes'))
    print(json.dumps({
        'versionName': manifest.get('versionName'),
        'versionCode': manifest.get('versionCode'),
        'lines': offered,
        'preview': offered[:3],
    }))


if __name__ == '__main__':
    main(sys.argv)
