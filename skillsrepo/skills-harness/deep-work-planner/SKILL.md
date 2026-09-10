---
name: deep-work-planner
description: This skill should be used when the user wants to front-load an exhaustive interview about ONE project so a long autonomous agent session can run on it without interruption, freeing the user to context-switch to another project. Single-project scope — run once per project, per session. Predicts session length, rewards long+high-quality runs, punishes busywork. Tool-agnostic. Triggers on "plan this project", "batch interview me", "deep work planner", "/deep-work", "plan a session".
---

# Deep Work Planner

## What counts as deep work (normative)

A **deep work session** is a long, uninterrupted autonomous agent run on ONE project that produces a verifiable artefact advancing a real goal. Three properties:

1. **Uninterrupted** — the agent does not page the human mid-run. Every interruption reduces quality.
2. **Long** — ≥60 min minimum; reward grows with length conditional on quality.
3. **Productive** — shippable artefact (commit, PR, deploy, verified output), not churn, reformatting, or planning theater.

Shallow = short, self-interrupted, or long-but-no-artefact. This skill penalizes shallow so it cannot set personal bests.

Adapted from Cal Newport.

## Purpose and scope

**One invocation = one session on one project.** Run the skill from inside the project directory (or pass a path). The skill interviews the user exhaustively about *this* project, produces *one* plan file, prints the handoff command. The user then context-switches to another project and invokes the skill there.

This is NOT a day-planner. It is a per-project session planner. Run it 1–2 times per project, per day.

## Flow

```
user enters project dir  →  skill runs exhaustive interview  →  writes one plan file  →  prints handoff command  →  user starts long session and moves on
```

## Mode detection

- **Plan a session** (default) — "plan this project", "batch interview me", "deep work planner", "/deep-work". See `references/intake.md`.
- **Review a session** — "review this session", "log this run", "score my last session". See `references/review.md`.

## Project detection

1. Use `cwd` if it looks like a project root (has `.git`, `package.json`, `CLAUDE.md`, or similar).
2. Otherwise ask: "Which project are we planning? Give me the absolute path."
3. Read `CLAUDE.md` if present — treat its "Verification" and "Tech Stack" sections as authoritative constraints.

## Task source (optional, not required)

If the project declares a task source (Hypertask project id, Linear team, GitHub issues repo, a local markdown file), pull in-progress work from it. If not, ask the user what to work on. Schema and supported sources in `references/config.md`.

**Never hardcode Hypertask, Linear, or any vendor.** Every task lookup goes through a configured source or manual paste.

## Plan file output

Write one plan to: `<project_path>/.deep-work/YYYY-MM-DD-HHMM.md`

Use template in `assets/plan-template.md`. Every plan must contain:

- Planned duration (with comparison to history median for this project)
- One-sentence goal
- Context (files, recent commits, relevant tickets)
- Tasks with per-step verification
- Success criteria (checkable)
- Exact verification shell commands
- "If blocked" fallbacks so the session never idles and pages the human
- Handoff command

If the project has no `.deep-work/` dir, create it and add to `.gitignore` (ask first).

## Session length prediction

- Ask user's estimate in minutes for this session.
- Compare to history median for this project (from `.deep-work/history.jsonl`).
- If off by >50%, flag and propose the history-based number.
- Record `planned_min` in the plan file frontmatter.

## Reward mechanic (length × quality)

Reward = `duration_min × quality_multiplier`. Quality (0–1.5) is computed, never self-rated. Busywork caps quality at 0.3 and zeroes score if <0.5. Full formula and anti-busywork detectors in `references/quality-scoring.md`.

Key rules:
- Quality <0.5 → score = 0, regardless of length.
- No artefact after 60+ min → quality ≤0.3.
- Verification not run → verification score = 0 (not N/A).
- Trivial-commit inflation, churn, planning theater → auto-detected, penalized.

Personal bests require BOTH long duration AND quality ≥0.7.

## Next logical steps (proactive, before asking)

Before interviewing, surface candidate work items for THIS project:

1. `git log --since=7.days --oneline` in project path
2. Grep recent diffs for `TODO:` / `FIXME:` / `XXX:`
3. Read project `CLAUDE.md`
4. Pull in-progress tickets from task source
5. Scan open PRs (`gh pr list` if GitHub)

Propose 3–5 candidate items sized for ~2h of agent time. User picks.

## History (per-project)

Each project has its own history in `<project_path>/.deep-work/history.jsonl`. Schemas in `references/data-schemas.md`.

Streaks and personal bests are computed per-project, not across projects. "Longest session on vetsak" is meaningful; "longest session across all projects" is not (compares unlike things).

## Model delegation (to lengthen sessions and lower cost)

Running every task on Opus burns the weekly limit fast and shortens how long sessions can survive. Each plan MUST pre-assign a runtime per task. Available runtimes: `pi` (MiniMax-M2.7-highspeed), Codex rescue, Haiku/Sonnet subagents, main Opus session. Default rubric:

- Lookup / exploration → Haiku subagent
- Implementation ≥20 lines across multiple files → `pi`
- Single-file impl, tests, refactors → Sonnet subagent
- Architecture, security, auth, billing, schema → main Opus
- Stuck / failed twice → Codex rescue

Tag each task in the plan with its runtime (e.g. `[pi]`, `[sonnet-agent]`, `[main-opus]`). Full rubric, fallback chain, budget targets, and review-gate tiers (who reviews whom — pi is never trusted solo) in `references/delegation.md`.

## Integration (handoff, not auto-execute)

Skill writes the plan and prints ONE exact command the user pastes to start the session. Options:

- `claude "Execute <plan-path>"` — single long session
- `warm-claude exec --slot N --task <goal> --cwd <path>` — warm slot
- `/orchestrate <project-id> <slots> <cwd>` — task board drain

User decides when to start. Never auto-execute.

## Rules

- Single-project scope. Never rotate across projects in one invocation.
- Never ask a question answerable by reading a file or running a command.
- Never write a plan missing verification commands or "If blocked".
- Never rewrite past history entries. Append only.
- Use imperative, third-person language in plan files.

## First run in a project

If `<project_path>/.deep-work/` does not exist, bootstrap it once. See `references/bootstrap.md`.
