import argparse
import os
import re
from pathlib import Path
import subprocess
import tempfile
import unittest

parser = argparse.ArgumentParser()
parser.add_argument('--ref')
parser.add_argument('--config-only', action='store_true')
parser.add_argument('--repo', default=str(Path(__file__).resolve().parents[1]))
args, remaining = parser.parse_known_args()
root = Path(args.repo)


def source(name):
    if args.ref:
        return subprocess.check_output(['git', '-C', str(root), 'show', f'{args.ref}:{name}'], text=True)
    return (root / name).read_text()


class EmulatorProofTest(unittest.TestCase):
    def test_every_emulator_job_uses_one_shared_lifecycle(self):
        for name in ('test', 'verify', 'top-fade', 'account-journey'):
            workflow = source(f'.github/workflows/{name}.yml')
            events = re.findall(r'^  ([a-z_]+):', workflow.split('permissions:', 1)[0], re.M)
            self.assertEqual(len(events), len(set(events)), f'duplicate workflow event: {name}')
            self.assertNotIn('uses: reactivecircus/android-emulator-runner@', workflow)
            self.assertIn('uses: ./.github/actions/android-proof', workflow)
        action = source('.github/actions/android-proof/action.yml')
        for option in ('emulator-build: 12414864', 'ram-size: 4096M', 'disk-size: 6G',
                       'disable-animations: true', '-no-snapshot', '-Vulkan',
                       'test -r /dev/kvm && test -w /dev/kvm', 'kernel.txt', 'crashes'):
            self.assertIn(option, action)
        self.assertIn('python3 -B tools/emulator.test.py', source('tools/check.sh'))

    def run_stage(self, mode):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            for script in ('ready.sh', 'run.sh'):
                (path / script).write_text(source(f'.github/actions/android-proof/{script}'))
            (path / 'android-proof').mkdir()
            (path / 'android-proof/stage.sh').write_text('if [ yes = yes ]; then\n  echo proof > "$RUNNER_TEMP/proof"\nfi\n')
            adb = path / 'adb'
            adb.write_text('''#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$RUNNER_TEMP/adb-calls"
case "$*" in
  'reconnect offline') exit 0 ;;
  *wait-for-device) test "$MODE" != offline ;;
  *sys.boot_completed) if [ "$MODE" = booting ]; then echo 0; else echo 1; fi ;;
  *init.svc.bootanim*) test "$MODE" != bootanim ;;
  *boot_id*) if [ "$MODE" = reboot ] && [ -f "$RUNNER_TEMP/proof" ]; then echo rebooted; else echo original; fi ;;
  *) exit 2 ;;
esac
''')
            adb.chmod(0o700)
            for name, body in {'pgrep': 'echo 123', 'sudo': 'exec "$@"', 'strace': 'exit 0'}.items():
                tool = path / name
                tool.write_text('#!/usr/bin/env bash\n' + body + '\n')
                tool.chmod(0o700)
            env = dict(os.environ, PATH=f'{path}:{os.environ["PATH"]}', RUNNER_TEMP=tmp, MODE=mode, ANDROID_HOME=tmp)
            result = subprocess.run(['bash', str(path / 'run.sh')], env=env, capture_output=True, text=True)
            return result, (path / 'proof').exists(), (path / 'adb-calls').read_text()

    def test_ready_device_runs_complete_multiline_stage_and_checks_again(self):
        result, ran, calls = self.run_stage('ready')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(ran)
        self.assertIn('EMULATOR_STAGE_OK', result.stdout)
        self.assertEqual(calls.count('reconnect offline'), 2)

    def test_missing_or_unbooted_device_cannot_run_proof(self):
        for mode in ('offline', 'booting', 'bootanim'):
            with self.subTest(mode=mode):
                result, ran, _ = self.run_stage(mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(ran)
                self.assertNotIn('EMULATOR_STAGE_OK', result.stdout)

    def test_series_requires_consecutive_success_and_exact_commit(self):
        for failure in ('none', 'failed-run', 'wrong-commit'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp)
                script = path / 'series.sh'
                script.write_text(source('.github/actions/android-proof/series.sh'))
                gh = path / 'gh'
                gh.write_text("""#!/usr/bin/env python3
import json, os, pathlib, sys
p = pathlib.Path(os.environ['RUNNER_TEMP'])
a = sys.argv[1:]
with (p / 'calls').open('a') as f: f.write(' '.join(a) + '\u005cn')
if a[0] == 'api':
    n = int((p / 'count').read_text()) + 1 if (p / 'count').exists() else 1
    (p / 'count').write_text(str(n))
    print(json.dumps(dict(workflow_run_id=n, html_url=f'https://github.com/proof/actions/runs/{n}')))
elif a[1] == 'watch':
    if os.environ['FAILURE'] == 'failed-run': sys.exit(1)
elif a[1] == 'view':
    sha = 'wrong' if os.environ['FAILURE'] == 'wrong-commit' else os.environ['PROOF_COMMIT']
    print(json.dumps(dict(headSha=sha, headBranch=os.environ['PROOF_REF'], conclusion='success')))
else: sys.exit(2)
""")
                gh.chmod(0o700)
                env = dict(os.environ, PATH=f'{path}:{os.environ["PATH"]}', RUNNER_TEMP=tmp,
                           GITHUB_REPOSITORY='proof/repo', PROOF_REF='candidate', PROOF_COUNT='5',
                           PROOF_COMMIT='candidate-sha', FAILURE=failure)
                result = subprocess.run(['bash', str(script)], cwd=path, env=env, capture_output=True, text=True)
                calls = (path / 'calls').read_text()
                if failure == 'none':
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(calls.count('run watch'), 5)
                    self.assertIn('EMULATOR_SERIES_OK', result.stdout)
                else:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(calls.count('run watch'), 1)
                    self.assertNotIn('EMULATOR_SERIES_OK', result.stdout)

    def test_device_reboot_during_proof_is_not_a_success(self):
        result, ran, _ = self.run_stage('reboot')
        self.assertTrue(ran)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('EMULATOR_STAGE_OK', result.stdout)


if __name__ == '__main__':
    suite = unittest.TestSuite([EmulatorProofTest('test_every_emulator_job_uses_one_shared_lifecycle')]) if args.config_only else unittest.defaultTestLoader.loadTestsFromTestCase(EmulatorProofTest)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():
        raise SystemExit(1)
    print('EMULATOR_REGRESSION_OK')
