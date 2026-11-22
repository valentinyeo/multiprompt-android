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

    def test_pre_igsh_1230_exact_session_target_mistake_fails(self):
        source = (
            "fun resumeArchived(\n"
            "    val fresh = freshSessionName(sessionName)\n"
            "    val session = TmuxParser.shellQuote(fresh)\n"
            '    return "if tmux has-session -t $session 2>/dev/null; then " +\n'
            '        "tmux send-keys -t ${target(sessionName)} Enter"\n'
            "}\n"
            'private fun target(sessionName: String): String = TmuxParser.shellQuote("$sessionName:")\n'
            'private fun sessionTarget(sessionName: String): String = TmuxParser.shellQuote("=$sessionName")\n'
        )
        found = check.violations({check.COMMANDS: source})
        self.assertIn(f"{check.COMMANDS}: has-session-name-target", found)
        self.assertTrue(
            found[f"{check.COMMANDS}: target-helper-missing-exact"]
            or found[f"{check.COMMANDS}: colon-name-quote"],
            found,
        )

        replay = subprocess.run(
            ["git", "cat-file", "-e", "844f774b^"],
            cwd=ROOT,
            capture_output=True,
        )
        if replay.returncode == 0:
            historical = subprocess.check_output(
                ["git", "show", f"844f774b^:{check.COMMANDS}"],
                cwd=ROOT,
                text=True,
            )
            self.assertIn(f"{check.COMMANDS}: has-session-name-target", check.violations({check.COMMANDS: historical}))

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