import argparse
import json
import os
import re
import sqlite3
import stat
import sys
import uuid
from pathlib import Path


class RoomError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def fail(code):
    raise RoomError(code)


def identifier(value):
    if not isinstance(value, str):
        fail('invalid_id')
    try:
        if str(uuid.UUID(value)) != value:
            fail('invalid_id')
    except ValueError:
        fail('invalid_id')
    return value


def text(value, limit):
    if not isinstance(value, str) or not value or '\x00' in value:
        fail('invalid_text')
    try:
        if len(value.encode('utf-8')) > limit:
            fail('invalid_text')
    except UnicodeError:
        fail('invalid_text')
    return value


def number(value, minimum=0, maximum=9223372036854775807):
    if type(value) is not int or not minimum <= value <= maximum:
        fail('invalid_number')
    return value


def fields(value, keys):
    if not isinstance(value, dict) or set(value) != set(keys):
        fail('invalid_fields')


def new_id():
    return str(uuid.uuid4())


def encoded(value):
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(',', ':'), allow_nan=False)


class RoomStore:
    def __init__(self, directory):
        path = Path(directory)
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
        if os.name != 'posix':
            raise RuntimeError('owner-only storage requires POSIX permissions')
        info = path.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise RuntimeError('state directory must be owner-only')
        dbpath = path / 'rooms.sqlite3'
        fd = os.open(str(dbpath), os.O_CREAT | os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0), 0o600)
        info = os.fstat(fd)
        os.close(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise RuntimeError('database must be owner-only')
        self.db = sqlite3.connect(str(dbpath), timeout=30, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.executescript('''
            CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS rooms (creation_seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, name TEXT NOT NULL, revision INTEGER NOT NULL, seq INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS members (id TEXT PRIMARY KEY, room_id TEXT NOT NULL REFERENCES rooms(id), alias TEXT NOT NULL, display_name TEXT NOT NULL, host_uuid TEXT NOT NULL, provider TEXT NOT NULL, provider_sid TEXT NOT NULL, session_identity TEXT NOT NULL, active INTEGER NOT NULL);
            CREATE UNIQUE INDEX IF NOT EXISTS active_alias ON members(room_id, alias) WHERE active=1;
            CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, room_id TEXT NOT NULL REFERENCES rooms(id), seq INTEGER NOT NULL, body TEXT NOT NULL, UNIQUE(room_id, seq));
            CREATE TABLE IF NOT EXISTS deliveries (delivery_seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, room_id TEXT NOT NULL, event_id TEXT NOT NULL REFERENCES events(id), event_seq INTEGER NOT NULL, member_id TEXT NOT NULL REFERENCES members(id), status TEXT NOT NULL, UNIQUE(event_id, member_id));
            CREATE INDEX IF NOT EXISTS delivery_pages ON deliveries(room_id, delivery_seq);
            CREATE TABLE IF NOT EXISTS commands (id TEXT PRIMARY KEY, body TEXT NOT NULL, receipt TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS responses (room_id TEXT NOT NULL, member_id TEXT NOT NULL, response_id TEXT NOT NULL, body TEXT NOT NULL, receipt TEXT NOT NULL, PRIMARY KEY(room_id, member_id, response_id));
            CREATE TABLE IF NOT EXISTS reply_progress (delivery_id TEXT PRIMARY KEY REFERENCES deliveries(id), after_seq INTEGER, turn TEXT, parts TEXT NOT NULL, done INTEGER NOT NULL, checked REAL NOT NULL);
        ''')
        self.db.execute('INSERT OR IGNORE INTO meta VALUES (?, ?)', ('host_uuid', new_id()))
        self.host_uuid = self.db.execute('SELECT value FROM meta WHERE key=?', ('host_uuid',)).fetchone()[0]

    def close(self):
        self.db.close()

    def room(self, room_id):
        identifier(room_id)
        row = self.db.execute('SELECT * FROM rooms WHERE id=?', (room_id,)).fetchone()
        if row is None:
            fail('room_not_found')
        return row

    def members(self, room_id):
        return [dict(row) for row in self.db.execute('SELECT id, alias, display_name, host_uuid, provider, provider_sid, session_identity FROM members WHERE room_id=? AND active=1 ORDER BY alias', (room_id,))]

    def event(self, room_id, kind, payload):
        self.db.execute('UPDATE rooms SET seq=seq+1 WHERE id=?', (room_id,))
        seq = self.room(room_id)['seq']
        event = {'id': new_id(), 'room_id': room_id, 'seq': seq, 'type': kind, **payload}
        self.db.execute('INSERT INTO events VALUES (?, ?, ?, ?)', (event['id'], room_id, seq, encoded(event)))
        return event

    def validate_members(self, members):
        if not isinstance(members, list) or len(members) > 64:
            fail('invalid_members')
        aliases = set()
        bindings = set()
        for member in members:
            fields(member, ('alias', 'display_name', 'host_uuid', 'provider', 'provider_sid', 'session_identity'))
            alias = member['alias']
            if not isinstance(alias, str) or not re.fullmatch(r'[a-z][a-z0-9_-]{0,31}', alias):
                fail('invalid_alias')
            text(member['display_name'], 256)
            identifier(member['host_uuid'])
            if member['host_uuid'] != self.host_uuid:
                fail('different_host')
            if member['provider'] not in ('claude', 'codex'):
                fail('invalid_provider')
            text(member['provider_sid'], 512)
            text(member['session_identity'], 512)
            binding = (member['host_uuid'], member['provider'], member['provider_sid'])
            if alias in aliases or binding in bindings:
                fail('duplicate_member')
            aliases.add(alias)
            bindings.add(binding)

    def replace_members(self, room_id, desired):
        self.validate_members(desired)
        existing = {m['alias']: m for m in self.members(room_id)}
        wanted = {m['alias']: m for m in desired}
        for alias, old in existing.items():
            if alias in wanted:
                new = wanted[alias]
                if any(old[k] != new[k] for k in ('host_uuid', 'provider', 'provider_sid', 'session_identity')):
                    fail('binding_immutable')
            else:
                self.db.execute('UPDATE members SET active=0 WHERE id=?', (old['id'],))
                self.db.execute("UPDATE deliveries SET status='cancelled' WHERE member_id=? AND status='pending'", (old['id'],))
        for alias, member in wanted.items():
            if alias in existing:
                self.db.execute('UPDATE members SET display_name=? WHERE id=?', (member['display_name'], existing[alias]['id']))
            else:
                self.db.execute('INSERT INTO members VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)', (new_id(), room_id, alias, member['display_name'], member['host_uuid'], member['provider'], member['provider_sid'], member['session_identity']))

    def execute(self, request):
        if not isinstance(request, dict) or not isinstance(request.get('op'), str):
            fail('invalid_fields')
        try:
            json.dumps(request, ensure_ascii=False, allow_nan=False).encode('utf-8')
            if len(encoded(request).encode('utf-8')) > 131072:
                fail('request_too_large')
        except (TypeError, ValueError, UnicodeError, RecursionError):
            fail('invalid_json')
        op = request['op']
        if op in ('capabilities', 'list', 'snapshot', 'events', 'deliveries', 'delivery_work'):
            self.db.execute('BEGIN')
            try:
                result = self.view(request)
                self.db.execute('COMMIT')
                return result
            except BaseException:
                self.db.execute('ROLLBACK')
                raise
        if op not in ('create', 'edit_members', 'edit', 'post_human', 'agent_reply'):
            fail('unknown_op')
        identifier(request.get('command_id'))
        body = encoded(request)
        self.db.execute('BEGIN IMMEDIATE')
        try:
            previous = self.db.execute('SELECT body, receipt FROM commands WHERE id=?', (request['command_id'],)).fetchone()
            if previous:
                if previous['body'] != body:
                    fail('command_conflict')
                result = json.loads(previous['receipt'])
            else:
                result = self.command(request)
                self.db.execute('INSERT INTO commands VALUES (?, ?, ?)', (request['command_id'], body, encoded(result)))
            self.db.execute('COMMIT')
            return result
        except BaseException:
            self.db.execute('ROLLBACK')
            raise

    def command(self, r):
        op = r['op']
        if op == 'create':
            fields(r, ('op', 'command_id', 'name', 'members'))
            text(r['name'], 256)
            room_id = new_id()
            self.db.execute('INSERT INTO rooms (id, name, revision, seq) VALUES (?, ?, 1, 0)', (room_id, r['name']))
            self.replace_members(room_id, r['members'])
            event = self.event(room_id, 'created', {'name': r['name'], 'revision': 1, 'members': self.members(room_id)})
            return {'event': event, 'deliveries': []}
        room = self.room(r.get('room_id'))
        room_id = room['id']
        if op in ('edit_members', 'edit'):
            fields(r, ('op', 'command_id', 'room_id', 'expected_revision', 'name', 'members') if op == 'edit' else ('op', 'command_id', 'room_id', 'expected_revision', 'members'))
            if number(r['expected_revision'], 1) != room['revision']:
                fail('revision_conflict')
            if op == 'edit':
                text(r['name'], 256)
            self.replace_members(room_id, r['members'])
            revision = room['revision'] + 1
            if op == 'edit':
                self.db.execute('UPDATE rooms SET name=?, revision=? WHERE id=?', (r['name'], revision, room_id))
                event = self.event(room_id, 'room_edited', {'name': r['name'], 'revision': revision, 'members': self.members(room_id)})
            else:
                self.db.execute('UPDATE rooms SET revision=? WHERE id=?', (revision, room_id))
                event = self.event(room_id, 'members_edited', {'revision': revision, 'members': self.members(room_id)})
            return {'event': event, 'deliveries': []}
        if op == 'post_human':
            fields(r, ('op', 'command_id', 'room_id', 'expected_revision', 'text', 'recipients'))
            if number(r['expected_revision'], 1) != room['revision']:
                fail('revision_conflict')
            message = text(r['text'], 65536)
            if not message.strip():
                fail('invalid_text')
            recipients = r['recipients']
            if not isinstance(recipients, list) or len(recipients) > 64 or any(not isinstance(a, str) for a in recipients) or len(set(recipients)) != len(recipients):
                fail('invalid_recipients')
            mentions = []
            for match in re.finditer(r'(?<![\w@.\\+-])@', message):
                alias = re.match(r'[A-Za-z0-9_-]+', message[match.end():])
                if not alias:
                    fail('invalid_mentions')
                mentions.append(alias.group())
            active = {m['alias']: m for m in self.members(room_id)}
            if any(a not in active for a in mentions) or set(mentions) != set(recipients):
                fail('invalid_mentions')
            targets = [active[a] for a in sorted(set(mentions))] if mentions else list(active.values())
            event = self.event(room_id, 'human', {'text': message, 'revision': room['revision'], 'recipients': [m['id'] for m in targets]})
            deliveries = []
            for member in targets:
                delivery = {'id': new_id(), 'room_id': room_id, 'event_id': event['id'], 'event_seq': event['seq'], 'member_id': member['id'], 'status': 'pending'}
                cursor = self.db.execute('INSERT INTO deliveries (id, room_id, event_id, event_seq, member_id, status) VALUES (?, ?, ?, ?, ?, ?)', tuple(delivery.values()))
                delivery['delivery_seq'] = cursor.lastrowid
                deliveries.append(delivery)
            return {'event': event, 'deliveries': deliveries}
        fields(r, ('op', 'command_id', 'room_id', 'parent_event_id', 'member_id', 'response_id', 'text'))
        for key in ('parent_event_id', 'member_id', 'response_id'):
            identifier(r[key])
        text(r['text'], 65536)
        response_body = encoded({k: r[k] for k in ('room_id', 'parent_event_id', 'member_id', 'response_id', 'text')})
        old = self.db.execute('SELECT body, receipt FROM responses WHERE room_id=? AND member_id=? AND response_id=?', (room_id, r['member_id'], r['response_id'])).fetchone()
        if old:
            if old['body'] != response_body:
                fail('response_conflict')
            return json.loads(old['receipt'])
        delivery = self.db.execute("SELECT d.id FROM deliveries d JOIN members m ON m.id=d.member_id WHERE d.room_id=? AND d.event_id=? AND d.member_id=? AND d.status='pending' AND m.active=1", (room_id, r['parent_event_id'], r['member_id'])).fetchone()
        if not delivery:
            fail('invalid_scope')
        event = self.event(room_id, 'agent_reply', {'parent_event_id': r['parent_event_id'], 'member_id': r['member_id'], 'response_id': r['response_id'], 'text': r['text']})
        result = {'event': event, 'deliveries': []}
        self.db.execute('INSERT INTO responses VALUES (?, ?, ?, ?, ?)', (room_id, r['member_id'], r['response_id'], response_body, encoded(result)))
        return result

    def view(self, r):
        op = r['op']
        if op == 'capabilities':
            fields(r, ('op',))
            return {'room_protocol': 1, 'host_uuid': self.host_uuid, 'store_only': True}
        if op == 'list':
            fields(r, ('op', 'after', 'through', 'limit'))
            after = number(r['after'])
            limit = number(r['limit'], 1, 200)
            current = self.db.execute('SELECT COALESCE(MAX(creation_seq), 0) FROM rooms').fetchone()[0]
            if r['through'] is None:
                if after != 0:
                    fail('invalid_cursor')
                through = current
            else:
                through = number(r['through'])
            if after > through or through > current:
                fail('invalid_cursor')
            rooms = [dict(row) for row in self.db.execute('SELECT creation_seq, id, name, revision, seq FROM rooms WHERE creation_seq>? AND creation_seq<=? ORDER BY creation_seq LIMIT ?', (after, through, limit))]
            return {'rooms': rooms, 'through': through, 'next_after': rooms[-1]['creation_seq'] if rooms else after}
        if op == 'snapshot':
            metadata = 'metadata' in r
            fields(r, ('op', 'room_id', 'metadata') if metadata else ('op', 'room_id'))
            if metadata and r['metadata'] is not True:
                fail('invalid_fields')
            room = dict(self.room(r['room_id']))
            room['members'] = self.members(room['id'])
            if metadata:
                row = self.db.execute("SELECT body FROM events WHERE room_id=? AND json_extract(body, '$.type') IN ('human', 'agent_reply') ORDER BY seq DESC LIMIT 1", (room['id'],)).fetchone()
                room['preview'] = None
                if row:
                    event = json.loads(row['body'])
                    author = None
                    if event['type'] == 'agent_reply':
                        member = self.db.execute('SELECT id, alias, display_name, host_uuid, provider, provider_sid, session_identity FROM members WHERE room_id=? AND id=?', (room['id'], event['member_id'])).fetchone()
                        if member is None:
                            fail('invalid_scope')
                        author = dict(member)
                    room['preview'] = {'seq': event['seq'], 'type': event['type'], 'text': event['text'][:256], 'member_id': event.get('member_id'), 'author': author}
                if len(encoded({'ok': True, 'result': room}).encode('utf-8')) > 262144:
                    fail('snapshot_too_large')
            return room
        if op == 'delivery_work':
            fields(r, ('op', 'room_id', 'delivery_id'))
            self.room(r['room_id'])
            identifier(r['delivery_id'])
            row = self.db.execute("SELECT d.id, d.event_id, d.event_seq, m.id AS member_id, m.host_uuid, m.provider, m.provider_sid, m.session_identity, e.body FROM deliveries d JOIN members m ON m.id=d.member_id JOIN events e ON e.id=d.event_id WHERE d.room_id=? AND d.id=? AND d.status='pending' AND m.active=1", (r['room_id'], r['delivery_id'])).fetchone()
            if row is None:
                fail('invalid_scope')
            event = json.loads(row['body'])
            return {'message_id': row['id'], 'room_id': r['room_id'], 'parent_event_id': row['event_id'], 'event_seq': row['event_seq'], 'member_id': row['member_id'], 'host_uuid': row['host_uuid'], 'provider': row['provider'], 'provider_sid': row['provider_sid'], 'session_identity': row['session_identity'], 'text': event['text']}
        if op == 'deliveries':
            fields(r, ('op', 'room_id', 'after', 'through', 'limit'))
            room = self.room(r['room_id'])
            after = number(r['after'])
            limit = number(r['limit'], 1, 200)
            if r['through'] is None:
                if after != 0:
                    fail('invalid_cursor')
                through = room['seq']
            else:
                through = number(r['through'])
            if through > room['seq']:
                fail('invalid_cursor')
            deliveries = [dict(row) for row in self.db.execute('SELECT delivery_seq, id, room_id, event_id, event_seq, member_id, status FROM deliveries WHERE room_id=? AND delivery_seq>? AND event_seq<=? ORDER BY delivery_seq LIMIT ?', (room['id'], after, through, limit))]
            return {'deliveries': deliveries, 'through': through, 'next_after': deliveries[-1]['delivery_seq'] if deliveries else after}
        fields(r, ('op', 'room_id', 'after', 'limit'))
        self.room(r['room_id'])
        after = number(r['after'])
        limit = number(r['limit'], 1, 200)
        events = []
        for row in self.db.execute('SELECT body FROM events WHERE room_id=? AND seq>? ORDER BY seq LIMIT ?', (r['room_id'], after, limit)):
            event = json.loads(row[0])
            result = {'events': events + [event], 'next_after': event['seq']}
            if len(encoded({'ok': True, 'result': result}).encode('utf-8')) > 262144:
                if not events:
                    fail('event_too_large')
                break
            events.append(event)
        return {'events': events, 'next_after': events[-1]['seq'] if events else after}


def serve(directory):
    store = RoomStore(directory)
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
                response = {'ok': True, 'result': store.execute(request)}
            except RoomError as exc:
                response = {'ok': False, 'error': exc.code}
            except (ValueError, UnicodeError, RecursionError):
                response = {'ok': False, 'error': 'invalid_json'}
            print(encoded(response), flush=True)
    finally:
        store.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--serve', action='store_true', required=True)
    parser.add_argument('--state-dir', required=True)
    args = parser.parse_args()
    serve(args.state_dir)
