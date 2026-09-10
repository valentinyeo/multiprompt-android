---
name: cro-benchmark
description: >
  Side-by-side competitor conversion element comparison analyzing CTAs, value
  propositions, trust signals, form friction, checkout steps, and conversion
  patterns. Use when user says "competitor analysis", "benchmark", "compare
  with competitor", "competitive CRO", or "how does my site compare".
argument-hint: "<url> <competitor-url>"
allowed-tools: Read, Grep, Glob, Bash, WebFetch
---

# CRO Competitive Benchmark

Perform a structured side-by-side comparison of conversion elements between a
target site and one or more competitors. This skill does not evaluate sites in
isolation -- it directly compares equivalent elements to reveal competitive
gaps, stolen best practices, and differentiation opportunities.

---

## Process

1. **Accept URLs** -- the user provides a target URL (their site) and one or
   more competitor URLs. If competitors are not specified, suggest looking up
   direct competitors in the same space.
2. **Fetch all pages** using `${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py`. Store HTML for each site.
3. **Extract CRO elements** from each site using `${CLAUDE_SKILL_DIR}/../cro/scripts/parse_cro.py`.
   Normalize the extracted elements so they can be compared apples-to-apples.
4. **Take screenshots** of each site if Playwright is available (desktop at
   1440px and mobile at 375px). Place screenshots side-by-side for visual
   comparison.
5. **Generate the comparison matrix** -- evaluate each site against the same
   criteria and rank them per category.
6. **Identify competitive gaps** -- where the target site falls behind -- and
   **unique strengths** -- where it leads.
7. **Produce the benchmark report** with actionable recommendations to close
   gaps and amplify advantages.

---

## Analysis Sections

### 1. Above-the-Fold Comparison

The first impression battle. Compare what each site shows before scrolling.

| Element | Target Site | Competitor 1 | Competitor 2 | Winner |
|---------|-------------|--------------|--------------|--------|
| Headline clarity | [Assessment] | [Assessment] | [Assessment] | [Best] |
| Value prop specificity | [Assessment] | [Assessment] | [Assessment] | [Best] |
| Hero visual impact | [Assessment] | [Assessment] | [Assessment] | [Best] |
| CTA visibility | [Assessment] | [Assessment] | [Assessment] | [Best] |
| CTA text | [Exact text] | [Exact text] | [Exact text] | [Best] |
| Visual hierarchy | [Assessment] | [Assessment] | [Assessment] | [Best] |
| Loading speed (perceived) | [Assessment] | [Assessment] | [Assessment] | [Best] |

### 2. Value Proposition Comparison

How each site communicates its unique value.

| Criterion | What to Compare |
|-----------|-----------------|
| Primary claim | What does each site promise? Is one more compelling or specific? |
| Differentiation | Does each site clearly explain why to choose THEM over alternatives? |
| Proof of claim | Does each site back up the value prop with data, testimonials, or demos? |
| Audience specificity | Which site best targets a specific audience vs trying to appeal to everyone? |
| Emotional appeal | Which site creates the strongest emotional connection? |
| Rational appeal | Which site provides the most logical, data-backed case? |

### 3. CTA Comparison

Direct comparison of call-to-action elements.

| Element | Target Site | Competitor 1 | Competitor 2 | Best Practice |
|---------|-------------|--------------|--------------|---------------|
| Primary CTA text | [Exact text] | [Exact text] | [Exact text] | [Which is most compelling] |
| CTA color/contrast | [Description] | [Description] | [Description] | [Strongest contrast] |
| CTA count (above fold) | [Count] | [Count] | [Count] | [Ideal: 1 primary] |
| CTA count (full page) | [Count] | [Count] | [Count] | [Assessment] |
| Secondary CTA | [Text/presence] | [Text/presence] | [Text/presence] | [Best backup option] |
| Urgency elements near CTA | [Present/absent] | [Present/absent] | [Present/absent] | [Most effective] |
| CTA placement pattern | [Where on page] | [Where on page] | [Where on page] | [Best rhythm] |

### 4. Trust Signal Comparison

Trust is a key differentiator. Who builds credibility best?

| Signal Type | Target Site | Competitor 1 | Competitor 2 | Gap |
|-------------|-------------|--------------|--------------|-----|
| Customer testimonials | [Count, quality] | [Count, quality] | [Count, quality] | [Who leads] |
| Star ratings/reviews | [Platform, count] | [Platform, count] | [Platform, count] | [Who leads] |
| Client/partner logos | [Count, quality] | [Count, quality] | [Count, quality] | [Who leads] |
| Trust badges | [Types present] | [Types present] | [Types present] | [Who leads] |
| Guarantees | [Type, visibility] | [Type, visibility] | [Type, visibility] | [Who leads] |
| Case studies | [Present/absent] | [Present/absent] | [Present/absent] | [Who leads] |
| Press mentions | [Present/absent] | [Present/absent] | [Present/absent] | [Who leads] |
| Certifications | [Present/absent] | [Present/absent] | [Present/absent] | [Who leads] |
| Specific numbers | [Revenue, users] | [Revenue, users] | [Revenue, users] | [Who leads] |

