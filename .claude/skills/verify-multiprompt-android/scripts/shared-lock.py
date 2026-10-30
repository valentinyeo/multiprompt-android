#!/usr/bin/env python3
import fcntl
import hashlib
import os
from pathlib import Path
import signal
import sys

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('HOME') == '/home/verifyssh' and os.getuid() != 0
provider, sid = sys.argv[1:]
assert (provider, sid) in {('claude', 'a1b2c3d4-1008-4000-8000-000000001008'), ('codex', 'verify-codex')}
path = Path('/home/verifyssh/.local/state/multiprompt/shared-messages/store.sqlite3')
path.parent.mkdir(parents=True, exist_ok=True)
lock_path = path.with_suffix('.' + hashlib.sha256((provider + ':' + sid).encode()).hexdigest() + '.message-worker')
with lock_path.open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    Path('/home/verifyssh/shared-lock-ready-' + provider).write_text('held\n')
    while True:
        signal.pause()
