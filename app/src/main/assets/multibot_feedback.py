#!/usr/bin/env python3
import argparse
import datetime
import html
import hashlib
import tempfile
import contextlib
import json
import os
import re
import subprocess
import struct
import zlib
import sys
import time
import unicodedata
import uuid
from pathlib import Path

SUCCESS = 'Feedback saved locally. Board submission not confirmed.'
EMPTY_WANT = 'multibot-feedback: say what you want from Multibot'
PROJECT = '2060'
PREFIX = 'Multibot wish: '
VOTE = 'multibot-feedback-vote:v1'
LABELS = {'Multiprompt', 'Agent Feedback'}


class BoardError(Exception):
    pass


def state_path():
    configured = os.environ.get('MULTIBOT_FEEDBACK_LOG')
    if configured:
        return Path(configured).expanduser()
    root = os.environ.get('XDG_STATE_HOME')
    base = Path(root).expanduser() if root else Path(os.path.expanduser('~/.local/state'))
    return base / 'multibot' / 'feedback.jsonl'


def session_name():
    for name in (os.environ.get('ZSB_SESSION'), os.environ.get('MULTIBOT_SESSION')):
        if name:
            return name
    parts = os.environ.get('TMUX', '').split(',')
    return parts[1] if len(parts) > 1 else ''


def feedback_row(want, why):
    return dict(id=str(uuid.uuid4()), at=int(time.time()), want=want, why=why,
                session=session_name(), provider=os.environ.get('MULTIBOT_PROVIDER', ''),
                app_version=os.environ.get('MULTIBOT_APP_VERSION', ''), cwd=os.path.basename(os.getcwd()))


def save(row):
    path = state_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    with os.fdopen(fd, 'a', encoding='utf-8') as stream:
        stream.write(json.dumps(row, sort_keys=True) + '\n')


def normalized(text):
    return ' '.join(unicodedata.normalize('NFKC', text).split()).casefold()


def contains_secret(text):
    secrets = [value for name, value in os.environ.items()
               if re.search(r'TOKEN|SECRET|PASSWORD|API_KEY', name, re.I)]
    return (any(len(value) >= 6 and value in text for value in secrets if value) or
            bool(re.search(r'(?i)(?:\b(?:bearer\s+\S+|(?:token|password|api[_ -]?key)\s*[:=]\s*\S+)|\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+|\b(?:sk-|ghp_|github_pat_|xox[baprs]-)[A-Za-z0-9_-]{12,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)', text)))


def safe_text(*parts):
    text = '\n'.join(parts)
    private = [os.getcwd(), str(Path.home())]
    if any(value and value in text for value in private) or contains_secret(text):
        return False
    return not re.search(r'(?i)(?:https?://|(?:^|\s)(?:/|~/|[a-z]:\\)|\b[\w.-]+/[\w./-]+)', text)


def snapshot_attachments(paths, directory):
    if len(paths) > 4:
        raise ValueError('at most four attachments are allowed')
    snapshots = []
    for source in paths:
        try:
            with open(source, 'rb') as stream:
                data = stream.read(8 * 1024 * 1024 + 1)
        except (OSError, ValueError):
            raise ValueError('attachment cannot be read') from None
        if len(data) > 8 * 1024 * 1024:
            raise ValueError('attachment exceeds 8 MiB')
        if not data.startswith(b'\x89PNG\r\n\x1a\n'):
            raise ValueError('invalid PNG attachment')
        offset = 8
        chunks = []
        while offset + 12 <= len(data):
            size = int.from_bytes(data[offset:offset + 4], 'big')
            end = offset + 12 + size
            if end > len(data):
                break
            kind = data[offset + 4:offset + 8]
            content = data[offset + 8:offset + 8 + size]
            crc = int.from_bytes(data[offset + 8 + size:end], 'big')
            if zlib.crc32(kind + content) != crc:
                break
            chunks.append(kind)
            if len(chunks) == 1:
                if kind != b'IHDR' or size != 13:
                    break
                width, height, depth, color, compression, filtering, interlace = struct.unpack('>IIBBBBB', content)
                if not (0 < width <= 100000 and 0 < height <= 100000 and
                        depth in {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8), 4: (8, 16), 6: (8, 16)}.get(color, ()) and
                        compression == 0 and filtering == 0 and interlace in (0, 1)):
                    break
            offset = end
            if kind == b'IEND':
                break
        if (offset != len(data) or not chunks or chunks[-1] != b'IEND' or
                data[-12:] != b'\x00\x00\x00\x00IEND\xaeB`\x82' or
                chunks.count(b'IHDR') != 1 or b'IDAT' not in chunks):
            raise ValueError('invalid PNG attachment')
        digest = hashlib.sha256(data).hexdigest()
        path = Path(directory) / (digest + '.png')
        path.write_bytes(data)
        snapshots.append((path, len(data)))
    return snapshots


