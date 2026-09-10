---
name: codebase-health
description: Analyze codebase quality through an AI-first lens. Scores 6 dimensions (token efficiency, AI agent friendliness + multi-agent readiness, TDD readiness, readability, dev speed, stability), renders a terminal scorecard with environment advisory, writes a detailed report, and offers to fix top issues.
---

# Codebase Health Analysis

You are running a codebase health analysis. Follow these steps exactly.

## Step 1: Run Static Analysis

Run the CLI to get structured metrics:

```bash
codebase-health analyze --json --env
```

Parse the JSON output. It contains:
- `overall` — weighted score 0-100
- `grade` — letter grade (A through F)
- `dimensions` — per-dimension scores, metrics, and findings
- `sample_candidates` — top 10 files for AI review
- `top_issues` — severity-sorted findings across all dimensions
- `environment` — unscored advisory on Claude Code environment (skills, MCP servers, hooks). Populated by `--env` flag; `null` if `~/.claude/` doesn't exist.

## Step 2: AI Sampling (Structured 3-Lens Review)

From `sample_candidates`, pick ~5 files biased toward variety (different directories). Read each file and run three review lenses in parallel (use subagents or sequential passes):

### Lens 1: Code Reuse & Token Efficiency

For each sampled file, check:
- **Duplicated logic** — Is there inline logic that duplicates an existing utility elsewhere in the codebase? Hand-rolled string manipulation, manual path handling, ad-hoc type guards?
- **Reinvented wheels** — New functions that replicate functionality already available in the project or standard library?
- **Genuine focus** — Is the file actually focused on one concern, or just short because logic leaked elsewhere?
- **Import depth** — Does the file pull in half the codebase, or is the dependency graph tight?

→ Feeds **Token Efficiency** modifier (-15 to +15)

### Lens 2: Code Quality & Readability

For each sampled file, check:
- **Naming quality** — Are names meaningful (`transformUserInputToAPIPayload`) or generic (`processData`, `handleStuff`)?
- **Unnecessary comments** — Comments explaining WHAT (well-named code already does that) vs non-obvious WHY (hidden constraints, workarounds)?
- **Stringly-typed code** — Raw strings where constants, enums, or branded types already exist in the codebase?
- **Copy-paste with variation** — Near-duplicate blocks that should be unified?
- **Self-containment** — Can you understand this file without reading 5 others?
- **Abstraction level** — Over-engineered or under-abstracted?
- **Error messages** — Are they actionable or generic?

→ Feeds **Human Readability** modifier (-15 to +15)
→ Feeds **AI Agent Friendliness** modifier (-15 to +15): Could an AI agent navigate this file without getting lost?

### Lens 3: Efficiency & Stability

For each sampled file, check:
- **Unnecessary work** — Redundant computations, repeated file reads, duplicate API calls, N+1 patterns?
- **Missed concurrency** — Independent operations run sequentially that could be parallel?
- **Hot-path bloat** — Blocking work on startup or per-request/per-render paths?
- **Error handling quality** — Thoughtful recovery vs empty try/catch swallowing errors?
- **Resource cleanup** — Unbounded data structures, missing cleanup, event listener leaks?
- **Existence checks** — Pre-checking file/resource existence before operating (TOCTOU anti-pattern) instead of operate-then-handle-error?
- **Test meaningfulness** — Do tests verify real behavior or just assert `true`?

→ Feeds **Stability/Robustness** modifier (-15 to +15)
→ Feeds **Development Speed** modifier (-15 to +15)
→ Feeds **TDD Readiness** modifier (-15 to +15)
→ Feeds **Safety** modifier (-15 to +15)

### Compute Adjusted Scores

For each dimension, compute: `final = clamp(static_score + ai_modifier, 0, 100)`

Recompute the overall score using weights:
- Token Efficiency: 20%
- AI Agent Friendliness: 20%
- TDD Readiness: 15%
- Safety: 15%
- Human Readability: 10%
- Development Speed: 10%
- Stability/Robustness: 10%

## Step 2.5: Karpathy Sanity Filter

Before anything from `top_issues` reaches the scorecard, report, or fix offer, run every issue through the `karpathy-guidelines` skill as a sanity check. Static analyzers over-flag — this step drops churn the user would never accept.

Invoke `karpathy-guidelines` via the Skill tool, then for each issue in `top_issues` decide **keep** or **drop** against these tests:

