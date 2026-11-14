import importlib.util
import sys
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("app_name_select_run", Path(__file__).with_name("select_run.py"))
select_run = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = select_run
spec.loader.exec_module(select_run)

COMMIT = "a" * 40
OTHER = "b" * 40


def run(identifier, number, status="completed", conclusion="success", sha=COMMIT, **extra):
    value = {
        "id": identifier,
        "run_number": number,
        "created_at": "2026-11-14T00:%02d:00Z" % number,
        "path": ".github/workflows/release.yml",
        "head_branch": "main",
        "head_sha": sha,
        "status": status,
        "conclusion": conclusion,
    }
    value.update(extra)
    return value


def lookup(*runs):
    return select_run.lookup({"workflow_runs": list(runs)}, COMMIT)


class SelectRunTest(unittest.TestCase):
    def test_one_success(self):
        self.assertEqual(lookup(run(11, 1)), "success 11")

    def test_cancelled_plus_success(self):
        cancelled = run(10, 1, conclusion="cancelled")
        good = run(11, 2)
        self.assertEqual(lookup(cancelled, good), "success 11")
        self.assertEqual(lookup(good, cancelled), "success 11")

    def test_cancelled_newer_than_success(self):
        self.assertEqual(lookup(run(10, 1), run(11, 2, conclusion="cancelled")), "success 10")

    def test_skipped_plus_success(self):
        self.assertEqual(lookup(run(10, 1, conclusion="skipped"), run(11, 2)), "success 11")

    def test_two_successes_newest_wins(self):
        self.assertEqual(lookup(run(10, 1), run(11, 2)), "success 11")
        self.assertEqual(lookup(run(11, 2), run(10, 1)), "success 11")

    def test_run_number_beats_created_at(self):
        older = run(10, 1, created_at="2026-11-14T09:00:00Z")
        newer = run(11, 2, created_at="2026-11-14T01:00:00Z")
        self.assertEqual(lookup(older, newer), "success 11")

    def test_only_cancelled_keeps_waiting(self):
        self.assertEqual(lookup(run(10, 1, conclusion="cancelled")), "waiting")
        self.assertEqual(
            lookup(run(10, 1, conclusion="cancelled"), run(11, 2, conclusion="skipped")),
            "waiting",
        )

    def test_none(self):
        self.assertEqual(select_run.lookup({"workflow_runs": []}, COMMIT), "waiting")

    def test_other_commit_ignored(self):
        self.assertEqual(lookup(run(10, 1, sha=OTHER)), "waiting")
        self.assertEqual(lookup(run(10, 1, sha=OTHER), run(11, 2)), "success 11")

    def test_other_branch_and_path_ignored(self):
        self.assertEqual(lookup(run(10, 1, head_branch="feature")), "waiting")
        self.assertEqual(lookup(run(10, 1, path=".github/workflows/other.yml")), "waiting")

    def test_failure_still_fails(self):
        self.assertEqual(lookup(run(10, 1, conclusion="failure")), "failed")
        self.assertEqual(lookup(run(10, 1, conclusion="cancelled"), run(11, 2, conclusion="failure")), "failed")

    def test_in_progress_waits(self):
        running = run(11, 2, status="in_progress", conclusion=None)
        self.assertEqual(lookup(running), "waiting")
        self.assertEqual(lookup(run(10, 1, conclusion="cancelled"), running), "waiting")
        self.assertEqual(lookup(run(10, 1, conclusion="failure"), running), "waiting")

    def test_success_beats_in_progress(self):
        self.assertEqual(
            lookup(run(10, 1), run(11, 2, status="in_progress", conclusion=None)),
            "success 10",
        )

    def test_unexpected_status_rejected(self):
        with self.assertRaises(AssertionError):
            lookup(run(10, 1, status="mystery", conclusion=None))

    def test_pending_with_conclusion_rejected(self):
        with self.assertRaises(AssertionError):
            lookup(run(10, 1, status="in_progress", conclusion="success"))

    def test_more_than_ten_runs_rejected(self):
        with self.assertRaises(AssertionError):
            lookup(*[run(i, i) for i in range(1, 12)])


if __name__ == "__main__":
    unittest.main()
