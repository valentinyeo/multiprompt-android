---
name: cro-plan
description: >
  Strategic CRO planning generating a 90-day roadmap with prioritized test
  backlog, quick wins vs strategic bets, and resource requirements. Industry-
  specific templates available. Use when user says "CRO plan", "CRO strategy",
  "CRO roadmap", "optimization plan", "conversion strategy", or "where should
  I start with CRO".
argument-hint: "<url>"
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Bash, WebFetch
---

# CRO Strategic Plan

Generate a comprehensive conversion rate optimization strategy tailored to the
site's industry, traffic level, and current maturity. This skill produces a
90-day roadmap with quick wins, a prioritized test backlog, resource
requirements, and success metrics. Industry-specific templates guide the
recommendations.

---

## Process

1. **Fetch the site** using `${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py`. Analyze the homepage and
   one or two key conversion pages to understand the business.
2. **Detect industry type** by examining the site's content, product/service
   offerings, and conversion patterns. Map to one of the supported industry
   templates: SaaS, E-commerce, Local Service, Lead Generation, Subscription,
   or Generic.
3. **Run a quick assessment** across the core CRO dimensions: value
   proposition, CTAs, trust signals, forms, page speed, mobile experience, and
   tracking readiness. Use `${CLAUDE_SKILL_DIR}/../cro/scripts/parse_cro.py` for element extraction. This
   is a rapid scan, not a full audit -- just enough to identify strengths,
   weaknesses, and maturity level.
4. **Load the industry template** from `assets/` directory (e.g.,
   `assets/saas.md`, `assets/ecommerce.md`). Use the template's goals,
   metrics, patterns, and test ideas to shape the strategy.
5. **Reference conversion benchmarks** by reading
   `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md` to set realistic baseline and target
   metrics.
6. **Reference testing framework** by reading `${CLAUDE_SKILL_DIR}/../cro/references/testing-framework.md`
   for ICE scoring methodology and test prioritization.
7. **Generate the strategic roadmap** with monthly phases, prioritized backlog,
   and resource plan.

---

## Analysis Sections

### 1. Current State Assessment

Before planning, establish where the site stands today.

| Dimension | What to Evaluate |
|-----------|------------------|
| Industry | SaaS, E-commerce, Local Service, Lead Gen, Subscription, or Other |
| Business model | How does the site make money? What is the primary conversion action? |
| Conversion maturity | Beginner (no testing history), Intermediate (some tests run), Advanced (active CRO program) |
| Traffic level | Low (<10K/month), Medium (10K-100K), High (100K+). Affects test velocity. |
| Current analytics | GA4, GTM, heatmaps, session recording in place? (Quick check, full audit via `cro-tracking`) |
| Current strengths | 2-3 things the site does well for conversion |
| Current weaknesses | 2-3 most critical conversion barriers |
| Competitive position | Quick impression vs competitors (full analysis via `cro-benchmark`) |

**Maturity Level Assessment:**

| Level | Indicators | Typical Monthly Tests |
|-------|------------|----------------------|
| Beginner | No A/B testing tool, basic analytics, ad hoc changes | 0 |
| Intermediate | Testing tool in place, occasional tests, some tracking | 1-2 |
| Advanced | Dedicated CRO resource, regular testing cadence, full tracking | 4+ |
| Expert | CRO team, experimentation culture, personalization, server-side testing | 8+ |

### 2. 90-Day Roadmap

Structure the first 90 days into three distinct phases.

#### Month 1 -- Foundation

The first month focuses on measurement setup and quick wins that do not
require testing.

| Week | Focus Area | Deliverables |
|------|-----------|--------------|
| Week 1 | Analytics audit | GA4 verified, key events configured, baseline metrics recorded |
| Week 2 | Heatmap/recording setup | Hotjar or Clarity installed on key pages, first data collecting |
| Week 3 | Quick wins implementation | Deploy 3-5 no-test improvements (see Quick Wins section) |
| Week 4 | Test tool setup + first test launch | A/B testing platform configured, first test live |

**Month 1 success metric:** Baseline conversion rate documented, first test
running, quick wins deployed.

#### Month 2 -- Testing

The second month launches the first batch of A/B tests and implements
medium-priority fixes.

