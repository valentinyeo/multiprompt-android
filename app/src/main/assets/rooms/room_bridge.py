import base64
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

from room_service import RoomError, RoomStore, encoded, fail, fields, identifier


SID = re.compile(r'[A-Za-z0-9_-]{1,80}\Z')


def private_file(directory, name, limit):
    directory_fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        info = os.fstat(directory_fd)
        if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
            fail('runtime_unavailable')
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory_fd)
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > limit:
                fail('runtime_unavailable')
            with os.fdopen(fd, 'rb', closefd=False) as stream:
                data = stream.read(limit + 1)
            if len(data) > limit:
                fail('runtime_unavailable')
            return data
        finally:
            os.close(fd)
    finally:
        os.close(directory_fd)


def selected_reader():
    directory = Path.home() / '.multiprompt-runtime'
    try:
        active = json.loads(private_file(directory, 'active.json', 4096))
        if not isinstance(active, dict) or set(active) != {'protocol', 'generation', 'sha256'} or type(active['protocol']) is not int or active['protocol'] != 1 or type(active['generation']) is not int or active['generation'] < 1 or not isinstance(active['sha256'], str) or not re.fullmatch('[0-9a-f]{64}', active['sha256']):
            fail('runtime_unavailable')
        source = private_file(directory, active['sha256'] + '.py', 4 * 1024 * 1024)
        if hashlib.sha256(source).hexdigest() != active['sha256']:
            fail('runtime_unavailable')
        reader = private_file(directory, 'reader.py', 4 * 1024 * 1024)
        if reader != source:
            fail('runtime_unavailable')
        return directory / 'reader.py'
    except (OSError, ValueError, UnicodeError):
        fail('runtime_unavailable')


def host_status(sid):
    if not isinstance(sid, str) or not SID.fullmatch(sid):
        fail('invalid_session')
    reader = selected_reader()
    try:
        with tempfile.TemporaryFile() as output:
            completed = subprocess.run([sys.executable, str(reader), 'host', 'status', 'claude', sid], stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.DEVNULL, timeout=5, check=False)
            if completed.returncode != 0 or output.tell() > 32768:
                fail('runtime_unavailable')
            output.seek(0)
            status = json.loads(output.read(32769))
    except (OSError, ValueError, UnicodeError, subprocess.TimeoutExpired):
        fail('runtime_unavailable')
    if not isinstance(status, dict) or status.get('status') != 'ok' or status.get('provider') != 'claude' or status.get('sid') != sid or type(status.get('enabled')) is not bool:
        fail('runtime_unavailable')
    return status


def runtime_request(query):
    reader = selected_reader()
    try:
        source = private_file(reader.parent, 'reader.py', 4 * 1024 * 1024)
        info = json.loads(private_file(reader.parent, 'active.json', 4096))
        if not isinstance(info, dict) or hashlib.sha256(source).hexdigest() != info.get('sha256'):
            fail('runtime_unavailable')
        source = ('ENGINE_INFO = ' + repr(info) + '\n').encode('utf-8') + source
        bootstrap = "import base64,sys;s=base64.b64decode(sys.stdin.buffer.readline()).decode();exec(compile(s,'<room-runtime>','exec'),dict(__name__='__main__',READER_SOURCE=s))"
        input = base64.b64encode(source) + b'\n' + (encoded(dict(query, id=1)) + '\n').encode('utf-8')
        with tempfile.TemporaryFile() as output:
            completed = subprocess.run([sys.executable, '-u', '-c', bootstrap, '--serve'], input=input, stdout=output, stderr=subprocess.DEVNULL, timeout=2, check=False)
            if completed.returncode != 0 or output.tell() > 524288:
                fail('runtime_unavailable')
            output.seek(0)
            response = json.loads(output.read(524289))
    except (OSError, ValueError, UnicodeError, subprocess.TimeoutExpired):
        fail('runtime_unavailable')
    if not isinstance(response, dict) or response.get('id') != 1:
        fail('runtime_unavailable')
    return response


def scoped_runtime():
    try:
        return runtime_request({'op': 'engine_info'}).get('room_message_protocols') == [1]
    except RoomError:
        return False


