#!/usr/bin/env python3
import fcntl
import hashlib
import os
from pathlib import Path
import select
import sqlite3
import sys

assert os.environ.get('GITHUB_ACTIONS') == 'true'
assert os.environ.get('HOME') == '/home/verifyssh' and os.getuid() != 0
provider, sid = sys.argv[1:]
assert (provider, sid) in {('claude', 'a1b2c3d4-1008-4000-8000-000000001008'), ('codex', '25340000-0000-4000-8000-000000000001'), ('claude', 'a1b2c3d4-1008-4000-8000-000000001009'), ('codex', 'verify-codex-neighbor')}
path = Path('/home/verifyssh/.local/state/multiprompt/shared-messages/store.sqlite3')
path.parent.mkdir(parents=True, exist_ok=True)
lock_path = path.with_suffix('.' + hashlib.sha256((provider + ':' + sid).encode()).hexdigest() + '.message-worker')
with lock_path.open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    Path('/home/verifyssh/shared-lock-ready-' + provider + '-' + sid).write_text('held\n')
    while True:
        if path.exists():
            with sqlite3.connect(path) as db:
                if db.execute("SELECT 1 FROM sqlite_master WHERE name='shared_messages'").fetchone():
                    db.execute("UPDATE shared_messages SET deadline=NULL WHERE provider=? AND sid=? AND state IN ('queued','sent') AND deadline IS NOT NULL", (provider, sid))
        select.select([], [], [], 0.1)
