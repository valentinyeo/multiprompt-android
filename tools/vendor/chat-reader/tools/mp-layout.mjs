#!/usr/bin/env node
import { spawnSync, spawn as childSpawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, realpathSync, statSync, lstatSync, openSync, closeSync, writeFileSync, renameSync, unlinkSync, constants } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const DEFAULT_URL = 'https://account.multiprompt.dev';
const USAGE = `usage: mp-layout <command> [options]

commands:
  list [--json]                                    show workspaces and their sessions
  create-section <name>                            add a workspace
  move --to <workspace> [--create] <session>...    move sessions into a workspace in one change
  move --to <workspace> --match <glob> [--host N]  move every session whose name matches
  spawn --name NAME --folder DIR --workspace WORKSPACE [--group GROUP] [--mode visible|headless] [--task TEXT] [--host N]  create or reveal a live chat
  open <session> [--workspace <name>] [--host N] [--title <text>] [--no-tmux]

environment:
  MULTIPROMPT_KEY   admin or workspace key (never printed)
  MULTIPROMPT_URL   account service address (default ${DEFAULT_URL})
  MULTIPROMPT_HOST  default host slot for open and spawn (default 0)
  MULTIPROMPT_READER  path to the installed host reader
`;

class Failure extends Error {}

const REFUSALS = {
  scope_denied: 'refused: this key may change only its own workspace',
  key_scope: 'refused: keys work only on the layout',
  device_required: 'the key was not accepted',
  workspace_not_found: 'the workspace for this key does not exist',
  invalid_layout: 'the service rejected the layout (workspace or session limits?)',
  invalid_tab: 'the service rejected a session entry',
  invalid_workspace: 'the service rejected the workspace name'
};

function parse(argv, command) {
  const flags = { _: [] };
  const withValue = new Set(['to', 'match', 'host', 'workspace', 'title', 'name', 'folder', 'task', 'group', 'mode']);
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) { flags._.push(arg); continue; }
    const name = arg.slice(2);
    if (command === 'spawn' && (!['name', 'folder', 'workspace', 'task', 'host', 'group', 'mode'].includes(name) || Object.hasOwn(flags, name))) throw new Failure('invalid spawn option');
    if (withValue.has(name)) {
      if (i + 1 >= argv.length || (command === 'spawn' && argv[i + 1].startsWith('--'))) throw new Failure(`--${name} needs a value`);
      flags[name] = argv[++i];
    } else if (['json', 'create', 'no-tmux'].includes(name)) flags[name] = true;
    else throw new Failure(`unknown option --${name}`);
  }
  return flags;
}

