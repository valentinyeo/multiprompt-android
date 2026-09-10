---
name: cro-trust
description: >
  Trust signals and social proof audit evaluating reviews, testimonials,
  security badges, guarantees, authority indicators, and contact information
  visibility. Use when user says "trust audit", "social proof", "trust signals",
  "credibility check", or "why visitors don't trust my site".
argument-hint: "<url>"
allowed-tools: Read, Grep, Glob, Bash, WebFetch
---

# CRO Trust Signals Audit

Evaluate the completeness and effectiveness of trust signals, social proof, and
credibility indicators on a page. Trust is a prerequisite for conversion -- no
amount of persuasive copy or beautiful design compensates for missing trust.

---

## Process

1. **Fetch the page** using `${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py`. Store the full HTML.
2. **Extract trust elements** using `${CLAUDE_SKILL_DIR}/../cro/scripts/parse_cro.py`. This identifies
   testimonials, review widgets, logos, badges, guarantees, contact info,
   certifications, and other trust indicators.
3. **Detect the business type** to determine industry-specific trust
   requirements. Read `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md` for the minimum trust
   requirements per business type.
4. **Evaluate each trust category** against the criteria below.
5. **Perform a gap analysis** -- compare what is present against what is required
   for the detected business type.
6. **Calculate the Trust Score** (0-100).
7. **Generate the trust audit report** with gap analysis and prioritized
   recommendations.

---

## Analysis Sections

### 1. Social Proof

Social proof is the most powerful trust mechanism. People trust what others
have validated.

#### Testimonials

| Criterion | What to Check | Impact |
|-----------|---------------|--------|
| Presence | Are customer testimonials on the page? | Critical -- missing testimonials is a major gap |
| Specificity | Do they include specific results, numbers, or outcomes? | "Revenue up 43% in 60 days" >> "Great product" |
| Attribution | Full name? Job title? Company? Photo? | Each missing element reduces credibility |
| Relevance | Do testimonials match the target audience visiting this page? | B2B testimonials on a B2C page is a mismatch |
| Recency | Are dates shown? Are testimonials recent? | Stale testimonials (2+ years) reduce trust |
| Quantity | How many testimonials? | 3-5 is a good range. 1 is too few. 20+ can feel fabricated. |
| Placement | Near conversion points? Above the fold or strategically placed? | Testimonials far from CTAs are less effective |
| Objection handling | Do any testimonials address common objections? | "I was skeptical but..." is highly effective |
| Format | Text only? Video? Audio? | Video testimonials are most persuasive |

#### Review Widgets

| Criterion | What to Check |
|-----------|---------------|
| Third-party reviews | Google Reviews, Trustpilot, G2, Capterra, Yelp embedded? |
| Platform credibility | Third-party widgets >> self-hosted reviews (no edit control = more trust) |
| Star rating | Average rating visible? 4.5+ is strong. Below 4.0 can hurt. |
| Review count | Total count displayed? Higher counts = more statistical trust. |
| Recent reviews | Are recent reviews visible? Shows ongoing customer satisfaction. |
| Response to negative | Does the business respond to negative reviews? Shows accountability. |

#### Social Proof Numbers

| Criterion | What to Check |
|-----------|---------------|
| Customer count | "10,000+ customers" style claims. Present? Specific? |
| Usage metrics | "1M+ downloads", "50K+ teams", "100+ countries" |
| Exact vs rounded | "10,847 customers" (exact) > "thousands of customers" (vague) |
| Growth indicators | "Fastest growing", "10x growth in 2024" |
| Third-party validation | AppSumo ratings, ProductHunt upvotes, G2 badges |

#### Client/Partner Logos

| Criterion | What to Check |
|-----------|---------------|
| Recognizable brands | Are logos of well-known companies shown? |
| Quantity | 5-8 logos is optimal. Too few looks weak. Too many looks cluttered. |
| Relevance | Do the logos match the target audience's industry or aspirational peers? |
| Display quality | Properly sized, consistent styling, clear/visible? Not pixelated? |
| Label | "Trusted by", "Used by teams at", "Our customers include" -- appropriate framing? |

#### Additional Social Proof

| Element | What to Check |
|---------|---------------|
| Case studies | Linked? Detailed? Problem-Solution-Result structure? With metrics? |
| Media mentions | "As seen in..." with recognizable media logos? |
| User-generated content | Customer photos, social media embeds, community content? |
| Community size | Forum members, Slack community, social followers? |

---

### 2. Security and Safety

Visitors need to feel safe before they share personal information or payment
details.

