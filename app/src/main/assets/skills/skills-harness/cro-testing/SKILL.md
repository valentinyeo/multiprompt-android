---
name: cro-testing
description: >
  A/B test hypothesis generation and prioritization using ICE framework
  (Impact x Confidence x Ease). Generates complete test briefs with control
  and variant descriptions. Use when user says "A/B test", "split test",
  "test ideas", "hypothesis", "experiment", or "what should I test".
argument-hint: "<url>"
allowed-tools: Read, Grep, Glob, Bash, WebFetch
---

# CRO Testing -- A/B Test Hypothesis Generation

Generate, prioritize, and document A/B test hypotheses for any page or funnel.
Uses the ICE framework to rank opportunities by expected ROI. Produces
ready-to-implement test briefs with control/variant descriptions, traffic
estimates, and duration calculations.

---

## Process

1. **Fetch and analyze the page** using `${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py` and
   `${CLAUDE_SKILL_DIR}/../cro/scripts/parse_cro.py`. Extract all conversion-relevant elements.
2. **Identify conversion issues and opportunities** by evaluating the page
   against CRO best practices. Look for gaps in copy, UX, trust, forms, and
   visual hierarchy.
3. **Load the testing framework** by reading `${CLAUDE_SKILL_DIR}/../cro/references/testing-framework.md`.
   This provides hypothesis structure, ICE scoring guidance, sample size
   calculators, and MDE (Minimum Detectable Effect) reference tables.
4. **Generate hypotheses** for each opportunity. Use the structured hypothesis
   format (below).
5. **Score each hypothesis** using the ICE framework.
6. **Sort by ICE score** (descending) and categorize into Quick Wins, Strategic
   Bets, and Avoid.
7. **Create detailed test briefs** for the top 5-10 hypotheses.
8. **Generate the TEST-PLAN.md** output with the full prioritized backlog.

---

## Hypothesis Format

Every hypothesis MUST follow this structure:

```
If we [CHANGE — specific, actionable change to a specific element],
then [METRIC — primary metric] will [DIRECTION — increase/decrease] by [ESTIMATE — percentage or range],
because [REASON — psychological principle, data point, or best practice that supports this].
```

**Good example:**
> If we change the CTA text from "Submit" to "Get My Free Quote" on the
> contact form, then form completion rate will increase by 10-20%, because
> specific, benefit-oriented CTA text reduces uncertainty about what happens
> after clicking (Clarity Principle) and first-person language increases
> ownership.

**Bad example:**
> If we improve the CTA, conversions will go up.

The bad example is too vague. It does not specify WHAT changes, by HOW MUCH,
or WHY it would work.

---

## ICE Framework

Score each hypothesis on three dimensions (1-10 scale):

### Impact (1-10)

How much will this move the primary conversion metric if the variant wins?

| Score | Definition | Example |
|-------|------------|---------|
| 9-10 | Transformative | Redesigning the entire above-the-fold section |
| 7-8 | High | Rewriting the headline and value proposition |
| 5-6 | Moderate | Changing CTA button text and color |
| 3-4 | Low | Adding a trust badge near the CTA |
| 1-2 | Minimal | Changing font size or minor spacing |

**Guidance:** Impact depends on how many visitors see the element AND how
central it is to the conversion decision. Above-the-fold headline changes
impact nearly 100% of visitors. A footer change impacts only the 10% who
scroll that far.

### Confidence (1-10)

How sure are we that this change will produce a positive result?

| Score | Definition | Basis |
|-------|------------|-------|
| 9-10 | Near certain | Multiple case studies showing consistent results for this exact change |
| 7-8 | High | Strong theoretical basis + some case study evidence |
| 5-6 | Moderate | Established best practice but no direct evidence for this context |
| 3-4 | Low | Logical reasoning but no supporting data |
| 1-2 | Speculative | Gut feeling, novel idea, no precedent |

**Guidance:** Base confidence on:
- Published case studies with similar changes
- Psychology principles from `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md`
- Industry benchmarks from `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md`
- Heuristic evaluation findings
- Quantitative data (if available from analytics)

### Ease (1-10)

How easy is this to implement and deploy?

| Score | Definition | Effort |
|-------|------------|--------|
| 9-10 | Trivial | Text change, color change, hide/show element. < 1 hour. |
| 7-8 | Easy | Copy rewrite, button redesign, add trust badge. < 4 hours. |
| 5-6 | Moderate | Layout change, new section, form restructure. 1-2 days. |
| 3-4 | Hard | New page design, multi-step form, dynamic content. 3-5 days. |
| 1-2 | Very hard | Full redesign, backend changes, new functionality. 1+ weeks. |

