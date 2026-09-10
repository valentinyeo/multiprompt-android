---
name: verify
description: Force self-verification of changes using browser cascade (Playwriter/dev-browser/agent-browser/Playwright), curl, and test runs. Never let user QA.
---

# Self-Verification

Invoke after ANY change — frontend, backend, deploy, config. Never present work as done without evidence.

## Trigger

- Run `/verify` manually after completing work
- Auto-trigger before claiming "done", "finished", "deployed", "fixed", or "ready"

## Flow

1. **Classify changes** — `git diff --stat` to determine: UI, API, deploy, CLI, config, or mixed
2. **Select strategy** — see table below, detailed guides in `references/`
3. **Execute verification** — use browser cascade for visual, curl/tests for functional
4. **Fix issues** — loop until clean. Never present broken work
5. **Send screenshot to Telegram** — for ANY visual/UX change, send screenshot + URL via Telegram (see `references/telegram-evidence.md`)
6. **Report with evidence** — screenshot paths, curl output, test results

## Strategy Table

| Change type | Primary verification | Reference |
|-------------|---------------------|-----------|
| UI/frontend | Browser cascade → screenshot + DOM snapshot | `references/browser-cascade.md` |
| Deploy (CF/Vercel) | Open deployed URL → screenshot + console check | `references/browser-cascade.md` |
| API endpoint | `curl` → status code + response shape | `references/functional-checks.md` |
| CLI tool | Run command → confirm output | `references/functional-checks.md` |
| Tests/build | Run suite → confirm zero failures/errors | `references/functional-checks.md` |
| Auth/login | AgentMail end-to-end flow | `references/functional-checks.md` |
| Mixed | Combine strategies — verify each layer | Both references |

## Hard Rules

- **NEVER claim done without evidence.** Include screenshot paths, test output, or curl results
- **NEVER use DOM inspection as proof of rendering.** `querySelectorAll` finding elements does NOT mean they are visible. Take a screenshot, look at it, describe what you actually see. If the user says it's broken, believe them — investigate, don't argue
- **ALWAYS send screenshot to Telegram for UX changes.** URL + screenshot = proof. See `references/telegram-evidence.md`
- **NEVER ask user to verify.** "Can you check..." is FORBIDDEN
- **NEVER skip because a tool is unavailable.** Cascade to the next tool
- **Fix issues found** — loop `verify → fix → re-verify` until clean
- **Run in subagent** when possible to avoid context bias
- **Zero Trust is not a blocker.** VPS sessions have `CF_ACCESS_CLIENT_ID`/`CF_ACCESS_CLIENT_SECRET` service tokens. Use them. If that fails, use `valentin.yeo@agentmail.to` to complete email-based login. See `references/access-methods.md`

## Evidence Format

Present after every verification:

```
## Verification Report
- **Type:** UI / API / Deploy / CLI / Config
- **Tool:** Playwriter / dev-browser / agent-browser / Playwright / curl / test runner
- **Status:** PASS / FAIL
- **Evidence:** [screenshot path / output / results]
- **Telegram:** [sent / not applicable]
- **Issues fixed:** [list or "none"]
```