- **Simplicity First** — Would acting on this issue add code, abstraction, or ceremony without fixing a real problem? (e.g. "wrap in try/catch", "extract 3-line helper", "add JSDoc to internal function") → **drop**.
- **Goal-Driven** — Is there a verifiable success criterion for the fix, or is it taste-level churn? (e.g. "rename for clarity" with no concrete reader confusion) → **drop** unless the finding cites specific evidence.
- **Surgical Changes** — Does the fix touch only the flagged concern, or does it cascade into rewrites of unrelated code? If cascading → **drop** or rescope.
- **Think Before Coding** — Does the finding rest on an assumption that could be wrong in this codebase (framework convention, intentional pattern, external constraint)? If yes and unverified → **drop**.

Keep an issue when it names a concrete defect (bug, security risk, real duplication, broken test, actual unused dep) with a clear verifiable fix.

Produce a filtered list: `kept_issues` (what survives) and `dropped_issues` (what was filtered, with one-line reason each). All downstream steps use `kept_issues`. Include `dropped_issues` as an appendix in the report so the user can override.

## Step 3: Present Terminal Scorecard

Print a scorecard with:
- Overall score and letter grade
- Per-dimension score with a 20-char progress bar and trend arrow
- Top 10 issues

Format:
```
  CODEBASE HEALTH: [score]/100  ([grade])
  [progress bar]

  Token Efficiency      [score]/100  [bar]  [trend]
  AI Agent Friendliness [score]/100  [bar]  [trend]
  TDD Readiness         [score]/100  [bar]  [trend]
  Safety                [score]/100  [bar]  [trend]
  Human Readability     [score]/100  [bar]  [trend]
  Development Speed     [score]/100  [bar]  [trend]
  Stability/Robustness  [score]/100  [bar]  [trend]

  Top Issues (Karpathy-filtered):
  1. [issue]
  ...up to 10 kept issues
  (N issues dropped by filter — see report appendix)

  Agent Environment:  (only if environment is not null)
    N skills, N MCP servers, hooks active/no hooks
    ! Missing skill: /name (if any referenced but not installed)
```

## Step 4: Write Report

Write a detailed markdown report to `.codebase-health/report.md` containing:
- Full scorecard
- Per-dimension breakdown with metrics, findings, and file paths
- AI sampling observations (what you noticed in the sampled files)
- Prioritized fix list with estimated impact (from `kept_issues` only)
- **Appendix: Dropped by Karpathy filter** — each `dropped_issues` entry with its one-line reason, so the user can override

## Step 5: Offer Fixes

Ask the user:

> "Want me to fix the top issues?"

If they accept, address each issue one at a time:
1. Make the change
2. Explain what you did and why
3. Move to the next issue

Only make changes the user agreed to. Each fix is a normal code edit — no automated scripts.

---

## Autonomous Improvement Loop

When running fixes autonomously (without human review per change), follow this protocol strictly. These safeguards prevent score gaming, regressions, and destructive changes.

### Rollback Protocol (Safeguard 4)

For each fix iteration:

1. **Checkpoint before fixing:** `git add -A && git commit -m "checkpoint: before fix [issue-name]"`
2. **Apply the fix**
3. **Build gate:** Run `npm run build` (or equivalent). If it fails → `git revert HEAD --no-edit` and skip this fix
4. **Test gate:** Run `npm test`. If any test fails → `git revert HEAD --no-edit` and skip this fix
5. **Score guard:** Run `codebase-health analyze --guard`. If it fails → `git revert HEAD --no-edit` and skip this fix
6. **Commit the fix:** `git add -A && git commit -m "fix: [description]"`
7. Only proceed to the next fix if all three gates pass

If two consecutive fixes get reverted, stop the loop and report to the user.

### Anti-Gaming Rules (Safeguard 6)

These rules are mandatory. Violating them makes the score meaningless.

- **DO NOT** delete functional code, tests, or meaningful comments to reduce line count
- **DO NOT** create stub test files with no meaningful assertions to inflate test ratio
- **DO NOT** split files below 20 lines purely to improve token efficiency metrics
- **DO NOT** add try/catch blocks that silently swallow errors to appear more stable
- **DO NOT** add comments that merely restate code to boost any metric
- **DO NOT** suppress errors, skip tests, or add `|| true` to hide failures
- **DO NOT** remove features or functionality to simplify the codebase
- **DO NOT** add empty or boilerplate files (README, LICENSE, configs) that serve no real purpose
- Score improvements MUST come from genuine quality improvements
- If a fix cannot improve the score legitimately, skip it and explain why

### Diff Size Limits (Safeguards 7 & 8)

Per iteration (single fix attempt):
- **Max 200 lines changed** — if a fix requires more, break it into smaller steps
- **Max 30% LOC deletion** — deleting large portions of the codebase is suspicious
- **Max 50 new files** — bulk file creation is suspicious

The `--guard` flag checks these automatically. If violated, the guard fails and the fix should be reverted.

### Validation Script

Run all gates in one command:

```bash
npm run build && npm test && codebase-health analyze --guard
```
