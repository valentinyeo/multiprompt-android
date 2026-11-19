#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fixture


def runtime(source, home):
    namespace = {'__name__': 'chat_shapes_probe'}
    exec(compile(Path(source).read_text(), str(source), 'exec'), namespace)
    reader = {}
    exec(namespace['READER_SOURCE'], reader)
    reader['HOME'] = Path(home)
    return reader


def visible_rows(reader, path):
    texts = []
    with Path(path).open('rb') as stream:
        base, end, data = reader['read_tail'](stream, Path(path).stat().st_size, 'claude')
    if isinstance(data, str):
        data = data.encode()
    for raw in data.split(b'\n'):
        if not raw.strip():
            continue
        try:
            event = json.loads(raw)
        except ValueError:
            continue
        for row in reader['conversation_rows'](event, 'claude', base):
            if row.get('role') in ('user', 'assistant') and row.get('text'):
                texts.append(row['text'])
    return texts


def probe(source, home):
    home = Path(home)
    manifest = fixture.check(home)
    reader = runtime(source, home)
    results = []
    for index, item in enumerate(manifest['shapes'], start=1):
        query = dict(id=index, provider='claude', codex=False, sid=item['sid'], tmux=item['session'],
                     cwd=item['cwd'], follow=False)
        agent, sid, path, wire = reader['resolve_response_path'](dict(query))
        texts = visible_rows(reader, path) if path is not None else []
        results.append(dict(shape=item['shape'], session=item['session'], sid=sid,
                            transcript=Path(path).name if path is not None else None,
                            latest=item['latest'], stale=item['stale'],
                            visible=texts, wire=(wire or '').splitlines()[:1]))
    return dict(reader=str(source), home=str(home), shapes=results)


def main(argv):
    parser = argparse.ArgumentParser(description='Print the transcript a pinned bundled reader picks for each seeded chat shape.')
    parser.add_argument('--reader', required=True, help='chat_history_reader.py source to run, for example a pinned revision')
    parser.add_argument('--home', required=True)
    arguments = parser.parse_args(argv[1:])
    print(json.dumps(probe(arguments.reader, arguments.home), indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
