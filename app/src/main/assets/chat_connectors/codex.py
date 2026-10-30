import hashlib
import json
import os
import stat
import subprocess
import uuid

_POLL_BUDGET = 256 * 1024
_QUEUE_TIMEOUT = 10


def _sid(value):
    try:
        parsed = uuid.UUID(value)
        return str(parsed) if str(parsed) == value.lower() else None
    except (ValueError, TypeError, AttributeError):
        return None


def _event_id(*parts):
    data = json.dumps(parts, ensure_ascii=True, separators=(',', ':')).encode()
    return 'codex:' + hashlib.sha256(data).hexdigest()


def _line_end(source, limit):
    while source.tell() < limit:
        chunk = source.read(min(64 * 1024, limit - source.tell()))
        if not chunk:
            return None
        newline = chunk.find(b'\n')
        if newline >= 0:
            return source.tell() - len(chunk) + newline + 1
    return None


def capabilities(context):
    return {'protocol': 1, 'provider': 'codex', 'transport': 'native_queue_cli',
            'exact_thread_target': True, 'text': True, 'attachments': False,
            'terminal_input': False, 'accepted': 'native_queue_success',
            'started': False, 'native_queue_id': False,
            'messages': 'validated_transcript_occurrences',
            'attachment_modes': ['daemon', 'direct'],
            'direct_pickup_interval_seconds': 10,
            'requires': ['codex_attempt_claim', 'codex_attempt_finish']}


def _direct_command(context, sid, text):
    binary = context.get('codex_binary')
    home = context.get('native_user_home')
    codex_home = context.get('codex_home')
    for path in (binary, home, codex_home):
        if not isinstance(path, str) or not os.path.isabs(path) or '\x00' in path:
            return None
    try:
        binary_stat = os.stat(binary)
        home_stat = os.stat(home)
        codex_home_stat = os.stat(codex_home)
        if (not stat.S_ISREG(binary_stat.st_mode) or not os.access(binary, os.X_OK) or
                not stat.S_ISDIR(home_stat.st_mode) or not stat.S_ISDIR(codex_home_stat.st_mode) or
                home_stat.st_uid != os.getuid() or codex_home_stat.st_uid != os.getuid()):
            return None
    except OSError:
        return None
    env = os.environ.copy()
    env['HOME'] = home
    env['CODEX_HOME'] = codex_home
    return [binary, 'queue', '--thread', sid, '--message', text], env


def submit(context, intent, state, services):
    state = dict(state or {})
    sid = _sid(context.get('sid'))
    identity = intent.get('id')
    attempt = intent.get('attempt')
    text = intent.get('text')
    if (not sid or not isinstance(identity, str) or not identity or
            not isinstance(attempt, (str, int)) or isinstance(attempt, bool) or
            not isinstance(text, str) or not text or '\x00' in text):
        return {'handoff': 'not_sent', 'facts': [], 'state': state}
    attachment = context.get('attachment')
    env = None
    if attachment == 'direct':
        direct = _direct_command(context, sid, text)
        if direct is None:
            return {'handoff': 'not_sent', 'facts': [], 'state': state}
        command, env = direct
    elif attachment in (None, 'daemon'):
        endpoint = context.get('daemon_endpoint')
        if (not isinstance(endpoint, str) or not endpoint.startswith('unix:///') or
                '\x00' in endpoint):
            return {'handoff': 'not_sent', 'facts': [], 'state': state}
        try:
            if not stat.S_ISSOCK(os.stat(endpoint[7:]).st_mode):
                return {'handoff': 'not_sent', 'facts': [], 'state': state}
        except OSError:
            return {'handoff': 'not_sent', 'facts': [], 'state': state}
        command = ['codex', 'queue', '--thread', sid, '--message', text,
                   '--remote', endpoint]
    else:
        return {'handoff': 'not_sent', 'facts': [], 'state': state}
    claim = services.get('codex_attempt_claim')
    finish = services.get('codex_attempt_finish')
    if not callable(claim) or not callable(finish):
        return {'handoff': 'not_sent', 'facts': [], 'state': state}
    key = {'provider': 'codex', 'sid': sid, 'id': identity, 'attempt': attempt,
           'epoch': intent.get('epoch'), 'text_sha256': hashlib.sha256(text.encode('utf-8')).hexdigest()}
    try:
        reservation = claim(key)
    except Exception:
        return {'handoff': 'uncertain', 'facts': [], 'state': state}
    if not isinstance(reservation, dict):
        return {'handoff': 'uncertain', 'facts': [], 'state': state}
    status = reservation.get('status')
    receipt = {'kind': 'accepted', 'sid': sid, 'id': identity, 'attempt': attempt,
               'event_id': _event_id('queue-ack', sid, identity, attempt)}
    if status == 'accepted':
        return {'handoff': 'sent', 'facts': [receipt], 'state': state}
    if status != 'claimed':
        return {'handoff': 'uncertain', 'facts': [], 'state': state}
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, env=env)
    except OSError:
        try:
            finish(key, 'not_sent')
        except Exception:
            pass
        return {'handoff': 'not_sent', 'facts': [], 'state': state}
    except Exception:
        try:
            finish(key, 'uncertain')
        except Exception:
            pass
        return {'handoff': 'uncertain', 'facts': [], 'state': state}
    try:
        returncode = process.wait(timeout=_QUEUE_TIMEOUT)
    except Exception:
        try:
            process.kill()
        except Exception:
            pass
        try:
            process.wait(timeout=0.1)
        except Exception:
            pass
        try:
            finish(key, 'uncertain')
        except Exception:
            pass
        return {'handoff': 'uncertain', 'facts': [], 'state': state}
    if returncode != 0:
        try:
            finish(key, 'uncertain')
        except Exception:
            pass
        return {'handoff': 'uncertain', 'facts': [], 'state': state}
    try:
        finish(key, 'accepted')
    except Exception:
        pass
    return {'handoff': 'sent', 'facts': [receipt], 'state': state}


