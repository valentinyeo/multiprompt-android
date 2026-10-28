import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("working_check", Path(__file__).with_name("check.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class WorkingEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.samples = [dict(at=at, visible=10000 <= at < 145000,
            interactive=not 55000 <= at < 58000) for at in range(9000, 147001, 100)]
        self.server = [dict(event="start", at=10000), dict(event="end", at=145000)]
        self.server += [dict(event="tool", at=at) for at in range(10000, 145000, 1000)]
        self.journey = [dict(event="clock", at=0, device_at=0), dict(event="lock", at=55000),
            dict(event="unlock", at=58000), dict(event="disconnect", at=90000, authentications=1),
            dict(event="reconnected", at=92000, authentications=2)]

    def test_complete_quiet_turn(self):
        self.assertEqual(check.verify(self.samples, self.server, self.journey)["tools"], 135)

    def test_missing_dot_interval_rejected(self):
        broken = copy.deepcopy(self.samples)
        for row in broken:
            if 70000 <= row["at"] < 72000:
                row["visible"] = False
        with self.assertRaisesRegex(AssertionError, "dots missing"):
            check.verify(broken, self.server, self.journey)

    def test_late_start_rejected(self):
        for row in self.samples:
            if row["at"] < 11500:
                row["visible"] = False
        with self.assertRaisesRegex(AssertionError, "appeared too late"):
            check.verify(self.samples, self.server, self.journey)

    def test_late_end_rejected(self):
        for row in self.samples:
            if 145000 <= row["at"] < 146500:
                row["visible"] = True
        with self.assertRaisesRegex(AssertionError, "cleared too late"):
            check.verify(self.samples, self.server, self.journey)

    def test_missing_sample_interval_rejected(self):
        broken = [row for row in self.samples if not 70000 <= row["at"] < 72000]
        with self.assertRaisesRegex(AssertionError, "sampling gap"):
            check.verify(broken, self.server, self.journey)

    def test_fake_reconnect_rejected(self):
        self.journey[-1]["authentications"] = 1
        with self.assertRaisesRegex(AssertionError, "No real SSH reconnect"):
            check.verify(self.samples, self.server, self.journey)


if __name__ == "__main__":
    unittest.main()
