#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
MPA = ROOT / '.claude/skills/verify-multiprompt-android/scripts/mpa'
ADB = os.environ.get('ADB') or 'adb'


class MeasurementError(Exception):
    pass


class Driver:
    def __init__(self):
        self.entries = []

    def reset(self):
        self.entries = []

    def add(self, command, started, ended, started_unix, ended_unix):
        self.entries.append(dict(command=command, started=started, ended=ended,
                                 started_unix=started_unix, ended_unix=ended_unix))

    def between(self, started_unix, ended_unix):
        return [entry for entry in self.entries
                if entry['started_unix'] >= started_unix and entry['ended_unix'] <= ended_unix]

    def summary(self, entries=None):
        chosen = self.entries if entries is None else entries
        per = {}
        for entry in chosen:
            bucket = per.setdefault(entry['command'], dict(calls=0, ms=0.0))
            bucket['calls'] += 1
            bucket['ms'] += (entry['ended'] - entry['started']) * 1000
        total = sum((entry['ended'] - entry['started']) * 1000 for entry in chosen)
        return dict(total_ms=round(total, 1), calls=len(chosen),
                    per={name: dict(calls=value['calls'], ms=round(value['ms'], 1))
                         for name, value in sorted(per.items())})


DRIVER = Driver()


def elapsed_ms(started, ended):
    return round((ended - started) * 1000, 1)


def run(command, label, timeout, check, failure):
    started = time.monotonic()
    started_unix = time.time()
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    finally:
        ended = time.monotonic()
        DRIVER.add(label, started, ended, started_unix, time.time())
    if check and result.returncode != 0:
        raise MeasurementError(failure(result))
    return result


def adb(*args, timeout=180, check=True, label=None):
    return run([ADB, *map(str, args)], label or 'adb ' + str(args[0]), timeout, check,
               lambda result: 'adb %s exited %s: %s%s' % (
                   ' '.join(map(str, args)), result.returncode,
                   result.stdout.strip()[-400:], result.stderr.strip()[-400:]))


def mpa(*args, timeout=300, check=True):
    return run([str(MPA), *args], 'mpa ' + str(args[0]), timeout, check,
               lambda result: 'mpa %s exited %s: %s%s' % (
                   ' '.join(args), result.returncode,
                   result.stdout.strip()[-600:], result.stderr.strip()[-600:]))
