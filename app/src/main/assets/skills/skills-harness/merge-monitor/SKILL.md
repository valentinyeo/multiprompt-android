---
name: merge-monitor
description: Watch a GitHub PR until it lands. Checks the reviewer's verdict on a cron pinned to the reviewer's working hours, auto-merges on approval (never with --admin), and on changes-requested implements the fixes, tests them on the dev env, pushes, and re-requests review. Telegram-pings Valentin at every milestone so he can stay away. Use when Valentin says "/merge-monitor", "merge monitor", "watch this PR and merge when approved", "babysit this PR", or wants a PR shepherded to merge while he's offline.
---

# merge-monitor

Shepherd a GitHub PR from "review requested" to "merged + deployed" without Valentin babysitting it. Runs on a **cron** pinned to the reviewer's working hours (not a self-paced loop), notifies via Telegram at each milestone.

**Why cron, not `ScheduleWakeup`:** a self-paced loop caps each sleep at 1h, so waiting out a night/weekend means dozens of empty "not a slot yet → sleep again" wakes — pure noise in the session. A cron fires *only* at the actual check times (Mon-Fri, 5 slots), so there are zero off-hours wakes. Both re-invoke this skill with `/merge-monitor <PR>`; the cron just removes the noise.

**Session scope:** the cron is session-only by default — it lives in this Claude session and dies if the session exits (same lifetime the old loop had). To make a monitor survive a session restart, create the cron with `durable: true` (it persists to `.claude/scheduled_tasks.json` and reloads). Only do that if Valentin asks for cross-session survival.

## Inputs

- **PR to monitor — detect it, don't ask.** Resolution order, stop at the first hit:
  1. **Explicit arg**: `/merge-monitor 1188` (a number → default repo) or `/merge-monitor <pr-url>` (any repo).
  2. **From this session's ticket (PRIMARY — this is where the PR link lives).** When a PR is opened its link is posted as a ticket comment, so the ticket is the authoritative source for "which PR." This session is working a ticket: its title is `PREFIX-NNN | ...` (if the title is unclear, fall back to the branch name's `htpr-NNNN`). Read that ticket's comments and take the most recent one containing a GitHub PR URL:
     ```bash
     hypertask comment list <PREFIX-NNN> --json 2>&1 | grep -v '"timestamp"'
     ```
     Pick the newest `https://github.com/<owner>/<repo>/pull/<n>` in the comments. Parse `<owner>/<repo>` and `<n>` from that URL — it also sets the target repo. **Prefer the session ticket over the current git branch:** the cwd branch often belongs to a different ticket than the one this session is about, so branch detection picks the wrong PR. Do NOT ask the user when the ticket comment already names the PR.
  3. **Current branch** (only if the ticket has no PR link): `gh pr view --json number,url -q '{n:.number,u:.url}'`.
  4. **Only if all of the above fail**: ask which PR.
- **Repo is whatever the resolved PR lives in — not hardcoded. Two repos, BOTH reviewed by Abdul (`AbdulWahhab66`, Hypertask userId `193` on product board project 15):**
  - **valentinyeo/hypertasks** (web app) — merge → AWS deploy via `deploy.yml`; fixes tested on the VPS dev env (Neon branch DB).
  - **aalian66loop/hypertask-mcp** (`hypertask` CLI/MCP, work lands in the `CLI/` subdir) — same reviewer (Abdul, same merge policy: get his review, never self-merge), BUT merging does NOT auto-deploy (publish is manual `npm run deploy` from `CLI/`) and there is NO Neon dev env — test CLI fixes with `npm test` + running the built binary (`node CLI/dist/hypertask_cli.js ...`) instead. The repo has no branch-protection gate, so a merge won't be *blocked* for "review required" — still wait for Abdul's approval before merging; do not self-merge just because GitHub would let you.

  Use the resolved PR's `<owner>/<repo>` for every `gh` call (pass `--repo <owner>/<repo>` so the cwd repo doesn't shadow it). Reviewer is Abdul regardless of repo: `reviewerGithub=AbdulWahhab66`, `reviewerName="Abdul Wahhab"`, `reviewerUserId=193` (CLI tickets are HTPR on project 15, same board).

