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

test('inspector rejection is not reported as a driver failure or timeout', t => {
  const fixture = retained(t);
  const file = path.join(fixture.dir, 'result.json');
  fs.writeFileSync(file, JSON.stringify({ ...JSON.parse(fs.readFileSync(file)), inspectorExit: 1 }));
  const result = fixture.inspect();
  assert.ok(result.errors.some(line => line.includes('checkpoint inspector failed with exit 1')));
  assert.ok(result.errors.every(line => !/20 minutes|driver failed/.test(line)));
});

test('the replay recorder preserves driver success when the inspector rejects checkpoints', t => {
  const fixture = retained(t);
  const apkFile = path.join(fixture.root, 'app.apk');
  fs.writeFileSync(apkFile, 'same APK');
  fs.writeFileSync(path.join(fixture.root, 'app-source-sha.txt'), sha);
  const source = fs.readFileSync(path.join(__dirname, 'android-break-it/run.sh'), 'utf8');
  const recorder = source.slice(source.indexOf('write_result() {'), source.indexOf('\nrecord() {'));
  const script = `root="$EVIDENCE"; journey=ai-switch.md; VERIFY_APK="$root/app.apk"; started=$SECONDS; phase=inspector\n${recorder}\nwrite_result 1`;
  const run = spawnSync('bash', ['-euo', 'pipefail', '-c', script], { env: { ...process.env, EVIDENCE: fixture.root }, encoding: 'utf8' });
  assert.equal(run.status, 0, run.stderr);
  const report = JSON.parse(fs.readFileSync(path.join(fixture.dir, 'result.json')));
  assert.equal(report.driverExit, 0);
  assert.equal(report.inspectorExit, 1);
});

test('journeys without a host still require every reachable screen checkpoint', t => {
  assert.deepEqual(proof.requiredStresses('chat-search.md'), proof.STRESSES);
  assert.deepEqual(proof.requiredStresses('native-skills.md'), proof.STRESSES);
  assert.deepEqual(proof.requiredStresses('new-driver.md'), proof.STRESSES);
  for (const journey of ['sign-in.md', 'launcher-icon.md', 'hosts-retired.md']) {
    const fixture = retained(t);
    const file = path.join(fixture.dir, 'result.json');
    fs.writeFileSync(file, JSON.stringify({ ...JSON.parse(fs.readFileSync(file)), journey }));
    for (const stress of ['chat-switch', 'stream-send']) {
      for (const ext of ['json', 'png', 'xml', 'txt']) fs.rmSync(path.join(fixture.dir, `${stress}.${ext}`));
    }
    assert.deepEqual(proof.inspect(fixture.root, journey, sha).errors, []);
    fs.rmSync(path.join(fixture.dir, 'rotate.xml'));
    assert.match(proof.inspect(fixture.root, journey, sha).errors.join('\n'), /rotate checkpoint missing/);
  }
});

test('the shared session-ready event runs all stress before drivers that never fill a message', t => {
  const fixture = retained(t);
  const script = `
    source tools/android-break-it/drive.sh
    tap_text() { printf 'TAP %s\\n' "$*"; }
    wait_for_text() { printf 'WAIT %s\\n' "$*"; }
    fill() { printf 'FILL %s\\n' "$*"; }
    tap_desc() { printf 'ACTION %s\\n' "$*"; }
    shot() { :; }
    select_node() { return 1; }
    sudo() { printf '{}\\n'; }
    break_it_draft() { break_it_started=true; printf 'FOUR_STRESSES\\n'; }
    break_it_stream_send() { printf 'STREAM_CHECKPOINT\\n'; }
    break_it_ready
    break_it_ready
  `;
  const run = spawnSync('bash', ['-euo', 'pipefail', '-c', script], { cwd: path.join(__dirname, '..'), env: { ...process.env, EVIDENCE: fixture.dir, BREAK_IT_MODE: 'true' }, encoding: 'utf8' });
  assert.equal(run.status, 0, run.stderr);
  assert.equal(run.stdout.match(/FOUR_STRESSES/g)?.length, 1);
  assert.equal(run.stdout.match(/STREAM_CHECKPOINT/g)?.length, 1);
  assert.match(run.stdout, /FILL Message verify-breakit-probe BREAK_IT_DRAFT_2679/);
  assert.match(run.stdout, /FILL BREAK_IT_DRAFT_2679 BREAK_IT_STREAM/);
  assert.match(run.stdout, /WAIT BREAK_IT_FOLLOWUP_REPLY/);
  assert.match(run.stdout, /ACTION Back\nWAIT Inbox\nTAP All/);
  const driver = fs.readFileSync(path.join(__dirname, '../.claude/skills/verify-multiprompt-android/scripts/drive.sh'), 'utf8');
  assert.match(driver, /shot sessions\nbreak_it_ready\n/);
  assert.match(run.stdout, /ACTION Search sessions\nFILL Search all sessions live-pi_verify-breakit-probe\nWAIT 1 result across all splits\nACTION Robot live-pi_verify-breakit-probe\nWAIT BREAK_IT_READY/);
  const seed = fs.readFileSync(path.join(__dirname, '../.claude/skills/verify-multiprompt-android/scripts/fixture.sh'), 'utf8');
  assert.match(seed, /new-session -d -s live-pi_verify-breakit-probe[^\n]*'sleep infinity'/);
  assert.match(seed, /rename-window -t live-pi_verify-breakit-probe verify-breakit-probe/);
  assert.doesNotMatch(driver.slice(driver.indexOf('fill() {'), driver.indexOf('\nsource ', driver.indexOf('fill() {'))), /break_it_/);
});

