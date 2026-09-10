---
name: say-no
description: Invert the backlog — walk every open ticket through a Minimalist Entrepreneur filter and surface what to kill, defer, or shrink. Use when the board feels heavy, before planning cycles, after strategy shifts, or as a weekly ritual. Invoke as /say-no. Config-driven via .claude/say-no.yml per repo.
---

# say-no

Saying yes is free. Saying no is the scarce skill. Most boards accumulate tickets faster than they ship. `/say-no` inverts the usual "what do we do next?" into **"what should we stop pretending we're going to do?"**

## When to run

- Weekly ritual (e.g. Friday, 30 min)
- Before any planning cycle — clear the field before adding new work
- After a strategy shift — re-score tickets filed under old assumptions
- Ad-hoc when the board feels heavy

## Per-repo config (MANDATORY, self-onboarding)

Reads `.claude/say-no.yml` from cwd. If missing, the skill **onboards itself on first run**. Do not guess a board silently.

### First-run onboarding

1. Detect cwd. Check if `.claude/say-no.yml` exists → if yes, load it and proceed.
2. If no, check if `.claude/next.yml` exists in the same repo. If yes, **borrow** what's compatible:
   - board type + project_id
   - exclude_sections / board section names
   - wiki_path
   - any shared board credentials/config
   Tell Valentin: *"No say-no.yml yet. Borrowed board config from .claude/next.yml. Need a couple of extra answers."*
3. If neither exists, interview Valentin from scratch:
   - Which board? (hypertask / trello / linear / github)
   - Project / board ID?
   - Which sections to exclude (Doing/Review/Done)?
   - Holding section name (default: "TME Review") — create it on the board if missing
   - Defer section (default: Backlog)
   - Veto window (default: 48h)
   - Wiki path (optional)
4. Write `.claude/say-no.yml` and `.claude/say-no-learnings.md` (empty header) to the repo. Commit prompt optional — ask before committing.
5. Proceed with the normal run.

```yaml
# .claude/say-no.yml
board:
  type: hypertask            # hypertask | trello | linear | github
  project_id: 15             # id for the chosen board
  assignee_filter: all       # all | me — Valentin is manager, default "all"
  exclude_sections:          # sections to skip (Doing/Review/Done etc.)
    - Doing
    - Review
    - Done
  defer_section: Backlog     # only used for "defer" verdicts — MUST be verified to exist before proposing
comment_format: html         # html for Hypertask, markdown elsewhere
wiki_path: wiki/             # optional — read for customer/product context before scoring
decision_log: wiki/decisions/say-no-log.md
learnings_file: .claude/say-no-learnings.md   # appended when you pick option 3
```

