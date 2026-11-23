import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const packages = readFileSync('.github/actions/android-proof/packages.sh', 'utf8');
const action = readFileSync('.github/actions/android-proof/action.yml', 'utf8');

test('emulator package setup repairs apt, retries install once, and names setup failures clearly', () => {
  assert.match(packages, /dpkg --configure -a/);
  assert.match(packages, /apt-get -f install/);
  assert.match(packages, /repair_apt_state\(\)/);
  assert.match(packages, /apt_install_with_retry/);
  assert.match(packages, /if apt_install_once "\$@"; then/);
  assert.ok((packages.match(/repair_apt_state/g) ?? []).length >= 4);
  assert.match(packages, /ANDROID_PROOF_SETUP_FAILED/);
  assert.match(action, /name: Emulator setup failed when pinned runner packages cannot install/);
});