def poll(context, state, pending, services):
    state = dict(state or {})
    sid = _sid(context.get('sid'))
    path = context.get('transcript_path')
    rows = services.get('conversation_rows')
    load = services.get('load_line')
    if not sid or not isinstance(path, str) or not callable(rows) or not callable(load):
        return {'facts': [], 'state': state}
    facts = []
    try:
        with open(path, 'rb') as source:
            stat = os.fstat(source.fileno())
            header_end = _line_end(source, stat.st_size)
            if header_end is None:
                return {'facts': [], 'state': state, 'more': False}
            source.seek(0)
            header = source.read(header_end)
            if len(header) != header_end:
                return {'facts': [], 'state': state, 'more': False}
            meta = load(header)
            payload = meta.get('payload') if isinstance(meta, dict) and meta.get('type') == 'session_meta' else None
            if not isinstance(payload, dict) or payload.get('id') != sid:
                return {'facts': [], 'state': state}
            fingerprint = [stat.st_dev, stat.st_ino]
            cursor = state.get('cursor')
            offset = 0
            if isinstance(cursor, dict) and cursor.get('sid') == sid and cursor.get('file') == fingerprint:
                candidate = cursor.get('offset')
                anchor = cursor.get('anchor')
                if isinstance(candidate, int) and 0 <= candidate <= stat.st_size and isinstance(anchor, str):
                    source.seek(max(0, candidate - 256))
                    if hashlib.sha256(source.read(min(candidate, 256))).hexdigest() == anchor:
                        offset = candidate
            source.seek(offset)
            consumed = 0
            more = False
            while True:
                start = source.tell()
                end = _line_end(source, stat.st_size)
                if end is None:
                    source.seek(start)
                    break
                if consumed and consumed + end - start > _POLL_BUDGET:
                    source.seek(start)
                    more = True
                    break
                source.seek(start)
                raw = source.read(end - start)
                if len(raw) != end - start or not raw.endswith(b'\n'):
                    raise OSError('transcript changed while reading')
                consumed += len(raw)
                event = load(raw)
                for index, row in enumerate(rows(event, 'codex', start)):
                    if not isinstance(row, dict) or row.get('role') not in ('user', 'assistant') or not isinstance(row.get('text'), str):
                        continue
                    occurrence = _event_id('message', sid, start, index, hashlib.sha256(raw).hexdigest())
                    native_id = row.get('id')
                    fact = {'kind': 'message', 'sid': sid, 'event_id': occurrence,
                            'role': row['role'], 'text': row['text'],
                            'row': dict(row, offset=start, end=end),
                            'inode': stat.st_ino, 'offset': start, 'end': end,
                            'cursor': {'offset': start, 'index': index},
                            'timestamp': row.get('timestamp', '')}
                    if isinstance(native_id, str) and native_id:
                        fact['native_id'] = native_id
                    facts.append(fact)
            offset = source.tell()
            source.seek(max(0, offset - 256))
            anchor = hashlib.sha256(source.read(min(offset, 256))).hexdigest()
            state['cursor'] = {'sid': sid, 'file': fingerprint, 'offset': offset, 'anchor': anchor}
    except (OSError, ValueError, TypeError):
        return {'facts': [], 'state': state}
    return {'facts': facts, 'state': state, 'more': more}