**No holding section. No veto window. No invented sections.** Accept = archive directly (using the board's native archive mechanism). If Valentin changes his mind later, the ticket is still in the archive — he can restore it.

No pre-wired repos. The skill onboards per-directory on first invoke.

## The TME scoring lens

For each ticket, ask:

1. **Customer-asked, or founder-guessed?** Real demand beats imagined demand.
2. **Reversible, or a lock-in?** Lock-in raises the bar.
3. **Weekend, or quarter?** Small wins compound; quarter-long bets need hard justification.
4. **Default-alive, or delays revenue?** Does it move toward profit?
5. **Today's customers, or hypothetical future ones?** Today wins.
6. **Time cost, or money cost?** Which is scarcer right now.

A ticket violating ≥2 of these without a strong counter-reason is a candidate for **kill / defer / shrink**.

## Verdicts

- **kill** — post a comment, then **archive the ticket directly** using the board's native archive. No holding section, no veto window. Reversible via the board's restore — no custom plumbing needed.
- **defer** — move to `defer_section` (must be verified to exist on the board first) with a "revisit on YYYY-MM-DD" note.
- **shrink** — leave in place, post a comment stating the reduced scope.
- **keep** — **never surfaced.** The skill silently skips anything that passes. Valentin does not want to see what survives — this is a drain tool, not a review tool.

**Never propose moving a ticket to a section you haven't verified exists.** Before suggesting any move, list the project's actual sections and use only real ones. If the needed section doesn't exist, fall back to the native archive (for kill) or ask Valentin which section to use (for defer).

## Procedure

1. Load `.claude/say-no.yml`. Fail loud if missing.
2. Load `learnings_file` (if present) — every prior rule Valentin taught the skill is in here. Apply them to scoring.
3. Read `wiki_path` (if set) for customer asks, shipped-vs-announced, product direction. TME scoring without founder context = noise.
4. Pull every open ticket NOT in `exclude_sections` from the configured board (respect `assignee_filter`, default `all`).
5. Score each ticket silently through the six TME questions. **Never surface "keep" verdicts.** If the ticket survives scoring, drop it — do not mention it, do not summarise it, do not count it out loud. The output is a drain list, not a review of the full board. Only tickets with a verdict of **kill / defer / shrink** get shown.
6. **Walk case-by-case — ONE ticket per message, then STOP and wait for input.** This is the core interaction. Violating it breaks the skill.

   **Hard rules for presentation:**
   - Show exactly one ticket per reply. Never print a list. Never group. Never preview "next up".
   - After printing the ticket block, STOP. Do not continue. Do not print any other ticket in the same message.
   - Wait for Valentin to type `1`, `2`, or `3`. Only then score + present the next ticket.
   - Do not announce the queue size ("36 candidates found"). Do not print a table of contents. The queue is internal state — he finds out the count at the end-of-run summary.
   - No headers like "## Kill (12)" or "DEFER (6)". No batching by verdict. The flow is strictly: one ticket → wait → next ticket → wait.
   - **Every ticket MUST include its clickable URL on its own line**, plain text (no markdown link syntax — Valentin's terminal renders `[title](url)` as unreadable dark-blue-on-black). Hypertask format: `https://app.hypertask.ai/detail/project-<project_id>/<numeric_id>`. If the URL is missing, the whole presentation is broken — do not proceed without it.

   Per ticket, print:

   ```
   <TICKET-ID> · <title>
   URL: <ticket url>
   Summary: <2–3 sentence plain summary of what the ticket is asking for>
   TME verdict: kill / defer / shrink
   Why: <1–3 bullets — which TME principles it violates and why>
   Suggested action: <archive via native archive / defer to <verified section> with revisit date / shrink in place>
   Draft comment: <founder-voice, humanizer-passed, HTML for Hypertask>

   [1] accept  [2] skip  [3] teach the skill
   ```

7. On keypress:
   - **1 — accept.** Post the comment, then execute the suggested action: kill → archive the ticket via the board's native archive; defer → move to the verified defer section with a revisit date; shrink → leave in place (comment is the whole action). Append to `decision_log` with date + reason.
   - **2 — skip.** Do nothing, move to next ticket. No log entry.
   - **3 — teach the skill.** Ask *"what should I have done differently?"*. Append the lesson verbatim to `learnings_file` with date + ticket ID. Then re-score *this* ticket using the new rule and re-present. Future runs load these rules and apply them before scoring.

8. End-of-run summary: N archived / N skipped / N taught. Backlog delta vs previous run. After ≥3 runs, surface recurring kill themes ("you killed 6 observability tickets — stop filing them").

## Adapters

### Hypertask (primary)

Use the `hypertask` CLI. Never the REST API. HTML comments. Pinned command reference — **do not re-discover these at runtime**:

| Action | Command |
|---|---|
| List open tickets in a section | `hypertask tasks list --project <id> --section "<name>"` |
| Get ticket detail | `hypertask tasks get <ticket>` |
| Post a comment | `hypertask tasks comment <ticket> --body "<html>"` (HTML only, no image embeds) |
| **Archive a ticket (= kill)** | `hypertask tasks update <ticket> --status Archive` |
| Move to a different section | `hypertask tasks move <ticket> --section "<name>"` |
| List sections on a project | `hypertask sections list --project <id>` (use to verify sections before proposing moves) |

Status values for `--status`: `Normal`, `Archive`, `Deleted`. **Kill = `Archive`, never `Deleted`.** Archive is reversible via the Hypertask UI; Deleted is not.

### Other board types

- **Trello / Linear / GitHub Issues:** use the respective CLI. Markdown comments. The specific archive/move commands belong in this section once first used — don't re-discover them every run.

## Repeatable hooks

- **Decision log** at `decision_log` path — one line per kill/defer/shrink with ticket ID, date, reason. Append, never rewrite.
- **Pattern detection** — after ≥3 runs, scan the log for recurring kill themes and surface: *"you've killed 6 observability tickets in a row — stop filing them."*
- **Holding section veto window** — tickets in `holding_section` older than `veto_window_hours` get auto-archived on the next `/say-no` run unless Valentin commented "keep" on them.
- **Weekly digest** (optional) — killed/deferred counts, backlog size delta vs last run.

## What this is NOT

- Not a priority ranker — that's `/next`.
- Not a planner — that's `/deep-work` or `/lean`.
- Not a judgment of the ticket author. Framing: "past-us made a bet, present-us has new info."
- Not auto-destructive — every irreversible action has the veto window.

## Hard rules

- Never delete a ticket. Kill = move to holding. Archive is the board's job after the veto window.
- Never post a comment without Valentin approving it (or explicit "approve all" for the batch).
- Never skip the config check. No config = no run.
- Every kill must include *why* in the comment — "violates TME principle X because Y". No naked closes.
- Comments go through a humanizer pass before posting.
