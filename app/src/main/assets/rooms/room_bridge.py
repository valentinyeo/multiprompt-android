import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
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
            fields(request, ('op',))
            return {'room_protocol': 1, 'host_uuid': self.store.host_uuid, 'store_only': True, 'live_send': False, 'reason': 'unsupported_remote_capability'}
        if op == 'status':
            fields(request, ('op', 'host_uuid', 'sid'))
            if identifier(request['host_uuid']) != self.store.host_uuid:
                fail('different_host')
            status = host_status(request['sid'])
            return {'provider': 'claude', 'sid': status['sid'], 'enabled': status['enabled'], 'owner': status.get('owner'), 'alive': status.get('alive')}
        if op in ('post_human', 'agent_reply', 'delivery_work'):
            fail('unsupported_remote_capability')
        if op not in ('list', 'snapshot', 'events', 'deliveries', 'create', 'edit_members', 'edit'):
            fail('unknown_op')
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
        return self.store.execute(command)


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
