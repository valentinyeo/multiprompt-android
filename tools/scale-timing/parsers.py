import json
import re

PROC_FIELDS_BEFORE_UTIME = 11


def parse_proc_stat(text):
    close = text.rfind(')')
    if close < 0:
        return dict(utime=0, stime=0, total=0)
    fields = text[close + 1:].split()
    if len(fields) <= PROC_FIELDS_BEFORE_UTIME + 1:
        return dict(utime=0, stime=0, total=0)
    utime = int(fields[PROC_FIELDS_BEFORE_UTIME])
    stime = int(fields[PROC_FIELDS_BEFORE_UTIME + 1])
    return dict(utime=utime, stime=stime, total=utime + stime)


def parse_gfxinfo(text):
    found = dict(frames=0, janky=0, janky_percent=0.0, p50_ms=0, p90_ms=0, p95_ms=0, p99_ms=0,
                 missed_vsync=0, slow_ui=0, slow_draw=0)
    frames = re.search(r'Total frames rendered:\s*(\d+)', text)
    if frames:
        found['frames'] = int(frames.group(1))
    janky = re.search(r'Janky frames:\s*(\d+)\s*\(([\d.]+)%\)', text)
    if janky:
        found['janky'] = int(janky.group(1))
        found['janky_percent'] = float(janky.group(2))
    for name, pattern in (('missed_vsync', r'Number Missed Vsync:\s*(\d+)'),
                          ('slow_ui', r'Number Slow UI thread:\s*(\d+)'),
                          ('slow_draw', r'Number Slow issue draw commands:\s*(\d+)')):
        match = re.search(pattern, text)
        if match:
            found[name] = int(match.group(1))
    for percentile in (50, 90, 95, 99):
        match = re.search(r'%dth percentile:\s*(\d+)ms' % percentile, text)
        if match:
            found['p%d_ms' % percentile] = int(match.group(1))
    return found


def biggest_scrollable(nodes):
    best = None
    for node in nodes:
        if not node.get('scrollable'):
            continue
        bounds = [int(value) for value in re.findall(r'\d+', node.get('bounds') or '')]
        if len(bounds) != 4:
            continue
        area = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1])
        if area <= 0:
            continue
        if best is None or area > best[0]:
            best = (area, bounds)
    return None if best is None else best[1]


def fling_points(bounds, axis='vertical'):
    left, top, right, bottom = bounds
    if axis == 'vertical':
        x = (left + right) // 2
        span = bottom - top
        return [x, int(bottom - span * 0.1), x, int(top + span * 0.1)]
    y = (top + bottom) // 2
    span = right - left
    return [int(right - span * 0.1), y, int(left + span * 0.1), y]


def relay_events(stream):
    events = []
    for line in stream.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            events.append(json.loads(line))
        except ValueError:
            continue
    return events


def calls_in_window(events, started, ended, stream=None):
    chosen = []
    for event in events:
        if event.get('event') != 'call':
            continue
        if stream is not None and event.get('stream') != stream:
            continue
        moment = event.get('t')
        if not isinstance(moment, (int, float)):
            continue
        if started - 0.5 <= moment <= ended + 0.5:
            chosen.append(event)
    return chosen


def call_totals(calls):
    sizes = [call.get('bytes', 0) for call in calls]
    times = [call.get('ms', 0.0) for call in calls]
    total = sum(sizes)
    return dict(calls=len(calls), bytes=total, mean_bytes=round(total / len(calls)) if calls else 0,
                median_bytes=median(sizes), max_bytes=max(sizes, default=0),
                median_ms=median(times), max_ms=max(times, default=0.0))


def median(values):
    ordered = sorted(values)
    if not ordered:
        return 0
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2
