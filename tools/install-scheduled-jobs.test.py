import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PLACEHOLDER = '%h/projects/multiprompt-android-jobs'
UNITS = {
    'multiprompt-parity.service': 'tools/parity/multiprompt-parity.service',
    'multiprompt-parity.timer': 'tools/parity/multiprompt-parity.timer',
    'multiprompt-chat-probe.service': 'tools/chat-shapes/multiprompt-chat-probe.service',
    'multiprompt-chat-probe.timer': 'tools/chat-shapes/multiprompt-chat-probe.timer',
    'multiprompt-chat-probe-ticket.service': 'tools/chat-shapes/multiprompt-chat-probe-ticket.service',
}
INSTALLER = ROOT / 'tools/install-scheduled-jobs.sh'
HELPER = ROOT / 'tools/chat-shapes/file-ticket.sh'
TITLE = 'Daily chat check found a chat loading wrong messages'


def git(*args, cwd=None, env=None):
    return subprocess.run(['git', *args], cwd=cwd, env=env, check=True, capture_output=True, text=True)


class RepoUnitTest(unittest.TestCase):
    def test_units_run_the_script_from_the_repo_clone(self):
        for name, relative in UNITS.items():
            self.assertEqual(name, Path(relative).name)
            text = (ROOT / relative).read_text()
            self.assertNotIn('.local/share', text, name)
            self.assertNotIn('projects/multiprompt-android/', text, name)
            self.assertNotIn('/worktrees/', text, name)
            for line in text.splitlines():
                if line.startswith('ExecStart'):
                    self.assertIn(PLACEHOLDER, line, f'{name}: {line}')

    def test_parity_timer_still_schedules_the_parity_service(self):
        text = (ROOT / UNITS['multiprompt-parity.timer']).read_text()
        self.assertIn('OnCalendar=', text)
        self.assertIn('Persistent=true', text)

    def test_probe_failure_files_the_repo_helper(self):
        text = (ROOT / UNITS['multiprompt-chat-probe.service']).read_text()
        self.assertIn('OnFailure=multiprompt-chat-probe-ticket.service', text)
        ticket = (ROOT / UNITS['multiprompt-chat-probe-ticket.service']).read_text()
        self.assertIn(f'ExecStart={PLACEHOLDER}/tools/chat-shapes/file-ticket.sh', ticket)
        self.assertTrue(os.access(HELPER, os.X_OK))


class InstallTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        base = Path(self.temporary.name)
        self.home = base / 'home'
        self.home.mkdir()
        self.bin = base / 'bin'
        self.bin.mkdir()
        self.gitenv = dict(os.environ)
        self.gitenv.update(
            HOME=str(self.home),
            GIT_CONFIG_GLOBAL=os.devnull,
            GIT_CONFIG_SYSTEM=os.devnull,
            GIT_AUTHOR_NAME='Test',
            GIT_AUTHOR_EMAIL='test@example.com',
            GIT_COMMITTER_NAME='Test',
            GIT_COMMITTER_EMAIL='test@example.com',
        )
        self.origin = base / 'origin.git'
        git('init', '--bare', '--quiet', str(self.origin), env=self.gitenv)
        git('-C', str(self.origin), 'symbolic-ref', 'HEAD', 'refs/heads/main', env=self.gitenv)
        seed = base / 'seed'
        git('clone', '--quiet', str(self.origin), str(seed), env=self.gitenv)
        (seed / 'README.md').write_text('seed\n')
        git('add', 'README.md', cwd=seed, env=self.gitenv)
        git('commit', '--quiet', '-m', 'seed', cwd=seed, env=self.gitenv)
        git('push', '--quiet', 'origin', 'HEAD:main', cwd=seed, env=self.gitenv)
        self.clone = self.home / 'projects/multiprompt-android-jobs'
        self.units = self.home / '.config/systemd/user'
        shim = self.bin / 'systemctl'
        shim.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$*" >> "$HOME/systemctl.log"\n')
        shim.chmod(0o755)

    def run_installer(self):
        env = dict(self.gitenv)
        env['PATH'] = str(self.bin) + os.pathsep + os.environ['PATH']
        env['MULTIPROMPT_JOBS_REPO'] = str(self.clone)
        env['MULTIPROMPT_JOBS_ORIGIN'] = str(self.origin)
        return subprocess.run(['bash', str(INSTALLER)], env=env, capture_output=True, text=True)


