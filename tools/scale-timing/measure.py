#!/usr/bin/env python3
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import parsers
import probe
from runtime import DRIVER, MeasurementError, adb, elapsed_ms, mpa

HOST_LOG = '/home/verifyssh/scale-relay.jsonl'
INBOX_TIMEOUT = 180
CHAT_TIMEOUT = 240
LARGEST_TIMEOUT = 600
FLINGS = 10
SWITCHES = 5
IDLE_SECONDS = 20
NOOP_LABEL = 'All'
STAGES = (('process', 'process start'), ('application', 'Application init'), ('view_model', 'view model built'),
          ('displayed', 'first frame'), ('fully_drawn', 'fully drawn'), ('inbox', 'Inbox state ready'))


def screen():
    return json.loads(mpa('read', timeout=180).stdout)


def texts(payload):
    return [value for value in list(payload.get('texts') or []) + list(payload.get('descriptions') or []) if value]


def on_screen(payload, value):
    return any(value in text for text in texts(payload))


def wait_text(value, timeout, what):
    result = mpa('read', '--wait-text', value, '--timeout', str(timeout), timeout=timeout + 120)
    if result.returncode != 0:
        raise MeasurementError(f'{what}: {value!r} never appeared')


def has_robot(payload):
    return any(description.startswith('Robot ') for description in payload.get('descriptions') or [])


def poll(predicate, timeout, what):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            payload = screen()
        except MeasurementError:
            time.sleep(1)
            continue
        if predicate(payload):
            return payload
        time.sleep(1)
    raise MeasurementError('%s: still not on screen after %ss' % (what, timeout))


def wait_inbox(timeout):
    deadline = time.monotonic() + timeout
    pressed = False
    while time.monotonic() < deadline:
        try:
            payload = screen()
        except MeasurementError:
            time.sleep(1)
            continue
        if has_robot(payload):
            return payload
        if not pressed and on_screen(payload, NOOP_LABEL):
            pressed = True
            try:
                mpa('tap', 'text', NOOP_LABEL, timeout=180)
            except MeasurementError:
                pass
            continue
        time.sleep(1)
    raise MeasurementError('INBOX_NOT_SHOWN: still not on screen after %ss' % timeout)


def clk_tck():
    result = adb('shell', 'getconf', 'CLK_TCK', check=False)
    raw = result.stdout.strip()
    return int(raw) if raw.isdigit() and int(raw) > 0 else 100


def app_ticks():
    pids = adb('shell', 'pidof', PACKAGE, check=False).stdout.split()
    per = {}
    for one in pids:
        raw = adb('shell', 'cat', f'/proc/{one}/stat', check=False).stdout
        per[one] = parsers.parse_proc_stat(raw)['total']
    return dict(ticks=sum(per.values()), pids=pids, per=per)


def cpu_delta(before, after):
    measured = 0
    for pid, ticks in after['per'].items():
        measured += ticks - before['per'].get(pid, 0)
    return measured


def gfx_reset():
    adb('shell', 'dumpsys', 'gfxinfo', PACKAGE, 'reset', check=False)


def gfx_totals():
    return parsers.parse_gfxinfo(adb('shell', 'dumpsys', 'gfxinfo', PACKAGE, check=False).stdout)


def device_offset():
    raw = adb('shell', 'date', '+%s.%N', check=False, timeout=60).stdout.strip()
    try:
        return float(raw) - time.time()
    except ValueError:
        return 0.0


