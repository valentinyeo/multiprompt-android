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

    def test_each_legacy_path_has_its_own_budget(self):
        source = "LegacyMessageIdentity.shared(row, card)\nLegacyMessageIdentity.shared(row, card)"
        found = check.violations({check.OWNER: source})
        baseline = Counter({check.OWNER + ": legacy-shared": 1, check.OWNER + ": legacy-reply": 1})
        self.assertEqual(Counter({check.OWNER + ": legacy-shared": 1}), found - baseline)

    def test_real_pending_hash_collision_before_pr_77_is_rejected(self):
        source = subprocess.check_output(["git", "show", "ce2d32d9^:" + check.OWNER], cwd=check.ROOT, text=True)
        self.assertIn(check.OWNER + ": lossy-message-identity", check.violations({check.OWNER: source}))
        result = subprocess.run(["python3", "-B", str(Path(__file__).with_name("check.py")), "ce2d32d9^"], cwd=check.ROOT, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("MESSAGE_PROJECTION_DUPLICATE", result.stderr)
        self.assertIn("ChatHistory.projectMessages", result.stderr)
        self.assertIn("lossy-message-identity", result.stderr)

    def test_real_display_heuristics_before_pr_312_are_rejected(self):
        source = subprocess.check_output(["git", "show", "e31c9c4d^:" + check.OWNER], cwd=check.ROOT, text=True)
        found = check.violations({check.OWNER: source})
        self.assertIn(check.OWNER + ": parallel-projections", found)
        self.assertIn(check.OWNER + ": display-text-matching", found)


if __name__ == "__main__":
    unittest.main()
