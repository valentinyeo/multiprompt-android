import importlib.util
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("reader_ownership", Path(__file__).with_name("check.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)
BASELINE = json.loads((ROOT / check.BASELINE).read_text())


class ReaderOwnershipTest(unittest.TestCase):
    def test_current_owners_pass(self):
        self.assertEqual([], check.violations(check.source_files(), BASELINE))

    def test_new_screen_loop_fails(self):
        files = check.source_files()
        path = "app/src/main/java/dev/multiprompt/companion/ui/NewReader.kt"
        files[path] = "LaunchedEffect(Unit) { while (true) repository.streamSession(client, name) {} }"
        failures = check.violations(files, BASELINE)
        self.assertTrue(any(path in f and "SessionReaderConnection" in f for f in failures), failures)

    def test_new_history_owner_cannot_bypass_the_shared_gate(self):
        files = check.source_files()
        path = "app/src/main/java/dev/multiprompt/companion/ui/NewReader.kt"
        files[path] = "val history = ChatHistoryConnection(repository, host, identity, script, cache)"
        self.assertTrue(any(path in f and "second history owner" in f for f in check.violations(files, BASELINE)))

    def test_stop_releases_the_lease_without_waiting_for_a_composition_frame(self):
        files = check.source_files()
        path = "app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt"
        files[path] = files[path].replace("readerLease?.close()\n                    readerLease = null", "readerLease = null", 1)
        self.assertTrue(any(path in f for f in check.violations(files, BASELINE)))

    def test_baseline_is_a_per_file_ratchet_not_a_total(self):
        grown = dict(BASELINE, **{"new.kt": {"streamSession": 1}})
        self.assertTrue(check.baseline_growth(grown, BASELINE))
        self.assertFalse(check.baseline_growth({}, BASELINE))

    def test_real_unguarded_snapshot_loop_before_pr_341_fails(self):
        ref = "c3dc2a3b^"
        path = "app/src/main/java/dev/multiprompt/companion/reader/SessionReaderConnection.kt"
        source = subprocess.check_output(["git", "show", f"{ref}:{path}"], cwd=ROOT, text=True)
        self.assertIn("repository.streamSession(", source)
        self.assertNotIn("ReaderStreamPump", source)
        files = check.source_files()
        files[path] = source
        failures = check.violations(files, BASELINE)
        self.assertTrue(any(path in f and "ChatPollGate" in f for f in failures), failures)
        print("PR_341_PAST_MISTAKE_REJECTED: c3dc2a3b^ has a snapshot loop without a selected-session owner")

    def test_real_remote_capture_loop_from_pr_341_does_not_exit_on_close(self):
        path = "app/src/main/java/dev/multiprompt/companion/ssh/TmuxCommands.kt"
        source = subprocess.check_output(["git", "show", f"c3dc2a3b:{path}"], cwd=ROOT, text=True)
        self.assertIn("EXIT HUP INT TERM", source)
        files = check.source_files()
        files[path] = source
        self.assertTrue(any(path in f for f in check.violations(files, BASELINE)))
        print("PR_341_REMOTE_CLOSE_REJECTED: c3dc2a3b cleans up on hangup but does not exit its capture loop")

    def test_real_hidden_hax_history_before_pr_328_fails(self):
        path = "app/src/main/java/dev/multiprompt/companion/reader/ChatHistoryConnection.kt"
        source = subprocess.check_output(["git", "show", f"39a1b95a^:{path}"], cwd=ROOT, text=True)
        self.assertIn("!selected", source)
        self.assertIn('if (provider != null && !selected)', source)
        files = check.source_files()
        files[path] = source
        self.assertTrue(any(path in f for f in check.violations(files, BASELINE)))
        print("PR_328_PAST_MISTAKE_REJECTED: 39a1b95a^ gates only selected providers")


if __name__ == "__main__":
    unittest.main()
