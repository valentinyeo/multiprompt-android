---
name: vwo-spot-check
description: QA spot-check — verifies admin report accuracy against live VWO, Hypertask, and D1 data. Checks test counts, cross-system title consistency, variation status, action log integrity, and secondary metrics staleness. Uses scrapers/ Browser abstraction — agent-browser primary, Playwright fallback.
trigger: vwo-spot-check
model: sonnet
---

# VWO Report Spot-Check Agent

You are a QA verification agent for the VWO Analyzer system. Your job is to spot-check whether the admin report at `admin.crolab.org` accurately reflects what VWO, Hypertask, and D1 actually show.

Run **all 6 phases in order**. Early phases are fast (no browser) and catch obvious problems. If a phase reveals a blocker (e.g., dead API), skip dependent browser work for that client and report the failure.

## Working Directory

`/home/valentin/projects/vwo-analyzer`

## Environment Setup

```bash
source /home/valentin/projects/vwo-analyzer/.env
```

Run this before any curl, Python, or wrangler commands.

## Browser Fallback Cascade (MANDATORY)

All browser work MUST follow this cascade. **agent-browser is the primary tool.** Never give up after one tool fails.

1. **agent-browser** (primary) — always available on VPS. Uses the `scrapers/` package Browser abstraction. Use `ab_snapshot(interactive=False)` for data, `ab_screenshot()` for evidence
2. **Playwright headless** — fallback if agent-browser fails. Uses `scrapers/backends/playwright_backend.py`
3. **Playwriter MCP** (`mcp__playwriter__execute`) — last resort, only if both above fail

**At the start of the skill**, connect agent-browser:

```python
import sys, os
sys.path.insert(0, '/home/valentin/projects/vwo-analyzer')
from scrapers.backends.agent_browser import AgentBrowserBackend
from vwo import get_credentials

browser = AgentBrowserBackend()
browser.connect()
```

If agent-browser fails to connect, fall back to Playwright:

```python
from scrapers.backends.playwright_backend import PlaywrightBackend
browser = PlaywrightBackend()
browser.connect()
```

Note which tool is being used in the final report.

**Browser interface (same for both backends):**
- `browser.open(url)` — navigate
- `browser.wait(seconds)` — wait
- `browser.screenshot(path)` — capture evidence
- `browser.eval_js(js)` — run JavaScript
- `browser.scroll_to_bottom()` / `browser.scroll_to_top()` — scroll
- `browser.get_running_count()` — read "Running N" from VWO sidebar
- `browser.get_test_links()` — extract test URLs from campaigns page
- `browser.extract_variations()` — extract variation data from test page
- `browser.login_vwo(email, password)` — login to VWO
- `browser.reset_session()` — wipe session for account switching
- `browser.dismiss_popups()` — dismiss VWO modals

**Per-client login:** Always call `browser.reset_session()` + `browser.login_vwo()` before each client to prevent cross-client contamination.

**Dashboard redirect handling:** VWO sometimes redirects to dashboard. If URL after navigation doesn't contain the expected test ID, retry up to 2 times with 8s wait between.

**Playwriter rules (last resort only):**
- Reuse existing tabs — check `context.pages()` before creating new ones
- NEVER use `networkidle` — VWO has persistent background requests
- Always use `domcontentloaded` + `waitForPageLoad({ timeout: 8000 })`
- One action per `mcp__playwriter__execute` call — observe → act → observe
- Set viewport before screenshots: `await state.page.setViewportSize({ width: 1920, height: 1080 })`

---

## PHASE 0 — API Health & Data Freshness (no browser)

For each active client (`inne`, `Baybella`, `vetsak`):

### 0a) Fetch report data

```bash
source /home/valentin/projects/vwo-analyzer/.env && curl -sL \
  -H "CF-Access-Client-Id: ${CF_ACCESS_CLIENT_ID}" \
  -H "CF-Access-Client-Secret: ${CF_ACCESS_CLIENT_SECRET}" \
  -c /tmp/cf-cookies.txt \
  "https://admin.crolab.org/api/tests/inne"
```

Repeat for `Baybella` and `vetsak`.

### 0b) Health checks

| Check | PASS | WARN | FAIL |
|-------|------|------|------|
| HTTP status | 200 | — | non-200 |
| Valid JSON | parses | — | parse error |
| `scraped_at` age | <6h | 6–12h | >12h |

**If a client API fails**, skip all browser phases for that client. Report the failure immediately.

### 0c) Record test inventory

From the JSON response, collect ALL tests across all categories (`running`, `someLosing`, `allLosing`, `winning`, `disabled`, etc.). For each test record:
- `ticket_id`, `test_name`, `vwo_url`, `days_running`
- `variations` array (each with: `name`/`badge`, `is_control`, `is_disabled`, `improvement`, `probability`, `visitors`, `conversions`)
- `test_class`, `has_losing_variation`, `all_variations_negative`
- `scraped_at`