| Week | Focus Area | Deliverables |
|------|-----------|--------------|
| Week 5 | Analyze heatmap data | Identify scroll depth, click patterns, rage clicks on key pages |
| Week 6 | Launch test #2 | Based on heatmap insights + test backlog priority |
| Week 7 | Implement medium-priority fixes | Fixes that are clearly correct (not hypothesis-dependent) |
| Week 8 | Review test #1 results, launch test #3 | First test concluded, learnings documented |

**Month 2 success metric:** 2-3 tests concluded, first measurable conversion
improvement, heatmap insights documented.

#### Month 3 -- Optimization

The third month builds on learnings and scales the CRO program.

| Week | Focus Area | Deliverables |
|------|-----------|--------------|
| Week 9 | Deep-dive analysis | Segment test results by device, traffic source, user type |
| Week 10 | Iterate on winners | Refine winning variations, test new hypotheses from learnings |
| Week 11 | Implement strategic changes | Larger changes informed by 2 months of data |
| Week 12 | Quarter review + Q2 planning | Document all learnings, plan next quarter's roadmap |

**Month 3 success metric:** Demonstrable conversion lift, documented learnings,
Q2 test backlog ready.

### 3. Quick Wins

Quick wins are improvements that are universally best practice and can be
implemented immediately without A/B testing. They carry minimal risk and
should be deployed in Week 3.

| Category | Example Quick Wins |
|----------|--------------------|
| CTA improvements | Strengthen CTA text (action-oriented, specific), increase contrast, add above fold |
| Trust signal additions | Add trust badges near CTAs, display review count, add guarantee messaging |
| Form simplification | Remove unnecessary fields, add placeholder text, fix mobile input types |
| Copy fixes | Fix unclear headlines, add benefit-oriented subheadings, fix typos |
| Mobile fixes | Fix tap target sizes, add sticky mobile CTA, fix horizontal scroll |
| Speed improvements | Compress images, defer non-critical scripts, enable lazy loading |
| Navigation fixes | Simplify checkout nav, fix broken links, add breadcrumbs |
| Social proof | Add testimonial near primary CTA, display customer count, add case study links |

**Quick Win qualification criteria:**
- Industry best practice with strong evidence
- Low risk of negative impact
- Can be implemented in under 4 hours
- Does not require design resources
- Not dependent on a specific hypothesis

### 4. Test Backlog

Prioritized list of A/B test ideas scored using the ICE framework.

**ICE Scoring** (reference `${CLAUDE_SKILL_DIR}/../cro/references/testing-framework.md`):

| Factor | Scale | Definition |
|--------|-------|------------|
| **Impact** | 1-10 | How much will this test move the primary conversion metric? |
| **Confidence** | 1-10 | How confident are we this will win? Based on data, research, or best practice? |
| **Ease** | 1-10 | How easy is this to implement? 10 = copy change. 1 = full page redesign. |

**ICE Score = (Impact + Confidence + Ease) / 3**

| Rank | Test Name | Hypothesis | ICE Score | Timeline | Page |
|------|-----------|-----------|-----------|----------|------|
| 1 | [Name] | If we [change], then [metric] will [improve] because [reason] | [Score] | [Est. weeks] | [Page] |
| 2 | [Name] | If we [change]... | [Score] | [Est. weeks] | [Page] |
| ... | ... | ... | ... | ... | ... |

**Top 10 recommended tests** should be populated based on the quick assessment
findings and industry template. Each test brief includes:
- Test name
- Hypothesis in "If... then... because..." format
- Primary metric
- Secondary metrics
- Estimated sample size and duration (based on traffic level)
- Variation description
- ICE score with rationale

### 5. Strategic Initiatives

Larger projects that go beyond individual A/B tests. These require more
resources and time but have the potential for significant conversion impact.

| Initiative | Description | Estimated Impact | Timeline | Resources |
|------------|-------------|------------------|----------|-----------|
| Page redesign | Full redesign of [page] based on accumulated test learnings | High | 4-6 weeks | Designer + Developer |
| Funnel restructuring | Reduce checkout from N steps to M steps | High | 3-4 weeks | Developer |
| Personalization | Segment-based content/offer personalization | Medium-High | 6-8 weeks | Developer + CRO specialist |
| New landing pages | Dedicated landing pages for top traffic sources | Medium | 2-3 weeks each | Copywriter + Designer |
| Social proof overhaul | Systematic testimonial/review collection and display | Medium | 4-6 weeks | Marketing |

