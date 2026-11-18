const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const gate = require('../.github/scripts/pstack-verifier/coverage.cjs');
const verifier = require('../.github/scripts/pstack-verifier/verifier.cjs');
const root = path.join(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const base = '.claude/skills/verify-multiprompt-android/features';

test('batch five maps 25 workspace and 15 group controls with action and result proof', () => {
  const controls = JSON.parse(read(`${base}/controls.json`)).controls;
  const gaps = JSON.parse(read(`${base}/control-gaps.json`)).missing;
  for (const [journey, count] of [['workspace-controls.md', 25], ['group-controls.md', 15]]) {
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

test('workspace and group menu edits force the matching hosted journey', () => {
  for (const [file, text, expected] of [
    ['AppMenu.kt', 'label = "Arrange splits"', 'workspace-controls.md'],
    ['AppMenu.kt', 'label = "New workspace"', 'workspace-controls.md'],
    ['MultipromptApp.kt', 'splitOrderDialogVisible = true', 'workspace-controls.md'],
    ['MultipromptApp.kt', 'onMoveWorkspaceSplit(splitId, 1)', 'workspace-controls.md'],
    ['MultipromptApp.kt', 'chosenBots.clear()', 'workspace-controls.md'],
    ['MultipromptApp.kt', 'onMoveSession(session, workspace)', 'workspace-controls.md'],
    ['MultipromptApp.kt', 'groupDraft = it', 'group-controls.md'],
    ['MultipromptApp.kt', 'onSetChatGroup(tab, null, favorites)', 'group-controls.md'],
    ['MultipromptApp.kt', 'onUngroup(group, false)', 'group-controls.md'],
  ]) {
    const filePath = `app/src/main/java/dev/multiprompt/companion/ui/${file}`;
    const diff = `diff --git a/${filePath} b/${filePath}\n--- a/${filePath}\n+++ b/${filePath}\n@@ -1 +1 @@\n-old\n+${text}\n`;
    const result = verifier.includeRequiredJourneys({ features: [], dropped: [], smoke: true }, gate.planChange(diff, []), diff);
    assert.ok(result.features.includes(expected), `${file}: ${text}`);
    assert.equal(result.smoke, false);
  }
});

test('recipe edits require both account journeys without changing the app', () => {
  const diff = ['workspace-controls.md', 'group-controls.md'].map(name =>
    `diff --git a/${base}/${name} b/${base}/${name}\n--- a/${base}/${name}\n+++ b/${base}/${name}\n@@ -1 +1 @@\n-old\n+new\n`).join('');
  const result = verifier.includeRequiredJourneys({ features: [], dropped: [], smoke: true }, gate.planChange(diff, []), diff);
  assert.deepEqual(result.features, ['workspace-controls.md', 'group-controls.md']);
});

test('account fixture, row selectors and independent receipt oracles reject broken behavior', () => {
  execFileSync('python3', ['-B', 'tools/account-controls/proof.test.py'], { cwd: root, stdio: 'pipe' });
});
