---
name: cro-page
description: >
  Deep single-page conversion analysis covering headline effectiveness, CTA
  clarity, value proposition, visual hierarchy, trust signals, form design, and
  page speed impact. Use when user says "analyze this page", "check page
  conversion", "page CRO", or provides a single URL for review.
---

# CRO Page Analysis

Perform a comprehensive conversion rate optimization audit on a single page.
This skill examines every conversion-relevant element on the page and produces
a scored report with prioritized recommendations.

---

## Process

1. **Fetch the page HTML** using `${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py`. Store the raw HTML for
   downstream analysis.
2. **Extract CRO elements** using `${CLAUDE_SKILL_DIR}/../cro/scripts/parse_cro.py`. This extracts
   headlines, CTAs, forms, trust signals, images, navigation, and structured
   data into a structured format.
3. **Take a screenshot** if Playwright is available (desktop at 1440px and mobile
   at 375px). Use the screenshots to evaluate visual hierarchy, above-the-fold
   content, and CTA prominence.
4. **Analyze against quality gates** by reading `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md`.
   Check every extracted element against the minimum conversion requirements for
   the detected business type.
5. **Score each section** independently (0-100), then calculate the composite
   Page CRO Score.
6. **Generate the report** with findings, scores, and prioritized
   recommendations.

---

## Analysis Sections

### 1. Above-the-Fold Analysis

Evaluate what the visitor sees before scrolling. This is the most critical real
estate on the page.

| Criterion | What to Check |
|-----------|---------------|
| Headline clarity | Is the value proposition clear within 5 seconds? Can a stranger understand what this page offers? |
| CTA visibility | Is the primary CTA visible without scrolling? Does it stand out from surrounding content? |
| Hero image/video | Is the media relevant to the offering? Does it show the product in use or the outcome? |
| Visual hierarchy | Does the eye naturally flow: headline -> supporting text -> CTA? Is there a clear focal point? |
| Navigation distraction | Does the nav bar compete with the CTA? Are there too many links above the fold? |
| Loading impression | Does the above-the-fold content load first? Is there layout shift (CLS) affecting first impression? |

**Score weighting:** 20% of Page CRO Score.

### 2. Value Proposition

The value proposition is the single most important conversion element.

| Criterion | What to Check |
|-----------|---------------|
| Uniqueness | Does it differentiate from competitors, or could it belong to any company in the space? |
| Specificity | Does it use concrete numbers, outcomes, or timeframes? ("Save 10 hours/week" > "Save time") |
| Benefit orientation | Does it focus on what the customer gets, not what the product does? |
| Supporting proof | Is the claim backed by data, testimonials, or demonstrations immediately nearby? |
| Audience clarity | Is it clear WHO this is for? Does the visitor self-identify? |
| Objection handling | Are the top 2-3 objections addressed near the value proposition? |

**Score weighting:** 20% of Page CRO Score.

### 3. CTA Analysis

Evaluate all calls-to-action on the page.

| Criterion | What to Check |
|-----------|---------------|
| Primary CTA count | Exactly 1 primary CTA per page section. Multiple competing primary CTAs dilute focus. |
| CTA text | Action-oriented? First-person? Specific? ("Start My Free Trial" > "Submit") |
| Contrast | Does the CTA button contrast with surrounding elements? Run a quick contrast check. |
| Placement | Above the fold? After key persuasion points? At natural decision moments? |
| Size | Large enough to be noticed, proportional to importance. Minimum 44px touch target on mobile. |
| Urgency elements | Any time-based or scarcity triggers near the CTA? Are they legitimate? |
| Secondary CTAs | Is there a lower-commitment alternative? ("Learn More" alongside "Buy Now") |
| Repetition | Does the CTA repeat at logical intervals down the page? |

**Score weighting:** 15% of Page CRO Score.

### 4. Trust Signals

Evaluate social proof and credibility indicators. Cross-reference with the
`cro-trust` sub-skill for a deeper standalone audit.

| Criterion | What to Check |
|-----------|---------------|
| Testimonials | Present? Specific outcomes? Photos and names attached? Relevant to the audience? |
| Reviews | Third-party review widgets? Star ratings? Review count visible? |
| Client/partner logos | Recognizable brands? Clean display? Enough logos (aim for 5-8)? |
| Trust badges | Security, payment, guarantee badges? Positioned near conversion points? |
| Guarantees | Money-back, satisfaction, or service guarantees visible? Duration stated? |
| Contact info | Phone, email, or chat visible? Does it signal a real, reachable business? |

**Score weighting:** 10% of Page CRO Score.

### 5. Content and Copy

Assess the persuasiveness and readability of the page copy.

| Criterion | What to Check |
|-----------|---------------|
| Benefit vs feature ratio | Count distinct benefits and features. Aim for at least 2:1 benefit-to-feature ratio. |
| Readability | Calculate Flesch-Kincaid score. Target 6th-8th grade reading level for most audiences. |
| Emotional triggers | Which of the 6 core triggers are activated? (Fear, Trust, Curiosity, Belonging, Achievement, Urgency) |
| Power words | Count usage of high-conversion words: free, new, proven, guaranteed, instant, exclusive, limited, etc. |
| Specificity | Are claims backed by numbers, percentages, timeframes, or named outcomes? |
| Scanability | Headers, bullet points, bold text, short paragraphs? Can the page be skimmed in 30 seconds? |
| Tone match | Does the copy tone match the target audience? B2B vs B2C, technical vs casual. |

Read `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md` for the full list of emotional
triggers and persuasion principles to evaluate against.

**Score weighting:** 10% of Page CRO Score.

### 6. Form Analysis

Evaluate all forms on the page. If no forms exist, note this and skip.