### 6. Resource Requirements

Realistic assessment of what is needed to execute the CRO plan.

#### Tools

| Tool Category | Recommended Options | Monthly Cost Range | Priority |
|---------------|--------------------|--------------------|----------|
| A/B Testing | VWO, Optimizely, Google Optimize (sunset), AB Tasty | $0-$500 | P0 |
| Analytics | GA4 (free), Mixpanel, Amplitude | $0-$200 | P0 |
| Heatmaps | Hotjar, Microsoft Clarity (free), FullStory | $0-$100 | P0 |
| Session Recording | Hotjar, Clarity (free), FullStory | $0-$100 | P1 |
| Tag Management | GTM (free) | $0 | P0 |
| User Testing | UsabilityHub, UserTesting, Maze | $0-$300 | P2 |
| Survey | Hotjar, Qualaroo, Typeform | $0-$100 | P2 |

#### Team Roles

| Role | Responsibility | Hours/Week | In-House or Outsource |
|------|---------------|------------|----------------------|
| CRO Specialist | Strategy, analysis, test design, reporting | 10-20h | Either |
| Designer | Variation design, landing page design | 5-10h | Either |
| Developer | Test implementation, tracking setup | 5-10h | Either |
| Copywriter | CTA text, headline variations, landing page copy | 3-5h | Either |
| Data Analyst | Statistical analysis, segmentation, reporting | 3-5h | In-house preferred |

#### Budget Considerations

| Budget Level | Monthly Investment | What You Get |
|--------------|--------------------|--------------|
| Minimal | $0-$200 | Free tools (GA4, GTM, Clarity), manual testing |
| Moderate | $200-$1,000 | Professional testing tool, heatmaps, basic team |
| Professional | $1,000-$5,000 | Full tool stack, dedicated CRO specialist time |
| Enterprise | $5,000+ | Full team, advanced personalization, server-side testing |

### 7. Success Metrics

Define clear KPIs to measure the CRO program's impact.

| Metric Type | Examples | Measurement |
|-------------|----------|-------------|
| Primary KPIs | Conversion rate, revenue per visitor, lead quality | Weekly |
| Secondary KPIs | Bounce rate, pages/session, time on site, cart abandonment rate | Weekly |
| Testing KPIs | Tests launched/month, win rate, average lift per test | Monthly |
| Program KPIs | Revenue attributed to CRO, ROI of CRO investment | Quarterly |

**Baseline and Target format:**

| Metric | Current Baseline | 90-Day Target | Stretch Goal |
|--------|-----------------|---------------|--------------|
| [Primary conversion rate] | [X%] | [Y%] | [Z%] |
| [Revenue per visitor] | [$X] | [$Y] | [$Z] |
| [Tests per month] | [0] | [2-3] | [4+] |

**Reporting cadence:** Weekly metric review, monthly test review, quarterly
strategy review.

### 8. Industry-Specific Recommendations

Load and reference the appropriate template from `assets/`:

| Industry Detected | Template File |
|-------------------|---------------|
| SaaS | Read `assets/saas.md` |
| E-commerce | Read `assets/ecommerce.md` |
| Local Service | Read `assets/local-service.md` |
| Lead Generation | Read `assets/lead-gen.md` |
| Subscription | Read `assets/subscription.md` |
| Other / Unknown | Read `assets/generic.md` |

Apply the template's industry-specific goals, common patterns, recommended
tests, and benchmarks to the roadmap. If the site spans multiple categories
(e.g., SaaS with e-commerce), reference both templates.

---

## Scoring

This skill does not produce a single score. Instead, it outputs a maturity
assessment and a readiness evaluation:

**CRO Maturity Level:** Beginner / Intermediate / Advanced / Expert

**CRO Readiness Checklist:**

