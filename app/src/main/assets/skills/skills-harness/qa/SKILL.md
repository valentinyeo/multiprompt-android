---
name: qa
description: |
  Systematically QA-test a web application and fix bugs found. Runs QA testing,
  then iteratively fixes bugs in source code, committing each fix atomically and
  re-verifying. Use when asked to "qa", "test this site", "find bugs", "test and
  fix", or "fix what's broken". Three tiers: Quick (critical/high only),
  Standard (+ medium, default), Exhaustive (+ cosmetic). Produces before/after
  health scores, fix evidence, and a ship-readiness summary. For report-only
  mode use /qa-only. Adapted from gstack (garrytan/gstack).
allowed-tools:
  - Bash
  - Read
  - Write
  - Edit
  - Glob
  - Grep
  - WebSearch
---

# /qa — Test, Fix, Verify

You are a QA engineer. Test web applications like a real user — click everything, fill every form, check every state. Then fix every bug you find, one atomic commit per fix.

## Arguments

| Param | Default | Override |
|-------|---------|----------|
| URL | auto-detect | `https://myapp.com`, `http://localhost:3000` |
| Tier | Standard | `--quick`, `--exhaustive` |
| Mode | full | `--regression <baseline.json>` |
| Output | `.qa-reports/` | `--out /tmp/qa` |
| Scope | full app | `focus on billing page` |
| Auth | none | `sign in as user@example.com` |

Tiers determine which issues get fixed:
- **Quick** — fix critical + high only
- **Standard** — + medium (default)
- **Exhaustive** — + low/cosmetic

If no URL given and on a feature branch → auto-enter **diff-aware mode**: analyze `git diff main...HEAD`, only test changed routes/components.

## Browser cascade (Valentin's stack)

Use this priority order. NEVER give up on browser verification.

1. **`lh play`** (PRIMARY — visible Edge on Windows, Valentin can watch):
   ```bash
   lh play "await page.goto('https://dash.endlesstesting.ai'); const snap = await page.snapshotForAI(); console.log(snap.slice(0, 3000));"
   ```
2. **Playwriter MCP** (`mcp__playwriter__*`) — if `lh play` fails. Reuse tabs.
3. **`dev-browser`** CLI (VPS, always works):
   ```bash
   dev-browser <<'EOF'
   const page = await browser.newPage();
   await page.goto('URL');
   await page.screenshot({path: '/tmp/qa/shot.png'});
   console.log(await page.snapshotForAI());
   EOF
   ```
4. **`agent-browser`** CLI — fallback.
5. **Playwright MCP** (`mcp__plugin_playwright_playwright__*`) — last resort.

**If CDP-connected (lh play or `dev-browser --connect`):** skip cookie imports; real browser already has sessions.

**Always Read screenshots after capturing** so the user sees them inline.

## Precondition: detect auth & plan login FIRST

Before any QA work, decide up front whether the target is behind a login, and if so, how to get in. Don't start exploring unauth pages and skip the actual product.

### Step 1 — detect auth gate
Hit the landing URL with `curl -I` or a quick headless load. Signs of auth:
- 302 redirect to `accounts.*`, `auth.*`, `clerk.*`, `/sign-in`, `/login`
- Rendered page has "Sign in", "Log in", "Continue with ..." buttons as the only interactive elements
- `<meta property="og:title">` says something like "Sign in to ..."
- HTTP 401 JSON body

If unsure, open the URL, screenshot it, Read it. If it looks like a login gate — it is.

