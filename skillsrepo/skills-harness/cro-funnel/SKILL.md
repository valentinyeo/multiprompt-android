---
name: cro-funnel
description: >
  Multi-step conversion funnel mapping and optimization. Identifies drop-off
  risks, friction points, missing micro-conversions, and navigation leaks across
  page sequences. Use when user says "funnel analysis", "conversion funnel",
  "checkout flow", "user journey", "drop-off", or "funnel optimization".
argument-hint: "<url1> <url2> [url3...]"
allowed-tools: Read, Grep, Glob, Bash, WebFetch
---

# CRO Funnel Analysis

Map and optimize multi-step conversion funnels by analyzing each page in the
sequence, the transitions between them, and the overall journey from entry to
conversion. This skill identifies where users drop off, why they leave, and
what changes will recover the most lost conversions.

---

## Process

1. **Accept funnel URLs** -- the user provides 2 or more URLs representing
   sequential steps in a conversion funnel (e.g., landing page -> product page
   -> cart -> checkout -> confirmation). If the user provides a single URL,
   attempt to discover subsequent steps by following primary CTAs.
2. **Fetch each page** using `${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py`. Store the HTML for every
   step in the funnel.
3. **Extract CRO elements** from each page using `${CLAUDE_SKILL_DIR}/../cro/scripts/parse_cro.py`. Pay
   special attention to CTAs, navigation links, exit points, and form elements.
4. **Take screenshots** of each step if Playwright is available (desktop at
   1440px and mobile at 375px). Screenshots help identify visual consistency
   breaks and CTA prominence gaps between steps.
5. **Analyze the funnel** using `${CLAUDE_SKILL_DIR}/../cro/scripts/analyze_funnel.py`. This maps
   step-to-step transitions, calculates risk scores, and identifies leaks.
6. **Cross-reference benchmarks** by reading `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md`
   for expected conversion rates at each step type.
7. **Generate the report** with a visual funnel map, per-step findings, and
   prioritized recommendations.

---

## Analysis Sections

### 1. Funnel Map

Create a visual text representation of the complete funnel.

```
[Step 1: Landing Page] --84%--> [Step 2: Product Page] --32%--> [Step 3: Cart]
    |                               |                              |
    v (16% exit)                    v (68% exit)                   v (55% exit)
    - Nav links: 12                 - Nav links: 8                 - Continue shopping
    - Footer links: 18             - Related products: 6           - Promo code friction
    - No exit intent               - No exit intent                - Shipping surprise
```

For each step, document:

| Element | What to Record |
|---------|----------------|
| Step name | Descriptive label for the funnel stage |
| URL | Page URL |
| Primary purpose | What this step should accomplish |
| Entry CTA | The CTA on the previous page that brings users here |
| Exit CTA | The primary CTA leading to the next step |
| Exit points | All links, buttons, and navigation elements that lead away from the funnel |
| Expected conversion | Industry benchmark for this step type (reference conversion-benchmarks.md) |

### 2. Step-by-Step Analysis

For each funnel step, evaluate:

| Criterion | What to Check |
|-----------|---------------|
| Purpose clarity | Does the visitor immediately understand what this step is for and what to do next? |
| CTA to next step | Is the primary CTA to the next step prominent, clear, and compelling? |
| Friction points | What makes this step hard, confusing, or slow? Unnecessary fields, unclear copy, loading delays? |
| Cognitive load | Is the visitor asked to process too much information? Too many choices? Decision fatigue? |
| Trust signals | Are trust signals appropriate for this stage? (Early: social proof. Late: security/guarantee.) |
| Value reinforcement | Does the page remind the visitor WHY they are here and what they will get? |
| Error handling | If the visitor makes a mistake, is recovery easy and clear? |
| Progress indication | Does the visitor know where they are in the process and how many steps remain? |
| Momentum | Does the page create forward momentum or does it slow the visitor down? |

### 3. Drop-Off Risk Assessment

Rate each transition between steps:

| Risk Level | Criteria |
|------------|----------|
| **Low** (0-25%) | Clear next step, minimal distractions, strong momentum, trust maintained |
| **Medium** (25-50%) | Some friction present, minor distractions, adequate but not compelling transition |
| **High** (50-75%) | Significant friction, multiple exit points, trust gaps, confusing next step |
| **Critical** (75%+) | Major barrier: surprise costs, account wall, broken flow, missing information |

