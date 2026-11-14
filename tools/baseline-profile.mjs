#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const profileFile = path.join(root, 'app/src/main/baseline-prof.txt');
export const sourceRoot = path.join(root, 'app/src/main/java');
export const profileAssets = ['assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm'];

const methodRule = /^([HSP]{1,3})L([\w$/*]+?);?->(\*\*|[\w$<>]+)\((\*\*|[\w$/;[]*)\)(\*\*|[\w$/;[]+)$/;
const classRule = /^L([\w$/]+);$/;

function kotlinFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).filter(entry => entry.isFile() && entry.name.endsWith('.kt')).map(entry => path.join(directory, entry.name));
}

function declares(directory, name) {
  const pattern = new RegExp(`\\b(?:class|object|interface)\\s+${name}\\b`);
  return kotlinFiles(directory).some(file => pattern.test(readFileSync(file, 'utf8')));
}

function isDirectory(directory) {
  return existsSync(directory) && statSync(directory).isDirectory();
}

function classProblem(pattern, sources) {
  if (pattern.includes('*')) {
    const packagePath = pattern.slice(0, -3);
    if (!pattern.endsWith('/**') || packagePath.includes('*')) return 'wildcard must be one trailing /** after a package';
    return isDirectory(path.join(sources, packagePath)) ? null : `package ${packagePath} has no source directory`;
  }
  const slash = pattern.lastIndexOf('/');
  const packagePath = pattern.slice(0, slash);
  if (!isDirectory(path.join(sources, packagePath))) return `package ${packagePath} has no source directory`;
  const name = pattern.slice(slash + 1).split('$')[0];
  return declares(path.join(sources, packagePath), name) ? null : `class ${pattern} is not declared in ${packagePath}`;
}

export function profileProblems(text, sources = sourceRoot) {
  const problems = [];
  const lines = text.split('\n');
  if (lines.at(-1) === '') lines.pop();
  if (lines.length === 0) problems.push('profile has no rules');
  const seen = new Set();
  lines.forEach((line, index) => {
    const where = `line ${index + 1}`;
    if (seen.has(line)) problems.push(`${where}: duplicate rule`);
    seen.add(line);
    const method = methodRule.exec(line);
    const klass = classRule.exec(line);
    if (!method && !klass) {
      problems.push(`${where}: not a baseline profile rule: ${line}`);
      return;
    }
    const pattern = method ? method[2] : klass[1];
    if (!pattern.startsWith('dev/multiprompt/companion/')) problems.push(`${where}: rule is outside dev/multiprompt/companion`);
    const problem = classProblem(pattern, sources);
    if (problem) problems.push(`${where}: ${problem}`);
  });
  return problems;
}

export function apkProblems(apk) {
  const problems = [];
  const listing = execFileSync('unzip', ['-l', apk], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const sizes = new Map();
  for (const line of listing.split('\n')) {
    const match = /^\s*(\d+)\s+\S+\s+\S+\s+(\S.*)$/.exec(line);
    if (match) sizes.set(match[2].trim(), Number(match[1]));
  }
  for (const asset of profileAssets) {
    if (!sizes.has(asset)) problems.push(`${apk} has no ${asset}`);
    else if (sizes.get(asset) === 0) problems.push(`${apk} has an empty ${asset}`);
  }
  if (sizes.get(profileAssets[0]) > 0) {
    const head = execFileSync('unzip', ['-p', apk, profileAssets[0]], { maxBuffer: 64 * 1024 * 1024 }).subarray(0, 4);
    if (!head.equals(Buffer.from([0x70, 0x72, 0x6f, 0x00]))) problems.push(`${apk} ${profileAssets[0]} is not a compiled ART profile`);
  }
  return problems;
}

function main(args) {
  const [mode, ...files] = args;
  let problems;
  if (mode === 'source' && files.length === 0) problems = profileProblems(readFileSync(profileFile, 'utf8'));
  else if (mode === 'apk' && files.length > 0) problems = files.flatMap(apkProblems);
  else throw new Error('Usage: node tools/baseline-profile.mjs source | apk <apk> [apk...]');
  if (problems.length) {
    console.error(problems.join('\n'));
    process.exitCode = 1;
    return;
  }
  console.log(mode === 'source' ? 'baseline profile rules are valid' : `baseline profile compiled into ${files.join(', ')}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main(process.argv.slice(2));