### Step 2 — find credentials (priority order)
1. **AgentMail — PRIMARY (Valentin's default E2E pattern).** Valentin's AgentMail address `valentin.yeo@agentmail.to` is on allow policies / invite lists for almost every project he owns, and it's the canonical way AI agents authenticate end-to-end. Before anything else, try it:
   - Check the project `CLAUDE.md` and `memory/*` for mentions of AgentMail being wired up (it usually is).
   - Default sign-in email: `valentin.yeo@agentmail.to`. API key in env: `$AGENTMAIL_API_KEY`.
   - Flow: drive the login → enter AgentMail email → check AgentMail API for the verification code → paste it → continue.
   - If the provider is Clerk, the magic-link or OTP arrives at AgentMail as a normal email. Parse it with:
     ```bash
     curl -s -H "Authorization: Bearer $AGENTMAIL_API_KEY" \
       "https://api.agentmail.to/v0/inboxes/valentin.yeo@agentmail.to/messages?limit=5" | jq '.messages[0]'
     ```
**If Clerk is the auth provider, prefer CDP over AgentMail.** Clerk's `/v1/tickets/accept` + JS widget boot doesn't settle `networkidle` in headless, which stalls sign-up automation. Better path: `lh play --connect` so Playwriter reuses Valentin's already-signed-in Edge session. Fall back to AgentMail only if no CDP is available.

2. **Google OAuth (real account).** If AgentMail doesn't work, Valentin's Google account has been seeded in some projects. Check `CLAUDE.md` for "Google Workspace CLI (gws)" references. Use only if explicitly listed.
3. **Explicit arg.** `sign in as user@example.com` — obeys the user, uses AgentMail if domain matches.
4. **Cookies import.** If CDP-connected via `lh play --connect`, the real browser already has sessions. No login needed.
5. **Ask Valentin.** Last resort. Include a ready-to-paste prompt for Claude Chrome Extension.

### Step 3 — announce + proceed
Before Phase 1, tell Valentin one line: *"This target requires login; I'll sign in as `valentin.yeo@agentmail.to` via <provider> and read the OTP from AgentMail."* Then proceed.

If there's no auth gate → say so (*"target is public, skipping login"*) and go to Phase 1.

### Quick reference: AgentMail OTP / magic-link polling
```bash
# Most recent unread message (Clerk OTP, magic links, etc.)
curl -s -H "Authorization: Bearer $AGENTMAIL_API_KEY" \
  "https://api.agentmail.to/v0/inboxes/valentin.yeo@agentmail.to/messages?limit=1" \
  | jq -r '.messages[0] | .subject, .text, .html' | head -60
```
Extract the 6-digit code or click link, feed it into the browser.

---

## Precondition: clean working tree

```bash
git status --porcelain
```
If non-empty, stop and ask: commit / stash / abort. QA needs clean tree for one-bug-per-commit.

## Workflow

### Phase 1: Initialize
- Detect target URL (explicit arg → `.env` PUBLIC_URL → localhost dev server → git branch for diff mode).
- `mkdir -p .qa-reports/screenshots`
- Start timer.

### Phase 2: Authenticate (if needed)
If auth required, use `lh play` to drive login (never log passwords in reports — use `[REDACTED]`).

### Phase 3: Orient
Visit landing page. Capture annotated snapshot + full-page screenshot (`initial.png`). Identify top navigation targets. Note framework (from `<meta generator>`, headers, source files).

### Phase 4: Explore
Walk critical user flows:
- Landing → primary CTA → conversion
- Auth flows (sign in, sign up, forgot password)
- Core feature (dashboard, search, checkout, settings)
- Forms (submit empty, submit invalid, submit valid)
- Navigation (every top-level link)
- Empty states (log out, clear data)
- Error states (force 404, 500 via bad URL)

Per page collect: console errors, network failures, broken links, layout breakage, mobile viewport (375×667), accessibility warnings.

**Depth:** more time on core pages (home, dashboard, checkout); less on footer pages (terms, privacy).

**Quick mode:** only home + top-5 nav targets; just "loads? console clean? links not 404?".

### Phase 5: Document issues immediately
Don't batch. When you find a bug:
1. Screenshot before action → `issue-NNN-step-1.png`
2. Perform action
3. Screenshot result → `issue-NNN-result.png`
4. Write repro steps in the report

Each issue has: `id`, `title`, `severity` (critical|high|medium|low|cosmetic), `category` (console|links|forms|layout|a11y|auth|perf|mobile|data), `url`, `repro`, `evidence` (screenshot paths), `fixable-from-source` (y/n).

Severity quick guide:
- **Critical** — app unusable, data loss, auth broken
- **High** — primary flow broken (can't sign up, can't submit form)
- **Medium** — secondary flow broken, visible error, bad UX on main page
- **Low** — edge case, minor UX nit
- **Cosmetic** — spacing, typos, colors

### Phase 6: Baseline health score
Compute categories (0-100), weighted average = overall.

| Category | Weight | 100 | 70 | 40 | 10 |
|----------|--------|-----|----|----|-----|
| Console errors | 15% | 0 | 1-3 | 4-10 | 10+ |
| Broken links | 10% | 0 | 1 | 2-3 | 4+ |
| Forms | 15% | all submit | 1 bug | 2 bugs | 3+ |
| Critical flows | 20% | all work | 1 broken | 2 broken | 3+ |
| Layout (desktop+mobile) | 15% | clean | minor | visible | broken |
| Accessibility | 10% | >0 | 1-3 warn | 4-10 warn | WCAG fail |
| Performance | 10% | <3s | <5s | <8s | >8s LCP |
| Auth | 5% | works | minor | flaky | broken |

Save `baseline.json` in output dir:
```json
{"date":"YYYY-MM-DD","url":"...","healthScore":N,"issues":[{"id":"ISSUE-001","severity":"high","category":"forms","hypertaskTicket":"ENDL-201"}],"categoryScores":{...}}
```

### Phase 7: Triage
Sort by severity. Filter by tier. Mark third-party/infra issues as "deferred" (can't fix from source).

### Phase 7.5: File Hypertask tickets (MANDATORY)

Every issue (fixed, deferred, or attempted) gets a Hypertask ticket so there's a durable record outside the local `.qa-reports/` directory. File them **before** the fix loop so links exist to reference in commit messages.

1. **Discover the project.** If the repo has a `CLAUDE.md` with a "Hypertask board" / "project ID" line, use it. Otherwise ask once, remember in project memory, then never ask again. The endless-testing project is `473`.
2. **Discover the section.** Sections vary per project and the CLI errors if the name is wrong. Always run `hypertask task list --project <id>` first and pick a "Backlog" / "Todo" / "Ready" style column. Do NOT hardcode `"MVP Backlog"` — section names drift.
3. **Discover valid labels.** If the CLI errors with `Label "x" not found in project`, use one of the listed available labels (typically `Bug`, `Feature`, `Change Request`). Do not create new labels during a /qa run.
4. **File one ticket per issue.** Map severity to priority: `critical→urgent`, `high→high`, `medium→medium`, `low→low`, `cosmetic→low`. Prefix title with `QA:` so they're easy to filter later.
5. **Record the ticket number** (e.g. `ENDL-201`) next to the issue in the report and in `baseline.json` under a `hypertaskTicket` field. Fix commits later reference it: `fix(qa): <title> (ISSUE-NNN, ENDL-201)`.

Ticket body template (HTML, because Hypertask renders HTML):

```bash
hypertask task create \
  --project <id> \
  --section "Backlog" \
  --title "QA: <short summary>" \
  --priority <urgent|high|medium|low> \
  --labels "Bug" \
  --description "<h2>Problem</h2><p>...</p>
<h2>Repro</h2><ol><li>...</li></ol>
<h2>Evidence</h2><p>Screenshot: <code>.qa-reports/screenshots/issue-NNN-result.png</code></p>
<h2>Fix</h2><p>...</p>
<p>Found by /qa <YYYY-MM-DD>. Report: <code>.qa-reports/qa-report-&lt;domain&gt;-&lt;date&gt;.md</code>.</p>"
```

**Never embed images** in the description (`![](url)` crashes Hypertask UI) — link paths as plain text instead, per Valentin's global rule.

After filing, if the issue gets fixed in Phase 8, add a comment to the ticket with the commit SHA + deploy URL and move the status to `In Review` or `Done` via `hypertask task update <ticket> --status <section>`.

### Phase 8: Fix loop
For each fixable issue in severity order:

1. **Locate** — grep/glob source code for the failing component, route, or string
2. **Fix** — edit code. For >1 file or >20 lines, delegate to pi:
   ```bash
   pi -p --provider minimax --model "MiniMax-M2.7-highspeed" "<brief including files, expected behavior, test command>"
   ```
   Review pi's diff with `git diff`. Fall back to direct for auth/billing/schema/security or one-liners.
3. **Commit atomically** — one issue = one commit:
   ```
   fix(qa): <issue title> (ISSUE-NNN)
   ```
4. **Re-deploy** if needed (worker: `wrangler deploy`; dashboard: `npm run build && wrangler pages deployment create dist/ ...`)
5. **Re-verify** — drive the same repro with `lh play`. Screenshot → `issue-NNN-after.png`. Confirm issue is gone.
6. If re-verify fails: revert commit, log as "attempted-no-fix", move on.

### Phase 9: After-score + report
Recompute health score. Delta = after − before.

Write `.qa-reports/qa-report-<domain>-<YYYY-MM-DD>.md`:

```md
# QA Report — <domain> — <date>

**Health:** <before>/100 → <after>/100 (Δ+<N>)
**Tier:** <quick|standard|exhaustive>
**Duration:** <min>
**Pages visited:** <n>
**Issues:** <n> found · <n> fixed · <n> deferred

## Top 3 Fixes Needed (Ship Readiness)
1. ...
2. ...
3. ...

## Console Health
<aggregated console errors>

## Issues
### ISSUE-001 — <title> [FIXED | DEFERRED | ATTEMPTED]
- **Severity:** <level>
- **Category:** <category>
- **URL:** <url>
- **Repro:**
  1. ...
- **Evidence:** before.png, result.png, after.png
- **Fix:** <commit sha + brief>

## Regression (if --regression used)
- Fixed from baseline: <list>
- New regressions: <list>
- Score delta: <N>

## Ship readiness
- ✅ Safe to ship | ⚠️  Fix first | ❌ Blocked
- Reasoning: ...
```

### Phase 10: Summary
Print to user: score delta, fix count, deferred count, ship verdict, link to report file.

## Output structure
```
.qa-reports/
  qa-report-<domain>-<date>.md
  baseline.json
  screenshots/
    initial.png
    issue-NNN-step-1.png
    issue-NNN-result.png
    issue-NNN-before.png
    issue-NNN-after.png
```

## Rules
- Never refuse to use the browser — this skill IS browser-based. Don't substitute unit tests.
- Never claim "fixed" without the after-screenshot as evidence.
- Never skip re-verify after a fix.
- Never include real passwords/tokens in reports — use `[REDACTED]`.
- Never commit with a dirty tree — one bug = one commit.
- Screenshots accumulate on purpose; don't delete them.
- Read screenshots inline after capture so Valentin sees them.
- For /qa invoked inside the endless-testing repo: bump `packages/dashboard-v2/package.json` version + CHANGELOG entry per project release workflow (only if a fix touched dashboard-v2).
