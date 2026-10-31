#!/usr/bin/env python3
import os
import pwd
import select
import signal
import subprocess
import sys
import termios
import tty
from pathlib import Path

receipts = Path(sys.argv[1])


def information_pager():
    user = pwd.getpwnam("verifyssh")
    pane = os.environ.get("TMUX_PANE", "")
    if not (
        os.environ.get("GITHUB_ACTIONS") == "true"
        and os.environ.get("VERIFY_INFORMATION_1015") == "true"
        and os.environ.get("HOME") == "/home/verifyssh"
        and user.pw_dir == "/home/verifyssh"
        and os.getuid() == user.pw_uid != 0
        and Path(__file__).resolve() == Path("/home/verifyssh/console.py")
        and receipts == Path("/home/verifyssh/receipts.txt")
        and receipts.parent.stat().st_uid == user.pw_uid
        and not receipts.is_symlink()
        and (not receipts.exists() or (receipts.is_file() and receipts.stat().st_uid == user.pw_uid))
        and len(sys.argv) == 2
        and pane.startswith("%") and pane[1:].isdigit()
        and sys.stdin.isatty() and sys.stdout.isatty()
    ):
        raise RuntimeError("VERIFY_INFORMATION_1015 guard failed")
    identity = subprocess.check_output(
        ["tmux", "display-message", "-p", "-t", pane,
         "#{session_name}:#{window_index}.#{pane_index} #{pane_id} #{pane_pid} #{pane_in_mode} #{alternate_on}"],
        text=True,
    ).strip()
    if identity != f"verify-session:0.0 {pane} {os.getpid()} 0 0":
        raise RuntimeError(f"VERIFY_INFORMATION_1015 pane mismatch: {identity}")
    height = os.get_terminal_size(sys.stdout.fileno()).lines
    if not 4 <= height <= 500:
        raise RuntimeError(f"VERIFY_INFORMATION_1015 pane height: {height}")

    def receipt(message):
        with receipts.open("a") as stream:
            stream.write(message + "\n")

    lines = [[f"VERIFY_1015_PAGE_{page}_LINE_{line:04d}" for line in range(1, height + 1)]
             for page in (3, 2, 1)]
    old = termios.tcgetattr(sys.stdin.fileno())
    old_handlers = {}
    entered = False
    try:
        for sig in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT):
            old_handlers[sig] = signal.signal(sig, lambda number, frame: sys.exit(128 + number))
        for page in lines:
            for line in page:
                print(line, flush=True)
        tty.setraw(sys.stdin.fileno())
        sys.stdout.write("\x1b[?1049h\x1b[?25l")
        sys.stdout.flush()
        entered = True
        receipt(f"VERIFY_1015_SETUP height={height} pages=3 pane={pane}")
        page = 0

        def render():
            payload = ["VERIFY_1015_LIVE"] if page == 0 else lines[3 - page]
            sys.stdout.write("\x1b[H\x1b[2J" + "\r\n".join(payload))
            sys.stdout.flush()

        render()
        keys = {b"\x1b[5~": "pageup", b"\x1b[4~": "end", b"\x1b[F": "end", b"\x1bOF": "end"}
        pending = b""
        while True:
            ready, _, _ = select.select([sys.stdin], [], [], 1.0 if pending else None)
            if not ready:
                receipt(f"VERIFY_1015_UNKNOWN {pending.hex()}")
                pending = b""
                continue
            data = os.read(sys.stdin.fileno(), 64)
            if not data:
                break
            pending += data
            while pending:
                exact = next((key for key in keys if pending.startswith(key)), None)
                if exact:
                    pending = pending[len(exact):]
                    if keys[exact] == "pageup":
                        page = min(page + 1, 3)
                        receipt(f"VERIFY_1015_PAGEUP page={page} key={exact.hex()}")
                    else:
                        page = 0
                        receipt(f"VERIFY_1015_END key={exact.hex()}")
                    render()
                elif any(key.startswith(pending) for key in keys):
                    break
                else:
                    receipt(f"VERIFY_1015_UNKNOWN {pending[:1].hex()}")
                    pending = pending[1:]
    finally:
        try:
            if entered:
                sys.stdout.write("\x1b[?25h\x1b[?1049l")
                sys.stdout.flush()
        finally:
            termios.tcsetattr(sys.stdin.fileno(), termios.TCSADRAIN, old)
            for sig, handler in old_handlers.items():
                signal.signal(sig, handler)


print("VERIFY_READY_528", flush=True)
while True:
    try:
        line = input()
        if line == "VERIFY_INFORMATION_1015" and os.environ.get("VERIFY_INFORMATION_1015") == "true":
            information_pager()
            continue
        message = "VERIFY_ENTER_528" if not line else "VERIFY_RECEIVED:" + line
    except KeyboardInterrupt:
        message = "VERIFY_INTERRUPTED_528"
    except EOFError:
        break
    with receipts.open("a") as stream:
        stream.write(message + "\n")
    print(message, flush=True)