### ICE Priority Score

```
Priority = (Impact + Confidence + Ease) / 3
```

| Priority Score | Category |
|---------------|----------|
| 7.0+ | Quick Win -- implement first |
| 5.0-6.9 | Standard Test -- plan and schedule |
| 3.0-4.9 | Strategic Bet -- high risk, potentially high reward |
| < 3.0 | Avoid -- not worth the effort |

---

## Test Categories

### Quick Wins (ICE >= 7.0)

Changes that are easy to implement, likely to win, and have meaningful impact.
These should be tested FIRST.

**Common quick wins:**
- CTA button text changes (generic -> specific, benefit-oriented)
- Headline rewrites (clearer value proposition)
- Adding trust badges near conversion points
- Removing unnecessary form fields
- Adding urgency/scarcity elements
- Improving above-the-fold clarity

### Strategic Bets (High Impact, Lower Confidence)

Bigger changes that could produce outsized results but carry more uncertainty.
Worth testing after quick wins are exhausted.

**Common strategic bets:**
- Complete above-the-fold redesign
- Single-step to multi-step form conversion
- Long-form vs short-form landing page
- Video vs static hero
- Social proof layout redesign
- Pricing page restructure

### Avoid List (ICE < 3.0)

Tests not worth running. Either impact is too small, confidence is too low,
or implementation is too complex relative to expected gain.

**Common avoid tests:**
- Button color changes in isolation (too small impact)
- Minor copy tweaks on low-traffic pages (insufficient sample size)
- Changes requiring custom backend development for marginal gains
- Tests on pages with < 1,000 monthly visitors (will take too long)

---

## Test Brief Template

Generate a detailed brief for each of the top 5-10 hypotheses.

```markdown
### Test [NUMBER]: [TEST NAME]

**Hypothesis:**
If we [change], then [metric] will [direction] by [estimate],
because [reason].

**Primary Metric:** [e.g., Form completion rate, Click-through rate, Purchase conversion]
**Secondary Metrics:** [e.g., Bounce rate, Time on page, Scroll depth, Revenue per visitor]
**Guardrail Metrics:** [Metrics that should NOT decrease: e.g., Average order value, Customer satisfaction]

**ICE Score:** [I: X, C: X, E: X] = [Average]

**Control (Current State):**
[Describe exactly what exists now. Include current text, layout, design details.
Be specific enough that someone could recreate the current state.]

**Variant A:**
[Describe exactly what changes. Be specific: new text, new layout, new design.
Include mockup description or wireframe notes if applicable.]

**Variant B (optional):**
[If testing more than one variant, describe the second variation.]

**Expected Impact:** [Estimated lift percentage with reasoning]

**Traffic Estimate:**
- Monthly page visitors: [estimate or "ask client"]
- Required sample size per variant: [calculate using MDE tables in testing-framework.md]
- Estimated test duration: [weeks needed to reach significance]
- Minimum Detectable Effect (MDE): [smallest meaningful difference to detect]

**Statistical Requirements:**
- Confidence level: 95%
- Statistical power: 80%
- One-tailed or two-tailed: [recommendation with reasoning]

**Implementation Notes:**
- [Tool recommendation: Google Optimize successor, VWO, Optimizely, custom]
- [Technical requirements: CSS only? JS needed? Backend changes?]
- [QA considerations: mobile, cross-browser, edge cases]

**Risk Assessment:**
- [What could go wrong?]
- [Audience segments that might react differently?]
- [Seasonal or timing considerations?]
```

---

## Analysis Sections

### 1. Current State Assessment

Before generating hypotheses, document the current state.

| Question | What to Document |
|----------|-----------------|
| What is the page? | Page type, business type, primary purpose |
| Primary conversion goal | What action should the visitor take? (Buy, sign up, submit form, call, etc.) |
| Secondary goals | Newsletter signup, social follow, content download, etc. |
| Current conversion elements | List all CTAs, forms, trust signals, and persuasion elements present |
| Key metrics to track | Primary metric, secondary metrics, guardrail metrics |
| Traffic level | Estimate monthly visitors (affects test duration and MDE) |

### 2. Opportunity Identification

Systematically check each category for optimization opportunities.

