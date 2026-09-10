---
name: cro-copy
description: >
  Conversion copywriting analysis examining headline formulas, benefit vs
  feature language, urgency/scarcity elements, readability scores, emotional
  triggers, and CTA text effectiveness. Use when user says "copy analysis",
  "headline check", "CTA copy", "conversion copy", or "copywriting review".
argument-hint: "<url>"
allowed-tools: Read, Grep, Glob, Bash, WebFetch
---

# CRO Copy Analysis

Evaluate the conversion effectiveness of every piece of copy on a page. This
skill goes beyond surface-level readability to assess psychological impact,
persuasion structure, and conversion-specific language patterns.

---

## Process

1. **Fetch the page** using `${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py`. Store the full HTML.
2. **Extract text content** using `${CLAUDE_SKILL_DIR}/../cro/scripts/parse_cro.py`. This separates
   headlines, subheadlines, body copy, CTA text, testimonial copy, micro-copy,
   and meta content into distinct buckets for targeted analysis.
3. **Load psychology principles** by reading `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md`.
   Use these as the evaluation framework for persuasion and emotional triggers.
4. **Analyze each copy section** against the criteria below.
5. **Calculate the Copy Conversion Score** (0-100).
6. **Generate the report** with findings, scores, and specific rewrite
   suggestions for each problem area.

---

## Analysis Sections

### 1. Headline Analysis

The headline is the most-read element on the page. If it fails, nothing else
matters.

| Criterion | What to Check |
|-----------|---------------|
| Formula identification | Which headline formula is used? How/Number/Question/Command/Testimonial/News/Direct. Identify it explicitly. |
| Clarity | Can a stranger understand the offering in under 5 seconds? Strip jargon and test comprehension. |
| Specificity | Does it include concrete numbers, outcomes, or timeframes? Vague headlines convert poorly. |
| Emotional impact | Does it trigger curiosity, fear of missing out, desire, or a sense of possibility? |
| Benefit focus | Does the headline lead with what the customer GETS, not what the product DOES? |
| Unique mechanism | Does it hint at a proprietary method, framework, or approach that differentiates? |
| Length | Optimal: 6-12 words for clarity. Flag if too short (vague) or too long (diluted). |
| Keyword alignment | Does the headline match the visitor's search intent or ad copy they clicked? |

**Common headline formulas to identify:**
- **How to [achieve desired outcome]** -- practical, search-friendly
- **[Number] ways to [benefit]** -- listicle, curiosity-driven
- **[Question that hooks]?** -- engages by demanding an answer
- **[Command verb] your [outcome]** -- direct, action-oriented
- **[Specific result] in [timeframe]** -- concrete promise
- **The [adjective] way to [outcome]** -- implies there is a better way
- **Stop [pain point]. Start [benefit].** -- contrast-driven

**Score weighting:** 20% of Copy Conversion Score.

### 2. Subheadline

The subheadline expands on the headline and bridges to the body copy.

| Criterion | What to Check |
|-----------|---------------|
| Supports headline | Does it add detail to the headline's promise, not repeat or contradict it? |
| Adds specificity | Does it answer the "how" or "for whom" that the headline left open? |
| Maintains interest | Does it create enough curiosity to keep the visitor reading? |
| Length | 1-2 sentences maximum. Flag if longer -- it becomes body copy. |

**Score weighting:** 5% of Copy Conversion Score.

### 3. Value Proposition Copy

Assess the overall value proposition messaging, not just the headline.

| Criterion | What to Check |
|-----------|---------------|
| Clarity | Can you explain what they offer, who it's for, and why it's better in one sentence? |
| Specificity | Concrete outcomes > vague promises. Numbers, percentages, timeframes. |
| Differentiation | Why this over competitors? What is said here that competitors cannot say? |
| Objection handling | Are the top 2-3 buying objections addressed proactively in the copy? |
| "You" language | Is the copy about the customer, or about the company? Count "you/your" vs "we/our/I". Target: 2:1 ratio. |
| Proof stacking | Are claims supported by evidence immediately? (Data, testimonial, case study, demo) |

**Score weighting:** 15% of Copy Conversion Score.

### 4. Benefit vs Feature Ratio

Features tell, benefits sell. Count and categorize.

| Element | How to Identify |
|---------|-----------------|
| Feature | Describes what the product does or has. Technical specifications. ("256GB storage") |
| Benefit | Describes what the customer gains or avoids. Emotional or practical outcome. ("Never run out of space for your photos") |
| Feature disguised as benefit | Uses benefit language but is really a feature. ("Advanced AI algorithm" -- what does it DO for the user?) |

**Evaluation:**
- Count total distinct features mentioned on the page.
- Count total distinct benefits mentioned on the page.
- Calculate ratio. Target: at least 2 benefits per feature.
- For each feature without a corresponding benefit, provide a suggested rewrite.

**Score weighting:** 10% of Copy Conversion Score.

### 5. CTA Text

