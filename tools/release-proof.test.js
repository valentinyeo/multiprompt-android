const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');

const workflow = readFileSync(path.join(__dirname, '../.github/workflows/release.yml'), 'utf8');
const steps = workflow.split(/(?=^      - )/m);
const step = name => {
  const found = steps.find(value => value.startsWith(`      - name: ${name}\n`));
  assert.ok(found, `Missing release step: ${name}`);
  return found;
};

test('publishing waits for a successful signed launch check and never runs the recorded journey', () => {
  const launch = step('Launch signed M3 APK');
  assert.match(launch, /id: release-launch\n/);
  assert.match(launch, /bash tools\/release-launch\.sh/);
  assert.doesNotMatch(launch, /continue-on-error:|if:/);
  assert.doesNotMatch(workflow, /release-proof\.sh/);
  assert.doesNotMatch(workflow, /Prepare signed release journey/);
  assert.doesNotMatch(workflow, /Record signed release journey/);
  assert.doesNotMatch(workflow, /Upload release proof/);
  for (const channel of ['android-latest', 'android-m3-latest']) {
    const publish = step(`Publish fixed ${channel} channel`);
    assert.match(publish, /if: success\(\) && steps\.release-launch\.outcome == 'success'/);
    assert.ok(workflow.indexOf(launch) < workflow.indexOf(publish));
  }
});

test('signed release and M3 APKs build in one Gradle invocation before the launch gate', () => {
  const build = step('Build signed APKs');
  assert.match(build, /assembleRelease assembleM3/);
  assert.doesNotMatch(build, /release_pid=/);
  assert.doesNotMatch(build, /&\s*\n\s*m3_pid=/);
  assert.ok(workflow.indexOf(build) < workflow.indexOf(step('Launch signed M3 APK')));
});

test('the update manifest copies the built release APK without a recording digest gate', () => {
  const manifest = step('Build verified update manifest and carry forward its changelog history');
  assert.match(manifest, /cp "\$source_apk" "\$release_dir\/multiprompt-companion.apk"/);
  assert.match(manifest, /sha256=\$\(sha256sum "\$release_dir\/multiprompt-companion.apk"/);
  assert.doesNotMatch(manifest, /recorded-apk-sha256/);
});