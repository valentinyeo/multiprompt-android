#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

HOME = Path('/home/verifyssh')
MANIFEST = 'scale-chat.json'
RELAY = HOME / 'scale-relay.py'
RELAY_LOG = HOME / 'scale-relay.jsonl'
COMMAND_PREFIX = re.compile(r'^command="[^"]*"\s*')


def verifyssh(*args, check=True):
    return subprocess.run(['sudo', '-u', 'verifyssh', 'env', 'HOME=' + str(HOME), *map(str, args)],
                          capture_output=True, text=True, check=check)


def tmux(*args, check=True):
    return verifyssh('tmux', *args, check=check)


def install(source, destination, mode='755'):
    subprocess.run(['sudo', 'install', '-m', mode, str(source), str(destination)], check=True)


def authorized_key():
    keys = []
    for line in (HOME / '.ssh/authorized_keys').read_text().splitlines():
        stripped = COMMAND_PREFIX.sub('', line).strip()
        if stripped:
            keys.append(stripped)
    if not keys:
        raise SystemExit('SCALE_SEED_NO_KEY: the fixture user has no authorized key to wrap')
    return keys


def wrap_authorized_keys():
    lines = ''.join('command="%s" %s\n' % (RELAY, key) for key in authorized_key())
    path = HOME / '.ssh/authorized_keys'
    subprocess.run(['sudo', 'tee', str(path)], input=lines, text=True, capture_output=True, check=True)
    subprocess.run(['sudo', 'chown', 'verifyssh:verifyssh', str(path)], check=True)
    subprocess.run(['sudo', 'chmod', '600', str(path)], check=True)
    return len(lines.splitlines())


def generate(fixture):
    install(fixture, HOME / 'scale-chat-fixture.py', '644')
    result = verifyssh(sys.executable, '-B', str(HOME / 'scale-chat-fixture.py'), 'seed')
    if result.returncode != 0:
        raise SystemExit('SCALE_SEED_GENERATE_FAILED: ' + result.stderr.strip()[-800:])
    check = verifyssh(sys.executable, '-B', str(HOME / 'scale-chat-fixture.py'), 'check')
    if check.returncode != 0:
        raise SystemExit('SCALE_SEED_CHECK_FAILED: ' + check.stderr.strip()[-800:])
    return json.loads(check.stdout)


def sessions(manifest):
    names = set()
    for chat in manifest['chats']:
        session = chat['session']
        if session in names:
            raise SystemExit('SCALE_SEED_DUPLICATE_SESSION: ' + session)
        names.add(session)
        if tmux('has-session', '-t', '=' + session, check=False).returncode == 0:
            continue
        created = tmux('new-session', '-d', '-s', session, '-x', '100', '-y', '32', '-c', chat['cwd'],
                       'exec sleep infinity', check=False)
        if created.returncode != 0:
            raise SystemExit('SCALE_SEED_SESSION_FAILED: %s: %s' % (session, created.stderr.strip()[-400:]))
        tmux('set-option', '-t', '=' + session + ':', '@mp_host_sid', chat['chat'])
        tmux('set-window-option', '-t', '=' + session + ':', 'automatic-rename', 'off')
        tmux('rename-window', '-t', '=' + session + ':', session)
    listed = set(tmux('list-sessions', '-F', '#{session_name}').stdout.split())
    missing = sorted(name for name in names if name not in listed)
    if missing:
        raise SystemExit('SCALE_SEED_SESSION_MISSING: ' + ', '.join(missing))
    for chat in manifest['chats']:
        option = tmux('show-options', '-t', chat['session'], '-v', '@mp_host_sid').stdout.strip()
        if option != chat['chat']:
            raise SystemExit('SCALE_SEED_IDENTITY: %s has %r, expected %r' % (chat['session'], option, chat['chat']))
    return len(names)


def install_relay(source):
    install(source, RELAY, '755')
    wrapped = wrap_authorized_keys()
    subprocess.run(['sudo', 'truncate', '-s', '0', str(RELAY_LOG)], capture_output=True)
    return dict(relay=str(RELAY), authorized_keys=wrapped, log=str(RELAY_LOG))


def write(path, payload):
    if path:
        Path(path).write_text(json.dumps(payload, indent=2) + '\n')
    print(json.dumps(payload, indent=2))
    return 0


def main(argv):
    parser = argparse.ArgumentParser(description='Seed the fixture host with the owner-scale synthetic chats and the reader relay.')
    commands = parser.add_subparsers(dest='command', required=True)
    seed = commands.add_parser('seed')
    seed.add_argument('--fixture', required=True)
    seed.add_argument('--relay', required=True)
    seed.add_argument('--out')
    relay = commands.add_parser('relay')
    relay.add_argument('--relay', required=True)
    relay.add_argument('--out')
    arguments = parser.parse_args(argv[1:])
    if arguments.command == 'relay':
        return write(arguments.out, install_relay(arguments.relay))
    manifest = generate(arguments.fixture)
    seeded = sessions(manifest)
    payload = dict(sessions=seeded, transcripts=manifest['transcript_target'], measured=manifest['measured'],
                   manifest=str(HOME / MANIFEST), **install_relay(arguments.relay))
    return write(arguments.out, payload)


if __name__ == '__main__':
    sys.exit(main(sys.argv))