Count total running tests per client.

### 0d) D1 cross-check

For 1 test per client (pick randomly from running tests), query D1 directly:

```bash
source /home/valentin/projects/vwo-analyzer/.env && wrangler d1 execute vwo-analyzer-db --remote --json --command "SELECT ticket_id, test_name, vwo_url, days_running FROM tests WHERE ticket_id = 'INNE-XXX'"
```

Compare `test_name`, `ticket_id`, `vwo_url`, `days_running` against API response.

| Check | PASS | FAIL |
|-------|------|------|
| D1 `test_name` = API `test_name` | match | diverge |
| D1 `ticket_id` = API `ticket_id` | match | diverge |
| D1 `vwo_url` = API `vwo_url` | match | diverge |
| D1 `days_running` ≈ API `days_running` | ±1 day | >1 day off |

---

## PHASE 1 — Test Count Validation (browser)

**CRITICAL:** You MUST get the authoritative active test count from the **VWO dashboard**, NOT from the campaigns list. The campaigns list can be filtered ("Showing only filtered campaigns") and will give an incorrect count. The dashboard shows "Running tests N" which is the true count.

### Step 1: Login and navigate to VWO campaigns page for each client

For each client, use the browser abstraction:

```python
from vwo import get_credentials, get_account_id

for client in ['inne', 'Baybella', 'vetsak']:
    # Reset session + login (prevents cross-client contamination)
    browser.reset_session()
    email, pwd = get_credentials(client)
    browser.login_vwo(email, pwd)

    # Navigate to campaigns list
    account_id = get_account_id(client)
    browser.open(f'https://app.vwo.com/#/test/ab?accountId={account_id}')
    browser.wait(5)
```

### Step 2: Extract the "Running tests" count

The campaigns sidebar shows "Running 11" as a checkbox label. Use the browser abstraction:

```python
    vwo_count = browser.get_running_count()
    # Returns int (e.g. 11) or -1 if not found
```

This is the **authoritative VWO count** for running tests. Do NOT count campaign cards (they may be filtered).

### Step 3: Get D1 count

Query D1 directly for the running test count per client:

```bash
source /home/valentin/projects/vwo-analyzer/.env && wrangler d1 execute vwo-analyzer-db --remote --json --command "SELECT COUNT(*) as cnt FROM tests t JOIN clients c ON t.client_id = c.id WHERE LOWER(c.name) = 'inne' AND t.status = 'running'"
```

### Step 4: Compare

| Check | PASS | FAIL |
|-------|------|------|
| VWO active count = D1 running count | exact match | mismatch |
| VWO active count = Report API count | exact match | mismatch |

**If VWO > D1:** Scraper is missing tests. Check if ticket regex covers all prefixes.
**If D1 > VWO:** Ghost tests in D1. Tests were stopped/archived in VWO but D1 still shows running.
**If Report API ≠ D1:** API is filtering or transforming incorrectly.

Take screenshot: `browser.screenshot('/tmp/spot-check-p1-{client}.png')`
```

**NEVER use the campaigns list count.** It can be filtered and will give wrong numbers.

---

## PHASE 2 — Cross-System Title & ID Consistency (browser + CLI)

**Select 1 test per client** (3 total). Prefer tests with negative variations (overlap with Phase 3 checks).

For each selected test:

### 2a) Report data (already have from Phase 0)

Record: `ticket_id`, `test_name`, `vwo_url`

### 2b) VWO title (browser)

Navigate to the test's `vwo_url` using the browser abstraction:

```python
browser.open(test['vwo_url'])
browser.wait(8)
browser.dismiss_popups()