For each transition, document:
- The specific risk factors driving the rating
- The estimated percentage of users likely to drop off
- The primary reason for drop-off (friction, distraction, confusion, trust, surprise)

### 4. Navigation Leaks

Navigation leaks are links and elements that pull users OUT of the funnel.

| Leak Type | What to Check |
|-----------|---------------|
| Header navigation | Full site nav visible during checkout? Link count in header? |
| Footer links | Full footer present on conversion pages? Social media links? |
| Sidebar content | Related articles, ads, or promotions competing with the funnel? |
| Logo link | Does the logo link to homepage mid-funnel? (Common leak) |
| Breadcrumbs | Do breadcrumbs encourage backward navigation? |
| Cross-sell overload | Are product recommendations pulling attention from checkout completion? |
| External links | Any links to external sites (social, review platforms, partners)? |
| Chat widgets | Is live chat pulling focus from form completion? |

**Leak Score:** Count total exit links per step. Fewer than 3 on checkout pages is ideal.
Late-funnel pages (cart, checkout) should progressively REMOVE navigation elements.

### 5. Micro-Conversions

Micro-conversions are intermediate engagement signals that indicate progress
and build commitment.

| Criterion | What to Check |
|-----------|---------------|
| Progress indicators | Numbered steps, progress bars, "Step 2 of 4" labels? |
| Engagement triggers | Email capture, account creation, wishlist, save-for-later? |
| Value reinforcement | Order summary sidebar, product preview, benefit reminders? |
| Commitment escalation | Small yeses before big yeses? (e.g., quiz -> email -> trial -> purchase) |
| Intermediate confirmations | "Great choice!" messages, selection summaries, validation feedback? |
| Save and resume | Can the user leave and come back? Is progress saved? |

### 6. Consistency Check

Inconsistencies between funnel steps erode trust and increase cognitive load.

| Criterion | What to Check |
|-----------|---------------|
| Visual consistency | Same color scheme, fonts, button styles across all steps? |
| Messaging consistency | Same language, tone, and terminology? Promise on step 1 matches delivery on step 3? |
| Price consistency | Price shown on product page matches cart, matches checkout? No surprise fees? |
| Brand consistency | Logo, brand colors, and brand voice maintained throughout? |
| Trust signal continuity | Trust badges present on early AND late pages? No trust gap mid-funnel? |
| CTA style consistency | Primary button style consistent so users always know what to click? |

### 7. Mobile Funnel

Mobile conversion funnels face unique challenges.

| Criterion | What to Check |
|-----------|---------------|
| Step length | Are mobile steps shorter? Long forms should be split into multiple steps on mobile. |
| Thumb-friendly CTAs | Are all primary CTAs in the thumb zone? Large enough for easy tapping? |
| Keyboard optimization | Correct input types (numeric keyboard for phone/zip, email keyboard for email)? |
| Scroll depth | How far must a mobile user scroll to find the next-step CTA on each page? |
| Sticky elements | Is the primary CTA sticky/fixed on mobile so it is always accessible? |
| Autofill support | Do forms support autofill for addresses, payment, and personal info? |
| Mobile payment | Apple Pay, Google Pay, or other one-tap payment options available? |

### 8. Recovery Mechanisms

What happens when a user abandons the funnel mid-way?

| Criterion | What to Check |
|-----------|---------------|
| Cart abandonment email | Is there an automated email sequence for cart abandoners? Timing and content? |
| Exit intent popup | Does an exit-intent overlay trigger on desktop when cursor moves to close? |
| Save progress | Is the cart or form state saved if the user leaves and returns? |
| Retargeting pixels | Are retargeting pixels (Meta, Google) firing for funnel abandoners? |
| Reminder sequences | SMS, push notifications, or email reminders for incomplete funnels? |
| Incentive offers | Does the recovery mechanism include a discount or urgency element? |
| Browser notifications | Web push opt-in for re-engagement? |
| Session persistence | Does the funnel state persist across devices (if user is logged in)? |

### 9. Funnel Benchmarks

Reference `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md` for expected rates, then
compare:

| Step Type | Typical Conversion | Top Performers |
|-----------|--------------------|----------------|
| Landing -> Product | 40-60% | 70%+ |
| Product -> Add to Cart | 8-12% | 15%+ |
| Add to Cart -> Checkout | 30-50% | 60%+ |
| Checkout -> Purchase | 45-65% | 75%+ |
| Overall (Landing -> Purchase) | 1-3% | 5%+ |
| Lead form landing -> Submit | 10-25% | 35%+ |
| SaaS homepage -> Trial signup | 3-7% | 10%+ |

