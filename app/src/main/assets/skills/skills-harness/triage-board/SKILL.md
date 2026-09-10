---
name: triage-board
description: Systematically triage a Hypertask board's bug/ticket backlog — fan out parallel code-verified reviewers, post a verdict comment on every ticket, tag the unclear ones `needs-info`, and hand Valentin a grouped morning list. Use when Valentin says "/triage-board", "review all the bugs", "triage the board", "go through the backlog and evaluate staleness", or points at a board/section and asks which tickets are still real.
---

# Triage Board

Reads a board's ticket backlog, verifies each ticket against the **actual current code** (not just the ticket text), and leaves the board fully annotated so Valentin can act in minutes. Triage only — you comment, tag, and summarize. You do NOT write feature code or auto-archive.

Default target: **Hypertask Product board (project 15), the `Bugs` section.** Board 1534 no longer exists — bugs live on board 15. Override if Valentin names a different board/section.

## Ground rules (from this repo's CLAUDE.md / global rules)
- Reads via `ht` REST when possible; **all writes via the `hypertask` CLI** (never Prisma/DB).
- Every ticket mention is a full clickable URL: `https://app.hypertask.ai/detail/project-15/{num}`.
- **Pace all writes** (~0.5s sleep between CLI calls). The VPS IP trips Vercel's bot challenge on bursts — canary-post 3 first, confirm success, then continue.
- Do NOT public-host the summary if it contains security-ticket internals, file paths, or infra mechanics (it usually does). Deliver the list in chat instead.
- Triage-only: comment + tag + list. Do NOT archive, move, or reassign unless Valentin says so. The board is the record; he decides what to close.

## Steps

### 1. Scope the target
```bash
hypertask project list --json                       # confirm access
hypertask section list --project 15 --json          # section ids
hypertask tasks list --project 15 --section "Bugs" --limit 100 --json > bugs_section.json
```
The list JSON already includes `description` — you only need to fetch comments separately.

### 2. Fetch comments (only tickets with commentCount>0), paced
```bash
# loop tickets, sleep 0.25 between; hypertask comment list <TICKET> --json > comments/<TICKET>.json
```

### 3. Build clean digests
One text file per ticket: title, section, assignees, created/updated, HTML-stripped description, HTML-stripped comments (author + date, truncated ~600 chars). Keeps reviewer context tight (~1-1.5k chars/ticket).

### 4. Fan out parallel reviewers (the core)
Split tickets into batches of ~9-10. Spawn one **Sonnet** `general-purpose` subagent per batch, `run_in_background: true`, all in one message. Each agent:
- Reads its batch digests from the scratchpad path you give it.
- **Verifies against the live repo** (`/home/valentin/projects/hypertasks`) — grep/read code, check `git log`/`gh pr list` for merged fixes. Be a skeptic: many tickets are months old and already fixed.
- Does NOT modify files, touch git, or run the hypertask CLI.
- Returns ONLY a JSON array, one object per ticket:
  ```
  ticket, classification, confidence(high/med/low), evidence[] (cite file:line),
  rootCause, fixSketch, intensity (Model/effort, "Plan: X | Exec: Y" if differ),
  duplicateOf, needsValentin(bool), question
  ```
  `classification` ∈ `valid-open | likely-fixed | stale-close | duplicate | needs-info | invalid`.

Watch for truncated results (large arrays can get cut in the notification) — if an agent's array is incomplete, `SendMessage` it to re-emit the full JSON only. Save each batch's array to a file; merge; assert all tickets covered (no missing/extra).

### 5. Post a verdict comment on EVERY ticket
Generate tight HTML per ticket (verdict + confidence, evidence bullets, root cause/fix if valid-open, duplicate link, intensity, and the "Needs your call" question if any). Canary 3, then post the rest paced. Comment style: short, HTML with real `<a>` links for ticket refs, no em dashes.

### 6. Tag the unclear ones `needs-info`
Create the label once if missing: `hypertask project label create --project 15 --name "needs-info" --json`.
`--labels` on update REPLACES the set, so for each needs-info ticket: `hypertask tasks get` its current labels, append `needs-info`, then `hypertask tasks update <T> --labels "a,b,needs-info"`. Never wipe existing labels.

### 7. Hand Valentin the morning list (in chat, grouped)
- **Real bugs, ready to build** (valid-open) — with intensity; call out security/billing ones first.
- **Likely already fixed — verify & close** (likely-fixed) — cite the merged PR / wired code.
- **Stale — recommend close** (stale-close).
- **Not a bug / duplicate** (invalid, duplicate).
- **Needs your answer** (needs-info + any needsValentin questions) — tell him to filter the board by the `needs-info` label; put the one specific question per ticket.
Full clickable URLs, plain text (not markdown blue-on-black links). End by offering the follow-up actions (close batch, file infra tasks) — don't auto-do them.

## Notes
- ~56 bugs → 6 batches ran clean in one session; ~73 total board writes paced at 0.5s did not trip the bot challenge, but canary first every time.
- Distribution seen once (calibration, not a target): ~16 valid-open, ~16 needs-info, ~11 stale, ~9 likely-fixed, ~3 invalid, ~1 dup.
- Related: `/intensity` rubric for the intensity field; global rule to claim a ticket (assign 6 + In Progress) only when actually working it, not during triage.
