import base64
import datetime
import concurrent.futures
import json
import hashlib
import struct
import zlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

FAKE_CLI = r'''#!/usr/bin/env python3
import datetime, json, os, sys
from pathlib import Path
state_path = Path(os.environ['FAKE_BOARD'])
state = json.loads(state_path.read_text())
args = sys.argv[1:]
with open(os.environ['FAKE_CALLS'], 'a') as stream:
    stream.write(json.dumps(args) + '\n')
if os.environ.get('FAKE_MALFORMED'):
    print('{')
    sys.exit(0)
if os.environ.get('FAKE_FAIL'):
    sys.exit(1)
if not args or args.pop(0) != '--json':
    sys.exit(2)
def option(name, default=None):
    return args[args.index(name) + 1] if name in args else default
def page(key, values):
    offset = int(option('--cursor', '0'))
    selected = values[offset:offset + 1]
    more = offset + 1 < len(values)
    cursor = str(offset + 1) if more or (key == 'comments' and selected) else None
    response = dict(success=True, nextCursor=cursor, **{key:selected})
    if key == 'comments':
        response['has_more'] = more
    print(json.dumps(response))
if args == ['status']:
    human = os.environ.get('FAKE_HUMAN') == '1'
    print(json.dumps(dict(authenticated=True, hasToken=True, identity='user' if human else 'agent', agentId=None if human else os.environ['HT_TOKEN'].replace('credential-', 'agent-'))))
elif args[:2] == ['labels', 'list']:
    page('labels', [dict(name='Multiprompt'), dict(name='Agent Feedback')])
elif args[:2] == ['task', 'list']:
    page('tasks', [task for task in state['tasks'] if task['status'] == option('--status')])
elif args[:2] == ['task', 'create']:
    number = 1000 + len(state['tasks'])
    task = dict(title=option('--title'), ticketNumber='IGSH-' + str(number), status='Normal', labels=[dict(name=name) for name in option('--labels').split(',')], description=option('--description'))
    state['tasks'].append(task)
    state_path.write_text(json.dumps(state))
    print(json.dumps(dict(success=True, task=task)))
elif args[:2] == ['task', 'get']:
    if os.environ.get('FAKE_FAIL_READBACK'): sys.exit(1)
    task = next(task for task in state['tasks'] if task['ticketNumber'] == args[2])
    if os.environ.get('FAKE_FAIL_AFTER_UPLOAD') and task.get('attachments'): sys.exit(1)
    print(json.dumps(dict(success=True, tasks=[dict(ticketNumber=args[2], attachments=task.get('attachments', []))])))
elif args[:2] == ['task', 'update']:
    if os.environ.get('FAKE_FAIL_UPLOAD'): sys.exit(1)
    task = next(task for task in state['tasks'] if task['ticketNumber'] == args[2])
    path = Path(option('--attach'))
    task.setdefault('attachments', []).append(dict(fileName=path.name, fileType='image/png', fileSize=path.stat().st_size, fileSource='synthetic'))
    state_path.write_text(json.dumps(state))
    if os.environ.get('FAKE_LOST_UPLOAD_RESPONSE'): sys.exit(1)
    print(json.dumps(dict(success=True)))
elif args[:2] == ['comment', 'list']:
    if '--include-activity' not in args: sys.exit(2)
    activity = state['comments'].get(args[2], [])
    if os.environ.get('FAKE_BAD_ACTIVITY'): activity = {'text': 'bad'}
    print(json.dumps(dict(success=True, comments=[], agent_activity=activity, nextCursor=None, has_more=False)))
elif args[:2] == ['comment', 'add']:
    if os.environ.get('FAKE_FAIL_VOTE'):
        sys.exit(1)
    if '--kind' in args: sys.exit(2)
    comment = dict(id='activity-id', runId='run-id', type='thought', text='multibot-feedback-vote:v1', agentId=os.environ['HT_TOKEN'].replace('credential-', 'agent-'), agentName='Agent', createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
    state['comments'].setdefault(args[2], []).append(comment)
    state_path.write_text(json.dumps(state))
    print(json.dumps(dict(success=True, routedTo='log')))
else:
    sys.exit(2)
'''


class BoardFeedbackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.work = self.home / 'private-workspace'
        self.work.mkdir()
        self.bin = self.home / 'bin'
        self.bin.mkdir()
        fake = self.bin / 'hypertask'
        fake.write_text(FAKE_CLI)
        fake.chmod(0o700)
        self.board = self.home / 'board.json'
        self.calls = self.home / 'calls.jsonl'
        self.log = self.home / 'state' / 'multibot' / 'feedback.jsonl'
        self.write_board(dict(tasks=[], comments={}))

    def write_board(self, state):
        self.board.write_text(json.dumps(state))

    def read_board(self):
        return json.loads(self.board.read_text())

    def run_cli(self, *args, token='credential-one', **extra):
        env = {key: value for key, value in os.environ.items() if key not in ('HT_TOKEN', 'XDG_STATE_HOME', 'ZSB_SESSION', 'TMUX', 'MULTIBOT_FEEDBACK_LOG')}
        env.update(HOME=str(self.home), XDG_STATE_HOME=str(self.home / 'state'), PATH=str(self.bin) + os.pathsep + env.get('PATH', ''), FAKE_BOARD=str(self.board), FAKE_CALLS=str(self.calls), MULTIBOT_PROVIDER='codex', MULTIBOT_SESSION='private-chat', MULTIBOT_APP_VERSION='1.2.3')
        if token is not None:
            env['HT_TOKEN'] = token
        env.update(extra)
        return subprocess.run([sys.executable, str(ROOT / 'src/multibot_feedback.py'), *args], cwd=self.work, env=env, capture_output=True, text=True, timeout=60)

    def task(self, number, title, labels=('Multiprompt', 'Agent Feedback'), status='Normal'):
        return dict(ticketNumber='IGSH-' + str(number), title=title, status=status, labels=[dict(name=name) for name in labels])

    def vote(self, agent, date):
        return dict(id='activity-id', runId='run-id', type='thought', text='multibot-feedback-vote:v1', agentId=agent, agentName='Agent', createdAt=date.isoformat())

    def png(self, pixel=b'\x12\x34\x56', size=None):
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
        image = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0)) +
                 chunk(b'IDAT', zlib.compress(b'\x00' + pixel)))
        if size is not None:
            image += chunk(b'tEXt', b'x\x00' + b'a' * (size - len(image) - 26))
        return image + chunk(b'IEND', b'')

    def test_distinct_attachments_and_maximum_bounds(self):
        images = []
        for index in range(4):
            path = self.work / f'private-{index}.png'
            data = self.png(bytes([index, 0, 0]), 8 * 1024 * 1024 if index == 3 else None)
            path.write_bytes(data)
            images.append((path, data))
        self.assertEqual(len(images[3][1]), 8 * 1024 * 1024)
        args = [part for path, _ in images for part in ('--attach', str(path))]
        self.assertIn('Feedback submitted:', self.run_cli('four screenshots', *args[:4]).stdout)
        self.assertIn('Feedback voted:', self.run_cli('four screenshots', *args[:4]).stdout)
        self.assertEqual(len(self.read_board()['tasks'][0]['attachments']), 2)
        self.assertIn('Feedback voted:', self.run_cli('four screenshots', *args).stdout)
        self.assertIn('Feedback voted:', self.run_cli('four screenshots', *args).stdout)
        attachments = self.read_board()['tasks'][0]['attachments']
        self.assertEqual(len(attachments), 4)
        self.assertEqual({item['fileName'] for item in attachments},
                         {hashlib.sha256(data).hexdigest() + '.png' for _, data in images})
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertEqual(sum(call[1:3] == ['task', 'update'] for call in calls), 4)

    def test_attachments_retry_dedupe_and_private_filename(self):
        image = self.work / 'private-screenshot.png'
        data = self.png()
        image.write_bytes(data)
        digest = hashlib.sha256(data).hexdigest()
        first = self.run_cli('screenshots', '--attach', str(image), FAKE_LOST_UPLOAD_RESPONSE='1')
        self.assertIn('Board submission not confirmed', first.stdout)
        self.assertNotIn('Feedback submitted:', first.stdout)
        for _ in range(2):
            result = self.run_cli('screenshots', '--attach', str(image), '--attach', str(image))
            self.assertIn('Feedback voted:', result.stdout)
        board = self.read_board()
        self.assertEqual(len(board['tasks']), 1)
        self.assertEqual(len(board['tasks'][0]['attachments']), 1)
        self.assertEqual(board['tasks'][0]['attachments'][0]['fileName'], digest + '.png')
        self.assertNotIn(str(image), json.dumps(board))
        self.assertNotIn(image.name, json.dumps(board))
        self.assertIn(digest, self.log.read_text())
        self.assertNotIn(image.name, self.log.read_text())
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertEqual(sum(call[1:3] == ['task', 'update'] for call in calls), 1)
        self.assertTrue(all(str(image) not in json.dumps(call) for call in calls))

    def test_attachment_new_existing_and_failures(self):
        image = self.work / 'image.png'
        image.write_bytes(self.png())
        result = self.run_cli('new attachment', '--attach', str(image))
        self.assertIn('Feedback submitted:', result.stdout)
        self.assertIn('Feedback submitted:', self.run_cli('existing attachment').stdout)
        failed = self.run_cli('existing attachment', '--attach', str(image), FAKE_FAIL_UPLOAD='1')
        self.assertIn('Board submission not confirmed', failed.stdout)
        self.assertNotIn('Feedback voted:', failed.stdout)
        failed = self.run_cli('existing attachment', '--attach', str(image), FAKE_FAIL_READBACK='1')
        self.assertIn('Board submission not confirmed', failed.stdout)
        failed = self.run_cli('existing attachment', '--attach', str(image), FAKE_FAIL_AFTER_UPLOAD='1')
        self.assertIn('Board submission not confirmed', failed.stdout)
        self.assertNotIn('Feedback voted:', failed.stdout)
        self.assertIn('Feedback voted:', self.run_cli('existing attachment', '--attach', str(image)).stdout)
        self.assertEqual(len(self.read_board()['tasks'][1]['attachments']), 1)

    def test_invalid_attachments_rejected_before_board(self):
        image = self.work / 'invalid.png'
        for data in (b'bad', self.png()[:-1], b'\x89PNG\r\n\x1a\n' + b'x' * (8 * 1024 * 1024)):
            image.write_bytes(data)
            result = self.run_cli('invalid attachment', '--attach', str(image))
            self.assertEqual(result.returncode, 2)
        result = self.run_cli('missing attachment', '--attach', str(self.work / 'missing.png'))
        self.assertEqual(result.returncode, 2)
        result = self.run_cli('too many', *sum((['--attach', str(image)] for _ in range(5)), []))
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.run_cli('--rank', '--attach', str(image)).returncode, 2)
        self.assertFalse(self.calls.exists())
        self.assertFalse(self.log.exists())

    def test_reader_bundle_installs_the_real_tool_and_missing_source_is_a_negative_control(self):
        source = (ROOT / 'src/chat_history_reader.py').read_text()
        feedback = (ROOT / 'src/multibot_feedback.py').read_bytes()
        bootstrap = "import base64,sys;s=base64.b64decode(sys.stdin.buffer.readline());exec(compile(s,'<bundle>','exec'))"
        for include in (False, True):
            home = self.home / ('bundled' if include else 'missing')
            home.mkdir()
            prefix = 'MULTIBOT_APP_VERSION = "1.2.3"\n'
            if include:
                prefix += 'FEEDBACK_SOURCE = ' + repr(base64.b64encode(feedback).decode()) + '\n'
            payload = base64.b64encode((prefix + source).encode()) + b'\n' + b'{"id":1,"op":"engine_info"}\n'
            env = dict(os.environ, HOME=str(home))
            result = subprocess.run([sys.executable, '-c', bootstrap, '--serve'], input=payload,
                                    capture_output=True, env=env, timeout=60)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn(b'engine_info', result.stdout)
            installed = home / '.multiprompt-runtime' / 'multibot-feedback'
            self.assertEqual(installed.exists(), include)
            if include:
                self.assertEqual(installed.read_bytes(), feedback)
                self.assertEqual(installed.stat().st_mode & 0o777, 0o700)

    def test_create_repeat_other_agent_and_safe_metadata(self):
        first = self.run_cli('Browser  screenshots', '--why', 'Capture is slow')
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertIn('Feedback submitted:', first.stdout)
        second = self.run_cli(' browser screenshots ', token='credential-two')
        again = self.run_cli('BROWSER screenshots', token='credential-two')
        self.assertIn('Feedback voted:', second.stdout)
        self.assertIn('Feedback voted:', again.stdout)
        board = self.read_board()
        self.assertEqual(len(board['tasks']), 1)
        task = board['tasks'][0]
        self.assertEqual(task['title'], 'Multibot wish: browser screenshots')
        self.assertEqual({label['name'] for label in task['labels']}, {'Multiprompt', 'Agent Feedback'})
        self.assertIn(task['ticketNumber'], board['comments'])
        self.assertEqual(len(board['comments'][task['ticketNumber']]), 2)
        for heading in ('Problem', 'Fix', 'Done when', 'Risk', 'Decision'):
            self.assertIn('<strong>' + heading + '</strong>', task['description'])
        self.assertIn('Capture is slow', task['description'])
        self.assertIn('Browser  screenshots', task['description'])
        self.assertIn('The requested capability works in Multibot.', task['description'])
        self.assertIn('1.2.3', task['description'])
        self.assertIn('codex', task['description'])
        for private in ('private-workspace', 'private-chat', 'credential-one', str(self.home)):
            self.assertNotIn(private, json.dumps(board))
        self.assertTrue(self.log.exists())
        self.assertEqual(len(self.log.read_text().splitlines()), 3)
        self.assertIn('private-chat', self.log.read_text())

    def test_wrong_app_exact_title_pagination_and_deleted(self):
        state = dict(tasks=[self.task(1, 'Multibot wish: capture', ('Other', 'Agent Feedback')), self.task(2, 'Multibot wish: capture more'), self.task(3, 'Multibot wish: capture', status='Archive')], comments={})
        self.write_board(state)
        result = self.run_cli('capture')
        self.assertEqual(result.stdout.strip(), 'Feedback voted: https://app.hypertask.ai/detail/project-2060/3')
        self.assertEqual(len(self.read_board()['tasks']), 3)
        state['tasks'].append(self.task(4, 'Multibot wish: deleted', status='Deleted'))
        self.write_board(state)
        result = self.run_cli('deleted')
        self.assertIn('Board submission not confirmed', result.stdout)
        self.assertEqual(len(self.read_board()['tasks']), 4)

    def test_weekly_rank_counts_distinct_agents_and_sorts_ties(self):
        now = datetime.datetime.now(datetime.timezone.utc)
        start = (now - datetime.timedelta(days=now.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
        state = dict(tasks=[self.task(1, 'Multibot wish: beta'), self.task(2, 'Multibot wish: alpha'), self.task(3, 'Multibot wish: old'), self.task(4, 'Multibot wish: leading')], comments={'IGSH-1': [self.vote('a', start), self.vote('a', start)], 'IGSH-2': [self.vote('b', start)], 'IGSH-3': [self.vote('c', start - datetime.timedelta(seconds=1))], 'IGSH-4': [self.vote('d', start), self.vote('e', start)]})
        self.write_board(state)
        result = self.run_cli('--weekly')
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = result.stdout.splitlines()
        self.assertEqual(len(lines), 3)
        self.assertIn('2\tMultibot wish: leading', lines[0])
        self.assertIn('1\tMultibot wish: alpha', lines[1])
        self.assertIn('1\tMultibot wish: beta', lines[2])
        self.assertNotIn('old', result.stdout)

    def test_failed_vote_after_creation_is_not_reported_as_success_and_retry_converges(self):
        failed = self.run_cli('a safe feature', FAKE_FAIL_VOTE='1')
        self.assertIn('Board submission not confirmed', failed.stdout)
        self.assertNotIn('Feedback submitted:', failed.stdout)
        self.assertEqual(len(self.read_board()['tasks']), 1)
        retry = self.run_cli('a safe feature')
        self.assertIn('Feedback voted:', retry.stdout)
        self.assertEqual(len(self.read_board()['tasks']), 1)
        comments = self.read_board()['comments']
        self.assertEqual(len(comments), 1)
        self.assertEqual(len(next(iter(comments.values()))), 1)

    def test_simultaneous_submissions_and_retry_share_one_ticket_and_vote(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            results = list(pool.map(lambda _: self.run_cli('concurrent capability'), range(3)))
        results.append(self.run_cli('concurrent capability'))
        self.assertTrue(all('Feedback ' in result.stdout and 'Board submission not confirmed' not in result.stdout for result in results))
        board = self.read_board()
        self.assertEqual(len(board['tasks']), 1)
        self.assertIn(board['tasks'][0]['ticketNumber'], board['comments'])
        self.assertEqual(len(board['comments'][board['tasks'][0]['ticketNumber']]), 1)

    def test_full_activity_window_refuses_unverifiable_vote(self):
        task = self.task(1, 'Multibot wish: crowded')
        self.write_board(dict(tasks=[task], comments={'IGSH-1': [self.vote('a', datetime.datetime.now(datetime.timezone.utc))] * 100}))
        result = self.run_cli('crowded')
        self.assertIn('activity limit reached', result.stdout)
        self.assertEqual(len(self.read_board()['comments']['IGSH-1']), 100)

    def test_malformed_activity_fails_closed(self):
        task = self.task(1, 'Multibot wish: bad activity')
        self.write_board(dict(tasks=[task], comments={}))
        result = self.run_cli('bad activity', FAKE_BAD_ACTIVITY='1')
        self.assertIn('invalid activity response', result.stdout)
        self.assertEqual(self.read_board()['comments'], {})

    def test_secret_is_neither_saved_nor_printed(self):
        result = self.run_cli('credential-one')
        self.assertEqual(result.returncode, 2)
        self.assertNotIn('credential-one', result.stdout + result.stderr)
        self.assertFalse(self.log.exists())
        self.assertFalse(self.calls.exists())

    def test_missing_token_does_not_invoke_default_login(self):
        result = self.run_cli('a capability', token=None)
        self.assertIn('Board submission not confirmed', result.stdout)
        self.assertFalse(self.calls.exists())
        self.assertTrue(self.log.exists())

    def test_missing_token_human_failure_malformed_and_private_text_stay_local(self):
        for token, extra, wish in [(None, {}, 'no token'), ('agent-one', {'FAKE_HUMAN': '1'}, 'human login'), ('agent-one', {'FAKE_FAIL': '1'}, 'failed call'), ('agent-one', {'FAKE_MALFORMED': '1'}, 'bad reply'), ('agent-one', {}, 'open /private/notes')]:
            with self.subTest(wish=wish):
                result = self.run_cli(wish, token=token, **extra)
                self.assertEqual(result.returncode, 0)
                self.assertIn('Board submission not confirmed', result.stdout)
                self.assertNotIn('agent-one', result.stdout + result.stderr)
        self.assertEqual(self.read_board()['tasks'], [])
        self.assertTrue(self.log.exists())
        self.assertEqual(len(self.log.read_text().splitlines()), 5)
        self.assertTrue(self.calls.exists())
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertFalse(any(call[1:3] in (['task', 'create'], ['comment', 'add']) for call in calls))

    def test_common_session_names_do_not_block_wishes_or_attach_context(self):
        for session, wish in [('k', 'keyboard shortcut'), ('browser', 'browser capture')]:
            with self.subTest(session=session):
                result = self.run_cli(wish, MULTIBOT_SESSION=session)
                self.assertIn('Feedback submitted:', result.stdout)
        board = self.read_board()
        self.assertEqual(len(board['tasks']), 2)
        for private in ('private-workspace', 'private-chat', str(self.home)):
            self.assertNotIn(private, json.dumps(board))
        self.assertNotIn('Session type: browser', json.dumps(board))
        self.assertNotIn('Session type: k', json.dumps(board))

    def test_description_escapes_deliberate_input(self):
        result = self.run_cli('capture <screen>', '--why', 'Less <work> & time')
        self.assertIn('Feedback submitted:', result.stdout)
        tasks = self.read_board()['tasks']
        self.assertEqual(len(tasks), 1)
        self.assertIn('capture &lt;screen&gt;', tasks[0]['description'])
        self.assertIn('Less &lt;work&gt; &amp; time', tasks[0]['description'])


if __name__ == '__main__':
    unittest.main()
