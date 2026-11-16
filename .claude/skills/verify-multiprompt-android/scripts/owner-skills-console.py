#!/usr/bin/env python3
import sys
from pathlib import Path


def rows(path):
    file = Path(path)
    if not file.is_file():
        return None
    try:
        return file.read_text(encoding="utf-8").splitlines()
    except OSError:
        return None


def serve(path):
    print("\x1b[2J\x1b[HClaude Code\nVERIFY_READY_528\nReady\n❯ ", flush=True)
    for line in sys.stdin:
        text = line.rstrip("\r\n")
        with path.open("a", encoding="utf-8") as output:
            output.write("VERIFY_RECEIVED:" + text + "\n")
        print(f"\x1b[2J\x1b[HClaude Code\nVERIFY_READY_528\nReceived {text}\n❯ ", flush=True)


def main(argv):
    if len(argv) == 4 and argv[1] in ("absent", "line"):
        found = rows(argv[2])
        needle = argv[3]
        present = found is not None and (needle in found if argv[1] == "line" else any(needle in row for row in found))
        if argv[1] == "absent":
            if present:
                raise SystemExit("already sent")
            print("NOTHING_SENT")
            return
        if not present:
            raise SystemExit("not sent")
        print("SENT")
        return
    if len(argv) != 2:
        raise SystemExit("usage: owner-skills-console.py RECEIPTS | absent|line FILE NEEDLE")
    serve(Path(argv[1]))


if __name__ == "__main__":
    main(sys.argv)
