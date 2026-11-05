import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkNotes, loadCatalog, noteLines, ticketFor } from './notes.mjs';

const catalog = {
  'IGSH-1684': 'Multibot Android: every update note line names its ticket',
  'IGSH-1694': 'Rooms on the phone',
  'HYCI-12': 'Android checks finish in ten minutes',
  'IGSH-9': 'Checks and fixes, no visible change',
};
const tool = fileURLToPath(new URL('./notes.mjs', import.meta.url));

test('every line shows ticket number plus exact ticket title, once per ticket', () => {
  assert.deepEqual(noteLines([
    'IGSH-1694: Tapping a room opens it (#267)',
    'HYCI-12: drop the extra build (#270)',
    'IGSH-1694: Rooms show from every server (#269)',
  ], catalog), ['- IGSH-1694 Rooms on the phone', '- HYCI-12 Android checks finish in ten minutes']);
});

test('a commit without its ticket is refused', () => {
  assert.throws(() => noteLines(['Session previews no longer show terminal reply codes (#260)'], catalog), /must start with its ticket/);
  assert.throws(() => ticketFor('IGSH-0: nothing'), /must start with its ticket/);
  assert.throws(() => ticketFor('IGSH-12 no colon'), /must start with its ticket/);
});

test('a ticket missing from the title list is refused', () => {
  assert.throws(() => noteLines(['IGSH-1: something'], catalog), /needs its exact ticket title/);
  assert.throws(() => noteLines(['IGSH-1: something'], { 'IGSH-1': ' padded' }), /needs its exact ticket title/);
});

test('a filler line is refused', () => {
  assert.throws(() => noteLines(['IGSH-9: tidy'], catalog), /filler/);
  assert.throws(() => checkNotes('- No source changes', catalog), /lacks ticket number and title/);
  assert.throws(() => checkNotes('- IGSH-1684 Checks and fixes', catalog), /lacks ticket number and title/);
  assert.doesNotThrow(() => checkNotes('- IGSH-1684 Multibot Android: every update note line names its ticket\n', catalog));
});

test('the shipped title list holds only valid titles', () => {
  const shipped = loadCatalog();
  for (const id of Object.keys(shipped)) {
    assert.match(id, /^(?:IGSH|HYCI)-[1-9]\d*$/);
    assert.deepEqual(noteLines([`${id}: x`], shipped), [`- ${id} ${shipped[id]}`]);
  }
});

test('the command line checks PR titles and builds notes from a git range', () => {
  const ok = spawnSync('node', [tool, '--pr-title', 'IGSH-1684: notes name their ticket'], { encoding: 'utf8' });
  assert.equal(ok.status, 0, ok.stderr);
  const bad = spawnSync('node', [tool, '--pr-title', 'Add AGENTS.md pointing every agent at CLAUDE.md'], { encoding: 'utf8' });
  assert.equal(bad.status, 1);
  assert.match(bad.stderr, /must start with its ticket/);

  const repo = mkdtempSync(path.join(tmpdir(), 'notes-'));
  const git = (...args) => execFileSync('git', ['-C', repo, '-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { encoding: 'utf8' }).trim();
  git('init', '-q');
  const commit = message => { writeFileSync(path.join(repo, 'f'), message); git('add', 'f'); git('commit', '-qm', message); return git('rev-parse', 'HEAD'); };
  const base = commit('old commit without a ticket');
  commit('IGSH-1684: first part');
  commit('IGSH-1684: second part');
  const range = spawnSync('node', [tool, '--range', base, 'HEAD'], { cwd: repo, encoding: 'utf8' });
  assert.equal(range.status, 0, range.stderr);
  assert.equal(range.stdout.trim(), '- IGSH-1684 Multibot Android: every update note line names its ticket');
  commit('Checks and fixes, no visible change');
  const filler = spawnSync('node', [tool, '--range', base, 'HEAD'], { cwd: repo, encoding: 'utf8' });
  assert.equal(filler.status, 1);
  const empty = spawnSync('node', [tool, '--range', git('rev-parse', 'HEAD'), 'HEAD'], { cwd: repo, encoding: 'utf8' });
  assert.equal(empty.status, 0, empty.stderr);
  assert.equal(empty.stdout, '');
});