def attachment_readback(ticket):
    data = cli('task', 'get', ticket)
    tasks = data.get('tasks')
    if (data.get('success') is not True or not isinstance(tasks, list) or len(tasks) != 1 or
            not isinstance(tasks[0], dict) or tasks[0].get('ticketNumber') != ticket or
            not isinstance(tasks[0].get('attachments'), list) or
            any(not isinstance(item, dict) for item in tasks[0]['attachments'])):
        raise BoardError('attachment readback unavailable')
    return tasks[0]['attachments']


def confirm_attachments(ticket, snapshots):
    if not snapshots:
        return
    attachments = attachment_readback(ticket)
    for path, size in snapshots:
        if not any(item.get('fileName') == path.name and item.get('fileSize') == size for item in attachments):
            response = cli('task', 'update', ticket, '--attach', str(path))
            if response.get('success') is not True:
                raise BoardError('attachment upload not confirmed')
            attachments = attachment_readback(ticket)
            if not any(item.get('fileName') == path.name and item.get('fileSize') == size for item in attachments):
                raise BoardError('attachment upload not confirmed')


def cli(*args):
    if not os.environ.get('HT_TOKEN', '').strip():
        raise BoardError('agent token unavailable')
    try:
        result = subprocess.run(['hypertask', '--json', *args], env=dict(os.environ),
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                text=True, timeout=30, check=False)
        data = json.loads(result.stdout)
    except (OSError, subprocess.SubprocessError, ValueError, UnicodeError):
        raise BoardError('board unavailable') from None
    if result.returncode or not isinstance(data, dict) or data.get('success') is False:
        raise BoardError('board request failed')
    return data


def identity():
    status = cli('status')
    agent = status.get('agentId')
    if status.get('authenticated') is not True or status.get('hasToken') is not True or status.get('identity') != 'agent' or not isinstance(agent, str) or not agent.strip():
        raise BoardError('agent identity required')
    return agent


def pages(key, *args):
    cursor = None
    seen = set()
    while True:
        data = cli(*args, '--limit', '100', *(['--cursor', cursor] if cursor else []))
        rows = data.get(key)
        if data.get('success') is not True or 'nextCursor' not in data or not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
            raise BoardError('invalid board response')
        yield from rows
        next_cursor = data.get('nextCursor')
        if data.get('has_more') is False:
            return
        if next_cursor is None:
            if data.get('has_more') is True and (rows or cursor is None):
                raise BoardError('incomplete board response')
            return
        if not isinstance(next_cursor, str) or not next_cursor or next_cursor in seen:
            raise BoardError('invalid board pagination')
        seen.add(next_cursor)
        cursor = next_cursor


def board_labels():
    labels = list(pages('labels', 'labels', 'list', '--project', PROJECT))
    found = {item.get('name') for item in labels if isinstance(item.get('name'), str)}
    if not LABELS <= found:
        raise BoardError('feedback labels unavailable')


