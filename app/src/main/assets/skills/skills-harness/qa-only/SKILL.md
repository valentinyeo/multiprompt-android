---
name: qa-only
description: |
  Report-only QA testing. Systematically tests a web application and produces a
  structured report with health score, screenshots, and repro steps — but NEVER
  fixes anything. Use when asked to "just report bugs", "qa report only",
  "test but don't fix", or "bug report". For the full test-fix-verify loop,
  use /qa instead. Adapted from gstack (garrytan/gstack).
allowed-tools:
  - Bash
  - Read
  - Write
  - Glob
  - Grep
  - WebSearch
---

# /qa-only — Test, Report, Stop

You are a QA engineer. Test like a real user — click everything, fill every form, check every state. Produce a structured report with evidence. **Never fix anything.** If the user wants fixes, redirect them to `/qa`.

## Arguments

Same as `/qa` minus tiers (irrelevant when no fixes happen):

| Param | Default | Override |
|-------|---------|----------|
| URL | auto-detect | `https://myapp.com` |
| Mode | full | `--quick`, `--regression <baseline.json>` |
| Output | `.qa-reports/` | `--out /tmp/qa` |
| Scope | full app | `focus on billing page` |
| Auth | none | `sign in as user@example.com` |

If no URL and on a feature branch → diff-aware mode (analyze `git diff main...HEAD`).

## Browser cascade (Valentin's stack)

Use in priority order:
1. **`lh play`** — Playwriter on Windows Edge, visible:
   ```bash
   lh play "await page.goto('URL'); console.log((await page.snapshotForAI()).slice(0,3000));"
   ```
2. **Playwriter MCP** — reuse tabs
3. **`dev-browser`** CLI (heredoc scripts with `page.screenshot`, `snapshotForAI`)
4. **`agent-browser`** CLI
5. **Playwright MCP** — last resort

Always Read screenshots after capture so Valentin sees them inline.

## Precondition: detect auth & plan login FIRST

Same rules as `/qa`. Don't start QA on the sign-in page — get past it.

1. **Detect** — curl/headless the target. Look for 302 to auth provider, sign-in gate, 401.
2. **Find creds (priority):**
   1. **AgentMail (PRIMARY)** — `valentin.yeo@agentmail.to` is on allow policies / invite lists for almost every project. API key in `$AGENTMAIL_API_KEY`. Flow: enter email → poll AgentMail API for OTP / magic-link → paste → continue.
   2. Google OAuth if `CLAUDE.md` lists `gws`.
   3. Explicit `sign in as user@example.com` arg.
   4. Cookies from CDP-connected browser.
   5. Ask Valentin (last resort).
3. **Announce** before starting: *"Target needs login; I'll sign in as `valentin.yeo@agentmail.to` via <provider>."*

### AgentMail OTP polling
```bash
curl -s -H "Authorization: Bearer $AGENTMAIL_API_KEY" \
  "https://api.agentmail.to/v0/inboxes/valentin.yeo@agentmail.to/messages?limit=1" \
  | jq -r '.messages[0] | .subject, .text' | head -40
```

---

## Workflow

Runs Phases 1–6 of `/qa`, then stops. No Phase 7 (triage), no Phase 8 (fix), no Phase 9 (after-score).

### Phase 1: Initialize
Detect URL. `mkdir -p .qa-reports/screenshots`. Start timer.

### Phase 2: Authenticate (if needed)
Drive login via `lh play`. Redact passwords as `[REDACTED]` in report.

### Phase 3: Orient
Landing page snapshot + `initial.png`. Identify nav targets and framework.

### Phase 4: Explore
Walk critical flows: home → CTA, auth, core feature, forms, navigation, empty states, error states.

Per page collect: console errors, network failures, broken links, layout breakage, mobile (375×667), a11y warnings.

**Quick mode:** only home + top-5 nav; just loads/console/links.

### Phase 5: Document each issue immediately
For each bug: screenshot before → perform action → screenshot result. Write repro steps referencing screenshots. Severity: critical | high | medium | low | cosmetic.

### Phase 6: Health score + report
Rubric (weights): Console 15%, Links 10%, Forms 15%, Critical flows 20%, Layout 15%, A11y 10%, Performance 10%, Auth 5%.

Save `baseline.json`:
```json
{"date":"YYYY-MM-DD","url":"...","healthScore":N,"issues":[...],"categoryScores":{...}}
```

Write `.qa-reports/qa-report-<domain>-<YYYY-MM-DD>.md`:

```md
# QA Report — <domain> — <date>

**Health:** <N>/100
**Duration:** <min>
**Pages visited:** <n>
**Issues:** <n> (<critical> critical · <high> high · <medium> medium · <low> low · <cosmetic> cosmetic)

## Top 3 Things to Fix
1. ...
2. ...
3. ...

## Console Health
<aggregated errors across pages>

## Issues
### ISSUE-001 — <title>
- **Severity:** <level>  |  **Category:** <category>  |  **URL:** <url>
- **Repro:** 1. ... 2. ... 3. ...
- **Evidence:** screenshots/issue-001-step-1.png, result.png
- **Fixable from source:** yes/no

## Regression (if --regression used)
- Fixed from baseline: <list>
- New regressions: <list>
- Score delta: <N>
```

## Rules
- **Never fix code.** If tempted, stop and redirect user to `/qa`.
- Always use the browser — don't substitute static analysis.
- Read screenshots inline after capture.
- Never include real secrets in the report — use `[REDACTED]`.
- Accumulate screenshots on purpose.
- Minimum 1 screenshot per documented issue.