---

## Scoring

### Funnel Health Score Calculation

| Component | Weight | What it Measures |
|-----------|--------|------------------|
| Step Clarity | 15% | How clear each step's purpose and next action are |
| CTA Effectiveness | 15% | Prominence, copy, and placement of forward CTAs |
| Friction Level | 20% | Cognitive load, form complexity, surprise elements |
| Navigation Leak Control | 15% | How well exit points are minimized in late stages |
| Consistency | 10% | Visual, messaging, and pricing consistency across steps |
| Mobile Funnel Quality | 10% | Mobile-specific funnel usability |
| Recovery Mechanisms | 10% | Abandonment recovery systems in place |
| Micro-Conversions | 5% | Intermediate engagement and progress signals |
| **Total** | **100%** | |

**Per-Step Scores:** Each step also receives an individual score (0-100) based
on its clarity, CTA effectiveness, friction level, and leak count.

**Overall Drop-Off Risk Rating:**

| Rating | Meaning |
|--------|---------|
| Low | Funnel is well-optimized. Expected conversion within top-performer range. |
| Medium | Noticeable friction points. 20-40% improvement potential. |
| High | Major leaks or barriers. 50%+ improvement potential. |
| Critical | Fundamental funnel design issues. Significant restructuring recommended. |

---

## Output Format

```markdown
## CRO Funnel Analysis: [Funnel Name]

**Steps Analyzed:** [Count]
**Funnel Health Score:** [Score]/100 -- [Rating]
**Overall Drop-Off Risk:** [Low/Medium/High/Critical]
**Date:** [Current date]

### Executive Summary
[2-3 sentences: strongest and weakest transitions, biggest opportunity]

### Funnel Map
[Visual text funnel map as described in Section 1]

### Per-Step Scores

| Step | URL | Score | Drop-Off Risk | Primary Issue |
|------|-----|-------|---------------|---------------|
| 1. [Name] | [URL] | /100 | [Risk] | [Issue] |
| 2. [Name] | [URL] | /100 | [Risk] | [Issue] |
| ... | ... | ... | ... | ... |

### Critical Drop-Off Points
- [P0] [Step X -> Step Y]: [Finding] -- [Root cause]
- ...

### Navigation Leak Report

| Step | Total Exit Links | Recommendation |
|------|-----------------|----------------|
| [Name] | [Count] | [Action] |
| ... | ... | ... |

### Detailed Step Analysis

#### Step 1: [Name]
[Detailed findings for this step]

#### Step 2: [Name]
[Detailed findings...]

[... repeat for each step ...]

### Recommendations (Prioritized)

1. **[Critical]** [Action item targeting the highest-impact drop-off point]
2. **[High]** [Action item...]
3. **[Medium]** [Action item...]
4. **[Low]** [Action item...]

### Recovery Mechanism Checklist
- [ ] Cart abandonment email configured
- [ ] Exit intent popup on checkout pages
- [ ] Progress saved for returning visitors
- [ ] Retargeting pixels firing on funnel pages
- [ ] Reminder sequence for incomplete signups

### Suggested A/B Tests

| Test | Funnel Step | Hypothesis | Expected Impact | ICE Score |
|------|-------------|-----------|-----------------|-----------|
| [Name] | [Step] | If we [change], then [metric] will [improve] because [reason] | [Est. %] | [Score] |
```

---

## Cross-References

- **Individual page deep-dive:** Use the `cro-page` sub-skill for a detailed audit of any single funnel step
- **Form optimization:** Use the `cro-forms` sub-skill for granular form analysis on checkout or lead capture steps
- **E-commerce specifics:** Use the `cro-ecommerce` sub-skill for product page, cart, and checkout-specific patterns
- **Copy evaluation:** Use the `cro-copy` sub-skill to audit the persuasive copy across funnel steps
- **Trust continuity:** Use the `cro-trust` sub-skill to evaluate trust signal placement across the journey
- **Test planning:** Use the `cro-testing` sub-skill to generate full A/B test briefs for funnel experiments
- **Benchmarks:** Read `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md` for industry-specific funnel conversion data
- **Psychology:** Read `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md` for commitment and consistency principles relevant to funnels
