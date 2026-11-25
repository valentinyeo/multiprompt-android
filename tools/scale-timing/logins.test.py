import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import logins


def accepted(epoch, user=logins.USER, stamp=None, key='SHA256:abc'):
    moment = stamp if stamp is not None else datetime.fromtimestamp(epoch, timezone.utc).isoformat()
    return '%s runner sshd[4321]: Accepted publickey for %s from 127.0.0.1 port 5123 ssh2: ED25519 %s' % (
        moment, user, key)


class WindowTest(unittest.TestCase):
    def test_lines_inside_the_window_count_and_lines_outside_do_not(self):
        text = '\n'.join((accepted(99.0), accepted(100.0), accepted(105.0), accepted(106.0))) + '\n'
        found = logins.measure(text, 100.0, 105.0)
        self.assertEqual(found['logins'], 2)
        self.assertEqual(found['per_minute'], 24.0)

    def test_other_users_do_not_count(self):
        text = '\n'.join((accepted(102.0, user='root'), accepted(102.0, user='git'))) + '\n'
        self.assertEqual(logins.measure(text, 100.0, 110.0)['logins'], 0)
        self.assertEqual(logins.measure(text + accepted(103.0) + '\n', 100.0, 110.0)['logins'], 1)

    def test_multiple_logins_per_trial_count_correctly(self):
        text = '\n'.join(accepted(101.0 + index, key='SHA256:key%d' % index) for index in range(3)) + '\n'
        found = logins.measure(text, 100.0, 130.0)
        self.assertEqual(found['logins'], 3)
        self.assertEqual(found['per_minute'], 6.0)
        self.assertEqual([record['fingerprint'] for record in found['retained']],
                         ['SHA256:key0', 'SHA256:key1', 'SHA256:key2'])

    def test_retained_lines_carry_only_user_time_and_fingerprint(self):
        text = accepted(102.0, key='SHA256:inside') + '\n'
        found = logins.measure(text, 100.0, 110.0)
        self.assertEqual(found['retained'], [dict(user='verifyssh',
                                                  time='1970-01-01T00:01:42+00:00',
                                                  fingerprint='SHA256:inside')])

    def test_failed_or_unstamped_ssh_lines_are_not_logins(self):
        text = '\n'.join(('Jun  5 12:00:00 runner sshd[1]: Failed password for verifyssh from 127.0.0.1 port 1 ssh2',
                          'Accepted publickey for verifyssh from 127.0.0.1 port 2 ssh2: ED25519 SHA256:abc',
                          'Jun  5 12:00:01 runner sshd[1]: Connection closed by authenticating user verifyssh')) + '\n'
        self.assertEqual(logins.measure(text, 1780000000.0, 1780000100.0)['logins'], 0)

    def test_empty_window_is_a_failure_not_a_zero(self):
        with self.assertRaisesRegex(ValueError, 'BENCHMARK_EMPTY_WINDOW'):
            logins.measure(accepted(102.0), 100.0, 100.0)


class StampTest(unittest.TestCase):
    def test_offset_stamps_are_read_as_written(self):
        moment = datetime(2026, 6, 5, 12, 0, 0, tzinfo=timezone.utc)
        text = accepted(moment.timestamp(), stamp='2026-06-05T12:00:00.123456+00:00')
        self.assertEqual(logins.measure(text, moment.timestamp(), moment.timestamp() + 1)['logins'], 1)

    def test_syslog_stamps_are_read_in_server_local_time(self):
        epoch = datetime(2026, 6, 5, 12, 0, 0).timestamp()
        text = accepted(epoch, stamp='Jun  5 12:00:00')
        self.assertEqual(logins.measure(text, epoch - 30, epoch + 30)['logins'], 1)
        self.assertEqual(logins.measure(text, epoch + 30, epoch + 60)['logins'], 0)

    def test_january_syslog_line_inside_a_december_window_counts(self):
        started = datetime(2026, 12, 31, 23, 59, 50).timestamp()
        text = accepted(started, stamp='Jan  1 00:00:02')
        self.assertEqual(logins.measure(text, started, started + 30)['logins'], 1)
        self.assertEqual(logins.measure(text, started - 60, started - 10)['logins'], 0)


if __name__ == '__main__':
    unittest.main()
