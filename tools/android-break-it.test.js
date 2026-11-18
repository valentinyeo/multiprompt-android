const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const proof = require('../.github/scripts/pstack-verifier/break-it.cjs');
const verifier = require('../.github/scripts/pstack-verifier/verifier.cjs');

const sha = 'a'.repeat(40);
const apk = 'b'.repeat(64);

function retained(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'break-it-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const dir = path.join(root, 'break-it');
  fs.mkdirSync(dir);
  fs.writeFileSync(path.join(root, 'app-apk-sha256.txt'), `${apk}  app.apk\n`);
  fs.writeFileSync(path.join(dir, 'result.json'), JSON.stringify({ journey: 'ai-switch.md', sourceSha: sha, apkSha256: apk, baselinePassed: true, driverExit: 0, elapsedSeconds: 201 }));
  fs.writeFileSync(path.join(dir, 'driver-transcript.txt'), proof.STRESSES.map(stress => `BREAK_IT ${stress} PASSED\n`).join('') + 'DRIVER EXIT: 0\n+ exit 0\n');
  for (const stress of proof.STRESSES) {
    fs.writeFileSync(path.join(dir, `${stress}.json`), JSON.stringify({ stress, passed: true }));
    fs.writeFileSync(path.join(dir, `${stress}.png`), Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), Buffer.alloc(100)]));
    fs.writeFileSync(path.join(dir, `${stress}.xml`), '<hierarchy><node package="dev.multiprompt.companion.debug" text="PHONE_AFTER_SWITCH_2538"/></hierarchy>');
    fs.writeFileSync(path.join(dir, `${stress}.txt`), `${stress}: draft retained and original reply occurred once`);
  }
  return { root, dir, inspect: () => proof.inspect(root, 'ai-switch.md', sha) };
}

test('all five retained stress checkpoints pass and are cited', t => {
  const fixture = retained(t);
  const result = fixture.inspect();
  assert.deepEqual(result.errors, []);
  assert.equal(result.evidence.length, 5);
  for (const stress of ['rotate', 'background', 'chat-switch', 'network', 'stream-send']) assert.ok(result.evidence.some(line => line.includes(`break-it/${stress}.txt`)));
});

test('a missing, failed or unevidenced stress cannot pass', t => {
  for (const stress of proof.STRESSES) {
    for (const extension of ['json', 'png', 'xml', 'txt']) {
      const fixture = retained(t);
      fs.rmSync(path.join(fixture.dir, `${stress}.${extension}`));
      assert.ok(fixture.inspect().errors.some(line => line.includes(stress)), `${stress}.${extension}`);
    }
    const fixture = retained(t);
    fs.writeFileSync(path.join(fixture.dir, `${stress}.json`), JSON.stringify({ stress, passed: false }));
    assert.ok(fixture.inspect().errors.some(line => line.includes(stress)));
  }
});

test('a green normal journey cannot stand in for a missing stressed replay', t => {
  const fixture = retained(t);
  fs.rmSync(fixture.dir, { recursive: true });
  assert.ok(fixture.inspect().errors.length);
});

test('a broken or timed-out replay and evidence from a different APK fail', t => {
  for (const patch of [{ sourceSha: 'c'.repeat(40) }, { apkSha256: 'd'.repeat(64) }, { journey: 'hosts.md' }, { baselinePassed: false }, { driverExit: 1 }, { elapsedSeconds: 1201 }]) {
    const fixture = retained(t);
    const file = path.join(fixture.dir, 'result.json');
    fs.writeFileSync(file, JSON.stringify({ ...JSON.parse(fs.readFileSync(file)), ...patch }));
    assert.ok(fixture.inspect().errors.length, JSON.stringify(patch));
  }
  const fixture = retained(t);
  fs.appendFileSync(path.join(fixture.dir, 'driver-transcript.txt'), 'DRIVER EXIT: 1\n');
  assert.ok(fixture.inspect().errors.length);
});

test('repro-first success and a model PASS cannot override broken or missing stress proof', t => {
  const fixture = retained(t);
  const options = { modelVerdict: 'PASS', bugFix: true, features: ['ai-switch.md'], conclusions: { 'ai-switch.md': 'success' }, parents: { 'ai-switch.md': 'product assertion' } };
  assert.equal(verifier.applyRules(options).verdict, 'FAIL');
  const passed = verifier.applyRules({ ...options, stressProof: fixture.inspect() });
  assert.equal(passed.verdict, 'PASS');
  assert.ok(passed.notes.some(line => line.includes('break-it/stream-send.txt')));
  fs.rmSync(path.join(fixture.dir, 'rotate.png'));
  assert.equal(verifier.applyRules({ ...options, stressProof: fixture.inspect() }).verdict, 'FAIL');
});

test('break-it tooling changes exercise the real stress gate without inventing an Android parent repro', t => {
  const fixture = retained(t);
  const options = { modelVerdict: 'PASS', bugFix: false, stressRequired: true, features: ['ai-switch.md'], conclusions: { 'ai-switch.md': 'success' }, parents: { 'ai-switch.md': 'passed' } };
  assert.equal(verifier.applyRules(options).verdict, 'FAIL');
  assert.equal(verifier.applyRules({ ...options, stressProof: fixture.inspect() }).verdict, 'PASS');
});

test('tooling proof selects its complete adapter without dropping journeys for Android repairs', () => {
  const picked = { features: ['native-skills.md'], dropped: [], smoke: true };
  const diff = 'diff --git a/tools/android-break-it/drive.sh b/tools/android-break-it/drive.sh\n';
  const tooling = verifier.includeRequiredJourneys(picked, { product: false, outcomes: [] }, diff);
  assert.deepEqual(tooling.features, ['ai-switch.md']);
  assert.equal(tooling.smoke, false);
  assert.deepEqual(verifier.includeRequiredJourneys(picked, { product: true, outcomes: [] }, diff).features, ['native-skills.md']);
});