const tabId = tab => `${tab.tmux_host}:${tab.tmux_session.toLowerCase()}`;
const globToRegExp = glob => new RegExp('^' + glob.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.') + '$', 'i');
const hostSlot = text => {
  const n = Number(text);
  if (!Number.isInteger(n) || n < 0 || n > 2) throw new Failure('host must be 0, 1 or 2');
  return n;
};

async function call(ctx, method, body) {
  let response;
  try {
    response = await ctx.fetch(ctx.url + '/v1/layout', {
      method,
      headers: { Authorization: 'Bearer ' + ctx.key, 'Content-Type': 'application/json', ...(ctx.command === 'spawn' ? { 'X-Layout-Features': 'groups' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
      ...(ctx.command === 'spawn' ? { signal: AbortSignal.timeout(8000) } : {})
    });
  } catch (e) {
    throw new Failure('cannot reach the account service: ' + (e.cause?.code || e.message));
  }
  let data = null;
  try { data = await response.json(); } catch { data = null; }
  return { status: response.status, data, groupsSupported: (response.headers?.get('X-Layout-Features') || '').split(',').some(feature => feature.trim() === 'groups') };
}

function refusal(result) {
  const code = result.data?.error;
  return new Failure(REFUSALS[code] || `service answered ${result.status}${code ? ' ' + code : ''}`);
}

async function edit(ctx, mutate) {
  for (let attempt = 0; attempt < 5; attempt++) {
    const read = await call(ctx, 'GET');
    if (read.status !== 200) throw refusal(read);
    const next = mutate(structuredClone(read.data.layout));
    if (!next) return { changed: false, revision: read.data.revision };
    const write = await call(ctx, 'PUT', { base_revision: read.data.revision, layout: next });
    if (write.status === 200) return { changed: true, revision: write.data.revision };
    if (write.status !== 409) throw refusal(write);
  }
  throw new Failure('the layout kept changing; try again');
}

async function list(ctx, flags) {
  const read = await call(ctx, 'GET');
  if (read.status !== 200) throw refusal(read);
  const { revision, layout } = read.data;
  if (flags.json) { ctx.out(JSON.stringify({ revision, workspaces: layout.workspaces.map(w => ({ name: w.name, sessions: layout.tabs.filter(t => t.workspace === w.name).map(t => ({ session: t.tmux_session, name: t.name, host: t.tmux_host })) })) })); return; }
  ctx.out(`revision ${revision}`);
  for (const w of layout.workspaces) {
    const tabs = layout.tabs.filter(t => t.workspace === w.name);
    ctx.out(`${w.name} (${tabs.length})`);
    for (const t of tabs) ctx.out(`  ${t.tmux_session}  host ${t.tmux_host}  ${t.name}`);
  }
}

async function createSection(ctx, flags) {
  const name = flags._[0];
  if (!name || flags._.length !== 1) throw new Failure('give one section name');
  const result = await edit(ctx, layout => {
    if (layout.workspaces.some(w => w.name === name)) return null;
    layout.workspaces.push({ name });
    return layout;
  });
  ctx.out(result.changed ? `created section ${name} (revision ${result.revision})` : `section ${name} already exists (revision ${result.revision})`);
}

async function move(ctx, flags) {
  const target = flags.to;
  if (!target) throw new Failure('move needs --to <workspace>');
  const names = flags._.map(s => s.toLowerCase());
  if (!names.length && !flags.match) throw new Failure('give session names or --match <glob>');
  if (names.length && flags.match) throw new Failure('use session names or --match, not both');
  const pattern = flags.match ? globToRegExp(flags.match) : null;
  const host = flags.host === undefined ? null : hostSlot(flags.host);
  let moved = 0;
  const result = await edit(ctx, layout => {
    if (!layout.workspaces.some(w => w.name === target)) {
      if (!flags.create) throw new Failure(`workspace ${target} not found or not visible to this key (use --create to add it)`);
      layout.workspaces.push({ name: target });
    }
    const picked = layout.tabs.filter(t => (pattern ? pattern.test(t.tmux_session) : names.includes(t.tmux_session.toLowerCase())) && (host === null || t.tmux_host === host));
    const missing = names.filter(n => !picked.some(t => t.tmux_session.toLowerCase() === n));
    if (missing.length) throw new Failure(`no such session: ${missing.join(', ')}`);
    if (!picked.length) throw new Failure('no session matched');
    const ids = new Set(picked.map(tabId));
    for (const t of picked) t.workspace = target;
    layout.tabs = [...layout.tabs.filter(t => !ids.has(tabId(t))), ...picked];
    moved = picked.length;
    return layout;
  });
  ctx.out(`moved ${moved} session${moved === 1 ? '' : 's'} to ${target} (revision ${result.revision})`);
}

function ensureTmux(ctx, session) {
  if (ctx.tmux(['has-session', '-t', '=' + session]).status === 0) return false;
  const created = ctx.tmux(['new-session', '-d', '-s', session]);
  if (created.status !== 0) throw new Failure('could not start the tmux session');
  return true;
}

async function open(ctx, flags) {
  const session = flags._[0];
  if (!session || flags._.length !== 1) throw new Failure('give one session name');
  if (!/^[A-Za-z0-9_-]{1,63}$/.test(session)) throw new Failure('session names use letters, digits, - and _ (63 at most)');
  const host = hostSlot(flags.host ?? ctx.env.MULTIPROMPT_HOST ?? '0');
  const title = flags.title || session;
  let started = false;
  if (!flags['no-tmux']) started = ensureTmux(ctx, session);
  const result = await edit(ctx, layout => {
    const workspace = flags.workspace || (layout.workspaces.length === 1 ? layout.workspaces[0].name : null);
    if (!workspace) throw new Failure('give --workspace <name>');
    if (!layout.workspaces.some(w => w.name === workspace)) throw new Failure(`workspace ${workspace} not found or not visible to this key`);
    const existing = layout.tabs.find(t => tabId(t) === `${host}:${session.toLowerCase()}`);
    if (existing) {
      if (existing.workspace !== workspace) throw new Failure(`session ${session} is already open in ${existing.workspace}`);
      return null;
    }
    layout.tabs.push({ kind: 'tmux', name: title.slice(0, 64), tmux_session: session, tmux_host: host, workspace });
    return layout;
  });
  ctx.out(`${result.changed ? 'opened' : 'already open'} ${session}${started ? ' (tmux session started)' : ''} (revision ${result.revision})`);
}

const hash = value => createHash('sha256').update(value).digest('hex');

function spawnLock(path, env) {
  return new Promise((resolve, reject) => {
    const child = childSpawn('flock', ['-w', '8', path, 'sh', '-c', 'printf "locked\\n"; cat >/dev/null'], {
      env, stdio: ['pipe', 'pipe', 'ignore']
    });
    let ready = false;
    child.stdin.on('error', () => {});
    child.stdout.once('data', () => { ready = true; resolve(() => { if (!child.stdin.destroyed) child.stdin.end(); }); });
    child.once('error', () => reject(new Failure('could not acquire setup lock')));
    child.once('exit', () => { if (!ready) reject(new Failure('could not acquire setup lock')); });
  });
}

async function spawnSession(ctx, flags) {
  if (flags._.length || !flags.name || !flags.folder || !flags.workspace) throw new Failure('spawn needs name, folder and workspace');
  const name = flags.name.normalize('NFC').trim();
  const group = flags.group === undefined ? null : flags.group.normalize('NFC').trim();
  const mode = flags.mode ?? 'visible';
  if (!['visible', 'headless'].includes(mode)) throw new Failure('mode must be visible or headless');
  if (group !== null && (!group || group.length > 64 || /[\p{Cc}\p{Cs}]/u.test(group))) throw new Failure('invalid group');
  if (!name || name.length > 64 || /[\p{Cc}\p{Cs}]/u.test(name)) throw new Failure('invalid session name');
  if (!flags.workspace.trim() || Buffer.byteLength(flags.workspace, 'utf8') > 31 || /[\p{Cc}\p{Cs}]/u.test(flags.workspace)) throw new Failure('invalid workspace');
  if (flags.task !== undefined && (!flags.task.trim() || flags.task.includes('\0') || Buffer.byteLength(flags.task, 'utf8') > 65536)) throw new Failure('invalid task');
  const hostText = flags.host ?? ctx.env.MULTIPROMPT_HOST ?? '0';
  if (!/^[0-2]$/.test(hostText)) throw new Failure('host must be 0, 1 or 2');
  const host = hostSlot(hostText);
  let folder;
  try { folder = ctx.resolveFolder(flags.folder); }
  catch { throw new Failure('invalid folder'); }
  if (!/^[A-Za-z0-9_./~+-]{1,220}$/.test(folder)) throw new Failure('invalid folder');
  const digest = hash(JSON.stringify([host, name.toLowerCase()]));
  const bytes = Buffer.from(digest.slice(0, 32), 'hex');
  bytes[6] = (bytes[6] & 15) | 128;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = bytes.toString('hex');
  const sid = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  const session = 'live-' + hex;
  const token = 'mp-spawn-' + hex;
  const check = layout => {
    if (!layout.workspaces.some(w => w.name === flags.workspace)) throw new Failure('workspace not found or not visible to this key');
    const existing = layout.tabs.find(t => t.tmux_host === host && t.tmux_session?.toLowerCase() === session);
    const sameName = layout.tabs.find(t => t.tmux_host === host && typeof t.name === 'string' && t.name.normalize('NFC').trim().toLowerCase() === name.toLowerCase() && t !== existing);
    if (sameName || (existing && (existing.kind !== 'tmux' || existing.workspace !== flags.workspace || typeof existing.name !== 'string' || existing.name.normalize('NFC').trim().toLowerCase() !== name.toLowerCase()))) throw new Failure('session collision');
    if (existing && mode === 'headless') throw new Failure('session is already visible');
    if (existing && (existing.group || null) !== group) throw new Failure('group collision');
    return existing;
  };
  const initial = await call(ctx, 'GET');
  if (initial.status !== 200) throw new Failure(initial.status === 401 || initial.status === 403 ? 'key not authorized' : 'account read failed');
  if (group !== null && !initial.groupsSupported) throw new Failure('account service does not support groups');
  check(initial.data.layout);
  const home = ctx.env.HOME;
  if (!home) throw new Failure('HOME is required');
  const root = join(home, '.local/state/multiprompt/agent-host');
  const stateKey = hash('claude:' + sid).slice(0, 32);
  if (process.platform === 'win32' && !ctx.mockFolder) throw new Failure('spawn requires a Unix host');
  const unsafeMode = st => process.platform !== 'win32' && ((st.mode & 0o077) || (process.getuid && st.uid !== process.getuid()));
  const lockPath = join(root, stateKey + '.spawn.lock');
  try {
    mkdirSync(root, { recursive: true, mode: 0o700 });
    const dir = lstatSync(root);
    if (!dir.isDirectory() || dir.isSymbolicLink() || unsafeMode(dir)) throw 0;
    const fd = openSync(lockPath, constants.O_CREAT | constants.O_RDWR | (constants.O_NOFOLLOW || 0), 0o600);
    closeSync(fd);
    const lock = lstatSync(lockPath);
    if (!lock.isFile() || lock.isSymbolicLink() || unsafeMode(lock)) throw 0;
  } catch { throw new Failure('cannot access host state'); }
  const release = await ctx.lock(lockPath, ctx.childEnv);
  try {
    const readState = suffix => {
      try { const file = join(root, stateKey + suffix); const st = lstatSync(file); if (!st.isFile() || st.isSymbolicLink() || unsafeMode(st)) throw 0; const value = JSON.parse(readFileSync(file, 'utf8')); if (!value || typeof value !== 'object' || Array.isArray(value)) throw 0; return value; }
      catch (e) { if (e.code === 'ENOENT') return null; throw new Failure('invalid host state'); }
    };
    const config = readState('.config.json');
    const owner = readState('.owner.json');
    if (owner?.owner === 'terminal') throw new Failure('session is owned by terminal');
    if (config && (config.cwd !== folder || config.tmux !== session || config.spawn_workspace !== flags.workspace || config.spawn_name !== name.toLowerCase() || (config.spawn_group ?? null) !== group)) throw new Failure('host configuration collision');
    if (mode === 'headless' && config && config.spawn_mode !== 'headless') throw new Failure('session is already visible');
    const current = await call(ctx, 'GET');
    if (current.status !== 200) throw new Failure(current.status === 401 || current.status === 403 ? 'key not authorized' : 'account read failed');
    check(current.data.layout);
    const parked = ctx.tmux(['has-session', '-t', '=' + session]).status === 0;
    if (parked && !config) throw new Failure('terminal collision');
    if (parked && mode === 'headless') throw new Failure('parking session already exists');
    if (!config) {
      const target = join(root, stateKey + '.config.json');
      const temporary = target + '.' + process.pid + '.' + Math.random().toString(16).slice(2);
      try {
        writeFileSync(temporary, JSON.stringify({ enabled: false, cwd: folder, tmux: session, spawn_workspace: flags.workspace, spawn_name: name.toLowerCase(), spawn_group: group, spawn_mode: mode }), { mode: 0o600, flag: 'wx' });
        renameSync(temporary, target);
      } catch { try { unlinkSync(temporary); } catch {} throw new Failure('cannot write host state'); }
    }
    if (mode === 'visible') {
      if (!parked) {
        const created = ctx.tmux(['new-session', '-d', '-s', session, '-c', folder, 'sleep', 'infinity', ';', 'set-option', '-t', '=' + session + ':', '@mp_spawn_sid', sid, ';', 'set-option', '-t', '=' + session + ':', '@mp_host_sid', sid]);
        if (created.status !== 0 && ctx.tmux(['has-session', '-t', '=' + session]).status !== 0) throw new Failure('could not start parking session');
      }
      const pane = ctx.tmux(['display-message', '-p', '-t', '=' + session + ':', '#{pane_current_command}|#{pane_current_path}|#{@mp_spawn_sid}']);
      if (pane.status !== 0 || String(pane.stdout || '').trim() !== `sleep|${folder}|${sid}`) throw new Failure('terminal collision');
    }
    const runtime = (args, input) => {
      let result;
      try { result = ctx.runtime(args, input); } catch { throw new Failure('host operation failed'); }
      if (result.status !== 0) throw new Failure('host operation failed');
      try { return result.stdout.trim().split('\n').filter(Boolean).map(line => JSON.parse(line)); } catch { throw new Failure('invalid host response'); }
    };
    if (!config || config.enabled !== true) {
      const [enabled] = runtime(['host', 'enable', 'claude', sid, '--tmux', session, '--cwd', folder, '--permission-mode', 'bypassPermissions']);
      if (enabled?.status !== 'enabled') throw new Failure('host enable failed');
    }
    const result = mode === 'headless' ? { changed: false, revision: current.data.revision } : await edit(ctx, layout => {
      if (check(layout)) return null;
      layout.tabs.push({ kind: 'tmux', name, tmux_session: session, tmux_host: host, workspace: flags.workspace, manual: true, ...(group === null ? {} : { group }) });
      return layout;
    });
    const [started] = runtime(['host', 'start', 'claude', sid]);
    if (!['started', 'already'].includes(started?.status)) throw new Failure('host start failed');
    if (flags.task !== undefined) {
      const base = { message_protocol: 1, provider: 'claude', sid, tmux: session };
      const requests = [{ ...base, id: 1, op: 'message_submit', message_id: token, text: flags.task }, { ...base, id: 2, op: 'message_dispatch' }];
      const replies = runtime(['--serve'], requests.map(x => JSON.stringify(x)).join('\n') + '\n');
      const submitted = replies.find(r => r.id === 1)?.result?.message;
      const dispatched = replies.find(r => r.id === 2)?.result;
      if (submitted?.id !== token || submitted.text !== flags.task || !dispatched || submitted.state === 'failed' || dispatched.message?.state === 'failed') throw new Failure('task submission failed');
    }
    ctx.out(JSON.stringify({ ok: true, sid, session, host, name, workspace: flags.workspace, group, mode, first_task_id: flags.task === undefined ? null : token, revision: result.revision, changed: result.changed }));
  } finally { release(); }
}

export async function run(argv, overrides = {}) {
  const env = overrides.env || process.env;
  const out = overrides.out || (line => process.stdout.write(line + '\n'));
  const err = overrides.err || (line => process.stderr.write(line + '\n'));
  const [command, ...rest] = argv;
  if (!command || command === '--help' || command === 'help') { out(USAGE.trimEnd()); return command ? 0 : 2; }
  try {
    const key = env.MULTIPROMPT_KEY;
    if (!key) throw new Failure('set MULTIPROMPT_KEY');
    const ctx = {
      key, env, out, command,
      url: (env.MULTIPROMPT_URL || DEFAULT_URL).replace(/\/+$/, ''),
      fetch: overrides.fetch || globalThis.fetch,
      childEnv: { HOME: env.HOME, PATH: env.PATH || '/usr/bin:/bin', LANG: 'C.UTF-8' },
      lock: overrides.lock || spawnLock,
      mockFolder: Boolean(overrides.resolveFolder),
      resolveFolder: overrides.resolveFolder || (value => { const folder = realpathSync(value); if (!statSync(folder).isDirectory()) throw new Error(); return folder; }),
      runtime: overrides.runtime || ((args, input) => spawnSync('python3', [env.MULTIPROMPT_READER || join(env.HOME, '.multiprompt-runtime/reader.py'), ...args], { input, encoding: 'utf8', timeout: 30000, env: { HOME: env.HOME, PATH: env.PATH || '/usr/bin:/bin', LANG: 'C.UTF-8' }, maxBuffer: 1048576 })),
      tmux: overrides.tmux || (args => spawnSync('tmux', args, { ...(command === 'spawn' ? { timeout: 5000 } : {}), stdio: command === 'spawn' && args[0] === 'display-message' ? ['ignore', 'pipe', 'ignore'] : 'ignore', encoding: 'utf8', ...(command === 'spawn' ? { env: { HOME: env.HOME, PATH: env.PATH || '/usr/bin:/bin', LANG: 'C.UTF-8' } } : {}) }))
    };
    const flags = parse(rest, command);
    const commands = { list, 'create-section': createSection, move, open, spawn: spawnSession };
    if (!commands[command]) throw new Failure(`unknown command ${command}`);
    await commands[command](ctx, flags);
    return 0;
  } catch (e) {
    if (command === 'spawn') { err(JSON.stringify({ ok: false, error: e instanceof Failure && !e.message.startsWith('cannot reach') && !e.message.startsWith('service answered') ? e.message : 'spawn failed' })); return 1; }
    if (!(e instanceof Failure)) throw e;
    err('mp-layout: ' + e.message);
    return 1;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) process.exitCode = await run(process.argv.slice(2));
