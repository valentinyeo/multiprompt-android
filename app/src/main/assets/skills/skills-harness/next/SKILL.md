---
name: next
description: Surface the single most important next action across project board, inbox, Slack, email, and other channels — hybrid urgency + Deep Work scoring with client-contact boost and a learning loop. Invoke as /next (single item) or /next top / /next 10 (overview). Auto-detects repo from cwd via .claude/next.yml.
---

# /next

Point Valentin at the **one most important thing** to handle right now, then auto-advance through the queue. Reads per-repo config from `.claude/next.yml` in cwd.

## Modes

- `/next` — single top item (default, auto-advance)
- `/next top` or `/next 10` — top-10 table with scores, no menu
- `/next me` — same as `/next` but **after** presenting the top item, run a minimalist-entrepreneur review (see below). Designed for Valentin's startup-client context where big-company priorities often overweight vanity/process work.

## Minimalist-entrepreneur review (`/next me` mode)

After picking the top item and showing the usual single-item output (title, score, menu), invoke the `minimalist-review` skill on the full ranked list (or at minimum the top 5 + the current #1). Lens: Sahil Lavingia's principles — community first, start manual, build little, sell before scale, spend time before money, profitability is the goal, stay small.

Output an extra block below the menu:

```
— Minimalist review —
Score drivers passing the lens:
- ✅ <driver> — <why it aligns>

Score drivers the lens would push back on:
- ⚠️  <driver> — <minimalist counter>, consider <alternative>

Suggested reordering (if any):
- Swap #1 → #<n> because <one-line reason>, OR
- Top pick holds.
```

If the review wants to swap #1, show both picks and let Valentin choose (option 6 added for that session: "Take the minimalist pick instead"). Otherwise proceed with the normal 1–5 menu.

Trigger: `/next me` → run the review once on first item. Subsequent auto-advanced items skip the review unless Valentin types `me` again.

## Bootstrap — read config

On invocation, read `.claude/next.yml` from cwd. **If missing → error loudly** with a starter template. Do not proceed.

Config schema (example):

```yaml
repo_name: inne-cro
hypertask:
  project_ids: [339, 1564]
  review_section: { project: 339, section: 1291 }   # +3 boost; optional
email:
  gws_account: personal                              # personal | hypertask
  queries:
    - "from:(@inne.io OR @feralgmbh.com) is:unread newer_than:7d"
slack:
  workspace: inne-slack                              # CLI name
  channels: [cro, marketing]
trello:
  boards: []                                          # [{id: "...", name: "..."}]
paperclip:
  companies:                                          # [{name, url}]
    - name: Nora
      url: "http://localhost:3100/api/companies/caa429e4-528d-4450-8a37-63055c2f029e/issues"
client_contacts:                                     # +5 boost when they're the sender / @-mentioner
  - { name: Hicham, handles: ["hicham@inne.io", "@hicham"] }
deep_work_focus: [cro, experiments, conversion]      # topics counted as "deep"
extra_sources: []                                    # [{label, command}] — free-form shell commands
```

## Appointments preamble (once per 24h)

**Before** presenting the first item of a session, check `.claude/next-last-briefed.txt`. If the file is missing **or** its timestamp is >24h old, fetch today's appointments and output them as a preamble, then update the timestamp.

```
📅 Today's appointments (YYYY-MM-DD):
- HH:MM–HH:MM  <title>  (<attendees/location>)
- HH:MM–HH:MM  <title>
(or: "No appointments today.")
```

One appointment per line. Pull from every source in the `calendar` config block — merge, dedupe, sort by start time. After the preamble, proceed with the normal top item.

If no briefing happened at all in the current chat (file missing) → output and create the file. If the file exists but its mtime is within 24h → skip the preamble silently.

Sources for appointments come from `calendar:` in `.claude/next.yml`:

```yaml
calendar:
  gws_accounts: [personal]          # personal | hypertask | both — pulls via `gws calendar events list --time-min today --time-max tomorrow`
  cal_com: { api_env: CAL_COM_API_KEY }   # optional — curl v1/bookings
```

## Sources — probe in parallel

For each enabled config block, fire the corresponding command. Never ask "what should I check?" — just run everything configured. Run commands directly via Bash when output is small; delegate the full batch to a subagent via the Agent tool and use only its summary when probing is complex.

| Block | Command pattern |
|---|---|
| `hypertask.project_ids` | `hypertask tasks list --project <id> --json` per project |
| `hypertask.review_section` | paginate project and filter `sectionId == <section>` (CLI bug HTPR-3189) |
| Hypertask inbox | `hypertask inbox list --json`, filter to configured projects |
| `email` | `gws gmail +list --query "<q>"` (prefix with `GOOGLE_WORKSPACE_CLI_CONFIG_DIR=~/.config/gws-personal GOOGLE_WORKSPACE_CLI_KEYRING_BACKEND=file` if `gws_account: personal`) |
| `slack` | `<workspace> unread` / mentions / DMs — CLI per workspace |
| `trello.boards` | `trello card:list --board <name> --format json` |
| `paperclip.companies` | `curl <url>` → filter items needing review/approval |
| `extra_sources` | run the shell command as-is, include output in ranking |

**Missing capability = flag loudly, do not silently skip.** If a CLI is absent or a token expired, print a one-line warning `⚠️ source <name> unreachable: <reason>` and continue with the rest — but surface the warning block above the top item so Valentin sees it.

## Hybrid scoring (0–10, sort descending)

Base = urgency + depth + client boost.

| Signal | Points |
|---|---|
| **Direct client contact is waiting on Valentin** | **+5** |
| In a configured "Review Valentin" column | +3 |
| External stakeholder waiting (any non-client human) | +3 |
| Blocks live A/B test, deploy, or revenue flow | +4 |
| Deep work opportunity (matches `deep_work_focus`) | +3 |
| Direct @-mention of Valentin | +2 |
| Waiting >3d / >7d | +2 / +3 (stacks) |
| High-impact / urgent label | +2 |
| Paid-traffic / revenue page (PDP, pricing, checkout) | +2 |
| Trivial yes/no (<2 min) | +1 |
| Shallow / notification / digest / auto-generated | −3 (batch, see below) |
| Parked/stale >30d no movement | −2 |
| Fully delegable (Paperclip agent can own it) | −2 |

Then apply every rule from `.claude/next-learnings.md` (see below).

## Shallow batching (Newport)

If the top-ranked item after scoring is shallow (digests, billing notifications, Stack Overflow, auto-alerts), **do not present it as #1**. Group all shallow items into one synthetic entry: `Shallow batch — ~10 min (N items)`, and present it *after* the first deep item of the day.

## Learning loop — `.claude/next-learnings.md`

Read this file on every invocation. Each line = one rule. Apply as score adjustments before final sort.

Format:
```
- deprioritize: <pattern> (<points>) — <reason>
- boost: <pattern> (<points>) — <reason>
- always-top: <pattern> — <reason>
- never-surface: <pattern> — <reason>
```

When Valentin hits option **5** ("wrong priority — teach me"), ask one follow-up: *"Why is this wrong? (one line)"*. Translate the answer into a rule, append it to `.claude/next-learnings.md`, re-rank, and present the new #1. Learnings compound across sessions.

## Output — single-item mode

**Before presenting**, fetch the item's full context and synthesize a brief. Do NOT just show the title + link — that is a failure mode. Valentin must be able to act without opening the source.

**Required fetch depth per source:**
- Hypertask ticket → `hypertask tasks show <id>` + `hypertask comments list <id>` (full comment thread)
- Hypertask inbox → inbox item + parent ticket context
- Slack thread → full thread messages
- Email → full thread (not just subject)
- Trello card → description + all comments
- Paperclip issue → issue body + activity log

Then output this exact shape:

```
<optional ⚠️ unreachable-sources warnings>

### <title> (<source>, score X/10)
URL: <plain-text link>

**TL;DR:** <1 sentence — what the item is about>

**Timeline:**
- <date> — <who> <did/said what> (most relevant prior event)
- <date> — <who> <did/said what>
- <date> — <latest> (end with current state)

**Blocker:** <who is waiting on what from whom — name names, no passive voice>

**Proposed next move:** <concrete single action — reply with X, decide Y/N on Z, move ticket to section Q, schedule call with R>. If a written reply is needed, include the **full draft below** (already run through humanizer) so Valentin can copy-paste or hit 3 to autosend.

**Draft reply** (if applicable):
> <full drafted message — humanized, ready to send>

1. Next — I handled it (or don't want to act), move on
2. Start work (autonomous) — execute the Proposed next move, including posting the Draft reply as-is
3. Start work — instructions follow
4. Wrong priority — teach me

Type a number.
```

**Rules for the brief:**
- If the item has <3 events, show what's there — don't pad with filler
- Timeline = absolute dates (convert "yesterday" → ISO)
- Proposed next move must be **actionable in one step** — "follow up with Arbaz" is bad; "Reply to Arbaz that yes we can ship the shared module v2 this week, and ask him to confirm the integration PR is ready" is good
- If the brief reveals a dependency Valentin needs (info, decision from someone else, a screenshot, a staging link), call it out explicitly so he knows what to grab before acting
- Option **3** (autonomous) must execute the Proposed next move verbatim — that's the contract. If the next move is ambiguous or risky (money, commitments, client-facing copy Valentin hasn't seen before), mark it `[confirm first]` so option 3 pauses for his OK instead of firing
- Every drafted message passes through the `humanizer` skill before it's shown