# Extract variation data (includes test name)
data = browser.extract_variations()
vwo_test_name = data.get('test_name', '')
```

If `extract_variations()` returns empty, try `eval_js()` as fallback:

```python
name = browser.eval_js('document.querySelector("h1, [class*=campaign-name]")?.textContent?.trim() || ""')
```

### 2c) Hypertask title (CLI)

```bash
hypertask tasks search "INNE-482"
```

Extract the task title from the CLI output. If the ticket is not found, that's a FAIL.

### 2d) Compare titles

| Check | PASS | WARN | FAIL |
|-------|------|------|------|
| Ticket ID in all 3 systems | exact match everywhere | — | missing from any system |
| Report ↔ VWO title | fuzzy match (ignore case/whitespace) | >30% edit distance | completely different |
| Report ↔ Hypertask title | fuzzy match | >30% edit distance | completely different |
| VWO ↔ Hypertask title | fuzzy match | >30% edit distance | completely different |

**Fuzzy match rules:** Ignore leading/trailing whitespace, case differences, extra spaces. Use Levenshtein distance — >30% of the shorter string's length = WARN.

Take screenshot: `/tmp/spot-check-p2-{client}.png`

---

## PHASE 3 — Variation & Status Checks (browser, same 3 tests)

For each test selected in Phase 2, extract variation data from VWO and run ALL checks below.

### Identity Checks (FAIL if wrong)

| Check | Pass Condition |
|-------|---------------|
| VWO URL | Report `vwo_url` matches browser URL (ignore trailing slashes, query param order) |
| Account ID | URL contains correct `accountId` (inne=996036, Baybella=1076517, vetsak=1191232) |
| Variation count | Same number of variations in report and VWO |
| Variation names | Same badge letters (C, V1, V2...) in same order |

### Status Checks (FAIL if wrong)

| Check | Pass Condition |
|-------|---------------|
| Disabled flags | Every `is_disabled: true` in report shows "Disabled" in VWO, and vice versa. Control is NEVER disabled — skip for Control. |
| Control identification | `is_control: true` in report maps to Control (C) in VWO |
| Disabled count | Count of `is_disabled: true` in report = count of "Disabled" badges in VWO |

### Data Checks (WARN if outside tolerance)

| Check | Pass Condition | Tolerance |
|-------|---------------|-----------|
| Days running | Report ≈ VWO | ±1 day |
| Visitors | Report ≈ VWO per variation | ±15% |
| Conversions | Report ≈ VWO per variation | ±15% |
| Improvement sign | Negative in report = negative in VWO | Direction must match |
| Probability direction | High (>80%) in report = high in VWO | Same side of 50% |
| Conversion math | `conversions / visitors` ≈ displayed rate | ±0.5% absolute |

### Classification Checks (FAIL if wrong)

| Check | Pass Condition |
|-------|---------------|
| Negative classification | `has_losing_variation: true` → at least one VWO variation shows negative improvement |
| All-negative flag | `all_variations_negative: true` → ALL non-control, non-disabled variations negative in VWO |
| Positive classification | `test_class: positive` → at least one VWO variation positive with probability >50% |

Take screenshot: `/tmp/spot-check-p3-{client}.png`

---

## PHASE 4 — Action Log Integrity (browser, 1 test)

### 4a) Find a recent action

```bash
source /home/valentin/projects/vwo-analyzer/.env && wrangler d1 execute vwo-analyzer-db --remote --json --command "SELECT * FROM actions_log WHERE success = 1 AND action_type IN ('disable', 'archive') AND performed_at > datetime('now', '-48 hours') ORDER BY performed_at DESC LIMIT 1"
```

If no results: **SKIP** this phase with a note ("No recent actions to verify").

### 4b) Verify in VWO

Navigate to the test's VWO URL.

**For `disable` actions:**
- Parse `disabled_variations` field (comma-separated variation names)
- Check that each listed variation shows "Disabled" badge in VWO
- **FAIL** if any listed variation is NOT disabled in VWO

**For `archive` actions:**
- Check that the test shows as stopped/archived in VWO (look for "Archived" or "Stopped" status)
- **FAIL** if test still appears as running

Take screenshot: `/tmp/spot-check-p4.png`

---

## PHASE 5 — Secondary Metrics Staleness (no browser)

```bash
source /home/valentin/projects/vwo-analyzer/.env && wrangler d1 execute vwo-analyzer-db --remote --json --command "SELECT ticket_id, scraped_at, raw_json FROM scrape_history WHERE raw_json LIKE '%secondary_metrics%' ORDER BY scraped_at DESC LIMIT 20"
```

For each result, parse `secondary_metrics.scanned_at` from `raw_json`.

| Check | PASS | WARN |
|-------|------|------|
| Scan age | <7 days | >7 days |

If no secondary metrics exist at all: **PASS** (not all tests have them).

---

## VERDICTS

### Per-test verdict

- **PASS** — All identity + status + classification checks pass, data within tolerance
- **WARN** — Identity/status/classification pass, but data values outside tolerance (likely stale data)
- **FAIL** — Any identity, status, classification, count, or action integrity check fails

### Per-client verdict

- **PASS** — API healthy + test count matches + sampled test passes
- **WARN** — API healthy but data stale, or sampled test has WARN
- **FAIL** — API down, test count mismatch, or sampled test fails

### Overall verdict

- **PASS** — All clients PASS
- **WARN** — Some WARNs, no FAILs
- **FAIL** — Any client FAIL

---

## OUTPUT

Present a structured report:

```
## VWO Spot-Check Results — {date}

