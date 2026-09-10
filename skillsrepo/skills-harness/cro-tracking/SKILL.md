---
name: cro-tracking
description: >
  Analytics and tracking setup validation checking GA4/GTM presence, conversion
  event configuration, enhanced e-commerce tracking, consent management, and
  data layer quality. Use when user says "tracking audit", "analytics check",
  "GA4 setup", "GTM audit", "conversion tracking", or "event tracking".
argument-hint: "<url>"
allowed-tools: Read, Grep, Glob, Bash, WebFetch
---

# CRO Tracking Audit

Validate the analytics and tracking infrastructure required for effective
conversion rate optimization. Without proper tracking, you cannot measure
baselines, run A/B tests, or verify improvements. This skill audits the
complete tracking stack from tag management to consent compliance.

---

## Process

1. **Fetch the page** using `${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py`. Store the raw HTML,
   paying special attention to `<head>` and early `<body>` content where
   tracking scripts are typically placed.
2. **Extract tracking elements** using `${CLAUDE_SKILL_DIR}/../cro/scripts/parse_cro.py`. This detects
   analytics platforms, tag managers, data layer objects, consent banners,
   pixel scripts, and heatmap tools.
3. **Validate tag placement** -- check that scripts are in the correct
   location (GTM in head + body, GA4 configuration, consent mode initialization
   before tags).
4. **Inspect the data layer** -- look for `window.dataLayer` pushes, custom
   variables, event structures, and naming conventions.
5. **Check consent implementation** -- verify consent banner presence, Google
   Consent Mode v2 integration, and tag firing conditions.
6. **Score the tracking readiness** and generate the audit report with an
   implementation checklist for missing elements.

---

## Analysis Sections

### 1. Analytics Platform Detection

Identify all analytics and measurement platforms present on the page.

| Platform | Detection Method | What to Record |
|----------|-----------------|----------------|
| Google Analytics 4 (GA4) | `gtag('config', 'G-XXXXXXX')` or GTM GA4 config tag | Measurement ID, configuration method (gtag.js or GTM) |
| Google Tag Manager (GTM) | `googletagmanager.com/gtm.js` container snippet | Container ID (GTM-XXXXXXX), head + body snippets present? |
| Google Analytics UA (legacy) | `ua-` tracking ID, `analytics.js` | Flag as deprecated -- should migrate to GA4 |
| Adobe Analytics | `s_code.js`, `AppMeasurement.js`, `alloy.js` (Web SDK) | Implementation type, report suite ID |
| Mixpanel | `mixpanel.init()`, `cdn.mxpnl.com` | Project token |
| Amplitude | `amplitude.getInstance().init()` | API key |
| Heap | `heap.load()`, `cdn.heapanalytics.com` | Environment ID |
| Segment | `analytics.load()`, `cdn.segment.com` | Write key |
| Hotjar | `hj('init')`, `static.hotjar.com` | Site ID |
| Microsoft Clarity | `clarity('set')`, `clarity.ms` | Project ID |
| Meta Pixel | `fbq('init')`, `connect.facebook.net` | Pixel ID |
| Google Ads | `gtag('config', 'AW-')`, conversion linker | Conversion ID |
| TikTok Pixel | `ttq.load()` | Pixel ID |
| LinkedIn Insight | `snap.licdn.com`, `_linkedin_partner_id` | Partner ID |
| Pinterest Tag | `pintrk('load')` | Tag ID |

**Duplicate tracking check:** Flag if the same platform appears multiple times
(e.g., two GA4 measurement IDs, two GTM containers). This causes inflated
data.

### 2. Tag Manager Audit

If GTM (or another tag manager) is present, evaluate its implementation.

| Criterion | What to Check |
|-----------|---------------|
| Head snippet | GTM `<script>` tag in `<head>` as high as possible (after `<meta charset>`)? |
| Body snippet | GTM `<noscript>` tag immediately after opening `<body>`? |
| Single container | Only one GTM container per page? Multiple containers cause conflicts. |
| Loading method | Is GTM loaded async? Not render-blocking? |
| Container version | Is the container using a published version (not preview/debug mode in production)? |
| Consent integration | Does GTM respect consent signals before firing marketing tags? |
| Custom HTML tags | Any inline `<script>` tags that should be in GTM instead? |

**GTM Health Check:**

| Status | Meaning |
|--------|---------|
| Properly installed | Both snippets present, correct positions, single container |
| Partially installed | Missing body snippet or incorrect position |
| Misconfigured | Multiple containers, wrong placement, or debug mode in production |
| Not installed | No GTM detected |

### 3. Conversion Tracking

Evaluate whether key conversion events are being tracked.

| Event Category | Events to Look For |
|----------------|--------------------|
| **Purchase** | `purchase`, `transaction`, revenue value, order ID, items array |
| **Lead generation** | `generate_lead`, `form_submit`, `contact_form`, lead value |
| **Sign-up** | `sign_up`, `create_account`, `registration`, method |
| **Add to cart** | `add_to_cart`, item details, value, currency |
| **Begin checkout** | `begin_checkout`, cart value, item count |
| **Checkout steps** | `add_shipping_info`, `add_payment_info` |
| **Search** | `search`, `view_search_results`, search term |
| **Content engagement** | `scroll`, `video_start`, `video_complete`, `file_download` |
| **Phone calls** | Click-to-call tracking, call tracking number swap |
| **Chat initiation** | Live chat open events, chatbot interaction starts |

