#!/usr/bin/env python3
"""Stamp each new line of a log file with the time it appeared.

The fixture sshd writes `Accepted publickey` lines to its own log without a
timestamp, so a login can only be placed in a measured window by recording when
the line showed up. This tailer writes a copy of the fixture sshd log with one
UTC timestamp in front of every line that appears after it starts.
"""
import argparse
import os
import sys
import time
from datetime import datetime, timezone

CHUNK = 65536
POLL = 0.1


def stamp(now=None):
    moment = time.time() if now is None else now
    return datetime.fromtimestamp(moment, timezone.utc).isoformat()


def follow(source, sink, poll=POLL, should_stop=None):
    """Copy every line appended after this call, stamped with the time it appeared."""
    source.seek(0, os.SEEK_END)
    pending = b''
    while should_stop is None or not should_stop():
        chunk = source.read(CHUNK)
        if not chunk:
            time.sleep(poll)
            continue
        pending += chunk
        *lines, pending = pending.split(b'\n')
        for line in lines:
            sink.write('%s %s\n' % (stamp(), line.decode('utf-8', 'replace').rstrip('\r')))
        if lines:
            sink.flush()


def main(argv):
    parser = argparse.ArgumentParser(description='Stamp new lines of a log file with the time they appeared.')
    parser.add_argument('--log', required=True, help='log file to follow')
    parser.add_argument('--out', required=True, help='stamped copy to write')
    parser.add_argument('--poll', type=float, default=POLL)
    arguments = parser.parse_args(argv[1:])
    with open(arguments.log, 'rb') as source, open(arguments.out, 'w', encoding='utf-8', buffering=1) as sink:
        follow(source, sink, arguments.poll)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv))
    except KeyboardInterrupt:
        sys.exit(0)