def relay_text():
    try:
        result = subprocess.run(['sudo', 'cat', HOST_LOG], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return ''
    return result.stdout if result.returncode == 0 else ''


def probe_threads():
    try:
        return probe.thread_sample(PACKAGE)
    except (MeasurementError, subprocess.SubprocessError):
        return {}


def phase(phases, name, started, ended, before, after):
    wall = elapsed_ms(started, ended)
    driver = DRIVER.summary(DRIVER.between(started, ended))['total_ms']
    deltas = parsers.thread_deltas(before, after, CLK_TCK)
    phases.append(dict(name=name, wall_ms=wall, driver_ms=driver, wait_ms=round(wall - driver, 1),
                       cpu_ms=parsers.thread_total(deltas), threads=deltas[:6]))


def zero_baseline(sample):
    return {tid: dict(ticks=0, name=entry['name']) for tid, entry in sample.items()}


def step(name, out, work):
    gfx_reset()
    DRIVER.reset()
    before_threads = probe_threads()
    before_wall = time.time()
    before = app_ticks()
    result = work(before_wall)
    ended = time.time()
    after = app_ticks()
    driver = DRIVER.summary()
    after_threads = probe_threads()
    mpa('screenshot', f'{SEED}-{name}', timeout=240)
    ticks = cpu_delta(before, after)
    record = dict(name=name, started_unix=before_wall, ended_unix=ended,
                  wall_ms=elapsed_ms(before_wall, ended),
                  cpu_ticks=ticks, cpu_ms=round(ticks * 1000 / CLK_TCK, 1),
                  pids=after['pids'], gfx=gfx_totals(), driver=driver,
                  threads=parsers.thread_deltas(before_threads or zero_baseline(after_threads),
                                                after_threads, CLK_TCK)[:8],
                  detail=result)
    (Path(out) / f'{name}.json').write_text(json.dumps(record, indent=2) + '\n')
    return record


def launch_stages(log, offset, started_unix, inbox_at, events):
    marks = parsers.parse_launch_log(log, PACKAGE)
    points = [(title, marks[key] - offset) for key, title in STAGES if key in marks]
    requests = parsers.relay_open_times(events, stream='tmux', since=started_unix)
    if requests:
        points.append(('first listSessions request', requests[0]))
        replies = [event['t'] for event in events
                   if event.get('event') == 'call' and event.get('stream') == 'tmux'
                   and isinstance(event.get('t'), (int, float)) and event['t'] >= requests[0]]
        if replies:
            points.append(('first listSessions reply', min(replies)))
    points.append(('Inbox rows on screen', inbox_at))
    points.sort(key=lambda item: item[1])
    stages = []
    previous = started_unix
    for title, moment in points:
        if moment < previous:
            continue
        stages.append(dict(name=title, at_ms=elapsed_ms(started_unix, moment),
                           stage_ms=elapsed_ms(previous, moment),
                           driver_ms=DRIVER.summary(DRIVER.between(previous, moment))['total_ms']))
        previous = moment
    stages.append(dict(name='step end', at_ms=elapsed_ms(started_unix, time.time()),
                       stage_ms=elapsed_ms(previous, time.time()),
                       driver_ms=DRIVER.summary(DRIVER.between(previous, time.time()))['total_ms']))
    return dict(marks={key: round(value, 3) if isinstance(value, float) else value for key, value in marks.items()},
                stages=stages)


def open_chat(chat, timeout, phases):
    started = time.time()
    before = probe_threads()
    mpa('tap', 'desc', 'Robot ' + chat['session'], timeout=timeout + 120)
    tapped = time.time()
    after = probe_threads()
    phase(phases, 'tap the Inbox row', started, tapped, before, after)
    mpa('read', '--wait-text', chat['marker'], '--timeout', str(timeout), timeout=timeout + 120)
    ready = time.time()
    phase(phases, 'wait for the newest row', tapped, ready, after, probe_threads())
    return dict(session=chat['session'], marker=chat['marker'], bytes=chat['bytes'], rows=chat.get('rows'),
                tap_ms=elapsed_ms(started, tapped), marker_ms=elapsed_ms(tapped, ready))


def seed_host(fixture, evidence):
    key = base64.b64encode((Path(fixture) / 'user-key').read_bytes()).decode()
    instrument = (f'{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner')
    result = adb('shell', 'am', 'instrument', '-w',
                 '-e', 'class', 'dev.multiprompt.companion.account.SeedHostDeviceTest#importsCliHostFixture',
                 '-e', 'host_label', 'Verify', '-e', 'ssh_host', '10.0.2.2', '-e', 'ssh_port', '2222',
                 '-e', 'ssh_user', 'verifyssh', '-e', 'ssh_private_key', key, instrument,
                 timeout=300, check=False)
    (Path(evidence) / 'host-seed.txt').write_text(result.stdout + result.stderr)
    if 'OK (1 test)' not in result.stdout:
        raise MeasurementError('HOST_SEED_FAILED: ' + (result.stdout + result.stderr).strip()[-600:])


def sign_in(fixture, evidence, host_fingerprint, expected_version):
    mpa('launch', '--force-stop', timeout=240)
    poll(lambda page: on_screen(page, 'Use without account (debug)') or has_robot(page), 240, 'SIGN_IN_SCREEN_MISSING')
    mpa('doctor', '--expected-version', expected_version, timeout=300)
    if on_screen(screen(), 'Use without account (debug)'):
        mpa('tap', 'text', 'Use without account (debug)', timeout=240)
        wait_text('Inbox', 180, 'debug sign in')
    seed_host(fixture, evidence)
    mpa('launch', '--force-stop', timeout=240)
    payload = poll(lambda page: has_robot(page) or on_screen(page, 'Trust Verify?') or on_screen(page, 'Use without account (debug)'),
                   300, 'TRUST_SHEET_MISSING')
    if on_screen(payload, 'Use without account (debug)'):
        mpa('tap', 'text', 'Use without account (debug)', timeout=240)
        payload = poll(lambda page: has_robot(page) or on_screen(page, 'Trust Verify?'), 300, 'TRUST_SHEET_MISSING')
    if has_robot(payload):
        (Path(evidence) / 'signed-in.txt').write_text('host was already trusted\n')
        return 'already trusted'
    if host_fingerprint and host_fingerprint not in ' '.join(texts(payload)):
        raise MeasurementError('HOST_FINGERPRINT_MISMATCH: the sheet does not show ' + host_fingerprint)
    mpa('tap', 'text', 'Trust and connect', timeout=300)
    wait_inbox(300)
    (Path(evidence) / 'signed-in.txt').write_text('signed in and trusted ' + host_fingerprint + '\n')
    return 'signed in'


def measure(targets):
    steps = []
    probe.install_sampler()

    def launch(started_unix):
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('logcat', '-c', check=False)
        offset = device_offset()
        started = adb('shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/dev.multiprompt.companion.MainActivity')
        payload = poll(lambda page: on_screen(page, 'Use without account (debug)') or has_robot(page) or on_screen(page, NOOP_LABEL),
                       INBOX_TIMEOUT, 'LAUNCH_SCREEN_MISSING')
        if on_screen(payload, 'Use without account (debug)'):
            mpa('tap', 'text', 'Use without account (debug)', timeout=240)
        wait_inbox(INBOX_TIMEOUT)
        inbox_at = time.time()
        log = adb('logcat', '-v', 'epoch', '-d', timeout=180, check=False).stdout
        (Path(OUT) / 'launch-logcat.txt').write_text(log)
        trace = launch_stages(log, offset, started_unix, inbox_at, parsers.relay_events(relay_text()))
        return dict(inbox='shown', am_start=started.stdout.strip(), clock_offset_s=round(offset, 3), **trace)
    steps.append(step('launch-inbox', OUT, launch))

    largest = targets['largest']
    opened = {}

    def open_largest(started_unix):
        try:
            opened['chat'] = open_chat(largest, LARGEST_TIMEOUT, opened.setdefault('phases', []))
            return dict(opened['chat'], opened=True, phases=opened['phases'])
        except MeasurementError as failure:
            opened['error'] = str(failure)
            return dict(session=largest['session'], bytes=largest['bytes'], rows=largest.get('rows'), opened=False,
                        phases=opened.get('phases', []), error=str(failure))
    steps.append(step('open-largest', OUT, open_largest))

    def flings(started_unix):
        if 'chat' not in opened:
            for chat in targets['chats'][1:]:
                try:
                    opened['chat'] = open_chat(chat, CHAT_TIMEOUT, opened.setdefault('phases', []))
                    break
                except MeasurementError:
                    continue
        if 'chat' not in opened:
            raise MeasurementError('NO_CHAT_OPENED: the largest chat and the next four never opened: '
                                   + opened.get('error', 'no error recorded'))
        payload = screen()
        bounds = parsers.biggest_scrollable(payload['nodes'])
        if bounds is None:
            raise MeasurementError('NO_SCROLLABLE_VIEW: the chat screen exposes no scrollable container')
        points = parsers.fling_points(bounds)
        for _ in range(FLINGS):
            adb('shell', 'input', 'swipe', *points, 300)
        return dict(flings=FLINGS, session=opened['chat']['session'], bounds=bounds, points=points)
    steps.append(step('ten-flings', OUT, flings))

    def switches(started_unix):
        results = []
        for chat in targets['chats'][:SWITCHES]:
            started = time.time()
            before = probe_threads()
            mpa('key', 'back', timeout=120)
            backed = time.time()
            after = probe_threads()
            phases = []
            phase(phases, 'back to the Inbox', started, backed, before, after)
            wait_inbox(INBOX_TIMEOUT)
            listed = time.time()
            after_inbox = probe_threads()
            phase(phases, 'wait for the Inbox rows', backed, listed, after, after_inbox)
            base = dict(back_ms=elapsed_ms(started, backed), inbox_ms=elapsed_ms(backed, listed),
                        phases=phases, opened=False)
            try:
                result = open_chat(chat, CHAT_TIMEOUT, phases)
                results.append(dict(result, **dict(base, opened=True, phases=phases,
                                                   switch_ms=elapsed_ms(started, time.time()))))
            except MeasurementError as failure:
                results.append(dict(base, session=chat['session'], bytes=chat['bytes'], error=str(failure),
                                    switch_ms=elapsed_ms(started, time.time())))
        if not any(item['opened'] for item in results):
            raise MeasurementError('NO_CHAT_OPENED: none of the five switch targets opened')
        return dict(attempted=len(results), opened=sum(1 for item in results if item['opened']), chats=results)
    steps.append(step('switch-five', OUT, switches))

    mpa('key', 'back', timeout=120, check=False)

    def driver_alone(started_unix):
        wait_inbox(INBOX_TIMEOUT)
        try:
            return probe.driver_probe(NOOP_LABEL)
        except MeasurementError as failure:
            return dict(error=str(failure))
    steps.append(step('driver-alone', OUT, driver_alone))

    def idle(started_unix):
        wait_inbox(INBOX_TIMEOUT)
        time.sleep(IDLE_SECONDS)
        return dict(seconds=IDLE_SECONDS)
    steps.append(step('idle-inbox', OUT, idle))

    return steps


def copy_relay(out):
    destination = Path(out) / 'relay.jsonl'
    copies = subprocess.run(['sudo', 'install', '-m', '644', HOST_LOG, str(destination)], capture_output=True, text=True)
    if copies.returncode != 0:
        (Path(out) / 'relay-copy-error.txt').write_text(copies.stderr)
        return False
    return True


def save_logcat(out):
    result = adb('shell', 'logcat', '-d', timeout=180, check=False)
    (Path(out) / 'logcat.txt').write_text(result.stdout)


def main(argv):
    global PACKAGE, CLK_TCK, OUT, SEED
    parser = argparse.ArgumentParser(description='Time the phone at the fixture and owner scale on the GitHub emulator.')
    parser.add_argument('--seed', required=True)
    parser.add_argument('--targets', required=True)
    parser.add_argument('--out', required=True)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--fixture', required=True)
    parser.add_argument('--package', default=os.environ.get('VERIFY_PACKAGE') or 'dev.multiprompt.companion.debug')
    parser.add_argument('--expected-version', required=True)
    parser.add_argument('--ref', default='head')
    arguments = parser.parse_args(argv[1:])
    PACKAGE = arguments.package
    OUT = Path(arguments.out)
    SEED = arguments.seed
    OUT.mkdir(parents=True, exist_ok=True)
    targets = json.loads(Path(arguments.targets).read_text())
    CLK_TCK = clk_tck()
    fingerprint_path = Path(arguments.evidence) / 'host-fingerprint.txt'
    fingerprint = fingerprint_path.read_text().strip() if fingerprint_path.is_file() else ''
    session = None
    steps = []
    error = None
    try:
        session = sign_in(arguments.fixture, arguments.evidence, fingerprint, arguments.expected_version)
        steps = measure(targets)
    except MeasurementError as failure:
        error = str(failure)
    finally:
        kept = copy_relay(OUT)
        save_logcat(OUT)
    if error:
        (OUT / 'failure.txt').write_text(error + '\n')
        print(json.dumps(dict(ok=False, error=error, relay=kept)), file=sys.stderr)
        return 1
    summary = dict(seed=arguments.seed, ref=arguments.ref, package=PACKAGE, session=session, clk_tck=CLK_TCK,
                   relay=kept, targets=targets, steps=steps, device=dict(
                       model=adb('shell', 'getprop', 'ro.product.model', check=False).stdout.strip(),
                       sdk=adb('shell', 'getprop', 'ro.build.version.sdk', check=False).stdout.strip(),
                       abi=adb('shell', 'getprop', 'ro.product.cpu.abi', check=False).stdout.strip()))
    (OUT / 'timing.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv))
    except MeasurementError as error:
        print(json.dumps(dict(ok=False, error=str(error))), file=sys.stderr)
        sys.exit(1)