**Event naming audit:**

| Convention | Assessment |
|------------|------------|
| GA4 recommended events | Using Google's standard event names? (Better for reports/audiences) |
| Custom event naming | Consistent naming convention? (snake_case recommended) |
| Parameter naming | Standard parameter names (value, currency, items)? |
| Event deduplication | Any risk of duplicate event firing? |

### 4. Enhanced E-commerce

For e-commerce sites, verify the complete enhanced e-commerce tracking funnel.

| Event | dataLayer Push | Required Parameters |
|-------|----------------|---------------------|
| `view_item_list` | Product impressions on category/search pages | items[], item_list_id, item_list_name |
| `select_item` | Product click from a list | items[], item_list_id, item_list_name |
| `view_item` | Product detail page view | items[], value, currency |
| `add_to_cart` | Item added to cart | items[], value, currency |
| `remove_from_cart` | Item removed from cart | items[], value, currency |
| `view_cart` | Cart page viewed | items[], value, currency |
| `begin_checkout` | Checkout initiated | items[], value, currency, coupon |
| `add_shipping_info` | Shipping step completed | items[], shipping_tier, value |
| `add_payment_info` | Payment step completed | items[], payment_type, value |
| `purchase` | Transaction completed | transaction_id, items[], value, currency, tax, shipping |
| `refund` | Order refunded (server-side) | transaction_id, items[] (partial) or value (full) |
| `view_promotion` | Promotion banner impression | promotion_id, promotion_name, creative_name |
| `select_promotion` | Promotion banner click | promotion_id, promotion_name, creative_name |

**E-commerce tracking completeness:**

| Level | Events Present |
|-------|----------------|
| Complete | All events above are firing with correct parameters |
| Mostly complete | Purchase + add_to_cart + checkout events present, some gaps |
| Partial | Only purchase event or only page views with e-commerce |
| Minimal | Basic page views only, no e-commerce events |
| None | No e-commerce tracking detected |

### 5. Data Layer Quality

The data layer is the bridge between the website and tag management.

| Criterion | What to Check |
|-----------|---------------|
| dataLayer exists | Is `window.dataLayer` initialized before GTM loads? |
| Initialization timing | Is `dataLayer` defined before the GTM snippet, not after? |
| Structure consistency | Are dataLayer pushes using consistent object structures? |
| Required variables | Page type, user login status, page category populated? |
| E-commerce variables | Product IDs, prices, quantities in correct format? |
| Custom dimensions | User properties: customer type, membership level, segment? |
| User ID | Hashed user ID passed for cross-device tracking (if logged in)? |
| Naming conventions | Consistent casing and naming across all variables? |
| Data freshness | Are values dynamic and accurate, or hardcoded/stale? |
| Array formatting | Items arrays properly structured with required fields? |

### 6. Consent Management

GDPR, CCPA, and other privacy regulations require proper consent handling.

| Criterion | What to Check |
|-----------|---------------|
| Consent banner | Cookie/consent banner present on first visit? |
| Banner design | Clear accept/reject options? Not a dark pattern (giant Accept, tiny Reject)? |
| Consent categories | Analytics, marketing, functional cookies separated? |
| Google Consent Mode v2 | `gtag('consent', 'default', {...})` present? Both `ad_storage` and `analytics_storage`? |
| Advanced consent signals | `ad_user_data` and `ad_personalization` parameters set? (Required since March 2024) |
| Pre-consent behavior | Are marketing tags blocked before consent? Is only essential functionality running? |
| Consent persistence | Is consent choice stored and respected on return visits? |
| Consent revocation | Can users change their consent choice after initial selection? |
| CMP platform | OneTrust, Cookiebot, CookieYes, Usercentrics, or custom? |
| GPC signal | Is Global Privacy Control (GPC) browser signal respected? |
| Region detection | Different consent behavior for EU vs US visitors? |

**Consent Mode v2 status:**

| Status | Meaning |
|--------|---------|
| Fully implemented | Default consent state set, CMP updates consent, all tags respect signals |
| Partially implemented | Consent mode present but missing advanced signals or CMP integration |
| Legacy implementation | Cookie banner exists but no Consent Mode integration |
| Non-compliant | No consent mechanism detected, tags fire without consent |

### 7. Heatmap and Session Recording

Behavioral analytics tools provide qualitative data essential for CRO.

| Criterion | What to Check |
|-----------|---------------|
| Tool detected | Hotjar, Microsoft Clarity, FullStory, Lucky Orange, Mouseflow? |
| Recording consent | Does session recording respect user consent? |
| Sampling rate | Is a sampling configuration visible? (Full recording = storage cost) |
| Form field masking | Are sensitive fields (passwords, credit cards) automatically masked? |
| Heatmap pages | Are heatmaps configured for key conversion pages? |
| Rage click detection | Does the tool detect frustration signals (rage clicks, dead clicks)? |
| Funnel analysis | Is the behavioral tool configured to track key funnels? |

