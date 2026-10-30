#!/usr/bin/env python3
import os
import sys
import termios
import tty
from pathlib import Path

if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('HOME') != '/home/verifyssh' or os.getuid() == 0:
    raise SystemExit('Disposable GitHub fixture required')
raw = Path('/home/verifyssh/canary-raw.bin')
receipts = Path('/home/verifyssh/canary-receipts.txt')
ready = Path('/home/verifyssh/canary-ready')
if any(path.exists() for path in (raw, receipts, ready)):
    raise SystemExit('Canary files already exist')
original = termios.tcgetattr(0)
try:
    tty.setraw(0)
    with raw.open('xb', buffering=0) as byte_log, receipts.open('x', buffering=1) as line_log:
        ready.write_text('CANARY_READY\n')
        os.write(1, b'CANARY_SCREEN_IGSH1230\r\n')
        pending = bytearray()
        while True:
            data = os.read(0, 4096)
            if not data:
                break
            byte_log.write(data)
            for byte in data:
                if byte in (10, 13):
                    line = bytes(pending).replace(b'\x1b[200~', b'').replace(b'\x1b[201~', b'')
                    line_log.write('CANARY_RECEIVED:' + line.decode('utf-8', 'backslashreplace') + '\n')
                    os.write(1, b'CANARY_LINE_RECORDED\r\n')
                    pending.clear()
                else:
                    pending.append(byte)
finally:
    termios.tcsetattr(0, termios.TCSADRAIN, original)
