import importlib.util
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("tmux_target", Path(__file__).with_name("check.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class TmuxTargetCheckTest(unittest.TestCase):
    def test_current_ssh_sources_pass(self):
        check.check()

    def test_pre_844f774b_exact_session_target_mistake_fails(self):
        source = subprocess.check_output(
            ["git", "show", "844f774b^:" + check.COMMANDS],
            cwd=ROOT,
            text=True,
        )
        found = check.violations({check.COMMANDS: source})
        self.assertIn(f"{check.COMMANDS}: has-session-name-target", found)
        result = subprocess.run(
            ["python3", "-B", str(Path(__file__).with_name("check.py")), "844f774b^"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("TMUX_TARGET_UNQUOTED", result.stderr)
        self.assertIn("has-session-name-target", result.stderr)

    def test_wrong_target_helper_fails(self):
        source = (
            check.files()[check.COMMANDS]
            .replace(
                'private fun target(sessionName: String): String = TmuxParser.shellQuote("${exactName(sessionName)}:")',
                'private fun target(sessionName: String): String = TmuxParser.shellQuote("$sessionName:")',
            )
        )
        found = check.violations({check.COMMANDS: source})
        self.assertTrue(
            found[f"{check.COMMANDS}: target-helper-missing-exact"]
            or found[f"{check.COMMANDS}: target-without-exact-prefix"]
            or found[f"{check.COMMANDS}: colon-name-quote"],
            found,
        )


if __name__ == "__main__":
    unittest.main()