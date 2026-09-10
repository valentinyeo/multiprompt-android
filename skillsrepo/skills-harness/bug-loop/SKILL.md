---
name: bug-loop
description: 'Risk-first autonomous bug-drain loop for the Hypertask Bugs board (project 1534). Picks the lowest-risk, easiest, most-likely-real bug; VERIFIES it actually reproduces in the live app before acting; fixes it with the shortest diff (Ponytail); proves the fix with before/after screenshots attached to the ticket; opens a PR; and talks to Valentin through ticket comments under the coding-agent identity. High-risk or vague tickets are parked in dedicated columns for batch review. Use when Valentin invokes /bug-loop, /bug-loop N, "drain the bug board", "work the bugs", or runs it under /loop overnight.'
---

# bug-loop

Loop engineering for the Hypertask Bugs board. The agent is the "next button": **pick work →
verify it's real → fix it safely → prove it → hand off → repeat.** What `/next` lacks is RISK:
this loop works the lowest-risk, easiest, most-likely-still-real bug first.

## Identity & safety
- **Board writes go through `bin/agent-act`** (MCP at `https://mcp.hypertask.ai/mcp` with the agent
  JWT) so comments/moves/attachments show as **HT Coding Agent Loop**, not Valentin. Token at
  `~/.config/ht-bug-loop/agent-token` (chmod 600, NEVER commit).
- **Reads** go through the `hypertask` CLI (as Valentin) for board listing, and
  `agent-act inbox --agent` for the agent's OWN notifications (see "How Valentin talks back").
- All reproduction happens on the **dev env** (Neon branch, isolated writes) or read-only on prod.
  Never mutate prod data.
- **Fail loud (karpathy rule 12).** If you skip a gate, can't verify, or aren't sure a fix is
  right — say so and park it. NEVER fake a verdict.

## VERIFY LEGITIMACY IN THE LIVE APP BEFORE RANKING (the hard-won rules)
Order matters: **confirm it still reproduces before you rank or claim it.**
1. **Never trust ticket text.** (PERT-7 lesson: a "missing reset option" already existed; the
   ticket was stale.) Open the live app and check the actual behavior.
2. **RE-TEST every positive before posting.** (PERT-11 lesson: the kanban picker DID have Reset
   once the date persisted — a single observation nearly produced a wrong "still broken" verdict.
   Pickers/menus may only show context options after state persists.)
3. **Age is a staleness penalty, not a bonus.** Older than ~60 days and unconfirmed → assume
   fixed until proven otherwise. The board is ~3yr stale; most tickets are "confirm-close".
4. **Account-specific bugs exist.** (PERT-35 lesson: a bug real only for valentin.yeo@gmail.com
   won't reproduce on a fresh/dev login. If Valentin reports it real, his word wins over your
   dev-login test — re-check on HIS prod account or mark REAL.)
5. **Honesty over coverage.** If a ticket genuinely can't be verified in the available harness
   (mobile touch, live-AI output quality, multi-user collab, email infra) → NEEDS-INFO with the
   exact method required. Do not claim FIXED/REAL on what you couldn't actually test.

## Four-condition test (is this loop-able?)
Run a ticket through the loop only if: it's **repeatable** (clear-ish repro), verification is
**automated** (build + reproduce + a read-only verifier), the token **budget** has headroom
($200 plans, overnight ok), and you have **senior-eng tools** (dev env, dev server, browser
screenshots, git worktree). Metric that matters: **cost per accepted change.**

## Ponytail + model routing
- **Ponytail (full):** simplest thing that works, shortest diff, no speculative abstractions.
- **Opus 4.8 (main session)** plans + judges (the verify/rank/triage decisions). **Codex
  (`gpt-5.5`, $200 plan)** implements the fix (shortest diff). **Sonnet** is the fallback +
  read-only verifier. **pi** last resort, never for auth/billing/security. High-risk fixes are
  never auto-applied.

## The loop
0. **Refresh state.** `agent-act inbox --agent` (act on Valentin's replies first — answer →
   resume; "go" on a parked ticket → move to FIX; "it's real" → reopen). `eligible-bugs.sh` for
   open/unclaimed candidates.
