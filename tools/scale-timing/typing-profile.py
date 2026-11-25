#!/usr/bin/env python3
from collections import Counter
from pathlib import Path
import struct
import sys


def report(path):
    data = Path(path).read_bytes()
    split = data.index(b'*end\n') + len(b'*end\n')
    header = data[:split].decode()
    methods = {}
    threads = {}
    section = ''
    for line in header.splitlines():
        if line.startswith('*'):
            section = line
        elif section == '*methods':
            cells = line.split('\t')
            methods[int(cells[0], 16)] = ' '.join(cells[1:])
        elif section == '*threads':
            tid, name = line.split('\t', 1)
            threads[int(tid)] = name
    magic, version, offset = struct.unpack_from('<4sHH', data, split)
    if magic != b'SLOW' or version != 3 or 'clock=dual' not in header:
        raise SystemExit('TYPING_PROFILE_UNSUPPORTED: expected version 3 dual clock')
    size = struct.unpack_from('<H', data, split + 16)[0]
    main = next(tid for tid, name in threads.items() if name == 'main')
    stack = []
    previous = None
    exclusive = Counter()
    inclusive = Counter()
    for position in range(split + offset, len(data) - size + 1, size):
        tid, method, cpu, wall = struct.unpack_from('<HIII', data, position)
        if tid != main:
            continue
        if previous is not None and cpu >= previous:
            delta = cpu - previous
            if stack:
                exclusive[stack[-1]] += delta
                for active in set(stack):
                    inclusive[active] += delta
        previous = cpu
        identifier, action = method & ~3, method & 3
        if action == 0:
            stack.append(identifier)
        elif identifier in stack:
            del stack[len(stack) - 1 - stack[::-1].index(identifier):]
    print(f'## Separate Main sampled profile: {path}\n')
    for title, totals in [('Exclusive', exclusive), ('Inclusive', inclusive)]:
        print(f'### {title} CPU ms\n')
        for method, microseconds in totals.most_common(30):
            print(f'{microseconds / 1000:.3f} {methods.get(method, hex(method))}')
        print('\nApp methods\n')
        for method, microseconds in [(method, value) for method, value in totals.most_common()
                                     if methods.get(method, '').startswith('dev.multiprompt.')][:20]:
            print(f'{microseconds / 1000:.3f} {methods[method]}')


if __name__ == '__main__':
    report(sys.argv[1])
