import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { tokenize } from './strip-comments.mjs';
const checks = 'Checks and fixes, no visible change';

const reviewedReleases = {
  'v0.28.1027': ['77690fa1b105d6cd8070484decb8273d835334bf', { 'IGSH-1154': checks }],
  'v0.28.1028': ['93ff8a0631761eefaf772b58ccb7a6318c7edc02', { 'IGSH-1154': 'Large messages no longer block queue receipts.' }],
  'v0.28.1029': ['cf508172357d1344bfc6be893dc857fdeb87af91', { 'IGSH-1154': 'Stuck messages can recover without duplicate sends.' }],
  'v0.28.1030': ['967eee1cbabc094772d567daf3d75cc2d713d9e3', { 'IGSH-1154': checks }],
  'v0.28.1031': ['1648c59e146e06be0fec8247f6db800da4fba3ad', { 'IGSH-1193': 'The app title stays Multi Bot.' }],
  'v0.28.1032': ['f2bcef5380210cbbcf57d06c75031676e66f88f1', { 'IGSH-1229': 'Codex conversations stay with their sessions.', 'IGSH-1217': 'Codex conversations stay with their sessions.' }],
  'v0.28.1033': ['2959b4bb020415fe98730f0155e526f68a4308dd', { 'IGSH-1229': checks }],
  'v0.28.1034': ['af3ea232f557386345bd9628fa28c97e5d038deb', { 'IGSH-1154': 'Queued messages can recover after missed confirmations.' }],
  'v0.28.1035': ['c490189d5d85ebf4c1c65ad801feac634994d5b3', { 'IGSH-1239': 'Chat clients can share one message reader.' }],
  'v0.28.1036': ['3aa30b208e28eefd8b005ef252f2f208a99afa4b', { 'IGSH-1239': 'Damaged reader settings no longer select older versions.' }],
  'v0.28.1037': ['3d9dc2d44eaa91e47098c69054c0b02b4edd2c5f', { 'IGSH-1229': 'Unknown chats stay out of neighbouring sessions.', 'IGSH-1239': 'Unknown chats stay out of neighbouring sessions.' }],
  'v0.28.1038': ['920dffe80a424393d74e5bd0b8514b6bddb2df12', { 'IGSH-1154': 'Messages can queue while the bot works.', 'IGSH-1239': 'Messages can queue while the bot works.' }],
  'v0.28.1039': ['76f39d4c0ee1a140e4304c8995074daf81e86b53', { 'IGSH-1154': checks }],
  'v0.28.1040': ['8c4b6c99cef41bd6b8c9ab48e789c3f09430cb68', { 'IGSH-1239': checks }],
  'v0.28.1042': ['9ee03f97b05f50238aaac77154ebf11be3cc30b7', { 'IGSH-1154': checks, 'IGSH-1239': checks }],
  'v0.28.1043': ['fc76d5714ced43500552fa0398f8b2f56ee41bd5', { 'IGSH-1154': 'Confirmed message copies match by receipt.', 'IGSH-1239': 'Confirmed message copies match by receipt.' }],
  'v0.28.1044': ['28b4cc4cf52133396321b7e5fc7557bb2337843d', { 'IGSH-1154': checks, 'IGSH-1239': checks }],
  'v0.28.1045': ['62c6637d826fcd8c35e43ca52f9161cf8d4c154a', { 'IGSH-1154': checks }],
  'v0.28.1046': ['1c5e0cccc66cf1c6f962746b4d23506ff0d366f5', { 'IGSH-1154': 'Queued sends avoid competing terminal handoffs.' }],
  'v0.28.1047': ['4ebe5f53eeda7d8a945cfb3af2598672a87c0218', { 'IGSH-1154': checks }],
  'v0.28.1048': ['e62769a0862c8518a68a74ea6bfc6030b03dad3b', { 'IGSH-1154': checks, 'IGSH-1239': checks }],
};

const checksOnly = checks;