| Requirement | Status | Priority |
|-------------|--------|----------|
| Analytics platform installed | [Yes/No] | P0 |
| Conversion events tracked | [Yes/No] | P0 |
| Baseline metrics documented | [Yes/No] | P0 |
| A/B testing tool available | [Yes/No] | P0 |
| Heatmap tool installed | [Yes/No] | P1 |
| Sufficient traffic for testing | [Yes/No] | Dependent |
| Design resources available | [Yes/No] | P1 |
| Development resources available | [Yes/No] | P1 |

---

## Output Format

Generate two documents:

### Document 1: CRO-STRATEGY.md

```markdown
## CRO Strategy: [Site Name]

**URL:** [URL]
**Industry:** [Detected industry]
**CRO Maturity:** [Level]
**Traffic Assessment:** [Low/Medium/High]
**Date:** [Current date]

### Executive Summary
[3-4 sentences: industry, current state, biggest opportunity, recommended
approach]

### Current State Assessment
[Detailed findings from the quick scan]

### 90-Day Roadmap

#### Month 1: Foundation (Weeks 1-4)
[Detailed week-by-week plan]

#### Month 2: Testing (Weeks 5-8)
[Detailed week-by-week plan]

#### Month 3: Optimization (Weeks 9-12)
[Detailed week-by-week plan]

### Quick Wins (Deploy Immediately)
1. [Quick win with implementation guidance]
2. [Quick win...]
3. [Quick win...]
...

### Resource Requirements
[Tools, team, budget recommendations]

### Success Metrics
[Baseline, targets, reporting cadence]

### Industry-Specific Insights
[Recommendations from the industry template]
```

### Document 2: ACTION-PLAN.md

```markdown
## CRO Action Plan: [Site Name]

**Date:** [Current date]
**Timeframe:** 90 days

### Prioritized Test Backlog

| # | Test | Hypothesis | ICE | Timeline | Status |
|---|------|-----------|-----|----------|--------|
| 1 | [Name] | If [change], then [metric] will [improve] because [reason] | [Score] | [Weeks] | Planned |
| 2 | [Name] | ... | [Score] | [Weeks] | Planned |
| ... | | | | | |

### Quick Win Checklist
- [ ] [Action item] -- [Page] -- [Est. 1 hour]
- [ ] [Action item] -- [Page] -- [Est. 2 hours]
- [ ] ...

### Strategic Initiatives

| Initiative | Impact | Effort | Timeline | Owner |
|------------|--------|--------|----------|-------|
| [Name] | [High/Med/Low] | [High/Med/Low] | [Weeks] | [Role] |

### Tool Setup Checklist
- [ ] [Tool] -- [Purpose] -- [Priority]
- [ ] ...

### Weekly Review Template

**Week of [Date]:**
- Tests running: [List]
- Tests concluded: [List + results]
- Quick wins deployed: [List]
- Key metric movement: [Primary KPI trend]
- Next week focus: [Plan]
```

---

## Cross-References

- **Full page audit first:** Use the `cro-page` sub-skill for a detailed baseline of the main conversion page
- **Tracking readiness:** Use the `cro-tracking` sub-skill before starting -- proper tracking is a prerequisite
- **E-commerce specifics:** Use the `cro-ecommerce` sub-skill for the e-commerce-specific plan components
- **Competitor context:** Use the `cro-benchmark` sub-skill to inform competitive positioning in the strategy
- **Test design:** Use the `cro-testing` sub-skill to expand test backlog items into full test briefs
- **Funnel analysis:** Use the `cro-funnel` sub-skill to map the conversion funnel before planning optimizations
- **Benchmarks:** Read `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md` for realistic targets
- **Testing framework:** Read `${CLAUDE_SKILL_DIR}/../cro/references/testing-framework.md` for ICE methodology and test design
- **Industry templates:** Read the appropriate `assets/*.md` file for industry-specific guidance
- **Proven test databases:** Load the proven-tests file matching the detected industry: `${CLAUDE_SKILL_DIR}/../cro/references/proven-tests-ecommerce.md` for ecommerce/Shopify, `${CLAUDE_SKILL_DIR}/../cro/references/proven-tests-b2b.md` for B2B/SaaS. Always load `${CLAUDE_SKILL_DIR}/../cro/references/proven-tests-general.md` for universal tests. Use as pre-scored test ideas to seed the 90-day roadmap backlog
