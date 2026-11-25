import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const HISTORY_MAX_ENTRIES = 30;
export const MANIFEST_MAX_BYTES = 40 * 1024;
export const MANIFEST_GUARD_BYTES = 48 * 1024;

export function serializeManifest(manifest) {
  return `${JSON.stringify(manifest)}\n`;
}

export function manifestBytes(manifest) {
  return Buffer.byteLength(serializeManifest(manifest), 'utf8');
}

function entries(previous) {
  const history = previous && Array.isArray(previous.history) ? previous.history : [];
  return history.filter(entry => entry && typeof entry === 'object' && !Array.isArray(entry));
}

export function buildManifest({
  manifest,
  entry,
  previous,
  maxBytes = MANIFEST_MAX_BYTES,
  maxEntries = HISTORY_MAX_ENTRIES,
}) {
  const history = [];
  const codes = new Set();
  for (const candidate of [entry, ...entries(previous)]) {
    if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)) continue;
    if (Number.isInteger(candidate.versionCode)) {
      if (codes.has(candidate.versionCode)) continue;
      codes.add(candidate.versionCode);
    }
    history.push(candidate);
    if (history.length >= maxEntries) break;
  }
  while (history.length > 0 && manifestBytes({ ...manifest, history }) > maxBytes) history.pop();
  const built = { ...manifest, history };
  const bytes = manifestBytes(built);
  if (bytes > maxBytes) {
    throw new Error(`MANIFEST_TOO_LARGE: ${bytes} bytes with an empty history, budget ${maxBytes} bytes`);
  }
  return built;
}

export function assertManifestSize(raw, maxBytes = MANIFEST_GUARD_BYTES) {
  const bytes = Buffer.byteLength(raw, 'utf8');
  if (bytes > maxBytes) throw new Error(`MANIFEST_TOO_LARGE: ${bytes} bytes over the ${maxBytes} byte release guard`);
  return bytes;
}

function readJson(file) {
  const raw = readFileSync(file, 'utf8').trim();
  if (!raw) return {};
  const value = JSON.parse(raw);
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${file} must hold a JSON object`);
  return value;
}

function options(args) {
  const parsed = {};
  for (let index = 0; index < args.length; index += 2) {
    const flag = args[index];
    if (!flag.startsWith('--') || args[index + 1] === undefined) throw new Error(`Unexpected argument: ${flag}`);
    parsed[flag.slice(2)] = args[index + 1];
  }
  return parsed;
}

function run(args) {
  const flags = options(args);
  if (flags.check) {
    console.log(`MANIFEST_SIZE_OK: ${assertManifestSize(readFileSync(flags.check, 'utf8'))} bytes`);
    return;
  }
  for (const flag of ['manifest', 'entry', 'previous', 'out']) {
    if (!flags[flag]) throw new Error(`Missing --${flag}`);
  }
  const built = buildManifest({
    manifest: readJson(flags.manifest),
    entry: readJson(flags.entry),
    previous: readJson(flags.previous),
  });
  writeFileSync(flags.out, serializeManifest(built));
  console.log(`MANIFEST_OK: ${manifestBytes(built)} bytes with ${built.history.length} history entries`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    run(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
