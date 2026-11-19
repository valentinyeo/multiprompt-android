'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const registry = require('../../../.claude/skills/verify-multiprompt-android/features/registry.json');
const baseline = require('../../../.claude/skills/verify-multiprompt-android/features/coverage-baseline.json');
const REGISTRY_PATH = '.claude/skills/verify-multiprompt-android/features';
const debugJourneys = Object.entries(registry.journeys).filter(([, journey]) => journey.runner.kind === 'debug').map(([name]) => name);
const prJourneys = Object.entries(registry.journeys).filter(([, journey]) => ['debug', 'signed'].includes(journey.runner.kind)).map(([name]) => name);

function changedPaths(diff, files = []) {
  return new Set([
    ...files.flatMap(file => [file.filename, file.previous_filename]).filter(Boolean),
    ...[...String(diff).matchAll(/^diff --git a\/(\S+) b\/(\S+)$/gm)].flatMap(match => match.slice(1)),
  ]);
}

function changedRecipes(diff, files = []) {
  const paths = changedPaths(diff, files);
  return prJourneys.filter(name => paths.has(`${REGISTRY_PATH}/${name}`));
}

function pathOutcomes(diff, files = []) {
  const paths = [...changedPaths(diff, files)];
  return Object.entries(registry.outcomes)
    .filter(([, outcome]) => (outcome.paths ?? []).some(pattern => paths.some(file => new RegExp(pattern).test(file))))
    .map(([id]) => id);
}

function recipeOutcomes(diff, files = []) {
  return changedRecipes(diff, files).flatMap(name => registry.journeys[name].outcomes);
}

function changedJourneys(diff, files = []) {
  const paths = changedPaths(diff, files);
  return prJourneys.filter(name => paths.has(`${REGISTRY_PATH}/${name}`)
    || paths.has('.claude/skills/verify-multiprompt-android/scripts/drive.sh')
    || (registry.journeys[name].runner.scripts ?? []).some(script => paths.has(script)));
}

function changedProduct(diff) {
  return String(diff).split(/^diff --git /m).slice(1).filter(block => {
    const header = block.split('\n')[0];
    return /(?:a|b)\/app\/(?:src\/(?:main|debug|release|m3)\/|build\.gradle\.kts\b)/.test(header);
  }).map(block => {
    const lines = block.split('\n');
    return [lines[0], ...lines.filter(line => /^[+-](?![+-])/.test(line))].join('\n');
  });
}

function changedOutcomes(diff, files = []) {
  const changed = changedProduct(diff).join('\n');
  return [...new Set([
    ...Object.entries(registry.outcomes).filter(([, outcome]) => outcome.match.some(pattern => new RegExp(pattern).test(changed))).map(([id]) => id),
    ...pathOutcomes(diff, files),
    ...recipeOutcomes(diff, files),
  ])];
}

function planChange(diff, reported, files = []) {
  if (!Array.isArray(reported) || reported.some(id => typeof id !== 'string' || !registry.outcomes[id])) {
    throw new Error(`FEATURE_UNMAPPED: unregistered changed_outcomes ${JSON.stringify(reported)}; report exact outcome IDs from features/registry.json, or register the missing outcome and its journey.`);
  }
  const outcomes = [...new Set([...changedOutcomes(diff, files), ...reported])];
  return { product: changedProduct(diff).length > 0, outcomes };
}

