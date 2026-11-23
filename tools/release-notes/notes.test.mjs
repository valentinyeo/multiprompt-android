import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, readdirSync, writeFileSync } from 'node:fs';
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

function titleDirectory(entries) {
  const directory = mkdtempSync(path.join(tmpdir(), 'titles-'));
  for (const [name, content] of Object.entries(entries)) writeFileSync(path.join(directory, name), content);
  return directory;
}

test('every line shows ticket number plus exact ticket title, once per ticket', () => {
  assert.deepEqual(noteLines([
    'IGSH-1694: Tapping a room opens it (#267)',
    'HYCI-12: drop the extra build (#270)',
    'IGSH-1694: Rooms show from every server (#269)',
  ], catalog), ['- IGSH-1694 Rooms on the phone', '- HYCI-12 Android checks finish in ten minutes']);
});

test('MUBO tickets are accepted like other board prefixes', () => {
  const directory = titleDirectory({ 'MUBO-236.txt': 'Jump to newest sends a stray key that breaks the next command\n' });
  const shipped = { ...catalog, ...loadCatalog(directory) };
  assert.deepEqual(noteLines(['MUBO-236: stop sending End from jump to newest'], shipped), [
    '- MUBO-236 Jump to newest sends a stray key that breaks the next command',
  ]);
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

test('one title file per ticket loads as the catalog', () => {
  const directory = titleDirectory({
    'IGSH-1694.txt': 'Rooms on the phone\n',
    'HYCI-12.txt': 'Android checks finish in ten minutes',
  });
  assert.deepEqual(loadCatalog(directory), {
    'IGSH-1694': 'Rooms on the phone',
    'HYCI-12': 'Android checks finish in ten minutes',
  });
  assert.deepEqual(noteLines(['HYCI-12: drop the extra build (#270)', 'IGSH-1694: Tapping a room opens it (#267)'], loadCatalog(directory)), [
    '- HYCI-12 Android checks finish in ten minutes',
    '- IGSH-1694 Rooms on the phone',
  ]);
});

test('a ticket with no title file is refused', () => {
  const directory = titleDirectory({ 'IGSH-1694.txt': 'Rooms on the phone\n' });
  assert.throws(() => noteLines(['IGSH-1: something'], loadCatalog(directory)), /needs its exact ticket title in tools\/release-notes\/titles\/IGSH-1\.txt/);
  assert.throws(() => checkNotes('- IGSH-1 Rooms on the phone', loadCatalog(directory)), /IGSH-1 needs its exact ticket title in tools\/release-notes\/titles\/IGSH-1\.txt/);
  assert.throws(() => checkNotes('- IGSH-1694 Something else', loadCatalog(directory)), /lacks ticket number and title/);
});

test('a title file must hold one exact unpadded line named after its ticket', () => {
  assert.throws(() => noteLines(['IGSH-1694: x'], loadCatalog(titleDirectory({ 'IGSH-1694.txt': ' Rooms on the phone\n' }))), /needs its exact ticket title/);
  assert.throws(() => noteLines(['IGSH-1694: x'], loadCatalog(titleDirectory({ 'IGSH-1694.txt': 'Rooms on the phone ' }))), /needs its exact ticket title/);
  assert.throws(() => noteLines(['IGSH-1694: x'], loadCatalog(titleDirectory({ 'IGSH-1694.txt': 'Rooms on the phone\nMore detail\n' }))), /needs its exact ticket title/);
  assert.throws(() => noteLines(['IGSH-1694: x'], loadCatalog(titleDirectory({ 'IGSH-1694.txt': '' }))), /needs its exact ticket title/);
  assert.throws(() => loadCatalog(titleDirectory({ 'rooms.txt': 'Rooms on the phone\n' })), /must be named after its ticket, like IGSH-1684\.txt/);
});

test('the shipped titles directory holds one valid file per ticket and no shared list', () => {
  const shipped = loadCatalog();
  assert.ok(Object.keys(shipped).length > 0);
  for (const id of Object.keys(shipped)) {
    assert.match(id, /^(?:IGSH|HYCI|MUBO)-[1-9]\d*$/);
    assert.deepEqual(noteLines([`${id}: x`], shipped), [`- ${id} ${shipped[id]}`]);
  }
  const directory = fileURLToPath(new URL('./titles/', import.meta.url));
  for (const name of readdirSync(directory)) assert.match(name, /^(?:IGSH|HYCI|MUBO)-[1-9]\d*\.txt$/);
  assert.throws(() => readdirSync(fileURLToPath(new URL('./ticket-titles.json', import.meta.url))), /ENOENT/);
});

test('MUBO ticket prefixes are accepted', () => {
  const directory = titleDirectory({ 'MUBO-240.txt': 'Android: check a chat reply never shows twice\n' });
  const shipped = loadCatalog(directory);
  assert.deepEqual(noteLines(['MUBO-240: dedupe display-history replies'], shipped), [
    '- MUBO-240 Android: check a chat reply never shows twice',
  ]);
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
