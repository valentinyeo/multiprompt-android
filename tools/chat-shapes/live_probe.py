#!/usr/bin/env python3
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import probe

ROOT = Path(__file__).resolve().parents[2]
CURRENT_READER = ROOT / 'app/src/main/assets/chat_history_reader.py'
MIRROR_SUFFIX = '.history.jsonl'
MIRROR_LAG_LIMIT = 3600
LOG_WINDOW = 262144
TAIL_WINDOW = 262144
INIT_SESSIONS = 8
CACHE_BYTES = 2 * 1024 * 1024
STALE_SHARES = (0.5, 0.9)


def moment_of(value):
    if not isinstance(value, str):
        return None
    try:
        moment = datetime.fromisoformat(value.replace('Z', '+00:00'))
        return moment.timestamp()
    except (ValueError, OverflowError, OSError):
        return None


def tail_bytes(path, window):
    try:
        with Path(path).open('rb') as source:
            size = source.seek(0, os.SEEK_END)
            start = max(0, size - window)
            source.seek(start)
            return start > 0, source.read()
    except OSError:
        return None


def init_sessions(path):
    tail = tail_bytes(path, LOG_WINDOW)
    if tail is None:
        return []
    partial, data = tail
    found = []
    for raw in data.splitlines()[1 if partial else 0:]:
        try:
            event = json.loads(raw)
        except ValueError:
            continue
        if not isinstance(event, dict) or event.get('event') != 'init':
            continue
        detail = event.get('detail')
        session = detail.get('session') if isinstance(detail, dict) else None
        if isinstance(session, str):
            found.append(session)
    return found[-INIT_SESSIONS:]


def newest_row_moment(path):
    tail = tail_bytes(path, TAIL_WINDOW)
    if tail is None:
        return None
    _partial, data = tail
    best = None
    for raw in data.splitlines():
        try:
            event = json.loads(raw)
        except ValueError:
            continue
        row = event.get('row') if isinstance(event, dict) and event.get('type') == 'multiprompt_history' else event
        if not isinstance(row, dict):
            continue
        moment = moment_of(row.get('timestamp'))
        if moment is not None and (best is None or moment > best):
            best = moment
    return best


def known_sessions(reader, config, state, sid, log):
    values = list(reversed(init_sessions(log))) + [sid, state.get('sid'), state.get('context_session'),
                                                  state.get('native_session'), config.get('native_session')]
    episodes = reader['SESSION_ID']
    known = []
    for value in values:
        if isinstance(value, str) and episodes.fullmatch(value) and value not in known:
            known.append(value)
    return known


def session_transcripts(reader, agent, cwd, sessions):
    found = {}
    for session in sessions:
        try:
            path = reader['conversation_path'](agent, session, cwd)
        except (OSError, ValueError):
            path = None
        if path is None:
            continue
        try:
            moment = Path(path).stat().st_mtime_ns
        except OSError:
            continue
        found.setdefault(str(path), (moment, session))
    return found


def latest_transcript(paths):
    best = None
    for path, (moment, session) in paths.items():
        if best is None or moment > best[0]:
            best = (moment, path, session)
    return best


def loaded_session(agent, path, paths):
    known = paths.get(path)
    if known is not None:
        return known[1]
    stem = Path(path).stem
    if agent == 'pi' and '_' in stem:
        return stem.rsplit('_', 1)[-1]
    return stem


def query_for(chat):
    return dict(id=1, provider=chat['provider'], codex=chat['provider'] == 'codex', sid=chat['sid'],
                tmux=chat['name'], cwd=chat['cwd'], follow=False, offset=0, before=0, cached_sid='',
                inode=0, mtime=0, probe='', start=0, no_jump=True, source_offsets=True)


def frame_view(reader, chat, cached=None):
    query = query_for(chat)
    if cached:
        query.update(cached)
    resolved = reader['resolve_response_path'](dict(query))
    _agent, session, path, _delegated = resolved
    wire = reader['response'](dict(query), None, False, resolved)
    header = wire.splitlines()[0].split('\t') if wire else []
    page = None
    if len(header) == 15 and header[3] == 'ok':
        page = dict(sid=header[4], base=int(header[5]), end=int(header[6]), inode=int(header[7]),
                    mode=header[13], oldest=int(header[14]), probe=header[12], mtime=int(header[11]),
                    path=str(path) if path is not None else None)
    rows = []
    for line in wire.splitlines()[1:]:
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if isinstance(row, dict) and row.get('role') in ('user', 'assistant'):
            rows.append(row)
    return dict(session=session, path=path, page=page, rows=rows)


