---
name: vwo-disable-review
description: Review all running VWO tests across clients (inne, Baybella, vetsak) and execute disables + winner declarations against proper thresholds. Includes client-specific sales-metric safeguards. Use when asked to "review tests", "recommend disables", "declare winners", or "audit running tests".
trigger: vwo-disable-review
model: sonnet
---

# VWO Test Review & Action Routine

You are acting on live production data. A bad disable kills a winning variation; a premature winner declaration ships a losing change to all traffic. Follow the thresholds and client-specific rules below before you touch anything.

## Working Directory
`/home/valentin/projects/vwo-analyzer`

## Environment
```bash
cd /home/valentin/projects/vwo-analyzer && source venv/bin/activate && set -a && . ./.env && set +a
```

## The Routine

### 1. Pull every running test from D1

Run the review query via Bash. Load the latest `scrape_history.raw_json` per test, walk `variations`, flag:
- **Losers**: `improvement < 0` AND `visitors >= 200`
- **Winners**: `improvement > 0` AND `probability >= 90`

Present grouped by client. Mark severity: `⛔⛔` < -15%, `⛔` < -5%, `·` otherwise.

### 2. Apply thresholds BEFORE recommending anything

| Action | Minimum bar |
|---|---|
| **Disable variation** | `visitors >= 200` on that variation. Low ctrl conversions (<20) = flag as noise, don't auto-recommend |
| **Declare winner** | **≥ 250 conversions per variation**, ≥ 2 weeks running, probability > 95% holding across multiple scrapes |

Small samples make VWO's Bayesian probability unreliable (the prior is weak). **13 conversions on 100 visitors at 96% probability is not a winner** — don't present it as one. If you recommended a winner and the ticket had <100 conversions, you messed up.

### 3. Per-Client Rules

#### inne (account 996036, project 339)
- Primary metric: varies per test (usually behavioural — clicks/scrolls)
- Variations use **German names** ("Beobachten", "Entdecken") — show `vwo_name` next to badge in any output
- Sections: Test Running 1209 · Accepted→Implement 1373 · Done FAILED 2252 · Done WIN 2254

#### Baybella (account 1076517, project 394)
- Primary metric: **"Shopify Purchase Revenue AUD"** — IS a sales metric, so primary-based disable is safe
- Sections: Test is Running 1421 · Accepted→Implement 1422 · Done FAILED 1430 · Done WINNING 1635

#### vetsak (account 1191232, project 1443) — ⚠️ SPECIAL CASE

**vetsak's primary metric is often "Checkout Started" — a soft funnel metric, NOT revenue.** A variation can lose on clicks but win on actual paid orders. Never disable based on primary alone.

**The sales-metric safeguard is live in code** (`decisions.filter_sales_protected`, shipped v1.4.0, committed 88695aa). It cross-checks secondary metrics matching `paid order`, `revenue`, `purchase`, `sales`, `paid` (case-insensitive). Any variation positive on any such metric is excluded from disable.

**What this means for you:**
- When `abaction disable vetsak` runs, you'll see `WARNING: PROTECTED V1 on VETS-xxx — positive on sales metric(s): Conversion Rate (Paid Orders):+70.5%` — that's the gate firing correctly
- **In your own recommendations**, manually replicate this: for each vetsak loser, query `all_metrics` in `raw_json` and check secondary metrics for "Paid Orders", "Revenue Per Session", "Purchase". If positive on any → don't recommend disable, even if primary is -30%
- Sections: Test Running 3728 · Doing 3692 · Done WINNING 3693 · Done FAILED 3883

### 4. Present recommendations

**MANDATORY: always emit BOTH URLs for every test** — a Hypertask URL and a VWO URL. Never output a bare ticket number (`VETS-122`) — Valentin can't click it. URLs only.

- Hypertask: `https://app.hypertask.ai/detail/project-{pid}/{num}`
  - inne pid=339, Baybella pid=394, vetsak pid=1443
- VWO: pull directly from `tests.vwo_url` in D1 (shape: `https://app.vwo.com/#/test/ab/{id}/report?accountId={account}`)

Format (two-line-per-row tables are fine — readability over compactness):
```
## ⛔ Disable losing variations

**vetsak VETS-122 V2 "Experience vetsak your way"** — -9.2% primary, -28% Paid Orders, -20% RPS
Hypertask: https://app.hypertask.ai/detail/project-1443/122
VWO:       https://app.vwo.com/#/test/ab/70/report?accountId=1191232

## 🏆 Declare winners
(same two-line pattern — Hypertask URL + VWO URL, never just the ticket)
```

State which vetsak candidates were filtered out by the sales gate and why — also with both URLs.

### 5. Execute on confirmation

```bash
abaction disable <Client> --ticket <TICKET>                          # auto-finds negatives
abaction disable <Client> --ticket <TICKET> --variation V3           # pinpoint
abaction declare-winner <Client> --ticket <TICKET> --variation V6    # after threshold check
```

The sales-metric gate runs automatically inside `abaction disable` — no extra flag needed.

### 6. After every action, verify

```bash
python3 scrapers/api_scrape.py <client>          # refresh D1 post-action
```

Confirm in D1:
- Disabled variations show `is_disabled: true` in latest `raw_json`
- Winner ticket moved to Accepted→Implement in Hypertask
- Telegram VWO bot fired

### 7. Revert playbook (if you over-disabled)

Re-enable a variation via the API directly — no CLI command exists:

```python
from scrapers.backends.api_backend import disable_variation, _session_with_cookies
from config import get_account_id
import json, pathlib
account = get_account_id('vetsak')
cookies = json.loads((pathlib.Path.home()/f'.cache/vwo-analyzer/vwo-cookies-{account}.json').read_text())
sess = _session_with_cookies(cookies)
disable_variation(account, '<test_id>', <vwo_id>, cookies, session=sess, disabled=False)
```

Revert winner: `UPDATE tests SET status='running' WHERE id=<test_id>` in D1, then move Hypertask ticket back with `hypertask tasks update <TICKET> --section <running_id>`, delete the 🏆 comment via `hypertask comment delete <id>`.

## Failure Modes (learned the hard way)

1. **Declared BBAB-397 winner at 13 conversions** — probability 96% but sample too small. Always check absolute conversions, not just %.
2. **Auto-disabled VETS-134 V1** — lost on Checkout Started, but +69% on Revenue. The code-level safeguard now catches this; you must replicate the check in your *recommendations* before calling disable.
3. **Auto-disabled VETS-122 V2** — flat on Paid Orders, +31% on RPS. Same failure mode as above.

## Canonical Thresholds (don't lower these)

- Winner: ≥250 conv/variation · ≥14 days · prob >95% sustained
- Disable: ≥200 visitors on that variation · NOT positive on any sales metric
- Noise flag: ctrl conversions <20 or variation conversions <10 → lower confidence in all signals

## References
- Sales-metric gate: `decisions.py::filter_sales_protected`, `scrapers/run_action_cli.py::_run_via_api` (disable path)
- Primary metric lookup: `raw_json.primary_metric_name` (set by `api_backend.parse_test`)
- All metrics: `raw_json.all_metrics` — list of `{name, is_primary, variations}`
- Test schema & sections: `/home/valentin/projects/vwo-analyzer/CLAUDE.md`
