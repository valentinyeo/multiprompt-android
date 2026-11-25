import json
import re

PROC_FIELDS_BEFORE_UTIME = 11

LOGCAT_LINE = re.compile(r'^\s*(\d+(?:\.\d+)?)\s+\d+\s+\d+\s+[VDIWEF]\s+([^:]+?)\s*:\s(.*)$')
DISPLAYED_OFFSET = re.compile(r'\+(?:(\d+)s)?(\d+)ms')


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


def parse_thread_sample(text):
    found = {}
    for line in text.splitlines():
        parts = line.strip().split(' ', 2)
        if len(parts) < 2 or not parts[0].isdigit() or not parts[1].isdigit():
            continue
        found[parts[0]] = dict(ticks=int(parts[1]), name=(parts[2].strip() if len(parts) > 2 else ''))
    return found


def thread_deltas(before, after, clk_tck=100):
    deltas = []
    for tid, current in after.items():
        previous = before.get(tid)
        if previous is None:
            continue
        ticks = current['ticks'] - previous['ticks']
        if ticks <= 0:
            continue
        deltas.append(dict(tid=tid, name=current['name'], ticks=ticks, ms=round(ticks * 1000 / clk_tck, 1)))
    deltas.sort(key=lambda item: item['ms'], reverse=True)
    return deltas


def thread_total(deltas, tid=None):
    return round(sum(item['ms'] for item in deltas if tid is None or item['tid'] == tid), 1)


def parse_launch_log(text, package):
    marks = {}
    for line in text.splitlines():
        found = LOGCAT_LINE.match(line)
        if not found:
            continue
        epoch = float(found.group(1))
        tag = found.group(2).strip()
        body = found.group(3)
        if 'Start proc' in body and package in body and 'process' not in marks:
            marks['process'] = epoch
        elif tag == 'MpaStartup' and body.startswith('application') and 'application' not in marks:
            marks['application'] = epoch
        elif tag == 'MpaStartup' and body.startswith('view-model') and 'view_model' not in marks:
            marks['view_model'] = epoch
        elif tag == 'MpaStartup' and body.startswith('inbox') and 'inbox' not in marks:
            marks['inbox'] = epoch
        elif body.startswith('Displayed ') and package in body and 'displayed' not in marks:
            marks['displayed'] = epoch
            marks['displayed_reported_ms'] = duration_ms(body)
        elif body.startswith('Fully drawn ') and package in body and 'fully_drawn' not in marks:
            marks['fully_drawn'] = epoch
            marks['fully_drawn_reported_ms'] = duration_ms(body)
    return marks


def duration_ms(line):
    found = DISPLAYED_OFFSET.search(line)
    if not found:
        return None
    seconds = int(found.group(1) or 0)
    millis = int(found.group(2))
    return seconds * 1000 + millis


def relay_open_times(events, stream=None, since=None):
    moments = []
    for event in events:
        if event.get('event') != 'open':
            continue
        if stream is not None and event.get('stream') != stream:
            continue
        moment = event.get('t')
        if not isinstance(moment, (int, float)):
            continue
        if since is not None and moment < since:
            continue
        moments.append(moment)
    return sorted(moments)


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