class InstallScheduledJobsTest(InstallTest):
    def test_install_writes_real_units_at_the_clone_and_keeps_drop_ins(self):
        self.units.mkdir(parents=True)
        dropin = self.units / 'multiprompt-parity.service.d'
        dropin.mkdir()
        override = dropin / 'override.conf'
        override.write_text('[Service]\nNice=5\n')
        stale = self.units / 'multiprompt-parity.service'
        stale.symlink_to(self.home / 'projects/multiprompt-android-ops/tools/parity/multiprompt-parity.service')
        result = self.run_installer()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.clone / '.git').is_dir())
        detached = git('-C', str(self.clone), 'rev-parse', '--abbrev-ref', 'HEAD', env=self.gitenv).stdout.strip()
        self.assertEqual('HEAD', detached)
        for name in UNITS:
            unit = self.units / name
            self.assertTrue(unit.is_file(), name)
            self.assertFalse(unit.is_symlink(), name)
            text = unit.read_text()
            self.assertIn(str(self.clone), text, name)
            self.assertNotIn(PLACEHOLDER, text, name)
            self.assertNotIn('.local/share', text, name)
            self.assertNotIn('projects/multiprompt-android/', text, name)
        self.assertIn(f'ExecStart=/usr/bin/python3 {self.clone}/tools/parity/multiprompt-parity.py',
                      (self.units / 'multiprompt-parity.service').read_text())
        self.assertIn(f'ExecStart={self.clone}/tools/chat-shapes/file-ticket.sh',
                      (self.units / 'multiprompt-chat-probe-ticket.service').read_text())
        self.assertEqual('[Service]\nNice=5\n', override.read_text())
        log = (self.home / 'systemctl.log').read_text()
        self.assertIn('--user daemon-reload', log)
        self.assertIn('--user enable --now multiprompt-parity.timer', log)
        self.assertIn('--user enable --now multiprompt-chat-probe.timer', log)
        again = self.run_installer()
        self.assertEqual(again.returncode, 0, again.stderr)
        self.assertTrue((self.units / 'multiprompt-parity.service').is_file())
        self.assertFalse((self.units / 'multiprompt-parity.service').is_symlink())

    def test_installer_refuses_a_target_that_is_not_a_git_clone(self):
        self.clone.mkdir(parents=True)
        (self.clone / 'leftover.txt').write_text('not a clone\n')
        result = self.run_installer()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('not a git clone', result.stderr)
        self.assertFalse(self.units.exists())
        self.assertFalse((self.clone / '.git').exists())


class FileTicketTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name) / 'home'
        self.home.mkdir()
        self.bin = Path(self.temporary.name) / 'bin'
        self.bin.mkdir()
        self.token_path = self.home / '.config/hypertask-agents/credentials/sessions/multiprompt-android-dev-agent-token'
        self.token_path.parent.mkdir(parents=True)
        self.token_path.write_text('sentinel-token-one\n')
        (self.home / 'journal.txt').write_text(
            'CHAT_PROBE_OK chats=2 match=2 skip=0\n'
            'disk noise\n'
            'CHAT_PROBE_MISMATCH chats=4 mismatch=1 skip=0\n'
        )
        journal = self.bin / 'journalctl'
        journal.write_text('#!/usr/bin/env bash\ncat "$HOME/journal.txt"\n')
        journal.chmod(0o755)
        hypertask = self.bin / 'hypertask'
        hypertask.write_text(
            '#!/usr/bin/env bash\n'
            'printf "%s\\n" "$@" > "$HOME/hypertask.args"\n'
            'while [ $# -gt 0 ]; do\n'
            '  if [ "$1" = "--description-file" ]; then cp "$2" "$HOME/description.md"; fi\n'
            '  shift\n'
            'done\n'
        )
        hypertask.chmod(0o755)

    def run_helper(self):
        env = {'HOME': str(self.home), 'PATH': str(self.bin) + os.pathsep + os.environ['PATH']}
        return subprocess.run(['bash', str(HELPER)], env=env, capture_output=True, text=True)

    def args(self):
        return (self.home / 'hypertask.args').read_text().splitlines()

    def test_helper_files_one_high_priority_ticket_with_the_last_probe_line(self):
        result = self.run_helper()
        self.assertEqual(result.returncode, 0, result.stderr)
        args = self.args()
        self.assertEqual('--json', args[0])
        self.assertEqual(['tasks', 'create'], args[1:3])
        self.assertEqual('sentinel-token-one', args[args.index('--token') + 1])
        self.assertEqual('13734', args[args.index('--project') + 1])
        self.assertEqual('high', args[args.index('--priority') + 1])
        self.assertEqual(TITLE, args[args.index('--title') + 1])
        self.assertIn('--markdown', args)
        description = (self.home / 'description.md').read_text()
        self.assertIn('CHAT_PROBE_MISMATCH chats=4 mismatch=1 skip=0', description)
        self.assertNotIn('CHAT_PROBE_OK', description)
        for section in ('**Problem**', '**Fix**', '**Decision**', '**Done when**', '**Risk**'):
            self.assertIn(section, description)
        self.assertNotIn('sentinel-token-one', description)
        self.assertNotIn('sentinel-token-one', result.stdout + result.stderr)
        temporary = Path(args[args.index('--description-file') + 1])
        self.assertFalse(temporary.exists())

    def test_helper_reads_the_token_at_run_time(self):
        self.run_helper()
        self.token_path.write_text('sentinel-token-two\n')
        self.run_helper()
        args = self.args()
        self.assertEqual('sentinel-token-two', args[args.index('--token') + 1])


if __name__ == '__main__':
    unittest.main()
