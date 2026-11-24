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
        skills.tmux_folder.cache_clear()
        skills.hosted_folder.cache_clear()
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

    def test_dump_ui_retries_null_roots_without_using_stale_xml(self):
        function = "dump_ui() {" + DRIVER.read_text().split("dump_ui() {", 1)[1].split("\nshot() {", 1)[0]
        adb = self.write(self.home / "adb", """#!/usr/bin/env bash
if [[ "$1" != exec-out ]]; then
    exit 1
fi
n=$(cat "$EVIDENCE/attempts")
n=$((n + 1))
echo "$n" > "$EVIDENCE/attempts"
if [[ "$MODE" == failure ]]; then
    exit "$FAILURE_CODE"
elif [[ "$MODE" == empty ]]; then
    :
elif [[ "$MODE" == stdout-error ]]; then
    echo 'ERROR: null root node returned by UiTestAutomationBridge.'
elif (( n <= NULLS )); then
    echo 'ERROR: null root node returned by UiTestAutomationBridge.' >&2
else
    echo '<hierarchy><node text="fresh" /></hierarchy>'
fi
""")
        adb.chmod(0o755)
        cases = [("null", 2, 0, 0, 3), ("null", 3, 0, 1, 3), ("empty", 0, 0, 1, 3),
                 ("failure", 0, 1, 1, 1), ("failure", 0, 124, 1, 1)]
        for mode, nulls, failure_code, expected, attempts in cases:
            with self.subTest(mode=mode, nulls=nulls, failure_code=failure_code):
                self.write(self.home / "attempts", "0")
                self.write(self.home / "current.xml", "stale")
                env = dict(os.environ, EVIDENCE=str(self.home), MODE=mode, NULLS=str(nulls),
                           FAILURE_CODE=str(failure_code), PATH=f"{self.home}:{os.environ['PATH']}")
                result = subprocess.run(["bash", "-c", function + "\ndump_ui"], env=env, capture_output=True)
                self.assertEqual(result.returncode, expected)
                self.assertEqual((self.home / "attempts").read_text().strip(), str(attempts))
                if expected == 0:
                    self.assertIn('<node text="fresh" />', (self.home / "current.xml").read_text())
                else:
                    self.assertFalse((self.home / "current.xml").exists())

        old_function = function.replace('if [[ "$output" != *"<hierarchy"* ]]; then', 'if false; then')
        self.assertNotEqual(old_function, function)
        self.write(self.home / "attempts", "0")
        self.write(self.home / "current.xml", "stale")
        env = dict(os.environ, EVIDENCE=str(self.home), MODE="stdout-error", NULLS="3",
                   PATH=f"{self.home}:{os.environ['PATH']}")
        result = subprocess.run(["bash", "-c", old_function + "\ndump_ui"], env=env, capture_output=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn("null root node", (self.home / "current.xml").read_text())

    def device_chain(self):
        device = self.home / "device-bin"
        device.mkdir()
        umap = 'target=${2//\\/sdcard/$EVIDENCE}'
        catmap = 'for arg in "$@"; do last=$arg; done'
        self.write(device / "uiautomator", f"""#!/usr/bin/env bash
{umap}
n=$(cat "$EVIDENCE/attempts")
if (( n >= 2 )); then printf '<hierarchy><node text="fresh" /></hierarchy>' > "$target"; fi
exit 0
""").chmod(0o755)
        self.write(device / "cat", f"""#!/usr/bin/env bash
{catmap}
exec /bin/cat "${{last//\\/sdcard/$EVIDENCE}}"
""").chmod(0o755)
        adb = self.write(self.home / "adb", """#!/usr/bin/env bash
if [[ "$1" == exec-out ]]; then
    n=$(cat "$EVIDENCE/attempts")
    echo "$((n + 1))" > "$EVIDENCE/attempts"
    exec /bin/sh -c "$4"
fi
exit 1
""")
        adb.chmod(0o755)
        return device

    def test_dump_ui_retries_when_the_device_chain_writes_no_file(self):
        function = "dump_ui() {" + DRIVER.read_text().split("dump_ui() {", 1)[1].split("\nshot() {", 1)[0]
        device = self.device_chain()
        (device / "uiautomator").write_text("#!/usr/bin/env bash\nexit 1\n")
        (device / "uiautomator").chmod(0o755)
        self.write(self.home / "attempts", "0")
        self.write(self.home / "current.xml", "stale")
        env = dict(os.environ, EVIDENCE=str(self.home), PATH=f"{self.home}:{device}:{os.environ['PATH']}")
        result = subprocess.run(["bash", "-c", function + "\ndump_ui"], env=env, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.home / "attempts").read_text().strip(), "3")
        self.assertFalse((self.home / "current.xml").exists())

    def test_dump_ui_saves_the_hierarchy_after_a_retried_read(self):
        function = "dump_ui() {" + DRIVER.read_text().split("dump_ui() {", 1)[1].split("\nshot() {", 1)[0]
        device = self.device_chain()
        self.write(self.home / "attempts", "0")
        self.write(self.home / "current.xml", "stale")
        env = dict(os.environ, EVIDENCE=str(self.home), PATH=f"{self.home}:{device}:{os.environ['PATH']}")
        result = subprocess.run(["bash", "-c", function + "\ndump_ui"], env=env, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.home / "attempts").read_text().strip(), "2")
        self.assertIn('<node text="fresh" />', (self.home / "current.xml").read_text())

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
tap_text() { test "$1" = All; printf selected > "$EVIDENCE/selected"; }
sleep() { return 1; }
wait_for_text() { test "$1" = "$expected_screen"; }
""" + function + '\nrelaunch_app "$@"\n'
                result = subprocess.run(["bash", "-c", harness, "relaunch", *arguments], env=env, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                if screen == "Inbox":
                    self.assertTrue((self.home / "selected").is_file())
                (self.home / "selected").unlink(missing_ok=True)

    def test_native_fixture_accepts_held_messages_when_its_turn_finishes(self):
        from unittest.mock import patch
        root = SCRIPT.parents[4]
        console = root / ".claude/skills/verify-multiprompt-android/scripts/skills-console.py"
        result = subprocess.run([sys.executable, str(console), str(self.home / "receipts.txt")],
                                input="/review-native investigation\n", capture_output=True, text=True,
                                env=dict(os.environ, HOME=str(self.home)), check=True)
        transcript = next((self.home / ".claude/projects").rglob("*.jsonl"))
        reader_spec = importlib.util.spec_from_file_location("fixture_reader", SCRIPT.with_name("chat_history_reader.py"))
        reader = importlib.util.module_from_spec(reader_spec)
        reader_spec.loader.exec_module(reader)
        with patch.object(reader.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=result.stdout)):
            self.assertTrue(reader.held_queue_ready("verify-session", transcript))
        self.assertEqual((self.home / "receipts.txt").read_text(), "VERIFY_RECEIVED:/review-native investigation\n")

    def test_cli_uses_exact_tmux_session_without_interpolation(self):
        self.skill(self.cwd, "local")
        name = "quoted';not-a-command"
        binary = self.write(self.home / "bin/tmux", "#!/usr/bin/env python3\nimport sys\nassert sys.argv[1:]==" + repr(["display-message", "-p", "-t", "=" + name + ":", "#{pane_current_path}"]) + "\nprint(" + repr(str(self.cwd)) + ")\n")
        binary.chmod(0o700)
        env = dict(os.environ, HOME=str(self.home), PATH=str(binary.parent) + os.pathsep + os.environ["PATH"])
        result = subprocess.run([sys.executable, str(SCRIPT), name], env=env, capture_output=True, text=True, check=True)
        self.assertEqual(json.loads(result.stdout), [dict(name="local", description="Description")])

    def cli(self, *arguments, tmux=None):
        binary = self.write(self.home / "bin/tmux", tmux or "#!/usr/bin/env python3\nraise SystemExit(1)\n")
        binary.chmod(0o700)
        env = dict(os.environ, HOME=str(self.home), PATH=str(binary.parent) + os.pathsep + os.environ["PATH"])
        return subprocess.run([sys.executable, str(SCRIPT), *arguments], env=env, capture_output=True, text=True)

    def names(self, result):
        self.assertEqual(result.returncode, 0, result.stderr)
        return [row["name"] for row in json.loads(result.stdout)]

    def fake_reader(self, chats):
        self.write(self.home / ".multiprompt-runtime/reader.py",
                   "import json, sys\nassert sys.argv[1:] == ['host', 'list']\nprint(json.dumps({'chats': " + repr(chats) + "}))\n")

    def test_cli_prefers_the_live_tmux_pane_over_the_stale_working_directory(self):
        self.skill(self.cwd, "pane-skill")
        stale = self.home / "stale"
        stale.mkdir()
        self.skill(stale, "stale-skill")
        tmux = "#!/usr/bin/env python3\nprint(" + repr(str(self.cwd)) + ")\n"
        self.assertEqual(self.names(self.cli("verify-session", str(stale), tmux=tmux)), ["pane-skill"])

    def test_cli_uses_the_working_directory_when_the_chat_has_no_tmux_session(self):
        self.skill(self.cwd, "project-only")
        self.assertEqual(self.names(self.cli("live-abc", str(self.cwd))), ["project-only"])

    def test_cli_finds_a_hosted_chat_folder_from_the_desktop_reader(self):
        self.skill(self.cwd, "hosted-skill")
        other = self.home / "other"
        other.mkdir()
        self.fake_reader([dict(name="live-other", cwd=str(other)), dict(name="live-abc", cwd=str(self.cwd))])
        self.assertEqual(self.names(self.cli("live-abc")), ["hosted-skill"])
        self.assertEqual(self.names(self.cli("live-abc", str(self.home / "missing"))), ["hosted-skill"])

    def test_cli_working_directory_beats_the_desktop_reader(self):
        self.skill(self.cwd, "argv-skill")
        other = self.home / "other"
        self.skill(other, "reader-skill")
        self.fake_reader([dict(name="live-abc", cwd=str(other))])
        self.assertEqual(self.names(self.cli("live-abc", str(self.cwd))), ["argv-skill"])

    def test_cli_hosted_lookup_needs_a_matching_chat_with_a_real_folder(self):
        self.fake_reader([dict(name="live-other", cwd=str(self.cwd)), dict(name="live-abc", cwd=str(self.home / "gone")),
                          dict(name="live-rel", cwd="relative")])
        for name in ("live-abc", "live-rel", "live-none"):
            with self.subTest(name=name):
                result = self.cli(name)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(len(result.stderr.strip().splitlines()), 1)

    def test_cli_survives_a_missing_or_broken_desktop_reader(self):
        self.assertNotEqual(self.cli("live-abc").returncode, 0)
        for body in ("raise SystemExit(3)\n", "print('not json')\n", "print('[1, 2]')\n", "print('{\"chats\": 5}')\n"):
            with self.subTest(body=body):
                self.write(self.home / ".multiprompt-runtime/reader.py", body)
                result = self.cli("live-abc")
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("Traceback", result.stderr)

    def test_cli_expands_a_home_relative_working_directory(self):
        self.skill(self.cwd, "tilde-skill")
        binary = self.write(self.home / "bin/tmux", "#!/usr/bin/env python3\nraise SystemExit(1)\n")
        binary.chmod(0o700)
        env = dict(os.environ, HOME=str(self.home), PATH=str(binary.parent) + os.pathsep + os.environ["PATH"])
        result = subprocess.run([sys.executable, str(SCRIPT), "live-abc", "~/projects/one/nested"], env=env, capture_output=True, text=True, check=True)
        self.assertEqual([row["name"] for row in json.loads(result.stdout)], ["tilde-skill"])

    def test_cli_falls_back_to_the_tmux_pane_when_the_working_directory_is_unusable(self):
        self.skill(self.cwd, "pane-skill")
        binary = self.write(self.home / "bin/tmux", "#!/usr/bin/env python3\nprint(" + repr(str(self.cwd)) + ")\n")
        binary.chmod(0o700)
        env = dict(os.environ, HOME=str(self.home), PATH=str(binary.parent) + os.pathsep + os.environ["PATH"])
        for requested in ("relative/dir", "~/missing", str(self.home / "missing"), ""):
            with self.subTest(requested=requested):
                result = subprocess.run([sys.executable, str(SCRIPT), "live-abc", requested], env=env, capture_output=True, text=True, check=True)
                self.assertEqual([row["name"] for row in json.loads(result.stdout)], ["pane-skill"])

    def test_cli_failure_is_one_plain_stderr_line_not_a_traceback(self):
        binary = self.write(self.home / "bin/tmux", "#!/usr/bin/env python3\nraise SystemExit(1)\n")
        binary.chmod(0o700)
        env = dict(os.environ, HOME=str(self.home), PATH=str(binary.parent) + os.pathsep + os.environ["PATH"])
        result = subprocess.run([sys.executable, str(SCRIPT), "live-abc"], env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("Traceback", result.stderr)
        self.assertEqual(len(result.stderr.strip().splitlines()), 1)

    def accounts(self, **users):
        from types import SimpleNamespace

        def lookup(name):
            if name not in users:
                raise KeyError(name)
            uid, folder = users[name]
            return SimpleNamespace(pw_name=name, pw_uid=uid, pw_dir=folder)
        return lookup

    def test_owner_is_the_account_whose_home_holds_the_chat_folder(self):
        lookup = self.accounts(login=(1000, "/home/login"), other=(1001, "/home/other"),
                               root=(0, "/home/root"), odd=(1002, "/srv/odd"))
        owner = lambda path: skills.owner_account(path, "/home/login", lookup)
        self.assertEqual(owner("/home/other/projects/app"), "other")
        self.assertEqual(owner(Path("/home/other")), "other")
        self.assertIsNone(owner("/home/login/projects/app"))
        self.assertIsNone(owner("/home/root/projects"))
        self.assertIsNone(owner("/home/odd/projects"))
        self.assertIsNone(owner("/home/missing/projects"))
        self.assertIsNone(owner("/home/other/../root"))
        self.assertIsNone(owner("/home/other name/projects"))
        self.assertIsNone(owner("/srv/other/projects"))
        self.assertIsNone(owner("/home"))
        self.assertEqual(owner("/tmp//home/other"), None)
        self.assertIsNone(owner(""))
        self.assertIsNone(owner(None))

    def run_as_owner(self, owner_home, calls):
        real_run = subprocess.run

        def run(command, **options):
            calls.append((command, options))
            separator = command.index("--")
            env = dict(os.environ, HOME=str(owner_home))
            env.pop("CLAUDE_CONFIG_DIR", None)
            return real_run([sys.executable if part == "python3" else part for part in command[separator + 1:]],
                            input=options["input"], capture_output=True, text=True, env=env)
        return run

    def main_rows(self, requested):
        from io import StringIO
        from unittest.mock import patch
        output = StringIO()
        with patch.object(sys, "argv", [str(SCRIPT), "live-abc", str(requested)]), \
                patch.object(Path, "home", return_value=self.home), \
                patch.dict(os.environ, {"CLAUDE_CONFIG_DIR": ""}), \
                patch.object(sys, "stdout", output):
            skills.main()
        return json.loads(output.getvalue())

    def test_chat_under_another_account_lists_that_accounts_skills(self):
        from unittest.mock import patch
        self.skill(self.home, "login-only")
        other = tempfile.TemporaryDirectory()
        self.addCleanup(other.cleanup)
        owner_home = Path(other.name)
        project = owner_home / "chat-work"
        project.mkdir(parents=True)
        self.skill(owner_home, "owner-user")
        self.skill(project, "owner-project")
        calls = []
        self.skill(self.cwd, "stale-project-only")
        with patch.object(skills, "owner_account", return_value="other"), \
                patch.object(skills, "tmux_folder", return_value=project), \
                patch.object(skills.subprocess, "run", side_effect=self.run_as_owner(owner_home, calls)):
            rows = self.main_rows(self.cwd)
        self.assertEqual([row["name"] for row in rows], ["owner-project", "owner-user"])
        command, options = calls[0]
        self.assertEqual(command[:7], ["sudo", "-n", "-H", "-u", "other", "--", "python3"])
        self.assertEqual(command[7], "-c")
        self.assertEqual(command[8], skills.LAUNCHER)
        self.assertEqual(command[9:], ["live-abc", str(project)])
        self.assertEqual(options["input"], SCRIPT.read_text(encoding="utf-8"))
        self.assertNotIn("login-only", [row["name"] for row in rows])
        self.assertNotIn("stale-project-only", [row["name"] for row in rows])

    def test_chat_under_the_login_account_never_asks_sudo(self):
        from unittest.mock import patch
        login = Path.home() / "projects/one/nested"
        with patch.object(skills, "tmux_folder", return_value=None), \
                patch.object(skills, "hosted_folder", return_value=None), \
                patch.object(skills.subprocess, "run") as run:
            self.assertIsNone(skills.owner_answer("live-abc", str(login), Path.home()))
            self.assertIsNone(skills.owner_answer("live-abc", "", self.home))
        run.assert_not_called()

    def test_owner_is_found_through_the_desktop_reader_when_the_folder_is_unreadable(self):
        from unittest.mock import patch
        requested = "/home/other/chat-work"
        answer = '[{"name": "x", "description": ""}]'
        with patch.object(skills, "tmux_folder", return_value=None), \
                patch.object(skills, "hosted_folder", return_value=Path(requested)), \
                patch.object(skills, "owner_account", return_value="other") as owner, \
                patch.object(skills, "owner_skills", return_value=answer) as output:
            self.assertEqual(skills.owner_answer("live-abc", "", self.home), answer)
            owner.assert_called_once_with(Path(requested), self.home)
        output.assert_called_once_with("other", "live-abc", requested)

    def test_unreadable_chat_folder_still_names_its_owner(self):
        locked = self.home / "locked"
        locked.mkdir()
        inside = locked / "chat-work"
        inside.mkdir()
        locked.chmod(0)
        self.addCleanup(locked.chmod, 0o700)
        if os.access(inside, os.R_OK):
            self.skipTest("running with privileges that ignore folder modes")
        self.assertIsNone(skills.usable(str(inside)))
        self.assertEqual(skills.loose(str(inside)), inside)

    def test_login_chat_does_not_run_the_desktop_reader(self):
        from unittest.mock import patch
        with patch.object(skills, "tmux_folder", return_value=self.cwd), \
                patch.object(skills, "hosted_folder") as hosted:
            self.assertIsNone(skills.owner_answer("live-abc", str(self.cwd), self.home))
        hosted.assert_not_called()

    def test_account_and_handoff_folder_follow_the_live_chat_not_the_stale_request(self):
        from unittest.mock import patch
        lookup = self.accounts(live=(1001, "/home/live"), stale=(1002, "/home/stale"),
                               hosted=(1003, "/home/hosted"))
        answer = '[{"name": "owner-only", "description": ""}]'
        cases = [(Path("/home/live/app"), "/home/stale/app", Path("/home/hosted/app"), "live", "/home/live/app"),
                 (None, "/home/stale/app", Path("/home/hosted/app"), "stale", "/home/stale/app"),
                 (None, "", Path("/home/hosted/app"), "hosted", "/home/hosted/app")]
        for live, requested, hosted, account, folder in cases:
            with self.subTest(account=account):
                with patch.object(skills.pwd, "getpwnam", side_effect=lookup), \
                        patch.object(skills, "tmux_folder", return_value=live), \
                        patch.object(skills, "hosted_folder", return_value=hosted), \
                        patch.object(skills.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=answer)) as run:
                    self.assertEqual(self.main_rows(requested), [{"name": "owner-only", "description": ""}])
                command = run.call_args.args[0]
                self.assertEqual(command[:7], ["sudo", "-n", "-H", "-u", account, "--", "python3"])
                self.assertEqual(command[9:], ["live-abc", folder])

    def test_refused_or_broken_owner_reads_never_expose_login_skills(self):
        from unittest.mock import patch
        self.skill(self.home, "login-only")
        self.skill(self.cwd, "login-project-only")
        lookup = self.accounts(other=(1001, "/home/other"))
        failures = [subprocess.CompletedProcess([], 1, stdout="[]", stderr="sudo: a password is required"),
                    subprocess.CompletedProcess([], 1, stdout='[{"name": "partial"}]', stderr="sudo failed"),
                    subprocess.CompletedProcess([], 0, stdout="not json", stderr=""),
                    subprocess.CompletedProcess([], 0, stdout='{"name": "x"}', stderr=""),
                    subprocess.CompletedProcess([], 0, stdout="", stderr=""),
                    subprocess.CompletedProcess([], 0, stdout="null", stderr=""),
                    subprocess.CompletedProcess([], 0, stdout="[]", stderr=""),
                    OSError("no sudo"), subprocess.TimeoutExpired("sudo", 8)]
        for failure in failures:
            with self.subTest(failure=repr(failure)):
                with patch.object(skills.pwd, "getpwnam", side_effect=lookup), \
                        patch.object(skills, "tmux_folder", return_value=Path("/home/other/app")), \
                        patch.object(skills.subprocess, "run", side_effect=[failure]):
                    self.assertEqual(self.main_rows(self.cwd), [])

    def test_missing_handoff_source_never_exposes_login_skills(self):
        from unittest.mock import patch
        self.skill(self.home, "login-only")
        lookup = self.accounts(other=(1001, "/home/other"))
        with patch.object(skills.pwd, "getpwnam", side_effect=lookup), \
                patch.object(skills, "tmux_folder", return_value=Path("/home/other/app")), \
                patch.object(skills, "own_source", return_value=None), \
                patch.object(skills.subprocess, "run") as run:
            self.assertEqual(self.main_rows(self.cwd), [])
        run.assert_not_called()

    def test_login_chat_still_lists_its_own_skills_without_sudo(self):
        from unittest.mock import patch
        self.skill(self.home, "login-only")
        self.skill(self.cwd, "login-project-only")
        with patch.object(skills, "tmux_folder", return_value=self.cwd), \
                patch.object(skills, "hosted_folder", return_value=None), \
                patch.object(skills.subprocess, "run") as run:
            self.assertEqual([row["name"] for row in self.main_rows(self.cwd)], ["login-only", "login-project-only"])
        run.assert_not_called()

    def test_owner_run_reads_its_own_home_and_never_asks_sudo_again(self):
        self.skill(self.home, "own-home")
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("NATIVE_SKILLS_OWNER", skills.LAUNCHER)
        binary = self.write(self.home / "bin/sudo", "#!/bin/sh\necho unexpected sudo >&2\nexit 9\n")
        binary.chmod(0o700)
        env = dict(os.environ, HOME=str(self.home), CLAUDE_CONFIG_DIR=str(self.home / "login-config"),
                   PATH=str(binary.parent) + os.pathsep + os.environ["PATH"])
        self.write(self.home / "login-config/skills/login-config-skill/SKILL.md",
                   "---\nname: login-config-skill\n---\nbody")
        result = subprocess.run([sys.executable, "-c", skills.LAUNCHER, "live-abc", str(self.cwd)],
                                input=source, capture_output=True, text=True, env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([row["name"] for row in json.loads(result.stdout)], ["own-home"])

    def test_login_launcher_reads_the_script_from_stdin_for_the_owner_hop(self):
        self.skill(self.cwd, "local")
        launcher = ("import sys;s=sys.stdin.buffer.read().decode();"
                    'exec(compile(s,"<native-skills>","exec"),'
                    '{"__name__":"__main__","NATIVE_SKILLS_SOURCE":s})')
        env = dict(os.environ, HOME=str(self.home))
        result = subprocess.run([sys.executable, "-c", launcher, "live-abc", str(self.cwd)],
                                input=SCRIPT.read_text(encoding="utf-8"), capture_output=True, text=True, env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([row["name"] for row in json.loads(result.stdout)], ["local"])


if __name__ == "__main__":
    unittest.main()