## Hard rules (do not violate)

- **Merge directly on approval** — `gh pr merge <PR> --merge --delete-branch` (never `--admin`). Concurrent deploys are no longer a risk: `deploy.yml` has `concurrency: { group: production-deploy, cancel-in-progress: false }`, so GitHub Actions serializes deploys automatically (a second merge's deploy queues behind the first). After merging, watch the deploy run and confirm the site is live before reporting done.
- **Merging `main` ships to AWS production** (GitHub Action `deploy.yml`). Treat a merge as a prod deploy.
- **NEVER `gh pr merge --admin`** or bypass branch protection. A merge only happens after a genuine approving review satisfies the gate. If a merge is rejected for "review required", do NOT force it — keep waiting.
- **Before pushing any fix, test it on the VPS dev env** (Neon branch DB), per the project's hard rule. No untested pushes.
- Auth/security code: keep the diff surgical (Ponytail). Re-run the relevant `.check.ts`.

## Telegram notify

Creds live in the environment: `TELEGRAM_HYPERTASK_BOT_TOKEN`, `TELEGRAM_HYPERTASK_CHAT_ID`. Send with:

```bash
curl -s "https://api.telegram.org/bot${TELEGRAM_HYPERTASK_BOT_TOKEN}/sendMessage" \
  --data-urlencode chat_id="${TELEGRAM_HYPERTASK_CHAT_ID}" \
  --data-urlencode parse_mode="HTML" \
  --data-urlencode text="<message>"
```

Keep messages terse. Always include the PR number and a link (`https://github.com/valentinyeo/hypertasks/pull/<n>`).

## State file

Per-PR state lives at `~/.cache/merge-monitor/<PR>.json`:
`{pr, repo, ticket, reviewerUserId, reviewerName, reviewerGithub, commentWatermark, announced, announcedCommentId, cronId, lastCheckedSlot}`.
- `commentWatermark` — highest Hypertask comment id already handled. Set on first run to the current max so existing comments aren't re-processed. Advance after handling new feedback.
- `announced` / `announcedCommentId` — guard the one-time "now monitoring" ticket note.
- `cronId` — the CronCreate job id driving this monitor (so re-invocations don't double-create, and STOP can delete it).
- `lastCheckedSlot` — `YYYY-MM-DD-HH` (London) of the last real check; cheap idempotency guard against a double fire in the same slot.

## When checks run — cron at the reviewer's hours

Abdul works **Mon-Fri, 09:00-17:00 UK** (Europe/London, auto-handles BST/GMT). Checks run at **5 slots/day: 09:00, 11:00, 13:00, 15:00, 17:00 UK** — driven by one cron, created on first invocation. No weekend or after-hours wakes.

Cron fires in **machine-local time**, which may differ from UK. Compute the local-hour equivalents of the UK slots and build the expression (minute 7 to dodge the :00 fleet pileup):

```bash
python3 - <<'PY'
import datetime
from zoneinfo import ZoneInfo
UK=ZoneInfo("Europe/London")
LOCAL=datetime.datetime.now().astimezone().tzinfo
SLOTS=[9,11,13,15,17]
today=datetime.datetime.now(UK).date()
hrs=sorted({datetime.datetime.combine(today, datetime.time(h), UK).astimezone(LOCAL).hour for h in SLOTS})
print("CRON 7 " + ",".join(map(str,hrs)) + " * * 1-5")
PY
```

Then `CronCreate({cron: "<that>", prompt: "/merge-monitor <PR>", recurring: true})` and store the returned id as `cronId`. (UK and continental Europe share DST transitions so the offset is stable; the per-fire gate below still confirms the real slot, so a DST-edge or a manual off-hours invocation does nothing.)

**On every fire (and any manual `/merge-monitor` invocation), gate before doing work:**

```bash
python3 - <<'PY'
import datetime
from zoneinfo import ZoneInfo
SLOTS=[9,11,13,15,17]
now=datetime.datetime.now(ZoneInfo("Europe/London"))
print("IN_SLOT", now.weekday()<5 and now.hour in SLOTS)
print("SLOT_ID", now.strftime("%Y-%m-%d-%H"))
PY
```

- **`IN_SLOT False`** (off-hours/weekend, e.g. a manual invocation) → do NOT check GitHub/ticket, do NOT notify, do NOT touch the cron. End the turn. The cron fires the next real slot.
- **`IN_SLOT True`** but `SLOT_ID == lastCheckedSlot` → already handled this slot; end the turn.
- **`IN_SLOT True`** and new slot → do the full check below, set `lastCheckedSlot = SLOT_ID`, write state. **Never call `ScheduleWakeup`** — the cron is the only pacing mechanism.

## Each check — both sources

Abdul approves/blocks the merge on **GitHub**, but he writes his actual problem feedback on the **Hypertask ticket**. Check both, from `/home/valentin/projects/hypertasks`:

```bash
# 1) GitHub PR — merge gate
gh pr view <PR> --json state,reviewDecision,mergedAt,reviews \
  -q '{state:.state, decision:.reviewDecision, reviews:[.reviews[]|{by:.author.login, state:.state, body:.body}]}'

# 2) Hypertask ticket — Abdul's written feedback (creatorId == reviewerUserId, id > watermark = new)
hypertask comment list <TICKET> --json 2>&1 | grep -v '"timestamp"'
# each comment: {id, text, createdAt, creatorId, creator:{id,displayName}}
```

New Abdul feedback = any ticket comment with `creatorId == reviewerUserId` and `id > commentWatermark`, OR any new GitHub review from `reviewerGithub`. Read the actual text and classify it (model judgment) — approval vs problems vs question. Then branch on the result:

**STALE-REVIEW GUARD (check FIRST, prevents an autonomous re-fix loop):** GitHub keeps `reviewDecision: CHANGES_REQUESTED` until the reviewer submits a *new* review, so a changes-request you already fixed still reads as `CHANGES_REQUESTED` on every later fire. Before treating GitHub `CHANGES_REQUESTED` as case 3, compare Abdul's latest review `submitted_at` to the PR head commit date:
```bash
RV=$(gh api repos/<repo>/pulls/<PR>/reviews -q '[.[]|select(.user.login=="<reviewerGithub>")][-1]|.submitted_at')
HD=$(gh pr view <PR> --repo <repo> --json commits -q '.commits[-1].committedDate')
# actionable only if RV > HD (he reviewed the CURRENT head). If RV <= HD, you already pushed a fix after his review.
```
If his review predates the head commit (you already pushed a fix + re-requested), it is **already addressed → awaiting re-review → treat as case 5 (nothing new), do NOT re-implement.** Only treat `CHANGES_REQUESTED` as case 3 when his review is newer than the head commit (he reviewed your latest push and still wants changes).

1. **Already MERGED or CLOSED** → `CronDelete(cronId)`, Telegram "PR #<n> is already <state>, stopping monitor." STOP.

2. **Cleared to merge** — Abdul approved on GitHub (reviewDecision `APPROVED` / an `APPROVED` review from `reviewerGithub`) OR a new ticket comment from him clearly says ship it (lgtm / approved / merge it / go ahead) with no open problems:
   - **Merge it:** `gh pr merge <PR> --repo <repo> --merge --delete-branch` (NO `--admin`). Blocked for "review required" → do not force; Telegram "🟡 #<n> approved on the ticket but GitHub still needs the approving review", keep the cron running.
   - **Watch the deploy.** Get the merge commit (`SHA=$(gh pr view <PR> --repo <repo> --json mergeCommit -q .mergeCommit.oid)`), find its `deploy.yml` run, and block on it: `RID=$(gh run list --repo <repo> --workflow deploy.yml --limit 15 --json databaseId,headSha -q ".[]|select(.headSha==\"$SHA\")|.databaseId"|head -1); gh run watch "$RID" --repo <repo> --exit-status`. (`deploy.yml` concurrency serializes deploys, so a queued run just waits its turn — re-issue `gh run watch` if the Bash tool times out.)
   - **Confirm live:** curl `https://app.hypertask.ai` (200/3xx = up) after the run goes green.
   - Deploy green + site up → Telegram "✅ Merged PR #<n> and it's live." Best-effort move the ticket to **Deploy** + one-line "landed live" comment. Advance watermark, `CronDelete(cronId)` (job done), STOP.
   - Deploy FAILED or site not responding → Telegram "🔴 PR #<n> merged but deploy failed / site down — <run-url>", STOP (do not merge anything else; main may be broken).

3. **Problems / changes requested** — `CHANGES_REQUESTED` on GitHub, OR a new ticket comment from Abdul raising issues:
   - Read his feedback in full (ticket comment text + `gh pr view <PR> --comments` + review bodies).
   - Implement the fixes with the shortest correct diff (Ponytail). Update/extend the relevant `.check.ts`.
   - **Test on the dev env** before pushing (per the hard rule).
   - Push to the **same PR branch** (the PR updates in place — preserves the review thread; new PR only if the branch is unusable).
   - Re-request his GitHub review (`gh api -X POST repos/<repo>/pulls/<n>/requested_reviewers -f 'reviewers[]=<reviewerGithub>'`) AND reply on the ticket summarizing what you changed (so he sees it in his channel).
   - Telegram: "🔧 Abdul flagged on PR #<n>: <one-line summary>. Fixed + pushed + replied on the ticket + re-requested review. <pr-link>"
   - Advance watermark. **Keep the cron running** — wait for his re-review.

4. **A neutral/question comment from Abdul** (not clearly approve or problems) → reply on the ticket if you can answer confidently, else Telegram a one-line "Abdul asked X on PR #<n>, needs your call." Advance watermark, keep the cron running.

5. **Nothing new** (`REVIEW_REQUIRED`, no new ticket comment) → end the turn quietly, no notification.

After handling any case that isn't STOP, **write the new max handled comment id and `lastCheckedSlot` back to the state file** so the same comment/slot isn't reprocessed.

## Pacing

- The **cron** is the only scheduler — created once on first invocation, fires at the 5 weekday slots, never on weekends/off-hours. Do NOT use `ScheduleWakeup`.
- Recurring crons **auto-expire after 7 days** (one final fire, then deleted). If a PR is still open near that horizon, re-arm by invoking `/merge-monitor <PR>` again (it'll find the stale/absent cron via `CronList` and recreate it). Tell Valentin about the 7-day limit when arming.
- **Stop conditions:** merged, closed, or Valentin says stop. On stop, `CronDelete(cronId)` and end (no new cron).

## On first invocation

1. Resolve the PR/repo/reviewer (Inputs). Load or create the state file; set `commentWatermark` to the current max ticket comment id.
2. **Announce on the ticket — once.** Prefer amending the existing review-request comment (the latest comment from the PR author containing the PR link): append ONE terse line via `hypertask comment update <id> --text "<full amended html>"`, preserving the original. If that comment was posted under the coding-agent identity (so the CLI can't cleanly edit it) or none exists, post a minimal new note instead. Guard with `announced`; store `announcedCommentId`. Terse line:
   `<p>Now auto-monitored: approve on GitHub and it merges itself; leave feedback here and it gets fixed, tested, and pushed for re-review.</p>`
3. **Create the cron** (When-checks-run section): compute local-hour slots → `CronCreate` → store `cronId` in state. If state already has a live `cronId` (check `CronList`), don't double-create.
4. Do one tick immediately (report current state to Valentin in-session) — regardless of slot, since he just invoked it.
5. Send the Telegram "armed" message.
6. End the turn. The cron handles every check from here; no `ScheduleWakeup`.
