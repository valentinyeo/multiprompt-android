#!/usr/bin/env python3
import sys
from pathlib import Path

receipts = Path(sys.argv[1])
print("VERIFY_READY_528", flush=True)
while True:
    try:
        line = input()
        message = "VERIFY_ENTER_528" if not line else "VERIFY_RECEIVED:" + line
    except KeyboardInterrupt:
        message = "VERIFY_INTERRUPTED_528"
    except EOFError:
        break
    with receipts.open("a") as stream:
        stream.write(message + "\n")
    print(message, flush=True)
