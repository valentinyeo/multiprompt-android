import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import authwatch
import logins

KEY = 'SHA256:5NZ/i7zl4hYtA1liJHq2bm/aEVDJZHjqF2293bqPJWo'
SSHD_LINE = ('Accepted publickey for verifyssh from 10.0.2.2 port 40122 '
             'ssh2: ED25519 ' + KEY)
STARTUP = 'Server listening on 0.0.0.0 port 2222.'


class FollowTest(unittest.TestCase):
    def watched(self, directory):
        source = Path(directory) / 'sshd.log'
        stamped = Path(directory) / 'auth.log'
        source.write_bytes(STARTUP.encode() + b'\r\n')
        stop = threading.Event()
        reading = open(source, 'rb')
        writing = open(stamped, 'w', encoding='utf-8', buffering=1)
        thread = threading.Thread(target=authwatch.follow, args=(reading, writing),
                                  kwargs=dict(poll=0.01, should_stop=stop.is_set), daemon=True)
        thread.start()
        time.sleep(0.05)
        return source, stamped, stop, thread, (reading, writing)

    def finish(self, stamped, stop, thread, handles, expected):
        deadline = time.monotonic() + 10
        while len(stamped.read_text().splitlines()) < expected and time.monotonic() < deadline:
            time.sleep(0.01)
        stop.set()
        thread.join(10)
        for handle in handles:
            handle.close()
        return stamped.read_text()

    def test_sshd_lines_get_one_stamp_each_and_old_lines_are_not_replayed(self):
        with tempfile.TemporaryDirectory() as directory:
            source, stamped, stop, thread, handles = self.watched(directory)
            started = time.time()
            with open(source, 'ab') as appending:
                appending.write((SSHD_LINE + '\r\n').encode())
                appending.write((SSHD_LINE.replace('verifyssh', 'root') + '\r\n').encode())
            ended = time.time() + 1.0
            text = self.finish(stamped, stop, thread, handles, 2)

        self.assertEqual(len(text.splitlines()), 2)
        self.assertNotIn(STARTUP, text)
        self.assertNotIn('\r', text)
        found = logins.measure(text, started, ended)
        self.assertEqual(found['logins'], 1)
        self.assertGreater(found['per_minute'], 0)
        self.assertEqual(len(found['retained']), 1)
        self.assertEqual(found['retained'][0]['user'], 'verifyssh')
        self.assertEqual(found['retained'][0]['fingerprint'], KEY)
        self.assertEqual(found['retained'][0]['time'], text.splitlines()[0].split(' ', 1)[0])
        moment = logins.stamp(text.splitlines()[0], started)
        self.assertGreaterEqual(moment['t'], started)
        self.assertLessEqual(moment['t'], ended)
        self.assertEqual(logins.measure(text, ended, ended + 60)['logins'], 0)

    def test_a_line_written_in_two_pieces_is_stamped_once_when_it_is_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            source, stamped, stop, thread, handles = self.watched(directory)
            with open(source, 'ab') as appending:
                appending.write(('Accepted publickey for verifyssh from 10.0.2.2 port 40122 ').encode())
                appending.flush()
            time.sleep(0.1)
            with open(source, 'ab') as appending:
                appending.write(('ssh2: ED25519 ' + KEY + '\n').encode())
            text = self.finish(stamped, stop, thread, handles, 1)

        self.assertEqual(len(text.splitlines()), 1)
        self.assertIn(KEY, text)
        self.assertIn('port 40122 ssh2', text)


if __name__ == '__main__':
    unittest.main()
