import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ticketPrefix = /^((?:IGSH|HYCI)-[1-9]\d*):\s+\S/;
const filler = /checks and fixes|no visible change|no source changes|^(?:fix(?:es)?|update[sd]?|misc|cleanup|wip|chore)\.?$/i;

export function loadCatalog(file = new URL('./ticket-titles.json', import.meta.url)) {
  return JSON.parse(readFileSync(file, 'utf8'));
}

export function ticketTitle(id, catalog) {
  const title = Object.hasOwn(catalog, id) ? catalog[id] : undefined;
  if (typeof title !== 'string' || !title.trim() || title !== title.trim() || /[\x00-\x1f\x7f]/.test(title)) {
    throw new Error(`${id} needs its exact ticket title in tools/release-notes/ticket-titles.json`);
  }
  if (filler.test(title)) throw new Error(`${id} title is filler: ${title}`);
  return title;
}

export function ticketFor(subject) {
  const match = ticketPrefix.exec(subject);
  if (!match) throw new Error(`Commit "${subject}" must start with its ticket, like "IGSH-1684: what changed"`);
  return match[1];
}

export function noteLines(subjects, catalog) {
  const seen = new Set();
  const lines = [];
  for (const subject of subjects) {
    const id = ticketFor(subject);
    const title = ticketTitle(id, catalog);
    if (seen.has(id)) continue;
    seen.add(id);
    lines.push(`- ${id} ${title}`);
  }
  return lines;
}

export function checkNotes(notes, catalog) {
  for (const line of notes.split('\n').filter(line => line.trim())) {
    const match = /^- ((?:IGSH|HYCI)-[1-9]\d*) (.+)$/.exec(line);
    if (!match || ticketTitle(match[1], catalog) !== match[2]) throw new Error(`Release note line lacks ticket number and title: ${line}`);
  }
}

function subjectsBetween(from, to) {
  const range = from ? `${from}..${to}` : `${to}^!`;
  const out = execFileSync('git', ['log', '--no-merges', '--reverse', '--format=%s', range], { encoding: 'utf8' });
  return out.split('\n').filter(Boolean);
}

function run(args) {
  const catalog = loadCatalog();
  if (args[0] === '--pr-title') {
    const id = ticketFor(args[1] ?? '');
    console.log(`OK ${id} ${ticketTitle(id, catalog)}`);
    return;
  }
  if (args[0] === '--range') {
    const notes = noteLines(subjectsBetween(args[1] || '', args[2] || 'HEAD'), catalog).join('\n');
    checkNotes(notes, catalog);
    if (notes) console.log(notes);
    return;
  }
  throw new Error('Usage: node tools/release-notes/notes.mjs --pr-title <title> | --range <from-commit|""> [to]');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    run(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