| Criterion | What to Check |
|-----------|---------------|
| Field count | Compare against recommended maximums in `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md`. Every extra field reduces completion. |
| Labels | Top-aligned? Clear? No placeholder-only labels? |
| Validation | Inline real-time validation? Clear error messages? Fields retain values on error? |
| Friction level | Required vs optional fields. Can any fields be removed, auto-filled, or deferred? |
| Mobile input types | Correct HTML5 input types (email, tel, number, url) for mobile keyboards? |
| Privacy assurance | Privacy statement or "we won't spam" text near the form? |
| Submit button | Specific action text? High contrast? ("Get My Free Quote" > "Submit") |
| Multi-step | For 5+ fields, is a multi-step layout used? Progress indicator present? |

**Score weighting:** 10% of Page CRO Score.

### 7. Page Speed Impact

Page speed directly affects conversion rates. Every 100ms of load time reduces
conversion by approximately 1%.

| Criterion | What to Check |
|-----------|---------------|
| LCP (Largest Contentful Paint) | Target: < 2.5s. What is the LCP element? Can it be optimized? |
| CLS (Cumulative Layout Shift) | Target: < 0.1. Any images without dimensions? Late-loading ads or fonts? |
| Render-blocking resources | Undeferred CSS or JS in the head? Third-party scripts blocking paint? |
| Image optimization | WebP/AVIF format? Lazy loading on below-fold images? Proper sizing? |
| Above-the-fold speed | Does the conversion-critical content load fast, even if the rest is slow? |

**Score weighting:** 10% of Page CRO Score.

### 8. Mobile Experience

More than 50% of web traffic is mobile. Mobile conversion rates are typically
50% lower than desktop -- closing this gap is high impact.

| Criterion | What to Check |
|-----------|---------------|
| Responsive layout | Does content reflow properly? No horizontal scrolling? |
| Thumb zones | Are interactive elements in comfortable thumb-reach zones? |
| Tap targets | Minimum 44px spacing between tappable elements? |
| Mobile CTA prominence | Is the primary CTA visible and easily tappable on mobile? Fixed/sticky CTA? |
| Content priority | Is the most important content first on mobile? No unnecessary elements pushing the CTA down? |
| Font size | Minimum 16px body text on mobile to prevent zoom? |

**Score weighting:** 5% of Page CRO Score.

---

## Scoring

### Page CRO Score Calculation

| Section | Weight |
|---------|--------|
| Above-the-Fold | 20% |
| Value Proposition | 20% |
| CTA Analysis | 15% |
| Trust Signals | 10% |
| Content & Copy | 10% |
| Form Analysis | 10% |
| Page Speed | 10% |
| Mobile Experience | 5% |
| **Total** | **100%** |

**Formula:**
```
Page CRO Score = (ATF × 0.20) + (VP × 0.20) + (CTA × 0.15) + (Trust × 0.10)
               + (Copy × 0.10) + (Forms × 0.10) + (Speed × 0.10) + (Mobile × 0.05)
```

If the page has no forms, redistribute the 10% forms weight equally across
Above-the-Fold and Value Proposition (5% each).

### Score Interpretation

| Score | Rating | Meaning |
|-------|--------|---------|
| 90-100 | Exceptional | Best-in-class. Minor polish only. |
| 70-89 | Strong | Solid page with clear optimization opportunities. |
| 50-69 | Moderate | Significant gaps impacting conversion. Action needed. |
| 30-49 | Weak | Major conversion barriers. Urgent fixes required. |
| 0-29 | Critical | Fundamental issues blocking conversions. Redesign may be needed. |

---

## Output Format

```markdown
## CRO Page Analysis: [URL]

**Business Type:** [Detected type]
**Page CRO Score:** [Score]/100 -- [Rating]
**Date:** [Current date]

### Executive Summary
[2-3 sentences: what this page does well and what is hurting conversions most]

### Score Breakdown

| Section | Score | Rating | Key Issue |
|---------|-------|--------|-----------|
| Above-the-Fold | /100 | | |
| Value Proposition | /100 | | |
| CTA Analysis | /100 | | |
| Trust Signals | /100 | | |
| Content & Copy | /100 | | |
| Form Analysis | /100 | | |
| Page Speed | /100 | | |
| Mobile Experience | /100 | | |

### Critical Issues
- [P0] [Finding description] -- [Section]
- ...

### High Priority Issues
- [P1] [Finding description] -- [Section]
- ...

### Detailed Findings

#### Above-the-Fold
[Findings with specific observations and evidence]

#### Value Proposition
[Findings...]

[... repeat for each section ...]

### Recommendations (Prioritized)

1. **[Critical]** [Action item with specific guidance]
2. **[High]** [Action item...]
3. **[Medium]** [Action item...]
4. **[Low]** [Action item...]

### Quick-Win A/B Tests
| Test | Hypothesis | Expected Impact | ICE Score |
|------|-----------|-----------------|-----------|
| [Name] | If we [change], then [metric] will [improve] because [reason] | [Est. %] | [Score] |
```

---

## Cross-References

- **Deep copy analysis:** Use the `cro-copy` sub-skill for granular copywriting evaluation
- **Deep trust audit:** Use the `cro-trust` sub-skill for comprehensive trust signal assessment
- **Deep form analysis:** Use the `cro-forms` sub-skill for detailed form optimization
- **UX heuristics:** Use the `cro-ux` sub-skill for full Nielsen's heuristic evaluation
- **Test hypotheses:** Use the `cro-testing` sub-skill for full A/B test plan generation
- **Quality gates:** Read `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md` for pass/fail thresholds
- **Psychology:** Read `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md` for persuasion principles
- **Benchmarks:** Read `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md` for industry comparison data
