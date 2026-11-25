import re
import time
from datetime import datetime, timedelta, timezone

USER = 'verifyssh'
ACCEPTED = re.compile(r'Accepted publickey for ([^ ]+) from [^ ]+ port \d+ ssh2: *(.*)$')
ISO_STAMP = re.compile(r'(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?)(Z|[+-]\d{2}:?\d{2})?')
SYSLOG_STAMP = re.compile(r'(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) +(\d{1,2}) (\d{2}:\d{2}:\d{2})')
MONTHS = {name: index for index, name in enumerate(
    ('Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'), start=1)}


def offset_minutes(text):
    if not text or text == 'Z':
        return 0
    digits = text[1:].replace(':', '')
    minutes = int(digits[:2]) * 60 + int(digits[2:])
    return -minutes if text[0] == '-' else minutes


def stamp(line, reference):
    found = ISO_STAMP.search(line)
    if found:
        moment = datetime.fromisoformat(found[1])
        if found[2]:
            moment = moment.replace(tzinfo=timezone(timedelta(minutes=offset_minutes(found[2]))))
        return dict(t=moment.timestamp(), time=found[0])
    found = SYSLOG_STAMP.search(line)
    if not found:
        return None
    clock = [int(part) for part in found[3].split(':')]
    day = int(found[2])
    month = MONTHS[found[1]]
    year = datetime.fromtimestamp(reference).year
    seconds = min((time.mktime((year + shift, month, day, *clock, 0, 0, -1)) for shift in (-1, 0, 1)),
                  key=lambda candidate: abs(candidate - reference))
    return dict(t=seconds, time=found[0])


def accepted(text, reference, user=USER):
    records = []
    for line in text.splitlines():
        found = ACCEPTED.search(line)
        if not found or found[1] != user:
            continue
        parsed = stamp(line, reference)
        if parsed is None:
            continue
        keys = found[2].split()
        records.append(dict(user=user, time=parsed['time'], fingerprint=keys[-1] if keys else '', t=parsed['t']))
    return records


def measure(text, started, ended, user=USER):
    minutes = (ended - started) / 60
    if minutes <= 0:
        raise ValueError('BENCHMARK_EMPTY_WINDOW')
    chosen = [record for record in accepted(text, started, user) if started <= record['t'] <= ended]
    return dict(logins=len(chosen), per_minute=round(len(chosen) / minutes, 3),
                retained=[dict(user=record['user'], time=record['time'], fingerprint=record['fingerprint'])
                          for record in chosen])
