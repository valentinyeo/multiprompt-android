import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

RELAY = Path(__file__).resolve().parent / 'relay.py'
RESPONDER = '''import sys
first = sys.stdin.readline().strip()
sys.stdout.write("reply-one-" + first + "\\n")
sys.stdout.flush()
second = sys.stdin.readline().strip()
sys.stdout.write("reply-two-" + second + "\\n")
sys.stdout.write("reply-three\\n")
sys.stdout.flush()
'''


class RelayTest(unittest.TestCase):
    def prepare(self):
        directory = Path(tempfile.mkdtemp())
        (directory / 'responder.py').write_text(RESPONDER)
        return directory, directory / 'relay.jsonl'

    def run_relay(self, command, payload):
        directory, log = self.prepare()
        responder = directory / 'responder.py'
        resolved = command.format(python=sys.executable, responder=responder)
        env = dict(os.environ, SSH_ORIGINAL_COMMAND=resolved, SCALE_RELAY_LOG=str(log))
        result = subprocess.run([sys.executable, str(RELAY)], input=payload, capture_output=True, env=env, timeout=60)
        entries = [json.loads(line) for line in log.read_text().splitlines()]
        direct = subprocess.run([sys.executable, '-B', str(responder)], input=payload, capture_output=True, timeout=60)
        return result, entries, direct

    def test_relay_forwards_every_byte_and_logs_each_reply(self):
        result, entries, direct = self.run_relay('{python} -B {responder}', b'synthetic-request\n')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, direct.stdout)
        opened = [entry for entry in entries if entry['event'] == 'open']
        closed = [entry for entry in entries if entry['event'] == 'close']
        calls = [entry for entry in entries if entry['event'] == 'call']
        self.assertEqual(len(opened), 1)
        self.assertEqual(len(closed), 1)
        self.assertEqual(closed[0]['code'], 0)
        self.assertEqual(len(calls), 3)
        self.assertEqual(calls[0]['bytes'], len('reply-one-synthetic-request\n'))
        self.assertEqual(calls[1]['bytes'], len('reply-two-\n'))
        self.assertEqual(calls[2]['bytes'], len('reply-three\n'))
        self.assertEqual(calls[0]['request'], 1)
        self.assertGreaterEqual(calls[1]['ms'], 0)
        self.assertEqual(closed[0]['bytes'], sum(call['bytes'] for call in calls))
        self.assertEqual(closed[0]['calls'], 3)

    def test_relay_labels_the_history_channel(self):
        result, entries, _direct = self.run_relay('{python} -B {responder} --serve abcdef', b'one\n')
        self.assertEqual(result.returncode, 0)
        self.assertEqual({entry['stream'] for entry in entries}, {'history'})

    def test_relay_exits_when_the_command_finishes_even_if_stdin_stays_open(self):
        directory, log = self.prepare()
        command = '{python} -u -c "print(\'finished\')"'.format(python=sys.executable)
        env = dict(os.environ, SSH_ORIGINAL_COMMAND=command, SCALE_RELAY_LOG=str(log))
        process = subprocess.Popen([sys.executable, str(RELAY)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env)
        try:
            self.assertEqual(process.stdout.readline(), b'finished\n')
            self.assertEqual(process.wait(timeout=30), 0)
            entries = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual(entries[-1]['event'], 'close')
            self.assertEqual(entries[-1]['calls'], 1)
        finally:
            process.kill()

    def test_relay_passes_the_exit_code_through(self):
        result, entries, _direct = self.run_relay('{python} -c "import sys; sys.exit(3)"', b'')
        self.assertEqual(result.returncode, 3)
        self.assertEqual(entries[-1]['event'], 'close')
        self.assertEqual(entries[-1]['code'], 3)


if __name__ == '__main__':
    unittest.main()