### 5. Form Friction Comparison

For sites with lead capture or signup forms.

| Element | Target Site | Competitor 1 | Competitor 2 | Least Friction |
|---------|-------------|--------------|--------------|----------------|
| Field count | [Count] | [Count] | [Count] | [Fewest wins] |
| Required fields | [Count] | [Count] | [Count] | [Fewest wins] |
| Form steps | [Count] | [Count] | [Count] | [Fewest wins] |
| Social login options | [List] | [List] | [List] | [Most options] |
| Privacy reassurance | [Present/absent] | [Present/absent] | [Present/absent] | [Assessment] |
| Submit button text | [Exact text] | [Exact text] | [Exact text] | [Most compelling] |
| Inline validation | [Yes/no] | [Yes/no] | [Yes/no] | [Assessment] |
| Guest checkout (if e-com) | [Yes/no] | [Yes/no] | [Yes/no] | [Assessment] |

### 6. Content Approach

How each site uses content to persuade.

| Criterion | What to Compare |
|-----------|-----------------|
| Content length | Long-form vs short-form. Which approach suits the product/audience? |
| Benefit vs feature focus | Ratio of benefit-led vs feature-led content. Who leads with benefits? |
| Readability | Reading level, paragraph length, use of formatting. Who is easier to scan? |
| Visual content | Quality and quantity of images, videos, illustrations, animations. |
| Content structure | Information architecture. Who makes it easiest to find what you need? |
| FAQ / objection handling | Who proactively addresses buyer concerns? |

### 7. Social Proof

Beyond basic trust signals, evaluate the depth and quality of social proof.

| Criterion | What to Compare |
|-----------|-----------------|
| Proof specificity | Generic ("Thousands trust us") vs specific ("47,382 teams use us"). |
| Proof relevance | Does the social proof match the target audience? |
| Proof freshness | Dated testimonials? Recent case studies? Current review counts? |
| Proof variety | Multiple types of proof, or only one type? |
| User-generated content | Customer photos, videos, community content? |
| Industry-specific proof | Awards, certifications, or recognition relevant to the industry? |

### 8. Pricing Presentation

Compare how each site presents pricing (if visible).

| Element | Target Site | Competitor 1 | Competitor 2 | Best |
|---------|-------------|--------------|--------------|------|
| Pricing visibility | [Visible/hidden/contact sales] | [Same] | [Same] | [Assessment] |
| Plan count | [Count] | [Count] | [Count] | [Assessment] |
| Highlighted plan | [Which, how] | [Which, how] | [Which, how] | [Best anchoring] |
| Annual savings display | [How shown] | [How shown] | [How shown] | [Clearest savings] |
| Free tier / trial | [Details] | [Details] | [Details] | [Lowest barrier] |
| Money-back guarantee | [Duration] | [Duration] | [Duration] | [Strongest guarantee] |
| Price anchoring | [Technique] | [Technique] | [Technique] | [Most effective] |

### 9. Mobile Experience

Compare the mobile conversion experience.

| Criterion | What to Compare |
|-----------|-----------------|
| Responsive quality | Layout integrity, readability, image scaling on mobile. |
| Mobile CTA | Prominence, stickiness, tap target size of primary CTA. |
| Mobile navigation | Hamburger menu usability, search prominence, key page access. |
| Mobile speed | Perceived load time on mobile. Who loads fastest? |
| Mobile form experience | Input types, autofill, form flow on mobile. |
| App prompts | Native app install prompts? Disruptive or well-timed? |

### 10. Page Speed

Performance affects conversion. Compare load performance.

| Metric | Target Site | Competitor 1 | Competitor 2 | Winner |
|--------|-------------|--------------|--------------|--------|
| Perceived load time | [Assessment] | [Assessment] | [Assessment] | [Fastest] |
| Render-blocking resources | [Count/type] | [Count/type] | [Count/type] | [Fewest] |
| Image optimization | [Assessment] | [Assessment] | [Assessment] | [Best] |
| Third-party script load | [Count] | [Count] | [Count] | [Fewest] |

### 11. Unique Strengths

For each site, identify 2-3 things it does uniquely well that others do not.
These are elements worth studying or adopting.