function featureErrors({ plan, coverage = [], directories = {}, conclusions = {}, sha }) {
  const errors = [];
  const accepted = [];
  if (plan.product && !plan.outcomes.length) {
    errors.push('FEATURE_UNMAPPED: product code changed but no outcome was identified. Add its outcome and journey to features/registry.json; smoke is not coverage.');
  }
  for (const id of plan.outcomes) {
    const outcome = registry.outcomes[id];
    const fail = reason => errors.push(`FEATURE_NOT_EXERCISED: ${id} changed but ${reason}. Add its action/result journey to features/registry.json; native-skills smoke is not coverage.`);
    const claim = coverage.find(item => item?.outcome === id);
    if (!outcome?.journey || !prJourneys.includes(outcome.journey)) {
      fail('no automatic head journey covers it');
      continue;
    }
    const directory = directories[outcome.journey];
    if (!claim || claim.journey !== outcome.journey || !directory || conclusions[outcome.journey] !== 'success') {
      fail(`no matching successful ${outcome.journey} evidence was retained`);
      continue;
    }
    let sourceSha = '';
    try { sourceSha = fs.readFileSync(path.join(directory, 'app-source-sha.txt'), 'utf8').trim(); } catch {}
    if (!/^[0-9a-f]{40}$/.test(sha) || sourceSha !== sha) {
      fail('the retained APK source is not the exact PR head SHA');
      continue;
    }
    const readRef = (ref, kind) => {
      if (!ref || typeof ref.file !== 'string' || typeof ref.quote !== 'string' || ref.quote.trim().length < 8) return false;
      if (!/^[\w./-]+$/.test(ref.file) || ref.file.split('/').includes('..') || path.isAbsolute(ref.file)) return false;
      if (kind === 'action' && ref.file !== 'driver-transcript.txt') return false;
      if (kind === 'result' && (!/\.(xml|json)$/.test(ref.file) || /(?:side-result|coverage)\.json$/.test(ref.file))) return false;
      if (kind === 'sideEffect' && (!/\.(txt|jsonl?)$/.test(ref.file) || /(?:transcript|revision|source-sha|driver|package|activity|version|result|coverage)/i.test(ref.file))) return false;
      try {
        const file = path.join(directory, ref.file);
        if (!fs.realpathSync(file).startsWith(`${fs.realpathSync(directory)}${path.sep}`)) return false;
        return fs.readFileSync(file, 'utf8').includes(ref.quote);
      } catch { return false; }
    };
    if (!readRef(claim.action, 'action') || !readRef(claim.result, 'result') || (outcome.sideEffect && !readRef(claim.sideEffect, 'sideEffect'))) {
      fail('feature-specific action, resulting state or independent side-effect citations are missing from the artifact');
      continue;
    }
    accepted.push({ ...claim, sha });
  }
  return { errors, accepted };
}

function catalog() {
  const journeys = Object.entries(registry.journeys).map(([name, journey]) => `- ${name}: ${journey.name}: ${journey.description} [${journey.runner.kind}; outcomes ${journey.outcomes.join(', ')}]`);
  const outcomes = Object.entries(registry.outcomes).map(([id, outcome]) => `- ${id}: ${outcome.description} Journey: ${outcome.journey ?? 'MISSING; must fail if changed'}. Independent side effect required: ${outcome.sideEffect}.${outcome.citations ? ` Citation hints (verify exact quotes against retained head artifacts before citing): ${JSON.stringify(outcome.citations)}.` : ''}`);
  return `${journeys.join('\n')}\n\nOutcome catalog:\n${outcomes.join('\n')}`;
}

