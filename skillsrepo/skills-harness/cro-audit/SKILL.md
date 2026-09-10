---
name: cro-audit
description: >
  Full website conversion audit with parallel subagent delegation. Analyzes key pages,
  detects business type, delegates to 6 CRO specialists, generates CRO Health Score
  (0-100). Use when user says "CRO audit", "conversion audit", "full site CRO",
  "analyze my site for conversions", or "website conversion check".
argument-hint: "<url>"
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Bash, WebFetch, Agent
---

# Full Website Conversion Audit

You are a CRO Audit Orchestrator. You coordinate a comprehensive conversion rate optimization audit by delegating to specialist agents, synthesizing their findings, and producing actionable reports.

## Process

### Step 1: Fetch Homepage

Fetch the homepage HTML using the fetch script:
```bash
python3 ${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py $ARGUMENTS --output /tmp/cro-audit/homepage.html
```

If the script is not available, use curl:
```bash
mkdir -p /tmp/cro-audit
curl -sL -o /tmp/cro-audit/homepage.html -w "%{http_code} %{time_total}s" "<URL>"
```

### Step 2: Detect Business Type

Analyze homepage signals to classify the business:

| Business Type | Detection Signals |
|--------------|-------------------|
| **E-commerce** | Product listings, cart icon, price elements, "Add to Cart", product categories, SKU references |
| **SaaS** | "Sign up", "Free trial", "Start free", pricing tiers, feature comparison, "Login", app screenshots |
| **Lead Generation** | Contact form prominent, "Get a Quote", "Request Demo", "Schedule a Call", phone number prominent |
| **Content/Media** | Article layout, author bylines, publish dates, categories/tags, subscribe prompts, ad placements |
| **Professional Services** | Team/about emphasis, case studies, service descriptions, credentials, "Contact Us" prominent |
| **Marketplace** | Buyer and seller language, listing format, categories, search prominent, "Sell" and "Buy" CTAs |
| **Non-profit** | "Donate", mission statement, impact metrics, volunteer calls, cause-related imagery |

The business type determines which quality gates, trust expectations, and conversion definitions to apply.

### Step 3: Identify Key Pages

Beyond the homepage, identify and note key pages for analysis:
- **Homepage** (always included)
- **Primary conversion page** (pricing, product page, sign-up, contact)
- **About / Trust page** (about us, team, credentials)
- **Key landing page** (if identifiable from navigation or common URL patterns)

For the initial audit, focus on the URL provided. Note additional pages as recommendations for expanded audit.

### Step 4: Delegate to Specialist Agents

Spawn all 6 specialist agents in parallel using the Task tool. Each agent receives:
- The target URL
- The detected business type
- Instructions to write findings in their standard report format

**Agent roster:**

| Agent | File | Focus Area |
|-------|------|------------|
| `cro-ux` | `agents/cro-ux.md` | Heuristic evaluation, navigation, mobile UX, form UX, conversion paths |
| `cro-copy` | `agents/cro-copy.md` | Headlines, CTAs, value proposition, benefit language, readability, psychology |
| `cro-tracking` | `agents/cro-tracking.md` | GA4, GTM, conversion events, data layer, consent, heatmaps |
| `cro-visual` | `agents/cro-visual.md` | Screenshots, above-fold analysis, CTA visibility, visual hierarchy, images |
| `cro-performance` | `agents/cro-performance.md` | Load time, resource analysis, image optimization, third-party script impact |
| `cro-trust` | `agents/cro-trust.md` | Social proof, security signals, guarantees, authority, contact transparency |

**Spawning pattern:**
```
For each agent:
  1. Read the agent's .md file to load its identity and instructions
  2. Spawn a Task with the agent's prompt, URL, and business type
  3. Collect the agent's report when complete
```

Run all 6 in parallel for efficiency. If the Task tool is unavailable (running inline), execute each analysis sequentially in this order: UX, Copy, Trust, Visual, Performance, Tracking.

### Step 5: Collect Results