def boundary_probe(path, offset):
    try:
        with Path(path).open('rb') as source:
            source.seek(max(0, offset - 64))
            return hashlib.sha256(source.read(min(offset, 64))).hexdigest()
    except OSError:
        return None


def cache_position(page, rows):
    kept = []
    used = 0
    for row in reversed(rows):
        size = len(json.dumps(row, separators=(',', ':'))) + 1
        if used + size + 1024 > CACHE_BYTES:
            break
        kept.insert(0, row)
        used += size
    start = page['oldest'] if len(kept) == len(rows) else (kept[0]['offset'] if kept else page['end'])
    cached = dict(cached_sid=page['sid'], inode=page['inode'], offset=page['end'], mtime=page['mtime'],
                  probe=page['probe'], start=start)
    return cached, kept


def cache_verdict(reader, chat, page, rows):
    if page is None or page['mode'] == 'older':
        return None
    if page['mode'] == 'delta' and not rows:
        return None
    if any(not isinstance(row.get('offset'), int) or not isinstance(row.get('end'), int) for row in rows):
        return 'frame_row_without_offsets'
    if not any(row['role'] == 'assistant' for row in rows):
        return 'frame_without_assistant_rows'
    cached, kept = cache_position(page, rows)
    try:
        again = frame_view(reader, chat, cached)
    except Exception as error:
        return 'cache_' + type(error).__name__
    page2 = again['page']
    if page2 is None:
        return 'cache_frame_header'
    if page2['mode'] == 'delta' and (page2['base'] != cached['offset'] or page2['oldest'] != cached['start']):
        return 'cache_delta_position'
    fresh = again['rows']
    for row in fresh:
        if not (page2['base'] <= row['offset'] and row['end'] <= page2['end'] and row['end'] > row['offset']):
            return 'cache_row_outside_page'
    merged = list(fresh) if page2['mode'] == 'tail' else kept + fresh
    if not any(row['role'] == 'assistant' for row in merged):
        return 'cache_dropped_assistant_rows'
    newest = max(rows, key=lambda row: (row['offset'], row['role'] == 'assistant'))
    if newest['role'] == 'assistant' and newest['offset'] not in {row['offset'] for row in merged}:
        return 'cache_dropped_newest_assistant'
    return None


def stale_cache_verdict(reader, chat, page, rows):
    if page is None or page['mode'] == 'older' or len(rows) < 8 or not page.get('path'):
        return None
    for share in STALE_SHARES:
        index = int(len(rows) * share)
        if index <= 0 or index >= len(rows):
            continue
        row = rows[index]
        if not isinstance(row.get('offset'), int):
            continue
        cached = dict(cached_sid=page['sid'], inode=page['inode'], offset=row['offset'], mtime=0,
                      probe=boundary_probe(page['path'], row['offset']), start=rows[0]['offset'])
        try:
            again = frame_view(reader, chat, cached)
        except Exception as error:
            return 'stale_cache_' + type(error).__name__
        page2 = again['page']
        if page2 is None:
            return 'stale_cache_frame_header'
        if page2['mode'] == 'delta' and (page2['base'] != cached['offset'] or page2['oldest'] != cached['start']):
            return 'stale_cache_delta_position'
        fresh = again['rows']
        for fresh_row in fresh:
            if not (page2['base'] <= fresh_row['offset'] and fresh_row['end'] <= page2['end'] and fresh_row['end'] > fresh_row['offset']):
                return 'stale_cache_row_outside_page'
        merged = list(fresh) if page2['mode'] == 'tail' else rows[:index + 1] + fresh
        if not any(fresh_row['role'] == 'assistant' for fresh_row in merged):
            return 'stale_cache_dropped_assistant_rows'
        newest = max(rows, key=lambda fresh_row: (fresh_row['offset'], fresh_row['role'] == 'assistant'))
        if newest['role'] == 'assistant' and newest['offset'] not in {fresh_row['offset'] for fresh_row in merged}:
            return 'stale_cache_dropped_newest_assistant'
    return None


def phone_view(reader, chat):
    try:
        frame = frame_view(reader, chat)
    except Exception as error:
        return dict(kind='error', session=None, path=None, rows=0, detail=type(error).__name__)
    session, path, rows = frame['session'], frame['path'], frame['rows']
    view = dict(kind='none', session=session or None, path=None, rows=0, detail=None)
    if path is not None:
        view['path'] = str(path)
        view['kind'] = 'mirror' if str(path).endswith(MIRROR_SUFFIX) else 'transcript'
    view['rows'] = len(rows)
    view['assistant'] = sum(1 for row in rows if row.get('role') == 'assistant')
    view['cache'] = stale_cache_verdict(reader, chat, frame['page'], rows) or cache_verdict(reader, chat, frame['page'], rows)
    return view


