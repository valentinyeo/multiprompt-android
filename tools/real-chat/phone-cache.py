#!/usr/bin/env python3
import argparse
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def load_fixture(path):
    loader = importlib.machinery.SourceFileLoader('real_chat_fixture', str(path))
    spec = importlib.util.spec_from_loader('real_chat_fixture', loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


def host(prefs):
    root = ET.parse(str(prefs)).getroot()
    value = None
    for node in root.iter('string'):
        if node.get('name') in ('profiles_json', 'hosts') and node.text:
            value = node.text
            break
    if value is None:
        raise ValueError('HOSTS_MISSING: no hosts preference in the dump')
    hosts = json.loads(value)
    if not hosts:
        raise ValueError('HOSTS_MISSING: the app has no saved host')
    chosen = hosts[0]
    for item in hosts:
        if item.get('hostname') == '10.0.2.2' and item.get('username') == 'verifyssh':
            chosen = item
            break
    return dict(id=chosen['id'], hostname=chosen['hostname'], port=str(chosen.get('port', 22)),
                username=chosen['username'])


def prepare(prefs, fixture_path, home, out_dir, session, agent):
    record = host(prefs)
    fixture = load_fixture(fixture_path)
    manifest = fixture.check(home)
    payload = fixture.cache(home, record['id'], record['hostname'], record['port'], record['username'],
                            agent, session, manifest['cwd'])
    directory = Path(out_dir)
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / payload['name']
    target.write_text('\n'.join(payload['lines']) + '\n')
    return dict(host=record, session=session, cwd=manifest['cwd'], name=payload['name'], path=str(target),
                sid=payload['sid'], rows=payload['rows'], bytes=target.stat().st_size,
                latest=manifest['markers']['reply_newest'])


def main(argv):
    parser = argparse.ArgumentParser(description='Seed an older phone cache position for the real-size chat.')
    parser.add_argument('command', choices=['host', 'prepare'])
    parser.add_argument('--prefs', required=True)
    parser.add_argument('--fixture', default=str(Path(__file__).resolve().parent / 'fixture.py'))
    parser.add_argument('--home', default=None)
    parser.add_argument('--out-dir')
    parser.add_argument('--session', default='live-real-2781')
    parser.add_argument('--agent', default='CLAUDE')
    arguments = parser.parse_args(argv[1:])
    if arguments.command == 'host':
        payload = host(arguments.prefs)
    else:
        if not arguments.out_dir:
            raise SystemExit('--out-dir is required for prepare')
        payload = prepare(arguments.prefs, arguments.fixture, arguments.home, arguments.out_dir,
                          arguments.session, arguments.agent)
    print(json.dumps(payload, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