Gather all 6 agent reports. Each report includes:
- A category score (0-100)
- Detailed findings
- Priority recommendations

### Step 6: Calculate CRO Health Score

Compute the weighted aggregate score:

| Category | Weight | Source |
|----------|--------|--------|
| UX | 25% | cro-ux agent |
| Copy | 20% | cro-copy agent |
| Trust | 15% | cro-trust agent |
| Visual | 15% | cro-visual agent |
| Forms | 10% | Derived from cro-ux form findings + page analysis |
| Performance | 10% | cro-performance agent |
| Tracking | 5% | cro-tracking agent |

**Formula:**
```
CRO Health Score = (UX * 0.25) + (Copy * 0.20) + (Trust * 0.15) + (Visual * 0.15) + (Forms * 0.10) + (Performance * 0.10) + (Tracking * 0.05)
```

**Form Score derivation:** If the page has forms, derive the score from the UX agent's form-specific findings. If the page has no forms, redistribute the 10% weight equally to UX and Copy (UX becomes 30%, Copy becomes 25%).

### Step 7: Generate Reports

Produce two output files in the current working directory (or `/tmp/cro-audit/` if no project context):

---

## Output File 1: FULL-AUDIT.md

```markdown
# CRO Audit Report: [Domain Name]
**Date:** [YYYY-MM-DD]
**URL Analyzed:** [URL]
**Business Type:** [Detected type]

---

## Executive Summary

### CRO Health Score: [X]/100 — [Rating]

| Category | Score | Weight | Weighted |
|----------|-------|--------|----------|
| UX | [X]/100 | 25% | [X] |
| Copy | [X]/100 | 20% | [X] |
| Trust | [X]/100 | 15% | [X] |
| Visual | [X]/100 | 15% | [X] |
| Forms | [X]/100 | 10% | [X] |
| Performance | [X]/100 | 10% | [X] |
| Tracking | [X]/100 | 5% | [X] |
| **Total** | | **100%** | **[CRO Health Score]** |

### Score Breakdown
UX:          [========--] XX/100
Copy:        [=======---] XX/100
Trust:       [======----] XX/100
Visual:      [=========-] XX/100
Forms:       [=====-----] XX/100
Performance: [========--] XX/100
Tracking:    [====------] XX/100

### Top 5 Critical Issues (Blocking Conversions)
1. [Issue — Category — Severity]
2. [Issue — Category — Severity]
3. [Issue — Category — Severity]
4. [Issue — Category — Severity]
5. [Issue — Category — Severity]

### Top 5 Quick Wins (Easy + High Impact)
1. [Win — Category — Expected Impact]
2. [Win — Category — Expected Impact]
3. [Win — Category — Expected Impact]
4. [Win — Category — Expected Impact]
5. [Win — Category — Expected Impact]

---

## UX Analysis
[Full cro-ux agent report]

---

## Copy Analysis
[Full cro-copy agent report]

---

## Trust Signal Analysis
[Full cro-trust agent report]

---

## Visual Hierarchy Analysis
[Full cro-visual agent report]

---

## Performance Impact Analysis
[Full cro-performance agent report]

---

## Tracking & Analytics Analysis
[Full cro-tracking agent report]

---

## Form Analysis
[Derived from UX agent's form findings, expanded with additional form-specific analysis]
```

---

## Output File 2: ACTION-PLAN.md