test('chat opening retries a missed inbox tap but never drops the target assertion', t => {
  const fixture = retained(t);
  const script = `
    source tools/android-break-it/drive.sh
    tap_desc() { printf 'TAP %s\\n' "$1"; }
    fill() { :; }
    select_node() { [[ "$2" == Inbox ]]; }
    waits=0
    wait_for_text() {
      [[ "$1" == '1 result across all splits' ]] && return 0
      waits=$((waits + 1))
      printf 'ASSERT %s\\n' "$1"
      [[ "$waits" == 2 && "$RECOVERS" == true ]]
    }
    break_it_open_chat verify-breakit-other 'Message verify-breakit-other'
  `;
  for (const recovers of ['true', 'false']) {
    const run = spawnSync('bash', ['-euo', 'pipefail', '-c', script], { cwd: path.join(__dirname, '..'), env: { ...process.env, EVIDENCE: fixture.dir, RECOVERS: recovers }, encoding: 'utf8' });
    assert.equal(run.status === 0, recovers === 'true', run.stderr);
    assert.equal(run.stdout.match(/TAP Robot verify-breakit-other/g)?.length, 2);
    assert.equal(run.stdout.match(/ASSERT Message verify-breakit-other/g)?.length, 2);
  }
});

test('the streaming fixture emits progressive data before completion and holds until release', () => {
  const script = `
import json
import os
from pathlib import Path
import selectors
import subprocess
import sys
import tempfile
import textwrap
import time
source = Path('tools/android-break-it/fixture.py').read_text()
body = textwrap.dedent(source.split('\\nelse:\\n', 1)[1])
with tempfile.TemporaryDirectory() as directory:
    child = 'import json,sys,time\\nfrom pathlib import Path\\nHOME=Path(' + repr(directory) + ')\\ndef emit(event): print(json.dumps(event),flush=True)\\n' + body
    process = subprocess.Popen([sys.executable, '-u', '-c', child], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        process.stdin.write(b'{"type":"prompt","id":"first","message":"BREAK_IT_STREAM"}\\n')
        process.stdin.flush()
        pending = b''
        events = []
        deltas = []
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                for key, _ in selector.select(max(0, deadline - time.monotonic())):
                    chunk = os.read(key.fd, 65536)
                    assert chunk, 'Fixture exited before its streamed reply'
                    pending += chunk
                    while b'\\n' in pending:
                        line, pending = pending.split(b'\\n', 1)
                        event = json.loads(line)
                        events.append(event)
                        update = event.get('assistantMessageEvent', {})
                        if update.get('type') == 'text_delta':
                            deltas.append(update['delta'])
                if ''.join(deltas) == 'BREAK_IT_STREAM_REPLY':
                    break
            assert ''.join(deltas) == 'BREAK_IT_STREAM_REPLY', events
            assert len(deltas) > 1, 'Fixture must progressively stream, not emit one burst and stall'
            assert not any(event.get('assistantMessageEvent', {}).get('type') == 'text_end' for event in events)
            assert not any(event.get('type') == 'agent_settled' for event in events)
        (Path(directory) / 'breakit-release-stream').touch()
        process.stdin.write(b'{"type":"prompt","id":"second","message":"BREAK_IT_FOLLOWUP"}\\n')
        process.stdin.flush()
        process.stdin.close()
        process.stdin = None
        output, errors = process.communicate(timeout=5)
        assert process.returncode == 0, errors
        tail = [json.loads(line) for line in (pending + output).splitlines()]
        done = [event['assistantMessageEvent']['content'] for event in tail if event.get('assistantMessageEvent', {}).get('type') == 'text_end']
        assert done == ['BREAK_IT_STREAM_REPLY', 'BREAK_IT_FOLLOWUP_REPLY'], tail
    finally:
        if process.poll() is None:
            process.terminate()
            process.communicate(timeout=3)
`;
  const run = spawnSync('python3', ['-B', '-c', script], { cwd: path.join(__dirname, '..'), encoding: 'utf8' });
  assert.equal(run.status, 0, run.stderr);
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

test('a stressed replay is carried by its journey result, not by a second verdict rule', t => {
  const fixture = retained(t);
  const options = { features: ['ai-switch.md'], conclusions: { 'ai-switch.md': 'success' } };
  const complete = fixture.inspect();
  assert.ok(complete.evidence.some(line => line.includes('break-it/stream-send.txt')));
  assert.equal(verifier.applyRules({ ...options, stressProof: complete }).verdict, 'PASS');
  fs.rmSync(path.join(fixture.dir, 'rotate.png'));
  assert.ok(fixture.inspect().errors.length);
  assert.equal(verifier.applyRules({ ...options, stressProof: fixture.inspect() }).verdict, 'PASS');
});

test('break-it tooling changes never add a pull request journey', () => {
  const picked = { features: ['native-skills.md'], dropped: [], smoke: true };
  const diff = 'diff --git a/tools/android-break-it/drive.sh b/tools/android-break-it/drive.sh\n';
  const tooling = verifier.includeRequiredJourneys(picked, { product: false, outcomes: [] }, diff);
  assert.deepEqual(tooling.features, []);
  assert.equal(tooling.smoke, false);
  assert.deepEqual(verifier.includeRequiredJourneys(picked, { product: true, outcomes: ['claude.skills-model-effort'] }, diff).features, ['native-skills.md']);
  assert.deepEqual(verifier.includeRequiredJourneys(picked, { product: true, outcomes: [] }, diff).features, []);
});

test('the nightly android-full run schedules the break-it replay', () => {
  const workflow = fs.readFileSync(path.join(__dirname, '../.github/workflows/android-full.yml'), 'utf8');
  const start = workflow.indexOf('  nightly-journeys:');
  assert.ok(start >= 0);
  const end = workflow.indexOf('\n  baseline-profile:', start);
  const job = workflow.slice(start, end < 0 ? undefined : end);
  assert.match(job, /uses: \.\/\.github\/workflows\/verify\.yml/);
  assert.match(job, /^ {6}break_it: true$/m);
});

test('the probe seed enables the same host history that the real reader resolves', () => {
  const fixture = fs.readFileSync(path.join(__dirname, 'android-break-it/fixture.py'), 'utf8');
  const seed = fixture.slice(fixture.indexOf("    assert reader.host_cli(['enable'"), fixture.indexOf('\nelse:\n')).split('\n').map(line => line.slice(4)).join('\n');
  const script = `
import importlib.util
from pathlib import Path
import sys
import tempfile
spec = importlib.util.spec_from_file_location('reader', 'app/src/main/assets/chat_history_reader.py')
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)
with tempfile.TemporaryDirectory() as directory:
    HOME = reader.HOME = Path(directory)
    SID = 'b2679000-0000-4000-8000-000000002679'
    CHAT = 'live-pi_verify-breakit-probe'
    def cli(args):
        if args[0] == 'enable':
            reader.host_write(reader.host_paths('pi', SID)['config'], dict(enabled=True, provider='pi', sid=SID, tmux=CHAT, cwd=str(HOME)))
            return dict(status='enabled')
        query = dict(id=1, provider='pi', codex=False, sid=SID, tmux=CHAT, cwd=str(HOME))
        agent, sid, path, response = reader.resolve_response_path(query)
        assert path is not None and 'BREAK_IT_READY' in path.read_text(), (path, response)
        return dict(status='started')
    reader.host_cli = cli
    exec(${JSON.stringify(seed)})
`;
  const run = spawnSync('python3', ['-B', '-c', script], { cwd: path.join(__dirname, '..'), encoding: 'utf8' });
  assert.equal(run.status, 0, run.stderr);
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
  assert.match(source, /adb shell pm clear "\$package" \| grep -Fx Success\nadb install -r -g "\$VERIFY_APK"\n"\$scripts\/fixture\.sh"/);
});

test('settings-controls stress replay records the five checkpoints on the reader-version chat path', t => {
  const root = path.join(__dirname, '..');
  const read = file => fs.readFileSync(path.join(root, file), 'utf8');
  const readerTail = read('.claude/skills/verify-multiprompt-android/scripts/reader-version-drive.sh');
  const continuation = readerTail.slice(readerTail.indexOf('adb shell input keyevent KEYCODE_BACK'));
  const settings = read('.claude/skills/verify-multiprompt-android/scripts/inbox-controls-drive.sh');
  const proofLine = settings.indexOf("printf 'SETTINGS_MAPPING_NAVIGATION_CONTROLS_OK");
  const block = settings.slice(proofLine, settings.indexOf('\nfi', proofLine));
  assert.match(block, /if \[\[ "\$\{BREAK_IT_MODE:-\}" == true \]\]; then/);
  for (const line of continuation.trim().split('\n')) assert.ok(block.includes(line), line);
  assert.ok(block.trimEnd().endsWith('exit 0'));
  const fixture = read('.claude/skills/verify-multiprompt-android/scripts/fixture.sh');
  const logger = fixture.indexOf('settings-controls.md');
  const remap = fixture.indexOf('VERIFY_FEATURE=ai-switch.md');
  assert.ok(logger > 0 && logger < remap);
  assert.match(fixture, /== reader-version\.md \]\] \|\| \[\[ "\$\{VERIFY_FEATURE:-\}" == settings-controls\.md && "\$\{BREAK_IT_MODE:-\}" == true \]\]; then\n {4}VERIFY_FEATURE=ai-switch\.md/);
  const evidence = retained(t);
  const reportPath = path.join(evidence.dir, 'result.json');
  const report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
  report.journey = 'settings-controls.md';
  fs.writeFileSync(reportPath, JSON.stringify(report));
  assert.deepEqual(proof.inspect(evidence.root, 'settings-controls.md', sha).errors, []);
  for (const stress of proof.STRESSES) fs.rmSync(path.join(evidence.dir, `${stress}.txt`));
  const failed = proof.inspect(evidence.root, 'settings-controls.md', sha);
  for (const stress of proof.STRESSES) {
    assert.ok(failed.errors.includes(`BREAK_IT_FAILED: settings-controls.md: ${stress} checkpoint missing, failed or missing screenshot/text evidence`));
  }
  const stub = fs.mkdtempSync(path.join(os.tmpdir(), 'settings-stress-'));
  t.after(() => fs.rmSync(stub, { recursive: true, force: true }));
  fs.writeFileSync(path.join(stub, 'ai-switch-drive.sh'), 'printf SOURCED\\n');
  const tail = block.slice(block.indexOf('if [[ "${BREAK_IT_MODE:-}" == true ]]; then'), block.lastIndexOf('exit 0'));
  const harness = `adb() { :; }\nwait_for_text() { :; }\ntap_text() { :; }\n${tail}`;
  for (const mode of ['true', 'false']) {
    const run = spawnSync('bash', ['-euo', 'pipefail', '-c', harness], { env: { ...process.env, BREAK_IT_MODE: mode, scripts: stub }, encoding: 'utf8' });
    assert.equal(run.status, 0, run.stderr);
    assert.equal(run.stdout.includes('SOURCED'), mode === 'true');
  }
});

test('verification tooling is not an Android repair merely because its ticket mentions bugs', () => {
  assert.equal(verifier.isBugFix({ title: 'IGSH-2658: stress checks', changeKind: 'tooling', tickets: { 'IGSH-2658': 'Agents try to break every Android bug fix' } }), false);
  assert.equal(verifier.isBugFix({ title: 'IGSH-2658: stress checks', changeKind: 'product', tickets: { 'IGSH-2658': 'Agents try to break every Android bug fix' } }), true);
});
