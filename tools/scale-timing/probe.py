#!/usr/bin/env python3
import json
from pathlib import Path
import time

import parsers
from runtime import MeasurementError, adb, mpa

DIRECTORY = Path(__file__).resolve().parent
SAMPLER = DIRECTORY / 'sampler.sh'
DEVICE_SAMPLER = '/data/local/tmp/mpa-scale-sampler.sh'
DUMP = ('exec-out', 'sh', '-c',
        'rm -f /sdcard/verify-ui.xml; uiautomator dump /sdcard/verify-ui.xml >/dev/null 2>&1; '
        'cat /sdcard/verify-ui.xml 2>/dev/null; exit 0')
PROBES = 3


def install_sampler():
    adb('push', str(SAMPLER), DEVICE_SAMPLER, timeout=120, check=False, label='adb push')


def thread_sample(package):
    result = adb('shell', 'sh', DEVICE_SAMPLER, package, timeout=120, check=False, label='threads')
    return parsers.parse_thread_sample(result.stdout)


def timed_ms(call):
    started = time.monotonic()
    call()
    return round((time.monotonic() - started) * 1000, 1)


def sample(call, label):
    times = [timed_ms(call) for _ in range(PROBES)]
    return dict(label=label, ms=parsers.median(times), samples=times)


def raw_dump_ms():
    def dump():
        adb(*DUMP, timeout=90, check=False, label='dump')
    return sample(dump, 'uiautomator dump (driver)')


def read_ms():
    return sample(lambda: mpa('read', timeout=180), 'mpa read (one dump and parse)')


def wait_present_ms(value):
    return sample(lambda: mpa('read', '--wait-text', value, '--timeout', '20', timeout=120),
                  'mpa read --wait-text on a label already on screen')


def resolved_tap_ms(value):
    return sample(lambda: mpa('tap', 'text', value, timeout=180), 'mpa tap (two dumps and one input event)')


def noop_point(value):
    result = mpa('tap', 'text', value, '--dry-run', timeout=180)
    point = json.loads(result.stdout).get('point') or []
    if len(point) != 2:
        raise MeasurementError('NO_TAP_POINT: the inbox no-op control did not resolve: ' + result.stdout.strip()[:300])
    return [int(point[0]), int(point[1])]


def driver_probe(noop):
    point = noop_point(noop)
    dump = raw_dump_ms()
    tap = resolved_tap_ms(noop)
    return dict(noop=noop, point=point, dump=dump, read=read_ms(),
                wait_present=wait_present_ms(noop), tap=tap,
                input_ms=round(max(0.0, tap['ms'] - 2 * dump['ms']), 1))
