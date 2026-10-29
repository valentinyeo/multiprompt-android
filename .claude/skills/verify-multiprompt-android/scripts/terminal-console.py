#!/usr/bin/env python3
import sys
from pathlib import Path

receipts = Path(sys.argv[1])
for i in range(1, 501):
    print(f"SHELL_HISTORY_{i:04}")
print("VERIFY_READY_528", flush=True)
for line in sys.stdin:
    marker = line.strip()
    with receipts.open("a") as stream:
        stream.write(marker + "\n")
    print(marker, flush=True)