| Site | Unique Strength | Why It Works | Adoptable? |
|------|----------------|--------------|------------|
| Target | [Element] | [Reasoning] | N/A |
| Competitor 1 | [Element] | [Reasoning] | [Yes/No + effort] |
| Competitor 2 | [Element] | [Reasoning] | [Yes/No + effort] |

### 12. Competitive Gaps

Where the target site falls behind ALL competitors. These are the highest
priority items because the target is at a competitive disadvantage.

| Gap Area | Target Current State | Competitor Standard | Priority | Effort |
|----------|---------------------|--------------------|-----------| --------|
| [Area] | [What target does] | [What competitors do] | [P0-P3] | [Low/Med/High] |

---

## Scoring

### Benchmark Scoring

Each site receives a score per analysis section (0-100). The comparison
matrix shows relative positioning.

| Section | Weight |
|---------|--------|
| Above-the-Fold | 15% |
| Value Proposition | 20% |
| CTA Effectiveness | 15% |
| Trust Signals | 10% |
| Form Friction | 10% |
| Content Approach | 10% |
| Social Proof | 5% |
| Pricing Presentation | 5% |
| Mobile Experience | 5% |
| Page Speed | 5% |
| **Total** | **100%** |

**Competitive Position:**

| Position | Meaning |
|----------|---------|
| Leader | Target scores highest across most categories |
| Competitive | Target is on par with competitors, minor gaps |
| Behind | Target trails in multiple key categories |
| Significantly behind | Target trails in most categories, urgent action needed |

---

## Output Format

```markdown
## CRO Competitive Benchmark: [Target Site Name]

**Target:** [URL]
**Competitors:** [URL 1], [URL 2], ...
**Competitive Position:** [Leader/Competitive/Behind/Significantly behind]
**Date:** [Current date]

### Executive Summary
[2-3 sentences: overall competitive position, biggest gap, biggest advantage]

### Overall Scores

| Site | Score | Position |
|------|-------|----------|
| [Target] | /100 | [Rank] |
| [Competitor 1] | /100 | [Rank] |
| [Competitor 2] | /100 | [Rank] |

### Category Comparison

| Category | Target | Comp 1 | Comp 2 | Winner |
|----------|--------|--------|--------|--------|
| Above-the-Fold | /100 | /100 | /100 | [Name] |
| Value Proposition | /100 | /100 | /100 | [Name] |
| CTA Effectiveness | /100 | /100 | /100 | [Name] |
| Trust Signals | /100 | /100 | /100 | [Name] |
| Form Friction | /100 | /100 | /100 | [Name] |
| Content Approach | /100 | /100 | /100 | [Name] |
| Social Proof | /100 | /100 | /100 | [Name] |
| Pricing | /100 | /100 | /100 | [Name] |
| Mobile | /100 | /100 | /100 | [Name] |
| Page Speed | /100 | /100 | /100 | [Name] |

### Competitive Gaps (Where Target Trails)
- [P0] [Gap description] -- Competitors: [What they do better]
- ...

### Unique Strengths (Where Target Leads)
- [Strength] -- [How to amplify]
- ...

### Steal-Worthy Ideas from Competitors
1. **From [Competitor]:** [Specific element] -- [How to adapt for target]
2. **From [Competitor]:** [Specific element] -- [How to adapt for target]
3. ...

### Detailed Comparison

#### Above-the-Fold
[Side-by-side findings]

#### Value Proposition
[Side-by-side findings]

[... repeat for each section ...]

### Recommendations (Prioritized)

1. **[Critical]** [Close this competitive gap: specific action]
2. **[High]** [Adopt this competitor pattern: specific action]
3. **[Medium]** [Amplify this advantage: specific action]
4. **[Low]** [Nice-to-have improvement]

### Suggested A/B Tests Based on Competitor Insights

| Test | Inspired By | Hypothesis | Expected Impact | ICE Score |
|------|-------------|-----------|-----------------|-----------|
| [Name] | [Competitor] | If we [adopt X from competitor], then [metric] will [improve] because [reason] | [Est. %] | [Score] |
```

---

## Cross-References

- **Deep page audit:** Use the `cro-page` sub-skill for a thorough audit of the target page on its own merits
- **E-commerce comparison:** Use the `cro-ecommerce` sub-skill for product page, cart, and checkout comparisons
- **Trust deep-dive:** Use the `cro-trust` sub-skill for detailed trust signal analysis per site
- **Copy comparison:** Use the `cro-copy` sub-skill for granular copy and messaging analysis per site
- **UX comparison:** Use the `cro-ux` sub-skill for heuristic evaluation comparison
- **CRO strategy:** Use the `cro-plan` sub-skill to turn benchmark findings into a strategic roadmap
- **Psychology:** Read `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md` for persuasion principles behind competitor tactics
- **Benchmarks:** Read `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md` for industry norms to contextualize both sites
