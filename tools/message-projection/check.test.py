from collections import Counter
import importlib.util
from pathlib import Path
import subprocess
import unittest

spec = importlib.util.spec_from_file_location("projection_check", Path(__file__).with_name("check.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class MessageProjectionCheckTest(unittest.TestCase):
    def test_current_projection_and_named_legacy_paths_pass(self):
        check.check()

    def test_new_independent_constructor_names_the_fix(self):
        files = {"app/src/main/java/new/Chat.kt": 'val rows = listOf(ChatHistoryMessage("echo", "user", body, "", 0, 0))'}
        self.assertEqual(Counter({"app/src/main/java/new/Chat.kt: independent-row-constructor": 1}), check.violations(files))

    def test_legacy_matching_is_reached_only_through_message_identity(self):
        source = "LegacyMessageIdentity.shared(row, card)\nLegacyMessageIdentity.delivered(row, prompt, sid, inode)"
        self.assertEqual(Counter({"app/src/main/java/new/Chat.kt: legacy-bypass": 2}),
                         check.violations({"app/src/main/java/new/Chat.kt": source}))
        self.assertEqual(Counter(), check.violations({check.IDENTITY: source}))

    def test_a_new_row_key_or_raw_text_match_names_the_fix(self):
        source = 'val key = "@shared:$id"\nval gone = row.text == entry.text\nval note = row.text != null'
        self.assertEqual(Counter({"app/src/main/java/dev/multiprompt/companion/reader/New.kt: independent-key": 1,
                                  "app/src/main/java/dev/multiprompt/companion/reader/New.kt: raw-text-match": 1}),
                         check.violations({"app/src/main/java/dev/multiprompt/companion/reader/New.kt": source}))

    def test_the_matcher_must_take_message_text(self):
        typed = "fun same(left: MessageText, right: MessageText): Boolean = left == right"
        untyped = "fun same(left: String, right: String): Boolean = left == right"
        self.assertEqual(Counter(), check.violations({check.EDGE: typed}))
        self.assertEqual(Counter({check.EDGE + ": untyped-matcher": 1}), check.violations({check.EDGE: untyped}))

    def test_real_pending_echo_before_pr_539_is_rejected(self):
        source = subprocess.check_output(["git", "show", "00e1c674^:" + check.EDGE], cwd=check.ROOT, text=True)
        self.assertIn(check.EDGE + ": untyped-matcher", check.violations({check.EDGE: source}))
        result = subprocess.run(["python3", "-B", str(Path(__file__).with_name("check.py")), "00e1c674^"], cwd=check.ROOT, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("MESSAGE_IDENTITY_BYPASSED", result.stderr)
        self.assertIn("MessageIdentity.same", result.stderr)
        self.assertIn(check.EDGE + ": untyped-matcher", result.stderr)

    def test_real_pending_hash_collision_before_pr_77_is_rejected(self):
        source = subprocess.check_output(["git", "show", "ce2d32d9^:" + check.OWNER], cwd=check.ROOT, text=True)
        self.assertIn(check.OWNER + ": lossy-message-identity", check.violations({check.OWNER: source}))
        result = subprocess.run(["python3", "-B", str(Path(__file__).with_name("check.py")), "ce2d32d9^"], cwd=check.ROOT, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("MESSAGE_IDENTITY_BYPASSED", result.stderr)
        self.assertIn("MessageIdentity", result.stderr)

    def test_real_display_heuristics_before_pr_312_are_rejected(self):
        source = subprocess.check_output(["git", "show", "e31c9c4d^:" + check.OWNER], cwd=check.ROOT, text=True)
        found = check.violations({check.OWNER: source})
        self.assertIn(check.OWNER + ": parallel-projections", found)
        self.assertIn(check.OWNER + ": display-text-matching", found)


if __name__ == "__main__":
    unittest.main()
