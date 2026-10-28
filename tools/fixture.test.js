const test = require('node:test');
const assert = require('node:assert/strict');
const { mkdtempSync, readFileSync, rmSync, writeFileSync } = require('node:fs');
const { tmpdir } = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const source = process.env.FIXTURE_SOURCE || path.join(__dirname, '../.claude/skills/verify-multiprompt-android/scripts/fixture.sh');

test('ephemeral SSH fixture configuration starts on the supported host', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'fixture-config-'));
  try {
    const script = readFileSync(source, 'utf8');
    const template = script.match(/cat > "\$FIXTURE\/sshd_config" <<CONFIG\n([\s\S]*?)\nCONFIG/);
    assert.ok(template, 'fixture sshd configuration exists');
    const key = spawnSync('ssh-keygen', ['-q', '-t', 'ed25519', '-N', '', '-f', path.join(directory, 'host-key')], { encoding: 'utf8' });
    assert.equal(key.status, 0, key.stderr);
    const config = path.join(directory, 'sshd_config');
    writeFileSync(config, template[1].replaceAll('$FIXTURE', directory));
    const check = spawnSync('sudo', ['/usr/sbin/sshd', '-t', '-f', config], { encoding: 'utf8' });
    assert.equal(check.status, 0, check.stderr);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
