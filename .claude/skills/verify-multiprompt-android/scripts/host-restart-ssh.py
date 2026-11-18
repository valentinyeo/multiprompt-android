#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess

home = Path('/home/verifyssh')
assert Path.home() == home and os.getuid() != 0
command = os.environ['SSH_ORIGINAL_COMMAND']
if '--serve' not in command:
    os.execv('/bin/bash', ['bash', '-c', command])
child = subprocess.Popen(['bash', '-c', command])
with (home / 'host-restart-history-pids.jsonl').open('a') as output:
    output.write(json.dumps(dict(pid=child.pid)) + '\n')
raise SystemExit(child.wait())