Every CTA button and link text on the page.

| Criterion | What to Check |
|-----------|---------------|
| Action verbs | Starts with a verb? (Get, Start, Download, Join, Discover, Claim, Try) |
| First-person vs second-person | "Start My Free Trial" (first-person, higher conversion) vs "Start Your Free Trial" |
| Specificity | "Get My Free CRO Checklist" > "Download" > "Submit" |
| Urgency language | "Get Instant Access", "Start Free Today", "Claim Your Spot" |
| Risk reduction | Does the CTA text reduce perceived risk? ("Start Free", "No Credit Card Required", "Cancel Anytime") |
| Consistency | Do all instances of the same CTA use the same text? Inconsistency causes confusion. |
| Ghost/secondary CTA text | Are secondary CTAs clearly lower-commitment? ("Learn More", "See How It Works") |

**Score weighting:** 15% of Copy Conversion Score.

### 6. Social Proof Copy

How testimonials, case studies, and reviews are written matters as much as their
presence.

| Criterion | What to Check |
|-----------|---------------|
| Specificity | "Revenue increased 43% in 60 days" > "Great product, really helped us" |
| Relevance | Do testimonials match the target audience? B2B testimonial on a B2C page is mismatched. |
| Objection handling | Do testimonials address common objections? ("I was skeptical at first, but...") |
| Outcome focus | Do they describe the RESULT, not just the experience of using the product? |
| Attribution | Full name, title, company, photo? Each missing element reduces credibility. |
| Case study language | Does it follow Problem -> Solution -> Result structure? Are results quantified? |
| Number specificity | "10,000+ customers" is stronger than "thousands of customers". Exact numbers beat rounded numbers. |

**Score weighting:** 10% of Copy Conversion Score.

### 7. Urgency and Scarcity

Urgency drives action. But fake urgency destroys trust.

| Criterion | What to Check |
|-----------|---------------|
| Legitimate urgency | Real deadline, limited inventory, seasonal offer, cohort-based enrollment? |
| Countdown timers | Present? Real or evergreen/fake? (Fake timers are a trust destroyer.) |
| Limited availability | "Only 3 spots left" -- is it credible? Is there evidence of scarcity? |
| FOMO triggers | "Join 10,000+ marketers", "Don't miss out", "Others are viewing this now" |
| Loss framing | Does the copy frame inaction as a loss? ("Every day without X costs you Y") |
| Ethical check | Flag any urgency/scarcity that appears fabricated or manipulative. Note it in the report. |

**Score weighting:** 5% of Copy Conversion Score.

### 8. Readability

Copy that is hard to read does not get read. And copy that is not read cannot
convert.

| Metric | Target | How to Assess |
|--------|--------|---------------|
| Flesch-Kincaid Grade Level | 6th-8th grade | Calculate from sentence length and syllable count |
| Average sentence length | 15-20 words | Count words per sentence across body copy |
| Average paragraph length | 2-4 sentences | Shorter paragraphs improve scanability |
| Jargon density | Minimal | Flag industry jargon that the target audience may not understand |
| Passive voice | < 10% of sentences | Active voice is more direct and persuasive |
| Transition words | Present | "Because", "Here's why", "That means" -- guide the reader forward |

**Score weighting:** 5% of Copy Conversion Score.

### 9. Emotional Triggers

Read `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md` for the complete framework. Evaluate
which of these triggers are activated in the page copy.

| Trigger | Example in Copy |
|---------|-----------------|
| Fear/Pain | "Losing customers to competitors?", "Don't let [bad thing] happen" |
| Trust | "Trusted by [authority]", "100% money-back guarantee" |
| Curiosity | "The surprising reason...", "What most people don't know about..." |
| Belonging | "Join 50,000+ marketers", "Trusted by teams at Google, Stripe, and Notion" |
| Achievement | "Become the top performer", "Master [skill] in 30 days" |
| Exclusivity | "Invite-only", "For serious [audience] only", "Premium members get..." |

**Evaluation:** Identify which triggers are used, which are missing, and
recommend adding 1-2 underutilized triggers. Note: not every page needs all
triggers. Match to audience and business type.

**Score weighting:** 5% of Copy Conversion Score.

### 10. Micro-Copy

The small text that guides users through interactions. Often overlooked, but
high-impact on form completion and checkout conversion.

| Element | What to Check |
|---------|---------------|
| Button text | Beyond the primary CTA -- navigation buttons, "Learn more" links, tab labels |
| Form labels | Clear, concise, unambiguous? "Full Name" vs "Name" vs "Your Name" |
| Error messages | Helpful and specific? "Please enter a valid email" > "Invalid input" |
| Tooltips | Present where needed? Concise? Trigger on hover AND focus? |
| Confirmation messages | After form submission, what does the user see? Is next step clear? |
| Empty states | If applicable, what does the user see when there is no data? Is it helpful? |
| Loading text | During waits, is there reassuring copy? ("Securing your spot...", "Almost there...") |