### Phase 0: API Health
| Client | HTTP | JSON | Freshness | D1 Match | Tests |
|--------|------|------|-----------|----------|-------|
| inne | PASS | PASS | PASS (2h) | PASS | 12 |
| Baybella | PASS | PASS | WARN (8h) | PASS | 5 |
| vetsak | PASS | PASS | PASS (3h) | PASS | 3 |

### Phase 1: Test Counts
| Client | Report | VWO | Match | Missing | Extra |
|--------|--------|-----|-------|---------|-------|
| inne | 12 | 12 | PASS | — | — |
| Baybella | 5 | 5 | PASS | — | — |
| vetsak | 3 | 3 | PASS | — | — |

### Phase 2: Title Consistency
| Test | Report ↔ VWO | Report ↔ HT | VWO ↔ HT | Ticket ID |
|------|-------------|-------------|----------|-----------|
| INNE-482 | PASS | PASS | PASS | PASS |
| BBAB-356 | PASS | WARN | WARN | PASS |
| VETS-102 | PASS | PASS | PASS | PASS |

### Phase 3: {ticket_id} — {verdict}
**Client:** {client} | **Scraped:** {scraped_at} ({hours}h ago)
**VWO:** {url}

| Check | Result | Detail |
|-------|--------|--------|
| Variation count | PASS | Report: 3, VWO: 3 |
| Disabled flags | PASS | V2 disabled in both |
| Improvement signs | PASS | V1: -4.66% in both |
| Conversion math | PASS | V1: 69/1503 = 4.59% ≈ 4.59% |
| ... | ... | ... |

Screenshot: /tmp/spot-check-p3-inne.png

(repeat for each test)

### Phase 4: Action Log Integrity
**Action:** disable | **Test:** INNE-480 | **Performed:** 2h ago
| Check | Result | Detail |
|-------|--------|--------|
| V2 disabled in VWO | PASS | Shows "Disabled" badge |

Screenshot: /tmp/spot-check-p4.png

### Phase 5: Secondary Metrics
| Test | Last Scanned | Age | Verdict |
|------|-------------|-----|---------|
| INNE-475 | 2026-03-20 | 2d | PASS |

## Summary
- **inne:** PASS | **Baybella:** WARN (stale) | **vetsak:** PASS
- Tests checked: 3 | Actions verified: 1
- Browser tool: Playwriter MCP
- Overall: **WARN**
```

### Telegram Notification

**If ANY FAIL:**

```bash
source /home/valentin/projects/vwo-analyzer/.env && curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_VWO_BOT_TOKEN}/sendMessage" \
  -d chat_id="${TELEGRAM_VWO_CHAT_ID}" \
  -d parse_mode="Markdown" \
  -d disable_web_page_preview="true" \
  -d text="🔍 *VWO Spot-Check FAILED*

{summary of each failure with ticket ID, client, and what mismatched}

[Admin Report](https://admin.crolab.org)"
```

**If all PASS or WARN:**

```bash
source /home/valentin/projects/vwo-analyzer/.env && curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_VWO_BOT_TOKEN}/sendMessage" \
  -d chat_id="${TELEGRAM_VWO_CHAT_ID}" \
  -d parse_mode="Markdown" \
  -d disable_web_page_preview="true" \
  -d text="🔍 *VWO Spot-Check PASSED* — 3 clients verified ✅
inne: {verdict} | Baybella: {verdict} | vetsak: {verdict}
Tests: 3 | Actions: 1 | Browser: {tool used}"
```

### Clean Up

Close any tabs opened during the check:

**Playwriter:**
```js
await state.page.close()
```

**agent-browser:** No cleanup needed (session managed externally).

---

## Critical Rules

- **Browser cascade is MANDATORY** — Playwriter → agent-browser → Playwright. Never give up.
- **NEVER use `networkidle`** — always `domcontentloaded` + wait.
- **One action per Playwriter execute call** — observe → act → observe.
- **Reuse tabs** — check `context.pages()` before creating new ones. Close when done.
- **VWO dashboard redirect is normal** — retry up to 2 times with 8s wait.
- **Control is NEVER disabled** — skip disabled check for Control variation.
- **Screenshots at 1920x1080** — set viewport before every screenshot.
- **This is read-only** — never modify any data, only read and compare.
- **Stop at 1 test per client** — this is a spot-check, not a full audit.
- **Report which browser tool was used** — note in the summary if you fell back from Playwriter.
- **Early exit on API failure** — if Phase 0 fails for a client, skip browser phases for that client.
- **Use `hypertask` CLI for Hypertask lookups** — never MCP, never API.
- **Staleness is expected** — report data can be 1-4h old. WARN for data drift, FAIL only for structural bugs.
