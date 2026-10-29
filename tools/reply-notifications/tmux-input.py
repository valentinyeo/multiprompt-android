import os
from pathlib import Path
import pty
import select
import subprocess
import termios

home = Path.home()
pipe = home / "notification-input"
os.mkfifo(pipe)
feed = os.open(pipe, os.O_RDWR | os.O_NONBLOCK)
master, slave = pty.openpty()
termios.tcsetwinsize(slave, (32, 100))
client = subprocess.Popen(["tmux", "attach-session", "-t", "verify-session"],
                          stdin=slave, stdout=slave, stderr=slave,
                          env=dict(os.environ, TERM="xterm"))
os.close(slave)
while client.poll() is None:
    ready, _, _ = select.select([master, feed], [], [], 1)
    for descriptor in ready:
        data = os.read(descriptor, 65536)
        if descriptor == feed:
            os.write(master, data)
