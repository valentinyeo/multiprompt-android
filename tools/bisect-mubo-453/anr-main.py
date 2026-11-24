#!/usr/bin/env python3
"""Print the main thread block of every ANR trace that names the app."""
import sys
import zipfile
from pathlib import Path

PACKAGE_HINT = 'multiprompt'


def texts(path):
    """Every text file under a capture directory, bugreport zips included."""
    if path.is_dir():
        for item in sorted(path.rglob('*')):
            if item.is_file():
                yield from texts(item)
        return
    if path.suffix == '.zip':
        try:
            with zipfile.ZipFile(path) as archive:
                for name in archive.namelist():
                    if name.endswith('.txt'):
                        yield path / name, archive.read(name).decode('utf-8', 'replace')
        except zipfile.BadZipFile:
            return
        return
    try:
        yield path, path.read_text(encoding='utf-8', errors='replace')
    except OSError:
        return


def pid_blocks(text):
    pid, cmdline, block = None, '', []
    for line in text.splitlines():
        if line.startswith('----- pid '):
            if pid is not None:
                yield pid, cmdline, block
            parts = line.split()
            pid, cmdline, block = parts[2], '', []
            continue
        if pid is None:
            continue
        block.append(line)
        if not cmdline and line.startswith('Cmd line: '):
            cmdline = line[len('Cmd line: '):].strip()
    if pid is not None:
        yield pid, cmdline, block


def main_thread(lines):
    frames, inside = [], False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith('"main"'):
            inside = True
        elif inside and (stripped.startswith('"') or line.startswith('----- ')):
            break
        if inside:
            frames.append(line)
    while frames and not frames[-1].strip():
        frames.pop()
    return frames


def wanted(cmdline, package):
    target = package or PACKAGE_HINT
    return target in cmdline


def main(argv):
    root = Path(argv[0])
    package = argv[1] if len(argv) > 1 else ''
    seen = 0
    printed = 0
    if not root.exists():
        print(f'ANR_MAIN missing={root}')
        return 0
    for name, text in texts(root):
        if 'Cmd line: ' not in text:
            continue
        for pid, cmdline, block in pid_blocks(text):
            seen += 1
            if not wanted(cmdline, package):
                continue
            frames = main_thread(block)
            if not frames:
                continue
            printed += 1
            print(f'TRACE {name} pid={pid} cmdline={cmdline}')
            for line in frames:
                print(line)
            print('')
    print(f'ANR_MAIN blocks={seen} matched={printed} dir={root}')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