function validateRegistry(value = registry, oldGaps = baseline) {
  const errors = [];
  const gaps = [];
  if (value.journeys[value.smoke]?.runner.kind !== 'debug') errors.push('FEATURE_REGISTRY_INVALID: smoke must name a debug journey in features/registry.json.');
  for (const [id, outcome] of Object.entries(value.outcomes)) {
    if (!/^[a-z0-9.-]+$/.test(id) || typeof outcome.description !== 'string' || typeof outcome.sideEffect !== 'boolean' || !Array.isArray(outcome.match)) errors.push(`Invalid outcome ${id}`);
    for (const pattern of outcome.match ?? []) {
      try { new RegExp(pattern); } catch { errors.push(`Invalid source matcher for ${id}`); }
    }
    if (outcome.paths && (!Array.isArray(outcome.paths) || outcome.paths.some(pattern => typeof pattern !== 'string'))) errors.push(`Invalid path matcher for ${id}`);
    for (const pattern of outcome.paths ?? []) {
      try { new RegExp(pattern); } catch { errors.push(`Invalid path matcher for ${id}`); }
    }
    const journey = value.journeys[outcome.journey];
    if (outcome.journey && !journey?.outcomes.includes(id)) errors.push(`Outcome ${id} must be owned by its journey`);
    if (!journey || !['debug', 'signed'].includes(journey.runner.kind)) gaps.push(id);
  }
  for (const [name, journey] of Object.entries(value.journeys)) {
    if (!/^[\w-]+\.md$/.test(name) || !['debug', 'signed', 'dedicated', 'manual'].includes(journey.runner.kind)) errors.push(`Invalid journey ${name}`);
    if (!journey.outcomes.length || journey.outcomes.some(id => value.outcomes[id]?.journey !== name)) errors.push(`Journey ${name} must own registered outcomes`);
    if (journey.runner.scripts && (!Array.isArray(journey.runner.scripts) || journey.runner.scripts.some(script => typeof script !== 'string' || !/^[\w./-]+\.(?:sh|py)$/.test(script) || script.split('/').includes('..')))) errors.push(`Journey ${name} scripts must list repository-relative driver paths`);
    const evidence = journey.evidence ?? {};
    if (typeof evidence !== 'object' || Array.isArray(evidence) || Object.keys(evidence).length > 8 ||
        Object.entries(evidence).some(([file, limit]) => !/^[\w-]+\.(?:txt|json|xml)$/.test(file) || !Number.isInteger(limit) || limit < 1 || limit > 6_000) ||
        Object.values(evidence).reduce((sum, limit) => sum + limit, 0) > 12_000) errors.push(`Journey ${name} evidence must map at most eight artifact text filenames to character limits of 1-6000, totaling at most 12000`);
  }
  for (const id of gaps) if (!oldGaps[id]) errors.push(`FEATURE_COVERAGE_RATCHET: new uncovered outcome ${id}. Add its executable journey; do not expand coverage-baseline.json.`);
  for (const [id, gap] of Object.entries(oldGaps)) {
    if (!value.outcomes[id] || !gap.cleanupTicket) errors.push(`Baseline ${id} needs an existing outcome and cleanup ticket`);
  }
  return errors;
}

function ratchetErrors(previous, current = baseline) {
  return Object.keys(current).filter(id => !Object.hasOwn(previous, id)).map(id => `FEATURE_COVERAGE_RATCHET: ${id} is not an initial gap. Add its executable journey instead of a baseline exemption.`);
}