def tasks(status):
    for task in pages('tasks', 'task', 'list', '--project', PROJECT, '--status', status):
        if not isinstance(task.get('title'), str) or not isinstance(task.get('labels'), list):
            raise BoardError('invalid task response')
        names = {label.get('name') for label in task['labels'] if isinstance(label, dict)}
        if LABELS <= names and task['title'].startswith(PREFIX):
            if not isinstance(task.get('ticketNumber'), str) or not re.fullmatch(r'IGSH-\d+', task['ticketNumber']):
                raise BoardError('invalid task reference')
            yield task


def comments(task):
    data = cli('comment', 'list', task['ticketNumber'], '--project', PROJECT, '--include-activity', '--limit', '100')
    activity = data.get('agent_activity')
    if data.get('success') is not True or not isinstance(data.get('comments'), list) or not isinstance(activity, list) or any(not isinstance(row, dict) for row in activity):
        raise BoardError('invalid activity response')
    if len(activity) >= 100:
        raise BoardError('activity limit reached; votes cannot be verified')
    return activity


@contextlib.contextmanager
def wish_lock(title):
    try:
        import fcntl
        root = Path(tempfile.gettempdir()) / ('multibot-feedback-' + str(os.getuid()))
        root.mkdir(mode=0o700, exist_ok=True)
        info = root.lstat()
        if info.st_uid != os.getuid() or info.st_mode & 0o077 or not root.is_dir():
            raise OSError('unsafe lock directory')
        path = root / hashlib.sha256(title.encode()).hexdigest()
        fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'r+') as stream:
            fcntl.flock(stream, fcntl.LOCK_EX)
            yield
    except (ImportError, OSError):
        raise BoardError('private submission lock unavailable') from None


def votes(task):
    found = {}
    for comment in comments(task):
        text = comment.get('text', comment.get('commentText'))
        if not isinstance(text, str):
            raise BoardError('invalid comment response')
        plain = html.unescape(re.sub(r'<[^>]*>', '', text)).strip()
        if plain != VOTE:
            continue
        agent = comment.get('agentId')
        stamp = comment.get('createdAt')
        if not isinstance(agent, str) or not agent or not isinstance(stamp, str):
            raise BoardError('invalid vote response')
        try:
            at = datetime.datetime.fromisoformat(stamp.replace('Z', '+00:00'))
            if at.tzinfo is None:
                raise ValueError('missing timezone')
            at = at.astimezone(datetime.timezone.utc)
        except ValueError:
            raise BoardError('invalid vote timestamp') from None
        key = agent
        if key not in found or at < found[key]:
            found[key] = at
    return found


def ticket_url(task):
    return 'https://app.hypertask.ai/detail/project-2060/' + task['ticketNumber'].split('-', 1)[1]


def submit(row, snapshots):
    agent = identity()
    board_labels()
    title = PREFIX + normalized(row['want'])
    version = row['app_version']
    provider = row['provider']
    if len(title) > 200 or not safe_text(row['want'], row['why']) or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?', version) or provider not in ('claude', 'codex', 'pi'):
        raise BoardError('unsafe or missing feedback metadata')
    with wish_lock(normalized(title)):
        return submit_locked(row, agent, title, version, provider, snapshots)


