#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys
import time

import measure
import parsers
from runtime import DRIVER, MeasurementError, adb, mpa

SCENARIOS = ('cold_start', 'chat_switch', 'idle_inbox', 'typing_long_chat', 'streaming_reply')
METRICS = ('app_cpu_ms', 'main_thread_ms', 'frames_over_16_ms_secondary',
           'list_sessions_per_minute', 'bytes_per_minute', 'errors')
REPEATS = 5
SECONDS = 20
DRAFT = 'Frozen benchmark types this everyday message.'
VERSION = 'mubo-555-v1'
DIRECTORY = Path(__file__).resolve().parent


def spread(values):
    if not values or any(not isinstance(value, (int, float)) for value in values):
        raise ValueError('BENCHMARK_MISSING_SAMPLE')
    return dict(median=round(statistics.median(values), 3), min=round(min(values), 3), max=round(max(values), 3))


def verdict(baseline, candidate):
    if candidate['max'] < baseline['min']:
        return 'better'
    if candidate['min'] > baseline['max']:
        return 'worse'
    return 'within noise'


def frames_over_16(text):
    histogram = re.search(r'HISTOGRAM:\s*([^\n]+)', text)
    if not histogram:
        raise MeasurementError('GFX_HISTOGRAM_MISSING')
    return sum(int(count) for millis, count in re.findall(r'(\d+)ms=(\d+)', histogram[1]) if int(millis) > 16)


def errors(log, package, pids, failed=False):
    owned = set(map(str, pids))
    for line in log.splitlines():
        match = re.search(r'Start proc (\d+):' + re.escape(package) + r'(?:/|\s|$)', line)
        if match:
            owned.add(match[1])
    app_e = crashes = anrs = 0
    for line in log.splitlines():
        match = re.match(r'\s*\d+(?:\.\d+)?\s+(\d+)\s+\d+\s+([VDIWEF])\s+([^:]+):\s*(.*)', line)
        if not match:
            continue
        pid, priority, tag, body = match.groups()
        if pid in owned and priority == 'E':
            app_e += 1
        if ('FATAL EXCEPTION:' in body and pid in owned) or (tag.strip() == 'am_crash' and package in body):
            crashes += 1
        if ('ANR in ' + package in body) or (tag.strip() == 'am_anr' and package in body):
            anrs += 1
    return dict(crashes=crashes, anrs=anrs, failed_steps=int(failed), app_logcat_e=app_e)


def traffic(events, started, ended):
    chosen = [event for event in events if started <= event.get('t', 0) <= ended]
    listings = sum(event.get('event') == 'open' and event.get('list_sessions') is True for event in chosen)
    size = sum(event.get('bytes', 0) for event in chosen if event.get('event') == 'call')
    failed = sum(event.get('event') == 'close' and event.get('code', 0) != 0 for event in chosen)
    minutes = (ended - started) / 60
    if minutes <= 0:
        raise ValueError('BENCHMARK_EMPTY_WINDOW')
    return dict(list_sessions=listings, bytes=size, relay_failures=failed,
                list_sessions_per_minute=round(listings / minutes, 3), bytes_per_minute=round(size / minutes, 3))


def aggregate(samples, source_sha, seed, harness):
    rows = {}
    for scenario in SCENARIOS:
        chosen = [sample for sample in samples if sample['scenario'] == scenario]
        if len(chosen) != REPEATS or any(not sample['valid'] for sample in chosen):
            raise ValueError('BENCHMARK_INVALID_SAMPLES: ' + scenario)
        rows[scenario] = {metric: spread([sample['metrics'][metric] for sample in chosen]) for metric in METRICS}
    return dict(schema=VERSION, harness=harness, app_sha=source_sha, seed=seed, repeats=REPEATS, scenarios=rows)


def compare(baseline, candidate):
    for key in ('schema', 'harness', 'seed', 'repeats'):
        if baseline[key] != candidate[key]:
            raise ValueError('BENCHMARK_INCOMPATIBLE: ' + key)
    return {scenario: {metric: dict(baseline=baseline['scenarios'][scenario][metric],
                                   candidate=candidate['scenarios'][scenario][metric],
                                   verdict=verdict(baseline['scenarios'][scenario][metric],
                                                   candidate['scenarios'][scenario][metric]))
                       for metric in METRICS} for scenario in SCENARIOS}


