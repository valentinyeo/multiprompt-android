#!/usr/bin/env python3
import argparse
import base64
import hashlib
import pathlib
import sys

ASSETS = pathlib.Path('app/src/main/assets')
REVISION_FILE = ASSETS / 'chat_history_reader.sha'
PIN = pathlib.Path('app/src/androidTest/assets/chat_reader_pin.txt')
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
    parser = argparse.ArgumentParser(description='Record the desktop revision and the fingerprint of the bundled chat reader.')
    parser.add_argument('mode', choices=['write', 'check'])
    parser.add_argument('--root', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    parser.add_argument('--revision', required=True)
    args = parser.parse_args(argv)
    root = args.root
    revision_file = root / REVISION_FILE
    pin = root / PIN
    expected = {revision_file: f'{args.revision}\n', pin: pin_text(root / ASSETS, args.revision)}
    if args.mode == 'write':
        for path, text in expected.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8')
            sys.stdout.write(f'{path} now holds the pin for {args.revision}\n')
        return 0
    stale = []
    for path, text in expected.items():
        actual = path.read_text(encoding='utf-8') if path.exists() else ''
        if actual != text:
            stale.append(f'{path}\nexpected:\n{text}actual:\n{actual}')
    if stale:
        sys.stderr.write('The bundled reader pin is stale.\nRun tools/sync-chat-reader.sh to regenerate it.\n' + '\n'.join(stale))
        return 1
    sys.stdout.write('The bundled reader pin matches the bundled assets\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