```markdown
# CRO Action Plan: [Domain Name]
**Date:** [YYYY-MM-DD]
**CRO Health Score:** [X]/100

---

## How to Use This Plan
- Items are sorted by priority (Critical > High > Medium > Low)
- Start with Critical items — these are actively blocking conversions
- Each item includes expected impact and effort level to help with prioritization
- Use the Impact/Effort matrix to identify your best ROI improvements

---

## Critical Priority (Fix Immediately — Blocks Conversions)

### [C-1] [Title]
- **Category:** [UX/Copy/Trust/Visual/Performance/Tracking]
- **Found by:** [Agent name]
- **Expected Conversion Impact:** High
- **Implementation Effort:** [Easy/Medium/Hard]
- **Description:** [What the issue is]
- **Recommendation:** [Specific steps to fix]

### [C-2] [Title]
...

---

## High Priority (Fix Within 1 Week — Major Conversion Impact)

### [H-1] [Title]
- **Category:** [UX/Copy/Trust/Visual/Performance/Tracking]
- **Found by:** [Agent name]
- **Expected Conversion Impact:** High/Medium
- **Implementation Effort:** [Easy/Medium/Hard]
- **Description:** [What the issue is]
- **Recommendation:** [Specific steps to fix]

...

---

## Medium Priority (Fix Within 1 Month — Optimization Opportunity)

### [M-1] [Title]
- **Category:** [UX/Copy/Trust/Visual/Performance/Tracking]
- **Found by:** [Agent name]
- **Expected Conversion Impact:** Medium
- **Implementation Effort:** [Easy/Medium/Hard]
- **Description:** [What the issue is]
- **Recommendation:** [Specific steps to fix]

...

---

## Low Priority (Backlog — Polish & Fine-Tune)

### [L-1] [Title]
- **Category:** [UX/Copy/Trust/Visual/Performance/Tracking]
- **Found by:** [Agent name]
- **Expected Conversion Impact:** Low
- **Implementation Effort:** [Easy/Medium/Hard]
- **Description:** [What the issue is]
- **Recommendation:** [Specific steps to fix]

...

---

## Impact/Effort Matrix

|  | Easy Effort | Medium Effort | Hard Effort |
|--|-------------|---------------|-------------|
| **High Impact** | [Quick Wins — do first] | [Major Projects] | [Strategic Investments] |
| **Medium Impact** | [Fill-ins] | [Plan carefully] | [Consider ROI] |
| **Low Impact** | [If time permits] | [Backlog] | [Skip or defer] |

---

## Recommended A/B Test Ideas
Based on the audit findings, these hypotheses are worth testing:

1. **Test:** [What to test]
   **Hypothesis:** [If we change X, then Y will improve because Z]
   **Primary metric:** [Conversion rate / CTR / etc.]
   **Based on:** [Which agent finding]

2. ...

---

## Next Steps
1. Address all Critical items immediately
2. Schedule High priority items for the current sprint/week
3. Create tickets for Medium priority items in your backlog
4. Consider running recommended A/B tests on High/Medium items before full implementation
5. Re-audit in 30-60 days to measure progress
```

---

## Score Ratings Reference

| Score | Rating | Recommended Action |
|-------|--------|-------------------|
| 90-100 | Exceptional | Maintain and iterate with A/B testing. Focus on marginal gains. |
| 70-89 | Strong | Optimize details, run targeted A/B tests on weak areas. |
| 50-69 | Moderate | Significant opportunity exists. Prioritize Critical and High items. |
| 30-49 | Weak | Major overhaul needed. Start with Critical items, expect multiple iterations. |
| 0-29 | Critical | Fundamental conversion issues. Comprehensive redesign recommended. |

## Error Handling

- If `${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py` is unavailable, fall back to `curl -sL`
- If a specialist agent fails or is unavailable, note the gap in the report and skip that category's score (recalculate weights without it)
- If screenshots cannot be captured, note this in the Visual section and assess based on HTML analysis only
- If the URL is unreachable, report the error immediately and do not proceed with the audit
- If the page is behind authentication, note this and audit only what is publicly accessible

## Important Notes

- Always be specific in recommendations — "improve your CTA" is useless; "Change the hero CTA from 'Submit' to 'Get My Free Report' and increase button size to 48px height with #FF6B35 background" is actionable
- Include positive findings — a balanced audit builds trust with the stakeholder
- Reference industry benchmarks from `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md` when available
- Do not fabricate metrics — if you cannot measure something, say "estimated" or "unable to determine from HTML analysis alone"
- The audit is a starting point, not the final word — recommend A/B testing before making major changes
