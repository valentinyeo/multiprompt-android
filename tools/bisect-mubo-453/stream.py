#!/usr/bin/env python3
import datetime
import json
import sys
import time
from pathlib import Path

PREAMBLE = 'SPIKE_STREAM'
CHUNK = 'a growing streaming reply body written by the bisect probe '


def append(path, sid, identity, step):
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    text = f'{PREAMBLE}_STEP_{step} ' + CHUNK * (step + 1)
    event = dict(type='assistant', uuid=identity, sessionId=sid, timestamp=stamp,
                 message=dict(role='assistant', id=identity, content=[dict(type='text', text=text)]))
    with path.open('a') as output:
        output.write(json.dumps(event) + '\n')
        output.flush()


def main(argv):
    source = json.loads(Path(argv[0]).read_text())
    interval = float(argv[1]) if len(argv) > 1 else 1.0
    count = int(argv[2]) if len(argv) > 2 else 10
    path = Path(source['path'])
    sid = source['sid']
    identity = f'spike-{time.time_ns()}'
    print(f'SPIKE_STREAM_ID {identity}', flush=True)
    started = time.monotonic()
    for step in range(count):
        append(path, sid, identity, step)
        print(f'WROTE {PREAMBLE}_STEP_{step} {time.monotonic() - started:.3f}', flush=True)
        if step + 1 < count:
            time.sleep(interval)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
