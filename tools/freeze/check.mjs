#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export const allowlistFile = fileURLToPath(new URL('./allowed-tickets.json', import.meta.url));
const ticketAtStart = /^((?:IGSH|HYCI)-[1-9]\d*)\b/;

export function loadAllowlist(file = allowlistFile) {
  const parsed = JSON.parse(readFileSync(file, 'utf8'));
  if (typeof parsed.frozen !== 'boolean') {
    throw new Error(`${file} needs "frozen" set to true or false`);
  }
  if (!Array.isArray(parsed.allowed)) {
    throw new Error(`${file} needs an "allowed" list of ticket ids like IGSH-2752`);
  }
  for (const id of parsed.allowed) {
    if (typeof id !== 'string' || id !== id.trim() || !/^(?:IGSH|HYCI)-[1-9]\d*$/.test(id)) {
      throw new Error(`${file} has a ticket id that is not a whole id: ${JSON.stringify(id)}`);
    }
  }
  return parsed;
}

export function decide(title, allowlist) {
  const match = ticketAtStart.exec(String(title ?? '').trim());
  if (!match) {
    return { pass: true, message: 'The PR title does not start with a ticket id, so this check has nothing to look up. The PR title check fails that PR.' };
  }
  const id = match[1];
  if (!allowlist.frozen) {
    return { pass: true, message: `FEATURE_FREEZE_OFF: ${id} may ship. Nothing is frozen.` };
  }
  if (allowlist.allowed.includes(id)) {
    return { pass: true, message: `FEATURE_FREEZE_ALLOWED: ${id} is on the freeze allowlist.` };
  }
  return {
    pass: false,
    message: [
      `FEATURE_FREEZE_BLOCKED: ${id} is not on the freeze allowlist, so this PR cannot merge.`,
      'Feature work in this repo is frozen until phone testing meets the checklist (IGSH-2570).',
      `Only chat repair, faster checks or test setup work may add a ticket to tools/freeze/allowed-tickets.json.`,
      'Urgent bug? Add its ticket id to that file in this PR, then say why in the PR body.',
    ].join('\n'),
  };
}

function run(args) {
  const flag = args.indexOf('--title');
  const title = flag >= 0 ? args[flag + 1] : process.env.PR_TITLE;
  if (typeof title !== 'string') {
    throw new Error('Usage: node tools/freeze/check.mjs --title "<PR title>" or set PR_TITLE');
  }
  const result = decide(title, loadAllowlist());
  if (result.pass) console.log(result.message);
  else {
    console.error(result.message);
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  try {
    run(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