| Element | What to Check | Required For |
|---------|---------------|-------------|
| SSL certificate | Valid? EV (Extended Validation) for higher trust? | All sites |
| Payment security badges | PCI compliance, Norton Secured, McAfee Secure, SSL badges | E-commerce, SaaS with payments |
| Privacy policy | Linked in footer and near forms? Accessible? Clearly written? | All sites |
| Terms of service | Present? Linked? | All sites |
| GDPR/CCPA compliance | Cookie consent banner? Opt-out mechanism? Data deletion option? | Sites targeting EU/California visitors |
| Data handling transparency | How is data used? Where is it stored? Who has access? | SaaS, lead gen |
| Cookie consent | Properly implemented? Not just a dismissable banner? | All sites (EU) |
| Secure checkout indicators | Lock icons, "Secure checkout" text, trusted payment logos | E-commerce |

---

### 3. Guarantees and Risk Reducers

Every conversion requires the visitor to take a risk. Risk reducers lower the
perceived cost of that risk.

| Element | What to Check | Effectiveness |
|---------|---------------|---------------|
| Money-back guarantee | Duration stated? (30-day, 60-day, 90-day) Clear conditions? Easy process? | Very high -- longer = more trust |
| Free trial | Duration? No credit card required? Easy to start? Clear what's included? | Very high for SaaS |
| Freemium tier | Available? Useful enough to demonstrate value? Clear upgrade path? | High for SaaS |
| Free shipping | Threshold visible? Free shipping messaging near price/CTA? | High for e-commerce |
| Return policy | Clearly stated? Generous? Easy to find? Near purchase decision? | High for e-commerce |
| Satisfaction guarantee | "100% satisfaction or your money back" -- prominent? Unconditional? | High for services |
| "No credit card required" | Near signup CTA? Reduces the biggest SaaS signup barrier. | Very high for SaaS trials |
| "Cancel anytime" | Present for subscription products? Reduces commitment anxiety. | High for subscriptions |
| Risk-free language | "Try risk-free", "No obligation", "No strings attached" | Medium -- supportive |

---

### 4. Authority Signals

Authority establishes expertise and legitimacy.

| Element | What to Check |
|---------|---------------|
| Industry certifications | ISO, SOC2, HIPAA, PCI-DSS, relevant industry certifications visible? |
| Awards and recognition | Industry awards, "Best of" lists, editor's choice badges? |
| Years in business | "Since 2010", "15+ years experience" -- establishes longevity? |
| Team/founder credibility | Notable backgrounds, relevant experience, speaking engagements, publications? |
| Professional associations | Industry body memberships, partnerships? |
| Expert endorsements | Recommendations from recognized experts or thought leaders? |
| Patent/proprietary tech | "Patented technology", "Proprietary algorithm" -- unique claims backed up? |
| Accreditation | BBB, industry-specific accreditations? |
| Content authority | Blog, research, whitepapers, original data that demonstrate expertise? |

---

### 5. Contact and Transparency

Being reachable and transparent signals legitimacy. Scam sites hide their
identity. Legitimate businesses make it easy to reach them.

| Element | What to Check | Importance |
|---------|---------------|------------|
| Phone number | Visible in header/footer? Clickable on mobile? Local or toll-free? | Critical for local/service businesses |
| Physical address | On the page or in footer? Google Maps link? | High for local, medium for others |
| Email address | Contact email visible? Not just a form? | Medium -- form is OK if email is also available |
| Live chat | Chat widget present? Is it AI/bot or human? Response time expectations? | High for SaaS and e-commerce |
| Response time | "We respond within 24 hours" or similar expectation setting? | Medium -- reduces contact anxiety |
| About page | Exists? Shows real people? Tells the company story? | High for all business types |
| Team photos | Real photos of real people? Not stock photos? Names and roles? | High -- humanizes the business |
| Social media links | Active profiles linked? Recent activity? | Medium -- validates existence |
| Contact page | Dedicated page? Easy to find in navigation? | High for lead gen and services |

---

### 6. Industry-Specific Requirements

Different business types have different minimum trust requirements. Read
`${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md` for the complete list.

#### E-commerce Minimum Trust Requirements

| Element | Status Required |
|---------|----------------|
| Customer reviews on product pages | REQUIRED |
| Payment security badges at checkout | REQUIRED |
| Return/refund policy clearly stated | REQUIRED |
| Shipping information visible | REQUIRED |
| Contact information (phone or chat) | REQUIRED |
| SSL certificate | REQUIRED |
| Trust badges near "Add to Cart" | STRONGLY RECOMMENDED |
| Size guides / product details | RECOMMENDED for apparel |

#### SaaS Minimum Trust Requirements

| Element | Status Required |
|---------|----------------|
| Customer testimonials with attribution | REQUIRED |
| Client/partner logos | REQUIRED |
| Security compliance (SOC2, GDPR, etc.) | REQUIRED for B2B |
| Free trial or demo | STRONGLY RECOMMENDED |
| Case studies with metrics | STRONGLY RECOMMENDED |
| Uptime/SLA information | RECOMMENDED |
| Integration partner logos | RECOMMENDED |

