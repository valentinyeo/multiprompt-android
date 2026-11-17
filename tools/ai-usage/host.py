import json
import os
from pathlib import Path
import subprocess
import sys
import time


def main():
    home = Path.home()
    assert os.environ.get('GITHUB_ACTIONS') == 'true' and home == Path('/home/verifyssh')
    if sys.argv[1:] == ['seed']:
        status = dict(ts=int(time.time()), accounts=[
            dict(provider='claude', label='Claude', week=dict(used=37.5, time_gone=50, pace=dict(empty_hours=42)), five_hour=dict(used=12.5)),
            dict(provider='codex', label='Codex', week=dict(used=62.5, time_gone=50, pace=dict(empty_hours=18)), five_hour=dict(used=25)),
        ])
        path = home / '.local/state/token-ladder/status.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(status) + '\n')
        return
    command = os.environ['SSH_ORIGINAL_COMMAND']
    if command != 'cat ~/.local/state/token-ladder/status.json':
        os.execv('/bin/bash', ['bash', '-c', command])
    result = subprocess.run(['/bin/bash', '-c', command], capture_output=True, text=True)
    receipt = dict(command=command, exitCode=result.returncode, stdout=result.stdout)
    with (home / 'ai-usage-receipts.txt').open('a') as file:
        file.write(json.dumps(receipt) + '\n')
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