def table(result):
    lines = [f"### {result['seed']} · {result['app_sha']}", '',
             '| Scenario | App CPU ms | Main ms | >16 ms frames (secondary) | listSessions/min | bytes/min | Errors |',
             '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for scenario in SCENARIOS:
        values = result['scenarios'][scenario]
        cells = [f"{values[metric]['median']:g} [{values[metric]['min']:g}, {values[metric]['max']:g}]" for metric in METRICS]
        lines.append('| ' + scenario + ' | ' + ' | '.join(cells) + ' |')
    return '\n'.join(lines) + '\n'


def fingerprint():
    digest = hashlib.sha256()
    paths = sorted(path for path in DIRECTORY.glob('*') if path.suffix in ('.py', '.sh') and '.test.' not in path.name)
    paths += [DIRECTORY.parent / 'scale-chat/fixture.py',
              DIRECTORY.parents[1] / '.github/workflows/scale-timing.yml']
    for path in paths:
        digest.update(path.name.encode() + b'\0' + path.read_bytes())
    return digest.hexdigest()


def snapshot(package, absent=False):
    pids = adb('shell', 'pidof', package, check=False).stdout.split()
    if not pids and absent:
        return dict(pid=None, app=0, main=0)
    if len(pids) != 1:
        raise MeasurementError('BENCHMARK_PID_MISSING_OR_AMBIGUOUS: ' + repr(pids))
    pid = pids[0]
    raw = adb('shell', f'cat /proc/{pid}/stat; cat /proc/{pid}/task/{pid}/stat').stdout.splitlines()
    if len(raw) != 2 or any(')' not in line for line in raw):
        raise MeasurementError('BENCHMARK_CPU_UNREADABLE')
    return dict(pid=pid, app=parsers.parse_proc_stat(raw[0])['total'], main=parsers.parse_proc_stat(raw[1])['total'])


def sample(scenario, repetition, work, args, out):
    adb('logcat', '-c')
    adb('shell', 'dumpsys', 'gfxinfo', args.package, 'reset')
    before = snapshot(args.package, absent=scenario == 'cold_start')
    DRIVER.reset()
    started = time.time()
    failed = None
    detail = {}
    try:
        detail = work()
    except (MeasurementError, subprocess.SubprocessError, OSError) as error:
        failed = str(error)
    ended = time.time()
    driver = DRIVER.summary()
    try:
        after = snapshot(args.package)
        if before['pid'] is not None and before['pid'] != after['pid']:
            raise MeasurementError('BENCHMARK_PROCESS_RESTARTED')
        cpu = {key: round((after[field] - before[field]) * 1000 / args.clk_tck, 3)
               for key, field in (('app_cpu_ms', 'app'), ('main_thread_ms', 'main'))}
        if any(value < 0 for value in cpu.values()):
            raise MeasurementError('BENCHMARK_CPU_COUNTER_RESET')
    except MeasurementError as error:
        failed = failed or str(error)
        after = dict(pid=None)
        cpu = dict(app_cpu_ms=None, main_thread_ms=None)
    log = adb('logcat', '-b', 'all', '-v', 'epoch', '-d').stdout
    (out / f'{repetition}-{scenario}-logcat.txt').write_text(log)
    gfx = adb('shell', 'dumpsys', 'gfxinfo', args.package).stdout
    (out / f'{repetition}-{scenario}-gfxinfo.txt').write_text(gfx)
    try:
        frames = frames_over_16(gfx)
    except MeasurementError as error:
        failed = failed or str(error)
        frames = None
    relay = traffic(parsers.relay_events(measure.relay_text()), started, ended)
    if scenario == 'streaming_reply' and not failed:
        try:
            measure.wait_text(detail['marker'], 60, 'stream output reached phone')
            if relay['bytes'] == 0:
                raise MeasurementError('BENCHMARK_STREAM_NO_TRAFFIC')
        except MeasurementError as error:
            failed = str(error)
    counts = errors(log, args.package, [before['pid'], after['pid']], failed=bool(failed))
    counts['relay_failures'] = relay['relay_failures']
    metrics = dict(**cpu, frames_over_16_ms_secondary=frames,
                   list_sessions_per_minute=relay['list_sessions_per_minute'],
                   bytes_per_minute=relay['bytes_per_minute'], errors=sum(counts.values()))
    record = dict(scenario=scenario, repetition=repetition, valid=not failed,
                  metrics=metrics, error_counts=counts, failure=failed,
                  wall_ms=round((ended - started) * 1000, 3), driver=driver,
                  work=detail, traffic=relay)
    (out / f'{repetition}-{scenario}.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(record), flush=True)
    if failed:
        raise MeasurementError(failed)
    return record


def run(args):
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise MeasurementError('BENCHMARK_RUN_ON_GITHUB_ONLY')
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    targets = json.loads(Path(args.targets).read_text())
    args.clk_tck = measure.clk_tck()
    measure.PACKAGE = args.package
    measure.CLK_TCK = args.clk_tck
    measure.probe.install_sampler()
    fingerprint_path = Path(args.evidence) / 'host-fingerprint.txt'
    measure.sign_in(args.fixture, args.evidence, fingerprint_path.read_text().strip(), args.expected_version)
    largest, switch = targets['chats'][:2]
    records = []

    def launch():
        adb('shell', 'am', 'start', '-W', '-n', f'{args.package}/dev.multiprompt.companion.MainActivity')
        measure.wait_inbox(180)
        return dict(inbox='shown')

    def open_chat(chat):
        measure.open_chat(chat, 600, [])

    def switch_chat():
        mpa('key', 'back')
        measure.wait_inbox(180)
        open_chat(switch)
        return dict(from_session=largest['session'], to_session=switch['session'], marker=switch['marker'])

    def idle():
        time.sleep(SECONDS)
        return dict(seconds=SECONDS)

    def typing():
        result = mpa('fill', 'Message ' + largest['session'], DRAFT, timeout=300)
        proof = json.loads(result.stdout)
        if not proof.get('verified'):
            raise MeasurementError('BENCHMARK_DRAFT_NOT_VERIFIED')
        return dict(characters=len(DRAFT), verified=True)

    def streaming():
        marker = f'FROZEN_STREAM_{repetition}_039'
        result = subprocess.run(['sudo', '-u', 'verifyssh', sys.executable, '/home/verifyssh/frozen-stream.py',
                                 '--transcript', largest['transcript'], '--repetition', str(repetition)],
                                capture_output=True, text=True, check=True, timeout=SECONDS + 30)
        receipt = json.loads(result.stdout)
        if receipt['rows'] != 40 or receipt['marker'] != marker:
            raise MeasurementError('BENCHMARK_STREAM_INCOMPLETE')
        return receipt

    for repetition in range(1, REPEATS + 1):
        adb('shell', 'am', 'force-stop', args.package)
        adb('shell', 'run-as', args.package, 'rm', '-rf', 'files/chat-history')
        records.append(sample('cold_start', repetition, launch, args, out))
        open_chat(largest)
        records.append(sample('chat_switch', repetition, switch_chat, args, out))
        mpa('key', 'back')
        measure.wait_inbox(180)
        records.append(sample('idle_inbox', repetition, idle, args, out))
        open_chat(largest)
        records.append(sample('typing_long_chat', repetition, typing, args, out))
        mpa('fill', DRAFT, '', timeout=300)
        records.append(sample('streaming_reply', repetition, streaming, args, out))
        mpa('screenshot', f'frozen-{repetition}', timeout=240)
        subprocess.run(['sudo', '-u', 'verifyssh', sys.executable, '/home/verifyssh/frozen-stream.py',
                        '--transcript', largest['transcript'], '--restore'], check=True, timeout=30)
    measure.copy_relay(out)
    result = aggregate(records, args.app_sha, args.seed, fingerprint())
    (out / 'results.json').write_text(json.dumps(result, indent=2) + '\n')
    (out / 'samples.json').write_text(json.dumps(records, indent=2) + '\n')
    print(table(result))


def main():
    parser = argparse.ArgumentParser(description='Frozen five-scenario phone benchmark; run only on GitHub.')
    commands = parser.add_subparsers(dest='command', required=True)
    running = commands.add_parser('run')
    for name in ('seed', 'targets', 'out', 'evidence', 'fixture', 'expected-version', 'app-sha'):
        running.add_argument('--' + name, required=True)
    running.add_argument('--package', default='dev.multiprompt.companion.debug')
    reporting = commands.add_parser('report')
    reporting.add_argument('--root', required=True)
    reporting.add_argument('--out', required=True)
    comparing = commands.add_parser('compare')
    comparing.add_argument('baseline')
    comparing.add_argument('candidate')
    comparing.add_argument('--out')
    args = parser.parse_args()
    if args.command == 'run':
        run(args)
    elif args.command == 'report':
        paths = sorted(Path(args.root).rglob('results.json'))
        if not paths:
            raise ValueError('BENCHMARK_RESULTS_MISSING')
        rendered = '\n'.join(table(json.loads(path.read_text())) for path in paths)
        Path(args.out).write_text(rendered)
        print(rendered)
    else:
        result = compare(json.loads(Path(args.baseline).read_text()), json.loads(Path(args.candidate).read_text()))
        text = json.dumps(result, indent=2) + '\n'
        if args.out:
            Path(args.out).write_text(text)
        print(text, end='')


if __name__ == '__main__':
    try:
        main()
    except (MeasurementError, ValueError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