1. **Verify-then-rank.** For the top candidates, open the live app and confirm the bug still
   reproduces (rules above). Rank survivors lowest-risk/easiest/most-real first.
2. **Triage into lanes:**
   - **FIX** — confirmed real + low-risk → fix it.
   - **REAL (no auto-fix yet)** — confirmed real but you're sweeping, or it's medium-risk →
     comment "verified still reproduces" + evidence, leave in backlog as a fix candidate.
   - **FIXED** — can't reproduce → comment "couldn't reproduce on current build, OK to close?" +
     screenshot + move to **✅ Fixed? (confirm-close)** (4369).
   - **NEEDS INFO** — vague or can't-verify-in-harness → comment the specific blocker/question +
     move to **Needs Info** (4362).
   - **NEEDS APPROVAL** — high-risk (auth/billing/DB/data-loss/security) → comment + move to
     **Needs Approval** (4363). Never auto-fix.
3. **Claim** (for a FIX) — move to **Doing** (4031) + an agent comment with the resume string,
   and **open the ticket in zsb** so Valentin can watch (see "Work transparently").
4. **Reproduce** in the dev env, capture a **before** screenshot. **Open the dev server URL in
   zsb** too (the running app under test), so he can see what's being reproduced/fixed.
5. **Fix** with the shortest diff via Codex (Ponytail).
6. **Gate** — build + lint + typecheck + re-run the exact repro + a read-only Sonnet verifier.
7. **Prove + hand off** — **after** screenshot, open a PR, comment (resume string + PR link),
   **open the PR in zsb**, move to **In Review** (4364). **Request Abdul's review
   (`AbdulWahhab66`) and STOP — never merge, never `gh pr merge --admin`, never self-merge.**
   Merge to `main` auto-deploys to LIVE prod (AWS EC2) and is Abdul's call. Sequential
   merge-train (merge one → confirm live → next; halt on broken deploy) is handled separately.
8. **Next.**

## Work transparently (open what you touch in zsb)
Valentin must be able to drop into this session at any time and SEE what the loop is doing. So
**open the things you work on in the zsb pane** (the browser he can watch over the tunnel), and
narrate key actions briefly in chat. Open, at minimum:
- **The ticket** when you claim it: `zsb navigate "https://app.hypertask.ai/detail/project-1534/<num>" --new-tab`.
- **The dev server** (the app under test) when you reproduce/verify: open the Cloudflare tunnel
  URL (`pgrep -af cloudflared`) in a zsb tab.
- **The PR** when you open it.
Tab discipline: open in a NEW zsb tab and reuse the one you opened; NEVER hijack a tab another
session opened (the pane is one shared surface — `zsb tabs` to see them, match by URL). If a tab
you want isn't active, that's fine — it's still there for Valentin to click; do NOT `navigate`
the active tab to force it (that hijacks another session). If zsb has no pane for this session or
is unavailable (binding is flaky — IGSH-44), DON'T block the loop: note it in chat and continue.
The goal is openness, not a hard dependency.

## Verification browsers (Contabo — both VERIFIED 2026-06-21/27)
Two self-sufficient harnesses on this VPS. Both navigate + read DOM + screenshot to disk.

1. **Playwriter → GUI Chrome → PRODUCTION** (Valentin watches via VNC on display :12). Use for
   **read-only legitimacy checks** + real-state / account-specific screenshots. Logged in as
   valentin.yeo@gmail.com. Do NOT mutate prod.
   - `SID=$(playwriter session new --browser profile:110041956283456005246 | grep -oE -- '-s [0-9]+' | grep -oE '[0-9]+' | head -1)`
   - `playwriter -s "$SID" -e '<js>'` — extension mode: `context.pages()` are open tabs.
     Open your OWN tab: `state.page = await context.newPage(); await state.page.goto(url)`.
   - Screenshot: `await state.page.screenshot({ path: "/tmp/x.png", scale: "css" })`.
   - Keep `playwriter` on @latest (extension needs a recent CLI).