#### Lead Gen Minimum Trust Requirements

| Element | Status Required |
|---------|----------------|
| Phone number visible | REQUIRED |
| Physical address | REQUIRED |
| Privacy statement near form | REQUIRED |
| Testimonials or reviews | STRONGLY RECOMMENDED |
| Professional certifications | STRONGLY RECOMMENDED for services |
| Portfolio / case studies | RECOMMENDED |
| Response time commitment | RECOMMENDED |

#### Local Service Minimum Trust Requirements

| Element | Status Required |
|---------|----------------|
| Phone number (prominent, clickable) | REQUIRED |
| Physical address with map | REQUIRED |
| Google Reviews widget | REQUIRED |
| Service area clearly defined | REQUIRED |
| Licensing / insurance information | STRONGLY RECOMMENDED |
| Before/after photos or portfolio | RECOMMENDED |
| Team photos | RECOMMENDED |

---

## Scoring

### Trust Score Calculation

| Category | Weight |
|----------|--------|
| Social Proof | 30% |
| Security & Safety | 20% |
| Guarantees & Risk Reducers | 20% |
| Authority Signals | 15% |
| Contact & Transparency | 15% |
| **Total** | **100%** |

**Industry requirement compliance:** If a REQUIRED element (from the
industry-specific section) is missing, apply a -10 point penalty to the
overall Trust Score per missing element (regardless of category weighting).
This reflects that missing required elements are deal-breakers for the
business type.

### Score Interpretation

| Score | Rating | Meaning |
|-------|--------|---------|
| 90-100 | Exceptional | Comprehensive trust infrastructure. Visitors feel confident converting. |
| 70-89 | Strong | Good trust foundation with specific gaps to address. |
| 50-69 | Moderate | Notable trust gaps likely causing abandonment. Action needed. |
| 30-49 | Weak | Significant trust deficit. Many visitors will not convert due to trust concerns. |
| 0-29 | Critical | Trust infrastructure is fundamentally lacking. Conversion severely impacted. |

---

## Output Format

```markdown
## CRO Trust Audit: [URL]

**Business Type:** [Detected type]
**Trust Score:** [Score]/100 -- [Rating]
**Date:** [Current date]

### Executive Summary
[2-3 sentences on overall trust level and the most critical gaps]

### Score Breakdown

| Category | Score | Key Gap |
|----------|-------|---------|
| Social Proof | /100 | |
| Security & Safety | /100 | |
| Guarantees & Risk Reducers | /100 | |
| Authority Signals | /100 | |
| Contact & Transparency | /100 | |

### Industry Requirements Compliance

| Requirement | Status | Priority |
|-------------|--------|----------|
| [Element] | Present / MISSING / Partial | Critical / High / Medium |
| ... | ... | ... |

**Compliance Rate:** [X of Y required elements present]

### Trust Signal Inventory

#### Social Proof Found
- [List each social proof element found with quality assessment]

#### Social Proof Missing
- [List expected elements not found]

#### Security & Safety
- [Current state]
- [Gaps]

#### Guarantees
- [Current state]
- [Gaps]

#### Authority
- [Current state]
- [Gaps]

#### Contact & Transparency
- [Current state]
- [Gaps]

### Gap Analysis

| Gap | Impact on Conversion | Effort to Fix | Priority |
|-----|---------------------|---------------|----------|
| [Missing element] | [High/Medium/Low] | [Easy/Medium/Hard] | [Critical/High/Medium/Low] |
| ... | ... | ... | ... |

### Recommendations (Prioritized)

1. **[Critical]** [Specific action to add a missing required element]
2. **[High]** [Action to strengthen existing weak trust signal]
3. **[Medium]** [Action to add recommended trust element]
4. **[Low]** [Action to polish existing trust signals]

### Trust-Focused A/B Test Ideas

| Test | Hypothesis | Expected Impact |
|------|-----------|-----------------|
| [Name] | If we [add trust element], then [metric] will [improve] because [trust principle] | [Est.] |
```

---

## Cross-References

- **Full page analysis:** Use the `cro-page` sub-skill for complete page-level conversion audit
- **Copy analysis:** Use the `cro-copy` sub-skill for social proof copy quality (testimonial wording, etc.)
- **UX evaluation:** Use the `cro-ux` sub-skill for trust element placement and visibility
- **Form optimization:** Use the `cro-forms` sub-skill for form-adjacent trust elements
- **A/B testing:** Use the `cro-testing` sub-skill to turn trust gaps into test hypotheses
- **Psychology:** Read `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md` for the psychology behind trust and social proof
- **Quality gates:** Read `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md` for complete industry-specific trust requirements
- **Benchmarks:** Read `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md` for trust element impact data