class RoomBridge:
    def __init__(self, directory):
        self.store = RoomStore(directory)

    def close(self):
        self.store.close()

    def execute(self, request):
        if not isinstance(request, dict):
            fail('invalid_fields')
        op = request.get('op')
        if op == 'capabilities':
            opted_in = 'room_messages' in request
            fields(request, ('op', 'room_messages') if opted_in else ('op',))
            if opted_in and (type(request['room_messages']) is not int or request['room_messages'] != 1):
                fail('unsupported_remote_capability')
            live = opted_in and scoped_runtime()
            result = {'room_protocol': 1, 'host_uuid': self.store.host_uuid, 'store_only': not live, 'live_send': live, 'reason': None if live else 'unsupported_remote_capability'}
            if opted_in:
                result['room_reply_protocol'] = 1 if live else None
            return result
        if op == 'status':
            fields(request, ('op', 'host_uuid', 'sid'))
            if identifier(request['host_uuid']) != self.store.host_uuid:
                fail('different_host')
            status = host_status(request['sid'])
            return {'provider': 'claude', 'sid': status['sid'], 'enabled': status['enabled'], 'owner': status.get('owner'), 'alive': status.get('alive')}
        if op in ('agent_reply', 'delivery_work'):
            fail('unsupported_remote_capability')
        if op not in ('list', 'snapshot', 'events', 'deliveries', 'create', 'edit_members', 'edit', 'post_human'):
            fail('unknown_op')
        if op == 'post_human' and not scoped_runtime():
            fail('unsupported_remote_capability')
        if 'host_uuid' not in request or identifier(request['host_uuid']) != self.store.host_uuid:
            fail('different_host')
        command = {key: value for key, value in request.items() if key != 'host_uuid'}
        if op in ('create', 'edit_members', 'edit'):
            members = command.get('members')
            if not isinstance(members, list) or len(members) > 64:
                fail('invalid_members')
            identifier(command.get('command_id'))
            existing = self.store.db.execute('SELECT body FROM commands WHERE id=?', (command['command_id'],)).fetchone()
            if existing is None:
                self.store.validate_members(members)
                for member in members:
                    previous = None
                    if op == 'edit':
                        previous = self.store.db.execute('SELECT host_uuid, provider, provider_sid, session_identity FROM members WHERE room_id=? AND alias=? AND active=1', (command.get('room_id'), member['alias'])).fetchone()
                    if previous is not None and all(previous[key] == member[key] for key in ('host_uuid', 'provider', 'provider_sid', 'session_identity')):
                        continue
                    if member['provider'] != 'claude':
                        fail('unsupported_provider')
                    status = host_status(member['provider_sid'])
                    if not status['enabled']:
                        fail('host_disabled')
                    if member['session_identity'] != 'claude:' + member['provider_sid']:
                        fail('invalid_session')
        result = self.store.execute(command)
        if op == 'events' and scoped_runtime():
            try:
                self.sync_replies(command['room_id'])
            except RoomError as error:
                if error.code != 'runtime_unavailable':
                    raise
            result = self.store.execute(command)
        return result

    def sync_replies(self, room_id):
        deliveries = self.store.db.execute("SELECT d.id, d.member_id FROM deliveries d JOIN members m ON m.id=d.member_id LEFT JOIN reply_progress p ON p.delivery_id=d.id WHERE d.room_id=? AND d.status='pending' AND m.active=1 AND COALESCE(p.done,0)=0 ORDER BY (SELECT MAX(COALESCE(q.checked,0)) FROM deliveries e LEFT JOIN reply_progress q ON q.delivery_id=e.id WHERE e.member_id=d.member_id AND e.status='pending' AND COALESCE(q.done,0)=0),d.member_id,d.delivery_seq LIMIT 64", (room_id,)).fetchall()
        deadline = time.monotonic() + 2
        waiting = set()
        for delivery in deliveries:
            if time.monotonic() >= deadline:
                break
            if delivery['member_id'] in waiting:
                continue
            self.store.db.execute("INSERT INTO reply_progress VALUES (?,NULL,NULL,'[]',0,?) ON CONFLICT(delivery_id) DO UPDATE SET checked=excluded.checked", (delivery['id'], time.time()))
            work = self.store.execute(dict(op='delivery_work', room_id=room_id, delivery_id=delivery['id']))
            if work['provider'] != 'claude' or work['host_uuid'] != self.store.host_uuid or work['session_identity'] != 'claude:' + work['provider_sid']:
                waiting.add(delivery['member_id'])
                continue
            progress = self.store.db.execute('SELECT * FROM reply_progress WHERE delivery_id=?', (work['message_id'],)).fetchone()
            status = host_status(work['provider_sid'])
            if not status['enabled'] and progress['after_seq'] is None:
                waiting.add(delivery['member_id'])
                continue
            tmux = status.get('tmux')
            if not isinstance(tmux, str) or re.fullmatch(r'[A-Za-z0-9_.:-]{1,80}', tmux) is None:
                fail('runtime_unavailable')
            identity = dict(message_protocol=1, provider='claude', sid=work['provider_sid'], tmux=tmux)
            scope = {key: work[key] for key in ('host_uuid', 'room_id', 'parent_event_id', 'member_id')}
            progress = self.store.db.execute('SELECT * FROM reply_progress WHERE delivery_id=?', (work['message_id'],)).fetchone()
            if progress['after_seq'] is None:
                initial = runtime_request(dict(identity, op='message_status', message_id=work['message_id'])).get('result')
                if not isinstance(initial, dict) or type(initial.get('seq')) is not int or initial['seq'] < 0:
                    fail('runtime_unavailable')
                self.store.db.execute('UPDATE reply_progress SET after_seq=? WHERE delivery_id=? AND after_seq IS NULL', (initial['seq'], work['message_id']))
            if status['enabled']:
                self.store.db.execute('BEGIN IMMEDIATE')
                try:
                    if self.store.view(dict(op='delivery_work', room_id=room_id, delivery_id=work['message_id'])) != work:
                        fail('invalid_scope')
                    submitted = runtime_request(dict(identity, op='message_submit', message_id=work['message_id'], text=work['text'], room_scope=scope)).get('result')
                    if not isinstance(submitted, dict) or not isinstance(submitted.get('message'), dict) or submitted['message'].get('id') != work['message_id']:
                        fail('runtime_unavailable')
                    self.store.db.execute('COMMIT')
                except RoomError as error:
                    self.store.db.execute('ROLLBACK')
                    if error.code == 'invalid_scope':
                        waiting.add(delivery['member_id'])
                        continue
                    raise
                except BaseException:
                    self.store.db.execute('ROLLBACK')
                    raise
            progress = self.store.db.execute('SELECT * FROM reply_progress WHERE delivery_id=?', (work['message_id'],)).fetchone()
            page = runtime_request(dict(op='events_subscribe', provider='claude', sid=work['provider_sid'], after=progress['after_seq'])).get('result')
            if not isinstance(page, dict) or not isinstance(page.get('events'), list) or type(page.get('next_after')) is not int or page['next_after'] < progress['after_seq']:
                fail('runtime_unavailable')
            self.collect_reply(work, scope, progress, page)

    def collect_reply(self, work, scope, progress, page):
        turn = progress['turn']
        parts = json.loads(progress['parts'])
        done = False
        after = progress['after_seq']
        self.store.db.execute('BEGIN IMMEDIATE')
        try:
            current = self.store.db.execute('SELECT after_seq,done FROM reply_progress WHERE delivery_id=?', (work['message_id'],)).fetchone()
            active = self.store.db.execute("SELECT 1 FROM deliveries d JOIN members m ON m.id=d.member_id WHERE d.id=? AND d.status='pending' AND m.active=1", (work['message_id'],)).fetchone()
            if current['done'] or current['after_seq'] != after or active is None:
                self.store.db.execute('COMMIT')
                return
            for row in page['events']:
                if not isinstance(row, dict) or type(row.get('seq')) is not int or not after < row['seq'] <= page['next_after']:
                    fail('runtime_unavailable')
                after = row['seq']
                event = row.get('event')
                if not isinstance(event, dict) or event.get('sid') != work['provider_sid'] or event.get('room_scope') != scope or event.get('message_id') != work['message_id']:
                    continue
                payload = event.get('payload')
                if not isinstance(payload, dict):
                    continue
                if event.get('kind') == 'turn.started' and payload.get('message_id') == work['message_id'] and isinstance(event.get('turn'), str):
                    if turn is not None and turn != event['turn']:
                        fail('ambiguous_room_turn')
                    turn = event['turn']
                elif turn is not None and event.get('turn') == turn and event.get('kind') == 'text.done':
                    if isinstance(event.get('item'), str) and isinstance(payload.get('text'), str):
                        found = next((i for i, part in enumerate(parts) if part[0] == event['item']), None)
                        if found is None:
                            parts.append([event['item'], payload['text']])
                        else:
                            parts[found][1] = payload['text']
                        if len('\n\n'.join(part[1] for part in parts).encode('utf-8')) > 65536:
                            fail('reply_too_large')
                elif turn is not None and event.get('turn') == turn and event.get('kind') == 'turn.ended':
                    text = '\n\n'.join(part[1] for part in parts)
                    if payload.get('status') == 'completed' and text.strip():
                        response_id = str(uuid.uuid5(uuid.NAMESPACE_URL, 'multibot-room-reply:' + work['message_id']))
                        self.store.command(dict(op='agent_reply', command_id=response_id, room_id=work['room_id'], parent_event_id=work['parent_event_id'], member_id=work['member_id'], response_id=response_id, text=text))
                    done = True
                    break
            if not done and page['next_after'] != after:
                fail('runtime_unavailable')
            self.store.db.execute('UPDATE reply_progress SET after_seq=?,turn=?,parts=?,done=? WHERE delivery_id=?', (after, turn, encoded(parts), int(done), work['message_id']))
            self.store.db.execute('COMMIT')
        except BaseException:
            self.store.db.execute('ROLLBACK')
            raise


def serve(directory):
    bridge = RoomBridge(directory)
    try:
        while True:
            line = sys.stdin.buffer.readline(131073)
            if not line:
                break
            try:
                if len(line) > 131072:
                    while not line.endswith(b'\n'):
                        line = sys.stdin.buffer.readline(131073)
                        if not line:
                            break
                    fail('request_too_large')
                def pairs(items):
                    result = {}
                    for key, value in items:
                        if key in result:
                            fail('invalid_json')
                        result[key] = value
                    return result
                request = json.loads(line, object_pairs_hook=pairs, parse_constant=lambda value: fail('invalid_json'))
                result = bridge.execute(request)
                response = {'ok': True, 'result': result}
            except RoomError as error:
                response = {'ok': False, 'error': error.code}
            except (ValueError, UnicodeError, RecursionError, TypeError):
                response = {'ok': False, 'error': 'invalid_json'}
            print(encoded(response), flush=True)
    finally:
        bridge.close()


if __name__ == '__main__':
    if len(sys.argv) != 3 or sys.argv[1] != '--serve':
        sys.exit(2)
    serve(sys.argv[2])
