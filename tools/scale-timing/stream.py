#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import time

ROWS = 40
INTERVAL = 0.5


def stream(path, repetition):
    path = Path(path)
    checkpoint = path.with_suffix('.frozen-size')
    if checkpoint.exists():
        raise ValueError('BENCHMARK_STREAM_NOT_RESTORED')
    size = path.stat().st_size
    with path.open('rb') as source:
        source.seek(max(0, size - 65536))
        last = json.loads(source.read().splitlines()[-1])
    sid = last['sessionId']
    checkpoint.write_text(str(size))
    started = time.monotonic()
    written = 0
    with path.open('ab', buffering=0) as output:
        for index in range(ROWS):
            marker = f'FROZEN_STREAM_{repetition}_{index:03d}'
            identity = f'f5550000-0000-4000-{repetition:04d}-{index:012d}'
            row = dict(type='assistant', uuid=identity, sessionId=sid,
                       timestamp=f'2026-11-25T09:00:{index:02d}.000Z',
                       message=dict(role='assistant', id='msg_' + identity, content=[dict(
                           type='text', text=marker + ' Synthetic streaming reply. ' * 16)]))
            encoded = (json.dumps(row) + '\n').encode()
            output.write(encoded)
            written += len(encoded)
            time.sleep(max(0, started + (index + 1) * INTERVAL - time.monotonic()))
    return dict(rows=ROWS, bytes=written, seconds=round(time.monotonic() - started, 3), marker=marker)


def restore(path):
    path = Path(path)
    checkpoint = path.with_suffix('.frozen-size')
    with path.open('r+b') as output:
        output.truncate(int(checkpoint.read_text()))
    checkpoint.unlink()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Append assistant output at 2 Hz for 20 seconds on the fixture host.')
    parser.add_argument('--transcript', required=True)
    parser.add_argument('--repetition', type=int, default=1)
    parser.add_argument('--restore', action='store_true')
    args = parser.parse_args()
    if args.restore:
        restore(args.transcript)
    else:
        print(json.dumps(stream(args.transcript, args.repetition)))