const mainPath = 'src/main.zig';
const changelogPath = 'src/changelog.zig';
const versionLine = /^pub const APP_VERSION = (?:build_config\.app_version orelse )?"(\d+)\.(\d+)\.(\d+)";$/m;
const changelogStart = /(?:pub const CHANGELOG|const SOURCE_CHANGELOG) = \[_\]ChangelogEntry\{/;
export const fixHint = 'add changelog.d/<TICKET>-<slug>.md instead; the version is stamped after merge (node tools/version-stamp.mjs release)';

export function autoRevertBranch(env = process.env) {
  return (env.GITHUB_HEAD_REF || env.GITHUB_REF_NAME || '').startsWith('auto-revert/');
}

export function plainChange(line) {
  return !!line.trim() && !(/[A-Z]+-\d+|[\/\\`]|#\d+|\b(?:PR|pull request)\s*#?\s*\d+|\b[\w-]+\.[a-zA-Z][\w.-]*|[\x00-\x1f\x7f]/i.test(line));
}

export function productChange(line) {
  return plainChange(line) && !(/behind the scenes|latest improvements|^(?:various improvements|bug fixes|maintenance|improvements|fixes)\.?$|\b(?:checks?|tests?|CI|proofs?|pipelines?|workflows?)\b/i.test(line));
}

export function fragmentLines(file, text) {
  if (!/^changelog\.d\/[A-Z][A-Z0-9]*-\d+-[^/]+\.md$/.test(file)) throw new Error(`Bad fragment filename: ${file}`);
  const lines = text.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  if (!lines.length) throw new Error(`Fragment has no text: ${file}`);
  if (lines.some(line => !plainChange(line))) throw new Error(`${file}: write plain product lines without ticket ids, file names, paths or PR numbers`);
  return lines;
}

function runtimeZigTokens(source) {
  const tokens = tokenize(source, 'zig').tokens;
  const result = [];
  let depth = 0;
  for (let i = 0; i < tokens.length; i++) {
    if (depth === 0 && tokens[i][0] === 'word' && tokens[i][1] === 'test') {
      if (tokens[i + 1]?.[0] === 'string') i++;
      if (tokens[++i]?.[1] !== '{') throw new Error('Invalid Zig test declaration');
      let testDepth = 1;
      while (testDepth && ++i < tokens.length) {
        if (tokens[i][0] === 'code' && tokens[i][1] === '{') testDepth++;
        if (tokens[i][0] === 'code' && tokens[i][1] === '}') testDepth--;
      }
      if (testDepth) throw new Error('Unclosed Zig test declaration');
      continue;
    }
    if (tokens[i][0] === 'code' && tokens[i][1] === '{') depth++;
    if (tokens[i][0] === 'code' && tokens[i][1] === '}') depth--;
    result.push(tokens[i]);
  }
  return JSON.stringify(result);
}

export function checkFragments(base, head, changed) {
  for (const file of Object.keys(head).filter(file => file.startsWith('changelog.d/') && file !== 'changelog.d/README.md')) {
    const lines = fragmentLines(file, head[file]);
    if (changed.includes(file) && lines.some(line => !productChange(line))) throw new Error(`${file}: write a specific user-visible change; omit notes for checks, tests or pipeline work`);
  }
  const appChanged = changed.some(file => file.startsWith('src/') &&
    (!file.endsWith('.zig') || !(file in base) || !(file in head) || runtimeZigTokens(base[file]) !== runtimeZigTokens(head[file])));
  if (appChanged && !changed.some(file => !(file in base) && file in head && file.startsWith('changelog.d/') && file !== 'changelog.d/README.md')) {
    throw new Error('App code changed: add changelog.d/<TICKET>-<slug>.md with at least one plain product line');
  }
}

export function stampTree(files, requestedVersion) {
  const removed = Object.keys(files).filter(file => file.startsWith('changelog.d/') && file !== 'changelog.d/README.md').sort();
  if (!removed.length && !requestedVersion) return null;
  const current = files[mainPath]?.match(versionLine);
  if (!current) throw new Error('Expected one APP_VERSION line in src/main.zig');
  const version = requestedVersion ?? `${current[1]}.${current[2]}.${BigInt(current[3]) + 1n}`;
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)) throw new Error('Invalid build version');
  const tickets = [];
  const bullets = [];
  const escape = text => text.replaceAll('\\', '\\\\').replaceAll('"', '\\"').replaceAll('\t', '\\t');
  for (const file of removed) {
    const match = /^changelog\.d\/([A-Z][A-Z0-9]*-\d+)-([^/]+)\.md$/.exec(file);
    if (!match) throw new Error(`Bad fragment filename: ${file}`);
    const lines = fragmentLines(file, files[file]);
    if (!tickets.includes(match[1])) tickets.push(match[1]);
    bullets.push(...lines.filter(productChange).map(text => `        .{ .text = "${escape(text)}", .ticket = "${match[1]}" },`));
  }
  if (!bullets.length) throw new Error('No unpublished product changes; wait for the next useful update');
  if (!changelogStart.test(files[changelogPath] || '')) throw new Error('Missing CHANGELOG in src/changelog.zig');
  const result = { ...files };
  result[mainPath] = files[mainPath].replace(versionLine, line => line.replace(/"[^"]+"/, `"${version}"`));
  result[changelogPath] = files[changelogPath].replace(changelogStart, start => `${start}\n    .{ .ver = "${version}", .items = &.{\n${bullets.join('\n')}\n    } },`);
  for (const file of removed) delete result[file];
  return { version, tickets, files: result, removed };
}

export function classifyTrees(base, head, changed) {
  const kept = Object.keys(base).filter(file => file.startsWith('changelog.d/') && file in head);
  const folded = { ...base };
  for (const file of kept) delete folded[file];
  let stamp;
  try { stamp = stampTree(folded); } catch {}
  if (stamp) for (const file of kept) stamp.files[file] = base[file];
  if (stamp && changed.length && changed.every(file => file === mainPath || file === changelogPath || stamp.removed.includes(file) && !(file in head))) {
    const paths = new Set([...Object.keys(stamp.files), ...Object.keys(head)]);
    if ([...paths].every(file => stamp.files[file] === head[file])) return 'STAMP';
  }
  const lines = (tree, file, pattern) => (tree[file] || '').split(/\r?\n/).filter(line => pattern.test(line)).map(line => line.replace(/^(pub const APP_VERSION = )build_config\.app_version orelse ("\d+\.\d+\.\d+";)$/, '$1$2'));
  if (JSON.stringify(lines(base, mainPath, /\bAPP_VERSION\s*=/)) !== JSON.stringify(lines(head, mainPath, /\bAPP_VERSION\s*=/)) ||
      JSON.stringify(lines(base, changelogPath, /\.ver = /)) !== JSON.stringify(lines(head, changelogPath, /\.ver = /))) return 'HAND_EDIT';
  return 'CODE';
}

const fragmentFiles = tree => Object.fromEntries(Object.entries(tree).filter(([file]) => file.startsWith('changelog.d/') && file !== 'changelog.d/README.md'));
const addedSince = (current, previous) => Object.fromEntries(Object.entries(current).filter(([file, text]) => previous[file] !== text));

export function buildChangelog(files, tag, history = [], commits = []) {
  if (!/^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(tag)) throw new Error('Invalid release tag');
  const version = tag.slice(1);
  if (files[changelogPath]?.match(/\.ver\s*=\s*"([^"]+)"/)?.[1] === version) {
    if (compareTags(tag, 'v0.28.1027') >= 0) throw new Error(`Release ${tag} already exists in source; generate from the previous release`);
    return files[changelogPath];
  }
  let result = { ...files };
  const stamp = (stepTag, fragments, stepCommits, sha, current) => {
    if (compareTags(stepTag, 'v0.28.1027') < 0) {
      const tree = { ...result };
      for (const file of Object.keys(fragmentFiles(tree))) delete tree[file];
      Object.assign(tree, fragments);
      for (const [file, text] of Object.entries(fragments)) fragmentLines(file, text);
      if (Object.values(fragments).some(text => text.split(/\r?\n/).some(productChange))) {
        result[changelogPath] = stampTree(tree, stepTag.slice(1)).files[changelogPath];
      } else if (current) {
        throw new Error('No unpublished product changes; wait for the next useful update');
      } else {
        if (!changelogStart.test(result[changelogPath] || '')) throw new Error('Missing CHANGELOG in src/changelog.zig');
        result[changelogPath] = result[changelogPath].replace(changelogStart, start => `${start}\n    .{ .ver = "${stepTag.slice(1)}", .items = &.{\n    } },`);
      }
      return;
    }
    const reviewed = reviewedReleases[stepTag];
    if (reviewed && sha !== reviewed[0]) throw new Error(`${stepTag}: reviewed release commit does not match ${sha || 'missing commit'}`);
    const notes = new Map(Object.entries(reviewed?.[1] || {}));
    if (!reviewed) {
      for (const commit of stepCommits || []) {
        const match = /^(IGSH-\d+)(?::|\s)/.exec(commit.subject);
        if (!match) throw new Error(`${stepTag}: commit ${commit.sha} lacks a leading ticket ID; add reviewed attribution before releasing`);
        if (!notes.has(match[1])) notes.set(match[1], checksOnly);
      }
    }
    const fragmentNotes = new Map();
    for (const [file, text] of Object.entries(fragments).sort(([a], [b]) => a.localeCompare(b))) {
      const ticket = /^changelog\.d\/([A-Z][A-Z0-9]*-\d+)-/.exec(file)?.[1];
      const lines = fragmentLines(file, text).filter(productChange);
      if (lines.length) fragmentNotes.set(ticket, [...(fragmentNotes.get(ticket) || []), ...lines]);
    }
    for (const [ticket, lines] of fragmentNotes) {
      if (!reviewed && !notes.has(ticket)) throw new Error(`${stepTag}: ${ticket} fragment has no matching commit ticket`);
      notes.set(ticket, lines.join(' '));
    }
    if (!notes.size) throw new Error(`${stepTag}: no ticketed release lines; add a ticketed commit before releasing`);
    for (const [ticket, text] of notes) {
      if (!/^IGSH-\d+$/.test(ticket) || !plainChange(text) || !(productChange(text) || text === checksOnly)) {
        throw new Error(`${stepTag}: invalid ticketed line for ${ticket}`);
      }
    }
    if (!changelogStart.test(result[changelogPath] || '')) throw new Error('Missing CHANGELOG in src/changelog.zig');
    const escape = text => text.replaceAll('\\', '\\\\').replaceAll('"', '\\"').replaceAll('\t', '\\t');
    const bullets = [...notes].map(([ticket, text]) => `        .{ .text = "${escape(text)}", .ticket = "${ticket}" },`);
    result[changelogPath] = result[changelogPath].replace(changelogStart, start => `${start}\n    .{ .ver = "${stepTag.slice(1)}", .items = &.{\n${bullets.join('\n')}\n    } },`);
  };
  let previous = history.length ? fragmentFiles(history[0].files) : {};
  for (const step of history.slice(1)) {
    const added = addedSince(fragmentFiles(step.files), previous);
    stamp(step.tag, added, step.commits, step.sha, false);
    previous = fragmentFiles(step.files);
  }
  stamp(tag, addedSince(fragmentFiles(files), previous), commits, undefined, true);
  return result[changelogPath];
}

function git(args, cwd, timeout) {
  return execFileSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, timeout });
}

function readTree(rev, cwd, remaining, sourcePaths = []) {
  const read = args => git(args, cwd, remaining?.());
  const names = read(['ls-tree', '-r', '--name-only', '-z', rev, '--', mainPath, changelogPath, 'changelog.d/', ...sourcePaths]).split('\0').filter(Boolean);
  return Object.fromEntries(names.map(file => [file, read(['show', `${rev}:${file}`])]));
}

const tagParts = name => /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.exec(name)?.slice(1).map(BigInt) ?? null;
export function compareTags(a, b) {
  const x = tagParts(a);
  const y = tagParts(b);
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] < y[i] ? -1 : 1;
  return 0;
}

export function previousReleaseTag(tags, tag) {
  return tags.filter(name => tagParts(name) && compareTags(name, tag) < 0).sort(compareTags).at(-1) ?? null;
}

function releaseHistory(tag, changelog, publishedTags) {
  const older = publishedTags.filter(name => tagParts(name) && compareTags(name, tag) < 0).sort(compareTags);
  if (!older.length) {
    if (git(['rev-parse', '--is-shallow-repository']).trim() === 'true') throw new Error('Shallow checkout has no release tags; fetch tags before building');
    return [];
  }
  const stamped = changelog.match(/\.ver\s*=\s*"(\d+\.\d+\.\d+)"/)?.[1];
  const base = stamped ? older.findLastIndex(name => compareTags(name, `v${stamped}`) <= 0) : -1;
  const history = older.slice(Math.max(base, 0));
  for (const name of history) {
    const ref = `refs/tags/${name}`;
    try {
      execFileSync('git', ['rev-parse', '--verify', '--quiet', `${ref}^{commit}`], { stdio: 'ignore' });
    } catch {
      git(['fetch', '--no-tags', 'origin', `${ref}:${ref}`]);
    }
  }
  return history.map((name, i) => {
    const ref = `refs/tags/${name}`;
    return {
      tag: name,
      files: readTree(ref),
      sha: git(['rev-parse', `${ref}^{commit}`]).trim(),
      commits: i && compareTags(name, 'v0.28.1027') >= 0 && !reviewedReleases[name] ? releaseCommits(`refs/tags/${history[i - 1]}`, ref) : [],
    };
  });
}

function releaseCommits(previous, head) {
  git(['merge-base', '--is-ancestor', previous, head]);
  return git(['log', '--reverse', '--format=%H%x09%s', `${previous}..${head}`])
    .split(/\r?\n/).filter(Boolean).map(line => {
      const tab = line.indexOf('\t');
      return { sha: line.slice(0, tab), subject: line.slice(tab + 1) };
    });
}

function check(baseRev, headRev) {
  const changed = git(['diff', '--name-only', '-z', baseRev, headRev]).split('\0').filter(Boolean);
  const sourcePaths = changed.filter(file => file.startsWith('src/') && file.endsWith('.zig'));
  const base = readTree(baseRev, undefined, undefined, sourcePaths);
  const head = readTree(headRev, undefined, undefined, sourcePaths);
  const kind = classifyTrees(base, head, changed);
  if (kind === 'HAND_EDIT') {
    const diff = git(['diff', '--no-ext-diff', '--unified=0', baseRev, headRev, '--', mainPath, changelogPath]);
    console.error(diff.split(/\r?\n/).filter(line => /^[+-](?![+-])/.test(line) && (/\bAPP_VERSION\s*=/.test(line) || /\.ver = /.test(line))).join('\n'));
    console.error(fixHint);
  }
  if (kind === 'CODE' && autoRevertBranch()) console.error('Changelog note not required on an auto-revert/ branch');
  else if (kind === 'CODE') checkFragments(base, head, changed);
  if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT, `kind=${kind}\n`);
  console.log(kind);
  return kind === 'HAND_EDIT' ? 1 : 0;
}

async function release() {
  const root = git(['rev-parse', '--show-toplevel']).trim();
  const deadline = Date.now() + 45 * 60 * 1000;
  const remaining = () => {
    const left = deadline - Date.now();
    if (left <= 0) throw new Error('Release timed out after 45 minutes');
    return left;
  };
  const runGit = (args, cwd = root) => git(args, cwd, remaining());
  const gh = args => execFileSync('gh', args, { cwd: root, encoding: 'utf8', timeout: remaining(), stdio: ['ignore', 'pipe', 'inherit'] }).trim();
  const pollMs = Number(process.env.VERSION_STAMP_POLL_MS || 20000);
  const wait = async () => {
    await new Promise(resolve => setTimeout(resolve, Math.min(pollMs, remaining())));
    remaining();
  };
  while (true) {
    runGit(['fetch', 'origin', 'master']);
    const open = JSON.parse(gh(['pr', 'list', '--state', 'open', '--search', 'head:release/', '--json', 'number,url']));
    if (open.length) {
      const pr = open[0];
      console.log(pr.url);
      let greenNames;
      let mergeRequested = false;
      while (true) {
        const { state, statusCheckRollup } = JSON.parse(gh(['pr', 'view', String(pr.number), '--json', 'state,statusCheckRollup']));
        if (state === 'MERGED') break;
        if (state === 'CLOSED') throw new Error(`Release PR closed without merging: ${pr.url}`);
        const failed = (statusCheckRollup || []).filter(c => ['FAILURE', 'CANCELLED', 'TIMED_OUT', 'ERROR'].includes(c.conclusion || c.state));
        if (failed.length) throw new Error(`Release PR check failed (${failed.map(c => c.name || c.context).join(', ')}): ${pr.url}`);
        const checks = statusCheckRollup || [];
        const names = state === 'OPEN' && checks.length && checks.every(c => c.__typename === 'CheckRun' ? c.status === 'COMPLETED' && ['SUCCESS', 'SKIPPED', 'NEUTRAL'].includes(c.conclusion) : c.__typename === 'StatusContext' && c.state === 'SUCCESS') ? JSON.stringify([...new Set(checks.map(c => c.name || c.context))].sort()) : null;
        if (names && names === greenNames && !mergeRequested) {
          gh(['pr', 'merge', String(pr.number), '--squash', '--delete-branch']);
          mergeRequested = true;
        }
        greenNames = names;
        await wait();
      }
      continue;
    }
    const base = readTree('origin/master', root, remaining);
    const stamp = stampTree(base);
    if (!stamp) {
      console.log(`NOTHING_TO_STAMP ${base[mainPath].match(versionLine)?.slice(1).join('.')}`);
      return;
    }
    const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'version-stamp-'));
    const tree = path.join(temp, 'tree');
    const branch = `release/${stamp.version}`;
    try {
      runGit(['worktree', 'add', '--detach', tree, 'origin/master']);
      for (const file of [mainPath, changelogPath]) fs.writeFileSync(path.join(tree, file), stamp.files[file]);
      runGit(['rm', '--', ...stamp.removed], tree);
      runGit(['add', '--', mainPath, changelogPath], tree);
      const title = `v${stamp.version}: release ${stamp.tickets.join(', ')}`;
      runGit(['commit', '-m', title], tree);
      try {
        runGit(['push', 'origin', `HEAD:refs/heads/${branch}`], tree);
      } catch (error) {
        const left = /non-fast-forward|fetch first|already exists|stale info/.test(String(error.stderr)) ? runGit(['ls-remote', '--heads', 'origin', `refs/heads/${branch}`]).trim().split(/\s+/)[0] : '';
        if (!left) throw error;
        const attempts = JSON.parse(gh(['pr', 'list', '--state', 'all', '--head', branch, '--json', 'number,state,headRefOid']));
        if (!attempts.length || !attempts.every(attempt => attempt.state === 'CLOSED') || !attempts.some(attempt => attempt.headRefOid === left)) {
          await wait();
          continue;
        }
        try {
          runGit(['push', `--force-with-lease=refs/heads/${branch}:${left}`, 'origin', `HEAD:refs/heads/${branch}`], tree);
        } catch (forceError) {
          if (!/stale info|rejected|non-fast-forward|fetch first/.test(String(forceError.stderr))) throw forceError;
          await wait();
          continue;
        }
      }
      const url = gh(['pr', 'create', '--base', 'master', '--head', branch, '--title', title, '--body', 'Stamped by tools/version-stamp.mjs from changelog.d.']);
      console.log(url);
      try {
        execFileSync('gh', ['pr', 'merge', url, '--squash', '--auto', '--delete-branch'], { cwd: root, encoding: 'utf8', timeout: remaining(), stdio: ['ignore', 'pipe', 'pipe'] });
      } catch (error) {
        if (error.stderr) process.stderr.write(error.stderr);
        if (!/auto.?merge is not allowed|not allowed for this repository|enablePullRequestAutoMerge/i.test(`${error.stderr || ''}\n${error.message}`)) throw error;
      }
    } finally {
      try {
        if (fs.existsSync(tree)) git(['worktree', 'remove', '--force', tree], root, 60000);
      } finally {
        fs.rmSync(temp, { recursive: true, force: true });
      }
    }
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [command, base, head, ...extra] = process.argv.slice(2);
    if (command === 'check' && base && head && !extra.length) process.exitCode = check(base, head);
    else if (command === 'pending' && base && head && !extra.length) {
      const changes = addedSince(fragmentFiles(readTree(head)), fragmentFiles(readTree(base)));
      const lines = Object.entries(changes).flatMap(([file, text]) => fragmentLines(file, text).filter(productChange));
      if (lines.length) console.log(lines.join('\n'));
    } else if (command === 'build' && base && !extra.length) {
      const publishedTags = head ? JSON.parse(head) : execFileSync('gh', ['api', '--paginate', 'repos/{owner}/{repo}/releases', '--jq', '.[] | select(.draft == false and .prerelease == false) | .tag_name'], { encoding: 'utf8' }).trim().split(/\r?\n/).filter(Boolean);
      if (!Array.isArray(publishedTags) || publishedTags.some(tag => typeof tag !== 'string')) throw new Error('Expected an array of published release tags');
      const files = readTree('HEAD');
      const history = releaseHistory(base, files[changelogPath], publishedTags);
      if (!history.length && compareTags(base, 'v0.28.1027') >= 0) throw new Error('No published baseline; fetch published release tags before building');
      const commits = history.length ? releaseCommits(`refs/tags/${history.at(-1).tag}`, 'HEAD') : [];
      fs.writeFileSync(changelogPath, buildChangelog(files, base, history, commits));
    } else if (command === 'release' && !base) await release();
    else throw new Error('Usage: node tools/version-stamp.mjs check <base> <head> | pending <published-tag> <head> | build <tag> [published-tags-json] | release');
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