2. **agent-browser → DEV env (localhost:3000)** — isolated Neon branch, safe for **mutating
   repro + fix verification**. Headless; attach screenshots since Valentin can't see it.
   - `agent-browser open <url>` / `snapshot -i -c` / `click @eN` / `press <key>` / `dblclick` /
     `screenshot /tmp/x.png` (positional path). Refs go stale each snapshot.
   - **App keyboard model:** the app tracks its OWN j/k focus, NOT DOM focus. Click the board,
     press `j` to select a card, then a shortcut (`d`=set due date, `Ctrl+K`=palette,
     `Shift+F`=filter). `type`/`fill` need a ref or a prior click. The TipTap editor barely
     accepts synthetic input here — use Playwriter/prod for editor-input tests.
   - Gotcha: agent-browser sometimes drifts to a static prototype mock page — recover with
     `agent-browser open "http://localhost:3000/project?id=15"`. Dev server can return a
     transient connection-refused mid-nav — `curl localhost:3000`, retry.

**Dev env setup:** worktree `~/projects/hypertasks-dev` (shared `.git` — use `git reset --hard`,
not checkout). DB = Neon branch `dev-valentin`. Server detached + unsandboxed (the Bash sandbox
kills `next start`): `tmux new-session -d -s devsrv 'cd ~/projects/hypertasks-dev && exec npx next start -p 3000'`
with `dangerouslyDisableSandbox: true`; poll `curl localhost:3000`. Login: `node /tmp/mint-dev.js`
prints a `/login?token=...` URL — open it in agent-browser (client-side Firebase flow needs a real
browser).

## Board columns (project 1534) — section IDs
Workflow: **Doing** 4031 · **Done** 4032 · **Needs Info** 4362 · **Needs Approval** 4363 ·
**In Review** 4364 · **✅ Fixed? (confirm-close)** 4369.
Backlog = **Todo** 4030 + categories: **UX** 4036, **Editor** 4037, **Keyboard** 4038,
**Navigation** 4039, **Data** 4040, **Mobile** 4041, **Other** 4042. `eligible-bugs.sh` excludes
the workflow columns. `agent-act sections --project 1534` lists current IDs (re-check; they move).

## agent-act usage (ticket arg = "PERT-NN")
- `agent-act comment PERT-NN --project 1534 --text "<html>" [--attach /tmp/x.png ...]`
- `agent-act move PERT-NN --project 1534 --section-id 4369`
- `agent-act sections --project 1534`  ·  `agent-act mksection --project 1534 --title "..."`
- `agent-act inbox --agent`  (the agent's own notifications)  ·  `--user` (Valentin's)
Comments fail loud if NOT attributed to the agent. Hypertask comments are HTML; NEVER embed
`![](url)` images (crashes the UI) — attach files instead.

## How Valentin talks back
He replies in the ticket thread; those land in the **agent's** inbox (`agent_notifications`, read
with `agent-act inbox --agent`) — NOT the standard assignee field. A native agent shares Valentin's
userId 6, so `assigned_to=<agentId>` and the assignees array do NOT surface agent assignments; the
inbox does. Step 0 reads it every run and acts: answer → resume; "go" → FIX; "it's real" → reopen.
The ticket IS the chat.

## Calibration status
- VERIFIED: agent identity + token; `agent-act` (comment/attach/move/sections/mksection/inbox) on
  the live MCP; both verification browsers; full legitimacy sweep of all 83 eligible tickets.
- **Sweep result (2026-06-21):** 14 FIXED (→ 4369), 1 REAL (**PERT-27**, Assign missing from
  Ctrl+K — the one low-risk fix candidate), 1 APPROVAL (PERT-75 security), 67 NEEDS-INFO. Plus
  Valentin-confirmed-REAL via inbox: **PERT-89** (white screen), **PERT-35** (account-specific).
- STILL TO VERIFY end-to-end: the FIX path (Codex → gate → PR → screenshots → In Review). Run
  `/bug-loop` on ONE ticket (e.g. PERT-27) with Valentin watching before going overnight.