### 8. Missing Tracking Recommendations

Based on the audit, identify what is missing and prioritize implementation.

| Priority | Category | Recommendation Format |
|----------|----------|----------------------|
| P0 - Critical | Conversion events not tracked at all | Cannot measure ROI or run tests without this |
| P1 - High | Key funnel steps untracked | Blind spots in the conversion journey |
| P2 - Medium | Enhanced features missing | Behavioral data, audiences, or attribution gaps |
| P3 - Low | Nice-to-have improvements | Advanced tracking, micro-events, enrichments |

---

## Scoring

### Tracking Readiness Score Calculation

| Component | Weight | What it Measures |
|-----------|--------|------------------|
| Analytics Platform | 20% | GA4 or equivalent properly installed and configured |
| Tag Management | 15% | GTM or equivalent correctly implemented |
| Conversion Events | 25% | Key conversion events tracked with correct parameters |
| Enhanced E-commerce | 15% | Full e-commerce event funnel (if applicable, else redistributed) |
| Data Layer Quality | 10% | Structured, consistent, complete data layer |
| Consent Management | 10% | Privacy-compliant consent implementation |
| Behavioral Analytics | 5% | Heatmap/recording tools for qualitative insight |
| **Total** | **100%** | |

If enhanced e-commerce is not applicable (non-e-commerce site), redistribute
the 15% weight: 10% to Conversion Events, 5% to Data Layer Quality.

### Score Interpretation

| Score | Rating | Meaning |
|-------|--------|---------|
| 90-100 | CRO-Ready | Comprehensive tracking. Ready for advanced testing and optimization. |
| 70-89 | Mostly Ready | Good foundation with some gaps. Can run basic tests. |
| 50-69 | Partial Coverage | Significant tracking gaps. Limited ability to measure CRO impact. |
| 30-49 | Insufficient | Major tracking deficiencies. Must fix before starting CRO program. |
| 0-29 | No Foundation | Tracking is absent or fundamentally broken. Start from scratch. |

---

## Output Format

```markdown
## CRO Tracking Audit: [URL]

**Tracking Readiness Score:** [Score]/100 -- [Rating]
**Analytics Platform:** [GA4/Adobe/Other] via [gtag.js/GTM/Other]
**Tag Manager:** [GTM Container ID / None]
**Consent Management:** [CMP Name / None]
**Date:** [Current date]

### Executive Summary
[2-3 sentences: overall tracking health, biggest gap, most urgent action]

### Platforms Detected

| Platform | ID | Status |
|----------|----|--------|
| [Platform name] | [ID] | [OK/Issue/Duplicate] |
| ... | ... | ... |

### Score Breakdown

| Component | Score | Rating | Key Issue |
|-----------|-------|--------|-----------|
| Analytics Platform | /100 | | |
| Tag Management | /100 | | |
| Conversion Events | /100 | | |
| Enhanced E-commerce | /100 | | |
| Data Layer Quality | /100 | | |
| Consent Management | /100 | | |
| Behavioral Analytics | /100 | | |

### Conversion Event Audit

| Event | Status | Notes |
|-------|--------|-------|
| purchase | [Detected/Missing/Partial] | [Details] |
| add_to_cart | [Detected/Missing/Partial] | [Details] |
| begin_checkout | [Detected/Missing/Partial] | [Details] |
| generate_lead | [Detected/Missing/Partial] | [Details] |
| sign_up | [Detected/Missing/Partial] | [Details] |
| ... | ... | ... |

### Consent Mode Status
[Detailed consent implementation findings]

### Critical Issues
- [P0] [Finding] -- [Impact on CRO ability]
- ...

### Implementation Checklist

- [ ] [P0] [Specific implementation task]
- [ ] [P0] [Task...]
- [ ] [P1] [Task...]
- [ ] [P2] [Task...]
- [ ] [P3] [Task...]

### Detailed Findings

#### Analytics Platform
[Findings...]

#### Tag Management
[Findings...]

#### Conversion Events
[Findings...]

#### Enhanced E-commerce
[Findings...]

#### Data Layer
[Findings...]

#### Consent Management
[Findings...]

#### Behavioral Analytics
[Findings...]

### GTM Implementation Guide (if GTM is missing or misconfigured)
[Step-by-step setup instructions tailored to the site's platform]
```

---

## Cross-References

- **Full page audit:** Use the `cro-page` sub-skill for the complete conversion analysis (tracking is one dimension)
- **E-commerce tracking:** Use the `cro-ecommerce` sub-skill for shopping-specific conversion patterns
- **Funnel analysis:** Use the `cro-funnel` sub-skill -- proper funnel tracking is a prerequisite
- **Test planning:** Use the `cro-testing` sub-skill -- A/B testing requires solid tracking infrastructure
- **Quality gates:** Read `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md` for minimum tracking requirements per business type
- **Benchmarks:** Read `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md` for baseline metrics to measure against