def judge(view, latest, state):
    if view['kind'] == 'error':
        return 'mismatch', 'reader_error'
    if view.get('cache'):
        return 'mismatch', view['cache']
    if view['kind'] == 'transcript':
        if latest is None:
            return 'skip', 'latest_transcript_unknown'
        if view['path'] == latest[1] or view['session'] == latest[2]:
            return 'match', 'latest_session'
        return 'mismatch', 'older_session'
    if view['kind'] == 'mirror':
        if latest is None:
            return 'skip', 'latest_transcript_unknown'
        if state.get('inflight'):
            return 'skip', 'turn_in_flight'
        seen = newest_row_moment(view['path'])
        latest_seen = newest_row_moment(latest[1])
        if seen is None or latest_seen is None:
            return 'skip', 'timestamp_unknown'
        lag = int(latest_seen - seen)
        view['lag'] = lag
        if lag > MIRROR_LAG_LIMIT:
            return 'mismatch', 'mirror_stale'
        return 'match', 'mirror_current'
    if view['kind'] == 'none':
        if latest is None:
            return 'match', 'no_transcript'
        return 'mismatch', 'missing_transcript'
    return 'skip', 'unknown_view'


def inspect(reader, chat):
    agent, sid, cwd = chat['provider'], chat['sid'], chat['cwd']
    try:
        return examine(reader, chat, agent, sid, cwd)
    except Exception as error:
        return dict(chat=sid, provider=agent, session=None, latest=None, kind='error', rows=0, lag=None,
                    sessions=0, status='mismatch', reason='inspect_failed', error=type(error).__name__)


def examine(reader, chat, agent, sid, cwd):
    key = reader['host_paths'](agent, sid)
    config = reader['host_config'](agent, sid) or {}
    state = reader['host_session_state'](agent, sid)
    sessions = known_sessions(reader, config, state, sid, key['log'])
    paths = session_transcripts(reader, agent, cwd, sessions)
    latest = latest_transcript(paths)
    view = phone_view(reader, chat)
    if view['kind'] == 'transcript':
        view['session'] = loaded_session(agent, view['path'], paths)
    status, reason = judge(view, latest, state)
    record = dict(chat=sid, provider=agent, session=view['session'], latest=latest[2] if latest else None,
                  kind=view['kind'], rows=view['rows'], lag=view.get('lag'), sessions=len(sessions),
                  status=status, reason=reason)
    if view.get('detail'):
        record['error'] = view['detail']
    return record


def probe_home(source, home):
    home = Path(home)
    reader = probe.runtime(source, home)
    results = [inspect(reader, chat) for chat in reader['host_chats']()]
    counts = {}
    for result in results:
        counts[result['status']] = counts.get(result['status'], 0) + 1
    order = {'mismatch': 0, 'skip': 1, 'match': 2}
    results.sort(key=lambda result: (order.get(result['status'], 3), result['chat']))
    return dict(reader=str(source), home=str(home), chats=len(results),
                match=counts.get('match', 0), mismatch=counts.get('mismatch', 0),
                skip=counts.get('skip', 0), results=results)


def plant(home):
    import fixture
    manifest = fixture.create(home)
    item = next(shape for shape in manifest['shapes'] if shape['shape'] == 'compacted')
    entry = fixture.shape('compacted')
    config = fixture.read_json(Path(item['config']))
    config['native_session'] = entry['sid']
    fixture.write_json(Path(item['config']), config)
    return manifest


def main(argv):
    parser = argparse.ArgumentParser(description='Compare the transcript the phone reader loads for every live chat with that chat\'s latest transcript.')
    parser.add_argument('--reader', default=str(CURRENT_READER), help='chat_history_reader.py to run')
    parser.add_argument('--home', default=None, help='host home to inspect, defaults to this user')
    parser.add_argument('--plant-mismatch', action='store_true', help='Prove the probe fails on a synthetic home whose newest chat session is not the one the reader loads')
    arguments = parser.parse_args(argv[1:])
    if arguments.plant_mismatch:
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            plant(home)
            report = probe_home(Path(arguments.reader), home)
        report['home'] = 'synthetic'
    else:
        report = probe_home(Path(arguments.reader), Path(arguments.home or os.environ.get('HOME') or Path.home()))
    json.dump(report, sys.stdout, indent=2)
    sys.stdout.write('\n')
    if report['mismatch']:
        print(f'CHAT_PROBE_MISMATCH chats={report["chats"]} mismatch={report["mismatch"]} skip={report["skip"]}', file=sys.stderr)
        return 1
    print(f'CHAT_PROBE_OK chats={report["chats"]} match={report["match"]} skip={report["skip"]}', file=sys.stderr)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
