const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const gate = require('../.github/scripts/pstack-verifier/coverage.cjs');
const verifier = require('../.github/scripts/pstack-verifier/verifier.cjs');
const root = path.join(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const base = '.claude/skills/verify-multiprompt-android/features';

test('batch four maps exactly 40 controls with action and result proof', () => {
  const controls = JSON.parse(read(`${base}/controls.json`)).controls;
  const gaps = JSON.parse(read(`${base}/control-gaps.json`)).missing;
  for (const [journey, count] of [['settings-controls.md', 19], ['inbox-reminders.md', 21]]) {
    const mapped = controls.filter(control => control.journey === journey);
    assert.equal(mapped.length, count);
    for (const control of mapped) {
      assert.equal(control.proof.recipe, `${base}/${journey}`);
      assert.ok(control.proof.action.length > 3, control.id);
      assert.ok(control.proof.result.includes('.json'), control.id);
      assert.ok(!gaps.includes(control.id), control.id);
      assert.ok(!('missing' in control), control.id);
    }
    assert.ok(verifier.ALLOWLIST.includes(journey));
    assert.ok(read('.github/workflows/verify.yml').includes(`          - ${journey}\n`));
  }
});

test('menu changes require the matching journey even when the picker omits it', () => {
  for (const [file, text, expected] of [
    ['MultipromptApp.kt', 'settingsPage = SettingsPage.PREFERENCES', 'settings-controls.md'],
    ['HostMappingDialog.kt', 'fun HostMappingDialog(', 'settings-controls.md'],
    ['MultipromptApp.kt', 'description = "Open navigation"', 'settings-controls.md'],
    ['MultipromptApp.kt', 'ReminderDialog(session = session)', 'inbox-reminders.md'],
    ['MultipromptApp.kt', 'bucketMenuExpanded = true', 'inbox-reminders.md'],
    ['AppMenu.kt', 'internal fun InboxOverflowMenu(', 'inbox-reminders.md'],
  ]) {
    const filePath = `app/src/main/java/dev/multiprompt/companion/ui/${file}`;
    const diff = `diff --git a/${filePath} b/${filePath}\n--- a/${filePath}\n+++ b/${filePath}\n@@ -1 +1 @@\n-old\n+${text}\n`;
    const plan = gate.planChange(diff, []);
    const result = verifier.includeRequiredJourneys({ features: ['native-skills.md'], dropped: [], smoke: true }, plan, diff);
    assert.ok(result.features.includes(expected), `${file}: ${text}`);
    assert.equal(result.smoke, false);
  }
});

test('recipe edits require both new journeys without product source changes', () => {
  const diff = ['settings-controls.md', 'inbox-reminders.md'].map(name =>
    `diff --git a/${base}/${name} b/${base}/${name}\n--- a/${base}/${name}\n+++ b/${base}/${name}\n@@ -1 +1 @@\n-old\n+new\n`).join('');
  const result = verifier.includeRequiredJourneys({ features: [], dropped: [], smoke: true }, gate.planChange(diff, []), diff);
  assert.deepEqual(result.features, ['settings-controls.md', 'inbox-reminders.md']);
});