test('draft readback survives lost focus without relying on an empty-field placeholder', t => {
  for (const observed of ['PHONE_AFTER_SWITCH_2538', '']) {
    const fixture = retained(t);
    const script = `
      source tools/android-break-it/drive.sh
      scripts="$PWD/.claude/skills/verify-multiprompt-android/scripts"
      focused=false
      dump_ui() {
        printf '<hierarchy><node package="dev.multiprompt.companion.debug" class="android.widget.EditText" text="%s" content-desc="Message verify-session" focused="%s" bounds="[0,0][100,100]"/></hierarchy>' "$OBSERVED" "$focused" > "$EVIDENCE/current.xml"
      }
      select_node() { dump_ui; python3 "$scripts/ui.py" "$EVIDENCE/current.xml" "$1" "$2"; }
      tap_node() { printf 'Unexpected focus change' >&2; return 1; }
      adb() { :; }
      break_it_require_draft 'Message verify-session' PHONE_AFTER_SWITCH_2538
    `;
    const run = spawnSync('bash', ['-euo', 'pipefail', '-c', script], { cwd: path.join(__dirname, '..'), env: { ...process.env, EVIDENCE: fixture.dir, OBSERVED: observed }, encoding: 'utf8' });
    if (observed) {
      assert.equal(run.status, 0, run.stderr);
      assert.match(run.stdout, /FIELD_VALUE_VERIFIED/);
    } else {
      assert.notEqual(run.status, 0);
      assert.match(run.stderr, /expected 'PHONE_AFTER_SWITCH_2538'/);
    }
  }
});

test('retained landscape XML accepts the real draft and rejects the planted loss without a placeholder or focus', () => {
  for (const [file, passed] of [['rotation-draft.xml', true], ['rotation-lost.xml', false]]) {
    const run = spawnSync('python3', ['-B', path.join(__dirname, '../.claude/skills/verify-multiprompt-android/scripts/ui.py'), path.join(__dirname, 'fixtures/android-break-it', file), 'editor-value', 'Message verify-session', 'PHONE_AFTER_SWITCH_2538'], { encoding: 'utf8' });
    assert.equal(run.status === 0, passed, run.stderr);
    if (!passed) assert.match(run.stderr, /has '', expected 'PHONE_AFTER_SWITCH_2538'/);
  }
});

test('network stress requires the configured numeric rates and both latency bounds', t => {
  const source = fs.readFileSync(path.join(__dirname, 'android-break-it/drive.sh'), 'utf8');
  const start = source.indexOf('    break_it_active=network\n');
  const block = source.slice(start, source.indexOf('\n}', start));
  const status = 'Current network status:\n  download speed:     128000 bits/s\n  upload speed:       128000 bits/s\n  minimum latency:  150 ms\n  maximum latency:  300 ms\nOK\n';
  for (const response of [status, status.replace('upload speed:       128000', 'upload speed:       473600'), status.replace('download speed:     128000', 'download speed:     0'), status.replace('150 ms', '0 ms'), status.replace('300 ms', '100 ms')]) {
    const fixture = retained(t);
    const script = `label=chat; draft=prompt
      adb() {
        case "$*" in
          'emu network status') printf '%s' "$STATUS" ;;
          'emu network speed 128:128'|'emu network delay 150:300') : ;;
          *) printf 'Wrong network command: %s' "$*" >&2; return 1 ;;
        esac
      }
      break_it_require_draft() { :; }
      break_it_checkpoint() { printf 'NETWORK_CHECKPOINT_PASSED'; }
      ${block}`;
    const run = spawnSync('bash', ['-euo', 'pipefail', '-c', script], { env: { ...process.env, EVIDENCE: fixture.dir, STATUS: response }, encoding: 'utf8' });
    assert.equal(run.status === 0, response === status, run.stderr);
    assert.equal(run.stdout.includes('NETWORK_CHECKPOINT_PASSED'), response === status);
  }
});

test('fresh character input does not crowd the named journey actions out of verifier evidence', t => {
  const fixture = retained(t);
  fs.writeFileSync(path.join(fixture.root, 'driver-transcript.txt'), "+ tap_text Pi\n" + '+ adb shell input text X\n'.repeat(200) + "+ tap_desc 'Send prompt'\n");
  const evidence = verifier.journeyEvidence(fixture.root, 'ai-switch.md');
  assert.match(evidence, /\+ tap_text Pi/);
  assert.match(evidence, /\+ tap_desc 'Send prompt'/);
});

test('a fresh replay restores the same APK and its install-time permissions after clearing data', () => {
  const source = fs.readFileSync(path.join(__dirname, 'android-break-it/run.sh'), 'utf8');
  assert.match(source, /adb shell pm clear dev\.multiprompt\.companion\.debug \| grep -Fx Success\nadb install -r -g "\$VERIFY_APK"\n"\$scripts\/fixture\.sh"/);
});

test('verification tooling is not an Android repair merely because its ticket mentions bugs', () => {
  assert.equal(verifier.isBugFix({ title: 'IGSH-2658: stress checks', changeKind: 'tooling', tickets: { 'IGSH-2658': 'Agents try to break every Android bug fix' } }), false);
  assert.equal(verifier.isBugFix({ title: 'IGSH-2658: stress checks', changeKind: 'product', tickets: { 'IGSH-2658': 'Agents try to break every Android bug fix' } }), true);
});
