const test = require('node:test');
const assert = require('node:assert/strict');
const { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { spawnSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const path = require('node:path');

const workflow = readFileSync(path.join(__dirname, '../.github/workflows/release.yml'), 'utf8');
const steps = workflow.split(/(?=^      - )/m);
const step = name => {
  const found = steps.find(value => value.startsWith(`      - name: ${name}\n`));
  assert.ok(found, `Missing release step: ${name}`);
  return found;
};

test('both publishing steps require a successful journey and retained proof', () => {
  const proof = step('Record signed release journey');
  assert.match(proof, /id: release-proof\n/);
  assert.match(proof, /uses: \.\/\.github\/actions\/android-proof\n/);
  assert.match(proof, /bash tools\/release-proof\.sh/);
  assert.doesNotMatch(proof, /continue-on-error:|if:/);
  const upload = step('Upload release proof');
  assert.match(upload, /id: release-proof-upload\n/);
  assert.match(upload, /if: always\(\)/);
  assert.match(upload, /name: release-proof\n/);
  assert.match(upload, /path: \$\{\{ env.EVIDENCE \}\}/);
  assert.match(upload, /if-no-files-found: error/);
  assert.doesNotMatch(upload, /continue-on-error:/);
  for (const channel of ['android-latest', 'android-m3-latest']) {
    const publish = step(`Publish fixed ${channel} channel`);
    assert.match(publish, /if: success\(\) && steps\.release-proof\.outcome == 'success' && steps\.release-proof-upload\.outcome == 'success'/);
    assert.ok(workflow.indexOf(proof) < workflow.indexOf(upload));
    assert.ok(workflow.indexOf(upload) < workflow.indexOf(publish));
  }
  assert.ok(workflow.indexOf(step('Stop release fixture and retain receipts')) < workflow.indexOf(upload));
  assert.match(step('Launch signed M3 APK'), /bash tools\/release-launch\.sh/);
});

test('proof installs the signed candidate without rebuilding and publication checks that digest', () => {
  assert.match(workflow, /VERIFY_APK: \$\{\{ github.workspace \}\}\/app\/build\/outputs\/apk\/release\/app-release.apk/);
  assert.match(workflow, /VERIFY_FEATURE: robot-faces.md/);
  const prepare = step('Prepare signed release journey');
  assert.match(prepare, /CANDIDATE_APK: \$\{\{ env.VERIFY_APK \}\}/);
  assert.match(prepare, /EXPECTED_VERSION: \$\{\{ env.VERSION_NAME \}\}/);
  assert.match(prepare, /bash tools\/release-choices\/prepare.sh/);
  assert.match(prepare, /scripts\/fixture.sh/);
  const manifest = step('Build verified update manifest and carry forward its changelog history');
  assert.match(manifest, /cp "\$source_apk" "\$release_dir\/multiprompt-companion.apk"/);
  assert.match(manifest, /sha256=\$\(sha256sum "\$release_dir\/multiprompt-companion.apk"/);
  assert.match(manifest, /test "\$sha256" = "\$\(cat "\$EVIDENCE\/recorded-apk-sha256.txt"\)"/);
  assert.ok(manifest.indexOf('test "$sha256"') < manifest.indexOf('jq -n'));
  const prepareScript = readFileSync(path.join(__dirname, 'release-choices/prepare.sh'), 'utf8');
  const candidate = prepareScript.match(/if \[\[ -n "\$\{CANDIDATE_APK:-\}" \]\]; then([\s\S]*?)elif/);
  assert.ok(candidate, 'Existing signed APK needs a no-rebuild route');
  assert.match(candidate[1], /app_apk=\$CANDIDATE_APK/);
  assert.doesNotMatch(candidate[1], /gradle|curl|cp /);
});

test('the publication hash gate rejects changed APK bytes and missing proof', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'release-proof-'));
  try {
    const source = path.join(directory, 'app/build/outputs/apk/release/app-release.apk');
    const evidence = path.join(directory, 'evidence');
    mkdirSync(path.dirname(source), { recursive: true });
    mkdirSync(evidence);
    const bytes = Buffer.from('signed candidate fixture');
    const digest = createHash('sha256').update(bytes).digest('hex');
    writeFileSync(source, bytes);
    const manifest = step('Build verified update manifest and carry forward its changelog history');
    const commands = manifest.split('run: |\n')[1].split('\n\n')[0].replace(/^          /gm, '');
    const run = () => spawnSync('bash', ['-euc', commands], {
      cwd: directory,
      env: { ...process.env, EVIDENCE: evidence },
      encoding: 'utf8',
    });
    assert.notEqual(run().status, 0, 'Missing recorded digest must block publication');
    writeFileSync(path.join(evidence, 'recorded-apk-sha256.txt'), `${digest}\n`);
    assert.equal(run().status, 0, 'The exact recorded APK must pass');
    writeFileSync(source, 'different signed candidate fixture');
    assert.notEqual(run().status, 0, 'Changed publication bytes must fail');
    writeFileSync(source, bytes);
    writeFileSync(path.join(evidence, 'recorded-apk-sha256.txt'), `${'0'.repeat(64)}\n`);
    assert.notEqual(run().status, 0, 'An unrelated recording digest must fail');
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('recording spans the journey and requires a retained readable mp4', () => {
  const script = readFileSync(path.join(__dirname, 'release-proof.sh'), 'utf8');
  const recorder = readFileSync(path.join(__dirname, 'release-recorder.sh'), 'utf8');
  assert.match(script, /source tools\/release-recorder\.sh/);
  assert.match(script, /adb install -r -g "\$VERIFY_APK"/);
  assert.match(script, /adb shell pm path "\$VERIFY_PACKAGE"/);
  assert.match(script, /sha256sum "\$FIXTURE\/installed\.apk"/);
  assert.match(script, /test "\$installed_sha" = "\$candidate_sha"/);
  assert.match(recorder, /screenrecord --time-limit 0/);
  assert.match(script, /trap finish EXIT/);
  assert.match(recorder, /adb shell kill -0 "\$RECORDER_DEVICE"/);
  assert.match(recorder, /adb shell kill -2 "\$RECORDER_DEVICE"/);
  assert.match(recorder, /adb pull "\$RECORDER_REMOTE" "\$EVIDENCE\/release-journey\.mp4"/);
  assert.match(script, /ffprobe[^\n]*"\$EVIDENCE\/release-journey\.mp4"/);
  assert.match(script, /scripts\/drive\.sh "\$VERIFY_FEATURE"/);
  assert.match(script, /exit "\$code"/);
  assert.match(script, /if \[\[ "\$code" == 0 \]\]; then[\s\S]*recorded-apk-sha256\.txt/);
  assert.ok(recorder.indexOf('screenrecord --time-limit 0') < script.indexOf('scripts/drive.sh'));
});

test('recorder shutdown cannot wait forever on a wedged emulator', () => {
  const script = readFileSync(path.join(__dirname, 'release-proof.sh'), 'utf8');
  const recorder = readFileSync(path.join(__dirname, 'release-recorder.sh'), 'utf8');
  const stop = recorder.split('recorder_stop() {')[1].split('\n}')[0];
  assert.match(stop, /sleep "\$RECORDER_STOP_SECONDS"/);
  assert.match(stop, /kill -9 "\$RECORDER_CLIENT"/);
  assert.match(stop, /wait "\$RECORDER_CLIENT" \|\| code=1/);
  assert.doesNotMatch(recorder, /^ *wait "?\$\{?recorder_client"?$/m);
  for (const call of ['adb get-serialno', 'adb install', 'adb shell pm path', 'adb pull "$installed_path"']) {
    const line = script.split('\n').find(value => value.includes(call));
    assert.match(line, /timeout [0-9]+ adb /, `${call} needs a bound`);
  }
});
