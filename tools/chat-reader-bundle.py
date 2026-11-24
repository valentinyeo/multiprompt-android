#!/usr/bin/env python3
import argparse
import base64
import hashlib
import pathlib
import sys

ASSETS = pathlib.Path('app/src/main/assets')
PIN = 'chat_history_reader.sha'
PREFIX = (
    "CONNECTOR_SOURCES = {{'claude':'{claude}','codex':'{codex}'}}\n"
    "MP_SEND_SOURCE = '{mp_send}'\n"
    "FEEDBACK_SOURCE = '{feedback}'\n"
)


def encoded(assets, name):
    return base64.b64encode((assets / name).read_bytes()).decode('ascii')


def bundle(assets):
    prefix = PREFIX.format(
        claude=encoded(assets, 'chat_connectors/claude.py'),
        codex=encoded(assets, 'chat_connectors/codex.py'),
        mp_send=encoded(assets, 'mp_send.py'),
        feedback=encoded(assets, 'multibot_feedback.py'),
    )
    return prefix.encode('utf-8') + (assets / 'chat_history_reader.py').read_bytes()


def pin_text(assets, revision):
    data = bundle(assets)
    return f'{revision}\n{len(data)} {hashlib.sha256(data).hexdigest()}\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description='Pin the bundled chat reader to the desktop revision and record its fingerprint.')
    parser.add_argument('mode', choices=['write', 'check'])
    parser.add_argument('--root', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    parser.add_argument('--revision', required=True)
    args = parser.parse_args(argv)
    assets = args.root / ASSETS
    pin = assets / PIN
    expected = pin_text(assets, args.revision)
    if args.mode == 'write':
        pin.write_text(expected, encoding='utf-8')
        sys.stdout.write(f'{pin} holds {args.revision} and the bundle fingerprint\n')
        return 0
    actual = pin.read_text(encoding='utf-8') if pin.exists() else ''
    if actual != expected:
        sys.stderr.write(f'{pin} is stale for the bundled reader.\nRun tools/sync-chat-reader.sh to regenerate it.\nexpected:\n{expected}actual:\n{actual}')
        return 1
    sys.stdout.write(f'{pin} matches the bundled reader fingerprint\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
