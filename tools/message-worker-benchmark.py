import argparse
import base64
from contextlib import closing
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import statistics
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


def probe(asset, log):
    sources = {name: base64.b64encode((ASSETS / 'chat_connectors' / (name + '.py')).read_bytes()).decode()
               for name in ('claude', 'codex')}
    namespace = dict(__name__='benchmark_reader', CONNECTOR_SOURCES=sources,
                     MP_SEND_SOURCE=base64.b64encode((ASSETS / 'mp_send.py').read_bytes()).decode(),
                     FEEDBACK_SOURCE=base64.b64encode((ASSETS / 'multibot_feedback.py').read_bytes()).decode())
    exec(compile(Path(asset).read_bytes(), asset, 'exec'), namespace)
    original = subprocess.Popen
    workers = []

    def spawn(args, **kwargs):
        child = original(args, **kwargs)
        if '--message-worker' in args:
            workers.append(child)
            with open(log, 'a') as output:
                output.write(str(child.pid) + '\n')
        return child

    subprocess.Popen = spawn
    print(json.dumps(dict(ready=True)), flush=True)
    try:
        namespace['serve'](namespace['READER_SOURCE'])
    finally:
        deadline = time.monotonic() + 10
        for child in workers:
            try:
                child.wait(timeout=max(0.01, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                child.terminate()
                child.wait(timeout=2)


def no_work(provider, sid):
    path = Path.home() / '.local/state/multiprompt/shared-messages/store.sqlite3'
    with closing(sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)) as db:
        return db.execute("SELECT COUNT(*) FROM shared_messages WHERE provider=? AND sid=? AND state IN ('queued','sent','failed','accepted','started')", (provider, sid)).fetchone()[0] == 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default='3412dfdbe8567e46f1651826b91819461f0f77f4')
    parser.add_argument('--provider', required=True, choices=['claude', 'codex', 'hax'])
    parser.add_argument('--sid', required=True)
    parser.add_argument('--tmux', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if not no_work(args.provider, args.sid):
        raise ValueError('The selected chat has work; choose an idle real chat')
    before = subprocess.run(['git', 'show', f'{args.base}:app/src/main/assets/chat_history_reader.py'],
                            cwd=ROOT, check=True, capture_output=True, timeout=30).stdout
    after = (ASSETS / 'chat_history_reader.py').read_bytes()
    results = {}
    with tempfile.TemporaryDirectory(prefix='message-worker-benchmark-') as directory:
        root = Path(directory)
        processes = {}
        try:
            for name, source in [('before', before), ('after', after)]:
                asset = root / (name + '.py')
                asset.write_bytes(source)
                process = subprocess.Popen([sys.executable, '-B', __file__, '--probe', str(asset), str(root / (name + '.spawns'))],
                                           stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                processes[name] = process
                if json.loads(process.stdout.readline()) != dict(ready=True):
                    raise ValueError('Reader did not become ready')
                results[name] = dict(asset_sha256=hashlib.sha256(source).hexdigest(), milliseconds=[], errors=0)
            for block in range(5):
                for name in (['before', 'after'] if block % 2 == 0 else ['after', 'before']):
                    process = processes[name]
                    for _ in range(10):
                        rid = len(results[name]['milliseconds']) + 1
                        query = dict(id=rid, op='message_snapshot', message_protocol=1,
                                     provider=args.provider, sid=args.sid, tmux=args.tmux)
                        start = time.perf_counter_ns()
                        process.stdin.write(json.dumps(query) + '\n')
                        process.stdin.flush()
                        reply = json.loads(process.stdout.readline())
                        elapsed = (time.perf_counter_ns() - start) / 1_000_000
                        results[name]['milliseconds'].append(elapsed)
                        result = reply.get('result')
                        if (reply.get('id') != rid or reply.get('message_protocol') != 1 or not isinstance(result, dict)
                                or not isinstance(result.get('items'), list) or not result.get('snapshot_id')):
                            results[name]['errors'] += 1
            for name, process in processes.items():
                process.stdin.close()
                process.wait(timeout=20)
                if process.returncode:
                    raise ValueError(process.stderr.read())
                times = results[name]['milliseconds']
                log = root / (name + '.spawns')
                results[name].update(calls=len(times), median_ms=statistics.median(times),
                                     p95_ms=sorted(times)[math.ceil(len(times) * 0.95) - 1],
                                     worker_spawns=len(log.read_text().splitlines()) if log.exists() else 0)
        finally:
            for process in processes.values():
                if process.poll() is None:
                    process.stdin.close()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        process.terminate()
                        process.wait(timeout=5)
    if not no_work(args.provider, args.sid):
        raise ValueError('Chat gained work during measurement')
    report = dict(base=args.base, provider=args.provider, sid=args.sid, tmux=args.tmux,
                  method='Five alternating blocks of 10 sequential stdin/stdout message_snapshot requests per reader; no warmup; nearest-rank p95; startup excluded; actual worker Popen launches counted',
                  results=results)
    args.out.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    if any(result['errors'] for result in results.values()):
        raise ValueError('Snapshot replies failed validation')


if __name__ == '__main__':
    if sys.argv[1:2] == ['--probe']:
        probe(*sys.argv[2:])
    else:
        main()