function renderIndex() {
  const rows = Object.entries(registry.journeys).map(([name, journey]) => {
    const runner = journey.runner;
    return `- [${journey.name}](${name}): ${journey.description}\n  - Outcomes: ${journey.outcomes.map(id => `\`${id}\``).join(', ')}. Runner: \`${runner.workflow}\` (${runner.kind}).${Object.keys(runner.inputs).length ? ` Inputs: \`${JSON.stringify(runner.inputs)}\`.` : ''}${runner.prerequisites.length ? ` Prerequisites: ${runner.prerequisites.join(' ')}` : ''}${journey.evidence ? ` Priority evidence: ${Object.entries(journey.evidence).map(([file, limit]) => `\`${file}\` (${limit} characters)`).join(', ')}.` : ''}`;
  });
  const gaps = Object.entries(baseline).map(([id, gap]) => `| \`${id}\` | ${gap.sourcePr ? `[#${gap.sourcePr}](https://github.com/valentinyeo/multiprompt-android/pull/${gap.sourcePr})` : gap.sourceTicket ?? gap.sourceRecipe} | ${gap.cleanupTicket} |`);
  return `# multiprompt Android verification map\n\nGenerated from [registry.json](registry.json). Run \`node .github/scripts/pstack-verifier/coverage.cjs generate\` after editing the registry. CI rejects drift.\n\nRead [SKILL.md](../SKILL.md) before dispatching. Use a fresh GitHub-hosted emulator per journey. Never build or run Android locally. Debug SSH fixtures do not prove account OTP, live providers or signed-release behavior.\n\nA product change must name every changed outcome. The second-AI verifier requires the matching successful head journey, exact APK source SHA, action and result citations into the retained artifact, and independent side effects where required. Smoke alone cannot prove a product change. A new behavior needs a new outcome; extending an old recipe does not imply coverage.\n\n## Features\n\n${rows.join('\n')}\n\n## Initial coverage gaps\n\nThese IDs are an inventory, not exemptions for future changes. Untouched gaps do not block this migration. Changing one requires its own journey proof. New uncovered IDs fail the ratchet even if another gap was removed. Signed routes run parent/head candidates with their registered inputs. Dedicated and manual routes remain outside the PR picker.\n\n| Outcome | Existing case | Cleanup ticket |\n|---|---|---|\n${gaps.join('\n')}\n`;
}

function renderWorkflow(source) {
  const options = Object.entries(registry.journeys).filter(([, journey]) => ['debug', 'signed'].includes(journey.runner.kind) && journey.runner.driver).map(([name]) => `          - ${name}`).join('\n');
  return source.replace(/^      VERIFY_FEATURE: .*$/m, `      VERIFY_FEATURE: \${{ inputs.driver_feature || inputs.feature || '${registry.smoke}' }}`).replace(/(      feature:\n        description:[^\n]+\n        required: true\n        type: choice\n        options:\n)(?:          - [^\n]+\n)+/, `$1${options}\n`);
}

module.exports = { registry, baseline, REGISTRY_PATH, debugJourneys, prJourneys, changedRecipes, changedJourneys, changedProduct, pathOutcomes, recipeOutcomes, changedOutcomes, planChange, featureErrors, catalog, validateRegistry, ratchetErrors, renderIndex, renderWorkflow };

if (require.main === module) {
  if (process.argv[2] === 'journey') {
    if (!registry.journeys[process.argv[3]]?.runner.driver) throw new Error(`FEATURE_NOT_DISPATCHABLE: ${process.argv[3]}; use its registered workflow and prerequisites.`);
    process.exit(0);
  }
  const root = path.resolve(__dirname, '../../..');
  const targets = {
    [`${REGISTRY_PATH}/README.md`]: renderIndex(),
    '.github/workflows/verify.yml': renderWorkflow(fs.readFileSync(path.join(root, '.github/workflows/verify.yml'), 'utf8')),
  };
  const errors = validateRegistry();
  const files = fs.readdirSync(path.join(root, REGISTRY_PATH)).filter(name => name.endsWith('.md') && name !== 'README.md');
  for (const file of files) if (!registry.journeys[file]) errors.push(`FEATURE_UNREGISTERED: add ${file} to features/registry.json.`);
  for (const file of Object.keys(registry.journeys)) if (!files.includes(file)) errors.push(`Missing recipe ${file}`);
  for (const [file, expected] of Object.entries(targets)) {
    if (process.argv[2] === 'generate') fs.writeFileSync(path.join(root, file), expected);
    else if (fs.readFileSync(path.join(root, file), 'utf8') !== expected) errors.push(`FEATURE_REGISTRY_DRIFT: run node .github/scripts/pstack-verifier/coverage.cjs generate to update ${file}.`);
  }
  if (process.env.BASE_SHA) {
    const listing = execFileSync('git', ['ls-tree', '--name-only', process.env.BASE_SHA, `${REGISTRY_PATH}/coverage-baseline.json`], { encoding: 'utf8' }).trim();
    if (listing) errors.push(...ratchetErrors(JSON.parse(execFileSync('git', ['show', `${process.env.BASE_SHA}:${REGISTRY_PATH}/coverage-baseline.json`], { encoding: 'utf8' }))));
  }
  if (errors.length) {
    console.error(errors.join('\n'));
    process.exitCode = 1;
  } else console.log(`FEATURE_REGISTRY_OK: ${Object.keys(registry.journeys).length} journeys; ${Object.keys(baseline).length} initial gaps.`);
}