def submit_locked(row, agent, title, version, provider, snapshots):
    matching = []
    for status in ('Normal', 'Archive', 'Deleted'):
        matching += [(status, task) for task in tasks(status) if normalized(task['title']) == normalized(title)]
    if any(status == 'Deleted' for status, _ in matching):
        raise BoardError('matching deleted wish requires review')
    active = [task for status, task in matching if status in ('Normal', 'Archive')]
    if len(active) > 1:
        raise BoardError('duplicate wishes require review')
    created = not active
    if created:
        description = ('<p><strong>Problem</strong></p><p>' + html.escape(row['why'] or 'No reason provided.') + '</p>'
                       '<p><strong>Fix</strong></p><p>' + html.escape(row['want']) + '</p>'
                       '<p><strong>Done when</strong></p><p>The requested capability works in Multibot.</p>'
                       '<p><strong>Risk</strong></p><p>Review the wish before building. Undo changes if needed.</p>'
                       '<p><strong>Decision</strong></p><p>None.</p>'
                       '<p>App version: ' + html.escape(version) + '</p><p>Session type: ' + html.escape(provider) + '</p>')
        response = cli('task', 'create', '--project', PROJECT, '--title', title,
                       '--description', description, '--labels', 'Agent Feedback,Multiprompt')
        if response.get('success') is not True:
            raise BoardError('unconfirmed ticket creation')
        matching = [(status, task) for status in ('Normal', 'Archive', 'Deleted') for task in tasks(status) if normalized(task['title']) == normalized(title)]
        active = [task for status, task in matching if status in ('Normal', 'Archive')]
        if len(matching) != 1 or len(active) != 1:
            raise BoardError('ticket creation could not be confirmed')
    task = active[0]
    if agent not in votes(task):
        response = cli('comment', 'add', task['ticketNumber'], '--project', PROJECT,
                       '--text', '<p>' + VOTE + '</p>')
        if response.get('success') is not True or response.get('routedTo') != 'log' or agent not in votes(task):
            raise BoardError('vote could not be confirmed')
    confirm_attachments(task['ticketNumber'], snapshots)
    return ('submitted' if created else 'voted'), ticket_url(task)


def rank():
    identity()
    board_labels()
    now = datetime.datetime.now(datetime.timezone.utc)
    start = (now - datetime.timedelta(days=now.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
    end = start + datetime.timedelta(days=7)
    ranked = []
    for status in ('Normal', 'Archive'):
        for task in tasks(status):
            count = sum(start <= stamp < end for stamp in votes(task).values())
            if count:
                if contains_secret(task['title']):
                    raise BoardError('unsafe board title')
                ranked.append((count, task['title'], ticket_url(task)))
    ranked.sort(key=lambda item: (-item[0], normalized(item[1]), item[2]))
    for count, title, url in ranked:
        print(f'{count}\t{title}\t{url}')
    if not ranked:
        print('No votes this week.')


def main(argv):
    parser = argparse.ArgumentParser(prog='multibot-feedback', description='Save a wish locally and, with an agent token, submit it to the board.')
    parser.add_argument('want', nargs='?')
    parser.add_argument('--why', default='')
    parser.add_argument('--attach', action='append', default=[], metavar='PNG', help='attach a reviewed PNG (up to four)')
    parser.add_argument('--rank', '--weekly', action='store_true')
    args = parser.parse_args(argv)
    if args.rank:
        if args.want or args.why or args.attach:
            parser.error('ranking does not accept a wish')
        try:
            rank()
        except BoardError as exc:
            print(f'multibot-feedback: ranking unavailable: {exc}', file=sys.stderr)
            return 1
        return 0
    want = (args.want or '').strip()
    if not want:
        print(EMPTY_WANT, file=sys.stderr)
        return 2
    if contains_secret(want + '\n' + args.why):
        print('multibot-feedback: remove credentials from feedback', file=sys.stderr)
        return 2
    row = feedback_row(want, args.why.strip())
    if contains_secret('\n'.join(str(value) for value in row.values())):
        print('multibot-feedback: remove credentials from feedback context', file=sys.stderr)
        return 2
    temporary = tempfile.TemporaryDirectory(prefix='multibot-feedback-') if args.attach else contextlib.nullcontext(None)
    with temporary as directory:
        try:
            snapshots = snapshot_attachments(args.attach, directory)
        except (ValueError, OSError) as exc:
            print(f'multibot-feedback: {exc if isinstance(exc, ValueError) else "cannot snapshot attachment"}', file=sys.stderr)
            return 2
        if snapshots:
            row['attachment_hashes'] = [path.stem for path, _ in snapshots]
        try:
            save(row)
        except OSError:
            print('multibot-feedback: could not save locally', file=sys.stderr)
            return 1
        try:
            outcome, url = submit(row, snapshots)
        except BoardError as exc:
            print(f'Local-only: {SUCCESS} ({exc})')
            return 0
        print(f'Feedback {outcome}: {url}')
        return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