**Score weighting:** 5% of Copy Conversion Score.

### 11. Power Words

High-conversion words that trigger psychological responses. Count and assess
their usage.

**Power word categories:**
- **Trust:** Proven, guaranteed, certified, authentic, official, verified
- **Urgency:** Now, today, instant, immediately, hurry, limited, deadline, last chance
- **Value:** Free, bonus, exclusive, premium, save, discount, bargain
- **Curiosity:** Secret, hidden, revealed, discover, unlock, insider
- **Safety:** Risk-free, no-obligation, cancel anytime, money-back, secure, protected
- **Results:** Proven, results, transform, boost, increase, maximize, accelerate

**Evaluation:** Count total power words used. Assess distribution across
categories. Flag overuse of any single category (especially urgency -- it can
feel desperate). Recommend adding power words from underrepresented categories.

**Score weighting:** 5% of Copy Conversion Score.

---

## Scoring

### Copy Conversion Score Calculation

| Section | Weight |
|---------|--------|
| Headline Analysis | 20% |
| Subheadline | 5% |
| Value Proposition Copy | 15% |
| Benefit vs Feature Ratio | 10% |
| CTA Text | 15% |
| Social Proof Copy | 10% |
| Urgency & Scarcity | 5% |
| Readability | 5% |
| Emotional Triggers | 5% |
| Micro-Copy | 5% |
| Power Words | 5% |
| **Total** | **100%** |

### Score Interpretation

| Score | Rating | Meaning |
|-------|--------|---------|
| 90-100 | Exceptional | Copy is conversion-optimized, persuasive, and well-structured. |
| 70-89 | Strong | Good copy with specific areas to tighten. |
| 50-69 | Moderate | Copy is functional but leaves significant conversion on the table. |
| 30-49 | Weak | Major copy issues actively hurting conversion. Rewrite priority areas. |
| 0-29 | Critical | Copy is unclear, unfocused, or counterproductive. Full rewrite needed. |

---

## Output Format

```markdown
## CRO Copy Analysis: [URL]

**Business Type:** [Detected type]
**Copy Conversion Score:** [Score]/100 -- [Rating]
**Date:** [Current date]

### Executive Summary
[2-3 sentences on copy strengths and the most impactful weaknesses]

### Score Breakdown

| Section | Score | Key Finding |
|---------|-------|-------------|
| Headline | /100 | |
| Subheadline | /100 | |
| Value Proposition | /100 | |
| Benefit:Feature Ratio | /100 | [X:Y ratio] |
| CTA Text | /100 | |
| Social Proof Copy | /100 | |
| Urgency & Scarcity | /100 | |
| Readability | /100 | [Flesch grade] |
| Emotional Triggers | /100 | [X of 6 used] |
| Micro-Copy | /100 | |
| Power Words | /100 | [X total found] |

### Readability Metrics

| Metric | Value | Target | Status |
|--------|-------|--------|--------|
| Flesch-Kincaid Grade | | 6-8 | |
| Avg Sentence Length | | 15-20 words | |
| Avg Paragraph Length | | 2-4 sentences | |
| Passive Voice | | < 10% | |

### Detailed Findings

#### Headline
**Current:** "[exact headline text]"
**Formula:** [identified formula]
**Issues:** [specific problems]
**Suggested Rewrite:** "[improved headline]"
**Why:** [reasoning based on copy principles]

#### CTA Text
**Current CTAs found:**
- "[CTA 1 text]" -- [location on page]
- "[CTA 2 text]" -- [location on page]
**Issues:** [specific problems]
**Suggested Rewrites:**
- "[Improved CTA 1]" -- [reasoning]

[... repeat for each section with issues ...]

### Benefit vs Feature Inventory

| Statement | Type | Suggested Rewrite (if feature) |
|-----------|------|-------------------------------|
| "[text]" | Feature | "[benefit rewrite]" |
| "[text]" | Benefit | -- |

### Emotional Trigger Map

| Trigger | Used? | Where | Recommendation |
|---------|-------|-------|----------------|
| Fear/Pain | | | |
| Trust | | | |
| Curiosity | | | |
| Belonging | | | |
| Achievement | | | |
| Exclusivity | | | |

### Prioritized Rewrite Recommendations

1. **[Critical]** [Specific rewrite with before/after]
2. **[High]** [Specific rewrite...]
3. **[Medium]** [Specific rewrite...]
4. **[Low]** [Specific rewrite...]
```

---

## Cross-References

- **Full page analysis:** Use the `cro-page` sub-skill when copy is part of a broader page audit
- **UX evaluation:** Use the `cro-ux` sub-skill for layout and interaction analysis
- **Trust audit:** Use the `cro-trust` sub-skill for social proof element assessment
- **A/B testing:** Use the `cro-testing` sub-skill to turn copy findings into test hypotheses
- **Psychology:** Read `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md` for the full persuasion principles framework
- **Quality gates:** Read `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md` for minimum copy requirements by business type
