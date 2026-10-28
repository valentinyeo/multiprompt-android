import argparse
import hashlib
import os
import re
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
import xml.etree.ElementTree as ET

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
        for name in ('test', 'verify', 'top-fade', 'account-journey', 'working-dots'):
            workflow = source(f'.github/workflows/{name}.yml')
            events = re.findall(r'^  ([a-z_]+):', workflow.split('permissions:', 1)[0], re.M)
            self.assertEqual(len(events), len(set(events)), f'duplicate workflow event: {name}')
            self.assertNotIn('uses: reactivecircus/android-emulator-runner@', workflow)
            self.assertIn('uses: ./.github/actions/android-proof', workflow)
            self.assertNotIn('runs-on: ubuntu-latest', workflow)
            self.assertIn('runs-on: ubuntu-24.04', workflow)
        action = source('.github/actions/android-proof/action.yml')
        for option in ('api-level: 35', 'ram-size: 2048M', '-gpu swiftshader_indirect', 'uses: actions/cache@v4',
                       'disable-animations: true', '-no-snapshot', '-Vulkan',
                       'test -r /dev/kvm && test -w /dev/kvm', 'kernel.txt', 'crashes', 'gdb libpulse0', 'sdk.sh', 'pre-emulator-launch-script:', 'emulator.sh'):
            self.assertIn(option, action)
        self.assertNotIn('PerSourcePenalties', source('.claude/skills/verify-multiprompt-android/scripts/fixture.sh'))
        self.assertNotIn('-gpu off', action)
        self.assertNotIn('PerSourcePenalties', source('tools/account-journey/fixture.sh'))
        self.assertIn('package.xml', source('.github/actions/android-proof/emulator.sh'))
        self.assertNotIn('emulator-build:', action)
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
            for name, body in {'pgrep': 'echo 123', 'sudo': 'exec "$@"', 'gdb': 'exit 0'}.items():
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

    def test_partial_sdk_install_retries_and_never_reports_missing_image_ready(self):
        for ready_on in (2, 4):
            with self.subTest(ready_on=ready_on), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp)
                script = path / 'sdk.sh'
                script.write_text(source('.github/actions/android-proof/sdk.sh'))
                installer = path / 'sdkmanager'
                installer.write_text('''#!/usr/bin/env bash
set -eu
if [ "$*" = --licenses ]; then exit 0; fi
count=$(cat "$ANDROID_HOME/count" 2>/dev/null || echo 0)
count=$((count + 1))
echo "$count" > "$ANDROID_HOME/count"
if [ "$count" -eq "$READY_ON" ]; then
  image="$ANDROID_HOME/system-images/android-35/default/x86_64"
  mkdir -p "$image"
  echo image > "$image/system.img"
  echo package > "$image/package.xml"
fi
''')
                installer.chmod(0o700)
                env = dict(os.environ, ANDROID_HOME=tmp, PROOF_TARGET='default', READY_ON=str(ready_on), PATH=f'{tmp}:{os.environ["PATH"]}')
                result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True)
                self.assertEqual(int((path / 'count').read_text()), min(ready_on, 3))
                self.assertEqual(result.returncode == 0, ready_on == 2, result.stderr)
                self.assertEqual('ANDROID_IMAGE_READY' in result.stdout, ready_on == 2)

    def test_pinned_emulator_rejects_corrupt_download_and_reuses_validated_cache(self):
        for corrupt in (False, True):
            with self.subTest(corrupt=corrupt), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp)
                archive = path / 'fixture.zip'
                with zipfile.ZipFile(archive, 'w') as output:
                    entry = zipfile.ZipInfo('emulator/emulator')
                    entry.external_attr = 0o100755 << 16
                    output.writestr(entry, '#!/usr/bin/env bash\necho "Android emulator version 35.6.11.0"\n')
                    entry = zipfile.ZipInfo('emulator/qemu/linux-x86_64/qemu-system-x86_64')
                    entry.external_attr = 0o100755 << 16
                    output.writestr(entry, '#!/usr/bin/env bash\n[[ "${LD_LIBRARY_PATH:-}" == "$ANDROID_HOME/emulator/lib64:"* ]] || exit 127\necho "QEMU loaded"\n')
                checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
                script = path / 'emulator.sh'
                script.write_text(re.sub(r'checksum=[a-f0-9]{64}', f'checksum={checksum}', source('.github/actions/android-proof/emulator.sh')))
                curl = path / 'curl'
                curl.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
p = pathlib.Path(os.environ['HOME'])
with (p / 'downloads').open('a') as f: f.write('download\\n')
out = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
out.write_bytes(b'truncated' if os.environ['CORRUPT'] == 'yes' else (p / 'fixture.zip').read_bytes())
''')
                curl.chmod(0o700)
                (path / 'android-proof').mkdir()
                (path / 'emulator').mkdir()
                (path / 'emulator/stale-library').write_text('old')
                (path / 'emulator/package.xml').write_text('<repository xmlns:generic="urn:generic" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><localPackage path="emulator"><type-details xsi:type="generic:genericDetailsType"/><revision><major>37</major><minor>2</minor><micro>12</micro></revision></localPackage></repository>')
                env = dict(os.environ, HOME=tmp, RUNNER_TEMP=tmp, ANDROID_HOME=tmp, CORRUPT='yes' if corrupt else 'no', PATH=f'{tmp}:{os.environ["PATH"]}')
                result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True)
                if corrupt:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertNotIn('PINNED_EMULATOR_READY', result.stdout)
                    self.assertTrue((path / 'emulator/stale-library').exists())
                else:
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn('PINNED_EMULATOR_READY', result.stdout)
                    self.assertFalse((path / 'emulator/stale-library').exists())
                    revision = ET.parse(path / 'emulator/package.xml').find('localPackage/revision')
                    self.assertEqual([node.text for node in revision], ['35', '6', '11'])
                    self.assertIn('xmlns:generic="urn:generic"', (path / 'emulator/package.xml').read_text())
                    subprocess.run(['bash', str(script)], env=env, check=True, capture_output=True)
                    self.assertEqual((path / 'downloads').read_text().count('download'), 1)

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