- **1** → move on to the next item. No side effects — state is not tracked; if Valentin acted manually, that's assumed. No comment, no move, no archive.
- **2** → work autonomously: execute the Proposed next move (post drafted reply, move section, call API), verify, return with results
- **3** → wait for typed instructions before starting
- **4** → prompt for reason, append to `.claude/next-learnings.md`, re-rank, present new #1

**Never** preview the queue in single-item mode. One item at a time.

## Output — overview mode (`/next top` or `/next 10`)

```
| # | Score | Title | Source | Why |
|---|-------|-------|--------|-----|
| 1 |  10   | ...   | Slack  | client Hicham waiting 2d |
| 2 |   9   | ...   | HT     | Review Valentin, blocks deploy |
...
```

No menu. Wait for Valentin to say a number → switch to single-item mode on that pick.

## Auto-advance

After resolution or skip → immediately fetch next highest without asking. Stop only when the queue is empty or Valentin says "stop" / "pause" / "enough".

## Execution rules

- Use `humanizer` skill for anything written on Valentin's behalf (comments, email replies, Slack messages)
- Plain-text URLs only (per `feedback-plain-url-formatting`) — not markdown links
- Absolute dates only ("yesterday" → today's ISO date)
- **Never edit Hypertask descriptions** — append-only via comments
- Ticket URLs: `https://app.hypertask.ai/detail/project-{projectId}/{numericId}`
- If `extra_sources` errors, warn and continue — don't block the whole run
- Delegate downward when a Paperclip agent can own it — suggested move = brief + assign, not "do it yourself"

## First-run bootstrap

Read `.claude/next.yml`, fire every configured source concurrently (run commands directly or delegate via subagent), apply hybrid scoring + learnings, batch shallow items, present rank #1 (or the table if overview mode). No summary of what was gathered — just hand over the top item.