| Category | What to Look For |
|----------|-----------------|
| Headlines | Unclear, generic, feature-focused, missing unique mechanism |
| CTAs | Generic text, low contrast, poor placement, too many competing CTAs |
| Trust | Missing testimonials, no security badges, no guarantees, no social proof |
| Forms | Too many fields, poor labels, no inline validation, generic submit button |
| Copy | Feature-heavy, low readability, no emotional triggers, no urgency |
| Visual hierarchy | Cluttered layout, unclear focus, CTA not prominent, poor whitespace |
| Mobile | Poor responsive behavior, small tap targets, CTA hidden below fold |
| Speed | Slow LCP, high CLS, render-blocking resources |
| Pricing | Confusing tiers, no anchor pricing, no risk reducer, no social proof near price |

### 3. Test Sequencing

Order matters. Some tests should run before others.

**Testing sequence principles:**
1. Fix broken things first (severity 4 UX issues are not tests -- they are bugs)
2. Test above-the-fold elements first (highest visitor exposure)
3. Test one variable at a time (unless running multivariate)
4. Run quick wins first to build momentum and stakeholder confidence
5. Use learnings from early tests to inform later hypotheses
6. Account for seasonal patterns and traffic fluctuations

---

## Scoring

### Test Plan Quality Score

This is NOT a score of the page -- it is a score of the test plan itself.
Self-assess the quality of the generated hypotheses.

| Criterion | Weight |
|-----------|--------|
| Hypothesis specificity (actionable, measurable?) | 25% |
| ICE scoring accuracy (well-calibrated?) | 25% |
| Coverage (all categories examined?) | 20% |
| Prioritization logic (correct order?) | 15% |
| Brief completeness (enough detail to implement?) | 15% |

---

## Output Format

```markdown
## CRO Test Plan: [URL]

**Business Type:** [Detected type]
**Primary Conversion Goal:** [Goal]
**Date:** [Current date]
**Hypotheses Generated:** [Total count]
**Quick Wins:** [Count] | **Strategic Bets:** [Count] | **Avoid:** [Count]

### Current State Summary
[2-3 sentences describing the current page and its conversion elements]

### Prioritized Test Backlog

| Rank | Test Name | ICE (I/C/E) | Priority | Category |
|------|-----------|-------------|----------|----------|
| 1 | [Name] | [X/X/X] = [Avg] | Quick Win | [Headlines/CTA/Trust/etc.] |
| 2 | [Name] | [X/X/X] = [Avg] | Quick Win | |
| ... | ... | ... | ... | ... |

### Quick Wins (ICE >= 7.0)
[List with brief descriptions]

### Strategic Bets (ICE 5.0-6.9)
[List with brief descriptions]

### Avoid List (ICE < 3.0)
[List with reasoning for why these are not worth testing]

### Detailed Test Briefs
[Full briefs for top 5-10 tests using the template above]

### Implementation Timeline

| Phase | Tests | Duration | Traffic Needed |
|-------|-------|----------|----------------|
| Phase 1 (Quick Wins) | [Test names] | [X weeks] | [estimate] |
| Phase 2 (Standard) | [Test names] | [X weeks] | [estimate] |
| Phase 3 (Strategic) | [Test names] | [X weeks] | [estimate] |

### Notes and Assumptions
[Any caveats, data limitations, or assumptions made during analysis]
```

---

## Cross-References

- **Page analysis:** Use the `cro-page` sub-skill first to identify conversion issues that inform hypotheses
- **Copy analysis:** Use the `cro-copy` sub-skill for detailed copy findings to fuel headline/CTA test ideas
- **UX evaluation:** Use the `cro-ux` sub-skill for UX issues that suggest interaction-based tests
- **Form optimization:** Use the `cro-forms` sub-skill for form-specific test hypotheses
- **Trust audit:** Use the `cro-trust` sub-skill for trust signal gaps that could be tested
- **Testing framework:** Read `${CLAUDE_SKILL_DIR}/../cro/references/testing-framework.md` for MDE tables, sample size calculators, and ICE calibration guidance
- **Psychology:** Read `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md` for the "because" in hypotheses
- **Benchmarks:** Read `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md` for expected lift estimates
- **Proven tests:** Always load `${CLAUDE_SKILL_DIR}/../cro/references/proven-tests-general.md` for universal tests. Additionally load `${CLAUDE_SKILL_DIR}/../cro/references/proven-tests-ecommerce.md` for ecommerce/Shopify sites or `${CLAUDE_SKILL_DIR}/../cro/references/proven-tests-b2b.md` for B2B/SaaS sites — load only the domain-specific file that matches the detected industry
