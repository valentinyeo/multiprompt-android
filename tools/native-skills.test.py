import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "app/src/main/assets/native_skills.py"
DRIVER = ROOT / ".claude/skills/verify-multiprompt-android/scripts/drive.sh"
spec = importlib.util.spec_from_file_location("native_skills", SCRIPT)
skills = importlib.util.module_from_spec(spec)
spec.loader.exec_module(skills)


class NativeSkillsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.cwd = self.home / "projects/one/nested"
        self.cwd.mkdir(parents=True)

    def write(self, path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def skill(self, root, name, description="Description", extra=""):
        return self.write(root / ".claude/skills" / name / "SKILL.md",
                          f"---\nname: {name}\ndescription: {description}\n{extra}---\nPRIVATE BODY NEVER SENT")

    def test_native_session_roots_and_nearest_project_override(self):
        self.skill(self.home, "review", "user")
        self.skill(self.home / "projects/one", "review", "parent")
        self.skill(self.cwd, "review", "nearest")
        self.skill(self.cwd, "deploy")
        self.skill(self.home / "projects/other", "other-project-only")
        rows = skills.discover(self.home, self.cwd)
        self.assertEqual([row["name"] for row in rows], ["deploy", "review"])
        self.assertEqual(rows[-1]["description"], "nearest")
        self.assertNotIn("PRIVATE BODY", json.dumps(rows))
        self.assertEqual(set(rows[0]), {"name", "description"})

    def test_hidden_skills_and_non_native_bundles_never_appear(self):
        self.skill(self.home, "hidden", extra="user-invocable: false\n")
        self.skill(self.home, "manual", extra="disable-model-invocation: true\n")
        self.write(self.home / "app/src/main/assets/skills/old/SKILL.md", "old")
        self.assertEqual([row["name"] for row in skills.discover(self.home, self.cwd)], ["manual"])

    def test_symlinked_native_skill_and_folded_description(self):
        source = self.home / "official/skill"
        self.write(source / "SKILL.md", "---\nname: linked\ndescription: >-\n  First line\n  second line\n---\nsecret")
        target = self.home / ".claude/skills/linked"
        target.parent.mkdir(parents=True)
        target.symlink_to(source, target_is_directory=True)
        self.assertEqual(skills.discover(self.home, self.cwd), [dict(name="linked", description="First line second line")])

    def test_native_commands_support_nested_names(self):
        self.write(self.cwd / ".claude/commands/review/security.md", "---\ndescription: Check safety\n---\nbody")
        self.assertEqual(skills.discover(self.home, self.cwd), [dict(name="review:security", description="Check safety")])

    def test_command_name_comes_from_filename_not_skill_frontmatter(self):
        self.write(self.cwd / ".claude/commands/check.md", "---\nname: A descriptive title\ndescription: Native command\n---\nbody")
        self.assertEqual(skills.discover(self.home, self.cwd), [dict(name="check", description="Native command")])

    def test_only_enabled_applicable_plugins_with_namespace(self):
        install = self.home / "plugin-v1"
        self.write(install / "skills/test/SKILL.md", "---\nname: test\ndescription: Plugin test\n---\nbody")
        self.write(self.home / ".claude/plugins/installed_plugins.json", json.dumps({"plugins": {
            "qa@official": [dict(scope="user", installPath=str(install)), dict(scope="project", projectPath="/different", installPath="/wrong")],
            "disabled@official": [dict(scope="user", installPath=str(install))],
        }}))
        self.write(self.home / ".claude/settings.json", json.dumps(dict(enabledPlugins={"qa@official": True, "disabled@official": False})))
        self.assertEqual([row["name"] for row in skills.discover(self.home, self.cwd)], ["qa:test"])
        self.write(self.cwd / ".claude/settings.local.json", json.dumps(dict(enabledPlugins={"qa@official": False})))
        self.assertEqual(skills.discover(self.home, self.cwd), [])

    def test_custom_config_and_invalid_names(self):
        self.skill(self.home, "not-in-custom-config")
        config = self.home / "custom"
        self.write(config / "skills/valid/SKILL.md", "---\nname: native\ndescription: café\n---\nbody")
        self.write(config / "skills/bad/SKILL.md", "---\nname: bad name\n---\nbody")
        self.assertEqual(skills.discover(self.home, self.cwd, config), [dict(name="native", description="café")])

    def test_relaunch_waits_for_trust_dialog_or_default_inbox(self):
        function = "relaunch_app() {" + DRIVER.read_text().split("relaunch_app() {", 1)[1].split("refresh_inbox() {", 1)[0]
        for screen, arguments in (("Inbox", []), ("Trust Verify?", ["Trust Verify?"]), ("Inbox", ["Inbox"])):
            with self.subTest(screen=screen, arguments=arguments):
                self.write(self.home / "current.xml", f'<hierarchy><node text="{screen}" /></hierarchy>')
                env = dict(os.environ, EVIDENCE=str(self.home), scripts=str(ROOT / ".claude/skills/verify-multiprompt-android/scripts"), expected_screen=screen)
                harness = """set -euo pipefail
package=dev.multiprompt.companion.debug
adb() { :; }
dump_ui() { :; }
doctor() { :; }
sleep() { return 1; }
wait_for_text() { test "$1" = "$expected_screen"; }
""" + function + '\nrelaunch_app "$@"\n'
                result = subprocess.run(["bash", "-c", harness, "relaunch", *arguments], env=env, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_cli_uses_exact_tmux_session_without_interpolation(self):
        self.skill(self.cwd, "local")
        name = "quoted';not-a-command"
        binary = self.write(self.home / "bin/tmux", "#!/usr/bin/env python3\nimport sys\nassert sys.argv[1:]==" + repr(["display-message", "-p", "-t", "=" + name + ":", "#{pane_current_path}"]) + "\nprint(" + repr(str(self.cwd)) + ")\n")
        binary.chmod(0o700)
        env = dict(os.environ, HOME=str(self.home), PATH=str(binary.parent) + os.pathsep + os.environ["PATH"])
        result = subprocess.run([sys.executable, str(SCRIPT), name], env=env, capture_output=True, text=True, check=True)
        self.assertEqual(json.loads(result.stdout), [dict(name="local", description="Description")])


if __name__ == "__main__":
    unittest.main()
