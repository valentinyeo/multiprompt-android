---
name: cro-ecommerce
description: >
  E-commerce specific conversion optimization covering product detail pages,
  add-to-cart prominence, cart page design, checkout flow, pricing pages,
  urgency elements, and cross-sell/upsell patterns. Use when user says
  "ecommerce CRO", "product page", "PDP optimization", "cart page", "checkout
  optimization", "pricing page", or "add to cart".
argument-hint: "<url>"
allowed-tools: Read, Grep, Glob, Bash, WebFetch
---

# CRO E-commerce Analysis

Perform a specialized conversion audit for e-commerce pages. This skill
evaluates the complete shopping experience from product discovery through
purchase completion, applying e-commerce-specific UX patterns and conversion
psychology. It covers product detail pages, cart design, checkout flows, pricing
presentations, and urgency/scarcity elements.

---

## Process

1. **Fetch the page** using `${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py`. Store the raw HTML for
   analysis.
2. **Detect e-commerce platform** by examining HTML source signatures:
   - Shopify: `cdn.shopify.com`, `Shopify.` JS object, `/cart.js` endpoint
   - WooCommerce: `woocommerce`, `wc-` prefixed classes, `wp-content`
   - Magento: `mage/`, `Magento_`, `catalogsearch`
   - BigCommerce: `bigcommerce.com`, `stencil`
   - Custom: No recognizable platform signatures
3. **Extract e-commerce elements** using `${CLAUDE_SKILL_DIR}/../cro/scripts/parse_cro.py`. Focus on
   product data, pricing, cart elements, checkout forms, and trust signals.
4. **Take screenshots** if Playwright is available (desktop 1440px and mobile
   375px). Use to evaluate add-to-cart prominence, price visibility, and
   checkout layout.
5. **Evaluate against e-commerce UX patterns** by reading
   `${CLAUDE_SKILL_DIR}/../cro/references/ux-heuristics.md` for general heuristics and
   `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md` for e-commerce-specific thresholds.
6. **Score each section** and generate the comprehensive e-commerce CRO report.

---

## Analysis Sections

### 1. Product Detail Page (PDP)

The PDP is where the buying decision happens. Every element must reduce
uncertainty and build desire.

#### Product Images

| Criterion | What to Check |
|-----------|---------------|
| Image count | Minimum 4 product images. Best-in-class: 6-8 including lifestyle shots. |
| Image quality | High resolution? Professional photography? Consistent lighting and background? |
| Zoom capability | Can users zoom in on desktop (hover) and mobile (pinch)? |
| Multiple angles | Front, back, side, detail shots? 360-degree view available? |
| Lifestyle shots | Product shown in use or in context? Helps visitors imagine ownership. |
| Video | Product video available? Demo, unboxing, or how-to-use? |
| Size reference | Scale indicators, worn-on-model shots with model dimensions, or comparison objects? |
| User-generated | Customer photos in reviews or gallery? Adds authenticity. |
| Thumbnail navigation | Easy to navigate between images? Visible thumbnails or swipe indicators? |

#### Product Title and Description

| Criterion | What to Check |
|-----------|---------------|
| Title clarity | Descriptive, includes key attributes (brand, product type, key feature)? |
| Benefit orientation | Does the description lead with benefits before specifications? |
| Scanability | Bullet points for key features? Short paragraphs? Bold highlights? |
| Completeness | All information needed to buy: dimensions, materials, compatibility, care instructions? |
| SEO alignment | Does the title match likely search queries? |

#### Price Display

| Criterion | What to Check |
|-----------|---------------|
| Price visibility | Is the price immediately visible without scrolling? Large, clear font? |
| Discount presentation | Original price crossed out with sale price? Percentage saved shown? |
| Per-unit pricing | For multipacks or subscriptions: is the per-unit price displayed? |
| Currency | Correct currency for the market? Auto-detected or selectable? |
| Payment installments | Klarna, Afterpay, Affirm messaging? "As low as $X/month"? |
| Price anchoring | Is the price contextualized? (Reference `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md` for anchoring.) |

#### Add to Cart Button

| Criterion | What to Check |
|-----------|---------------|
| Prominence | Is ATC the most visually dominant element on the page? High contrast? |
| Position | Above the fold on desktop? Visible without scrolling on mobile? |
| Size | Large enough to be unmissable. Minimum 48px height, full-width on mobile? |
| Color | High contrast against background? Consistent with site's primary action color? |
| Text | Clear action text: "Add to Cart", "Add to Bag". Avoid generic "Submit" or "Buy". |
| Sticky on mobile | Does the ATC button become sticky/fixed when scrolling on mobile? |
| State feedback | Does the button change state on click (loading, "Added!", animation)? |
| Disabled state | If variant not selected, is ATC disabled with clear messaging? |

#### Variant Selection

| Criterion | What to Check |
|-----------|---------------|
| Size selector | Clear UI (buttons, dropdown)? Size guide linked nearby? |
| Color selector | Color swatches with names? Product image updates on selection? |
| Stock per variant | Is stock status shown per variant? ("Size M - Only 2 left") |
| Pre-selection | Is a default variant pre-selected, or must the user choose? |
| Error prevention | Clear indication if a variant is required before adding to cart? |

#### Reviews and Ratings

| Criterion | What to Check |
|-----------|---------------|
| Star display | Average star rating visible near product title and price? |
| Review count | Total number of reviews shown? (Higher count = more trust) |
| Review content | Are reviews detailed? Do they mention specific product attributes? |
| Review photos | Customer-submitted photos? Adds significant credibility. |
| Sorting/filtering | Can reviews be sorted by rating, recency, or helpfulness? |
| Negative reviews | Are negative reviews present? (100% positive looks fake) Response from brand? |
| Verified purchase | Are reviews marked as verified purchases? |
| Third-party badge | Trustpilot, Bazaarvoice, Yotpo, or Judge.me widget? |

#### Supporting Information

| Criterion | What to Check |
|-----------|---------------|
| Shipping info | Cost, delivery estimate, free shipping threshold visible near ATC? |
| Return policy | Return window and conditions visible near buy button? |
| Trust badges | Payment, security, and guarantee badges near ATC? |
| Availability | In-stock indicator? Delivery date estimate? |
| Cross-sell | "Complete the look", "Frequently bought together" sections? |
| Recently viewed | Recently viewed products to aid comparison shopping? |
| Wishlist/save | Can users save for later without buying? |

### 2. Cart Page

The cart page must reassure and move users forward while minimizing friction.

| Criterion | What to Check |
|-----------|---------------|
| Cart summary | All items clearly listed with thumbnails, names, prices, quantities? |
| Edit quantity | Easy +/- buttons or dropdown? No need to click "update cart"? |
| Remove item | Clear remove/delete option per item? Not hidden behind confusing UI? |
| Product thumbnails | Product images visible in cart? Helps confirm correct items. |
| Price breakdown | Subtotal, shipping estimate, tax, and total clearly separated? |
| Promo code field | Present but not dominant? Visible but does not distract from checkout. |
| Empty promo code | Does an empty promo field make users leave to search for codes? |
| Continue shopping | Link to return to products? Not more prominent than checkout CTA. |
| Checkout CTA | Checkout button is the most prominent element. Above and below cart items. |
| Cross-sell | Relevant "You may also like" suggestions? Not overwhelming? 3-4 items max. |
| Upsell | "Upgrade to XL for $5 more", free shipping threshold nudge? |
| Estimated delivery | Delivery date estimate visible in cart? |
| Saved for later | Can users move items to a wishlist instead of removing? |
| Cart persistence | Does the cart persist across sessions and devices (if logged in)? |
| Mini-cart | Is there a slide-out mini-cart that avoids full page load? |

### 3. Checkout Flow

Checkout is where most revenue is lost. Every element of friction costs money.

| Criterion | What to Check |
|-----------|---------------|
| Guest checkout | Can users complete purchase without creating an account? |
| Step count | How many pages/steps? Single-page, 2-step, or multi-step? (Fewer = less abandonment) |
| Progress indicator | Visual progress bar or numbered steps showing where user is? |
| Form optimization | Cross-reference with `cro-forms` sub-skill for field-level analysis. |
| Address autofill | Google Places or similar address autocomplete? |
| Payment options | Credit/debit, PayPal, Apple Pay, Google Pay, BNPL (Klarna/Afterpay)? |
| Express checkout | Apple Pay / Google Pay / Shop Pay at the top of checkout? |
| Shipping options | Clear shipping options with prices and delivery estimates? |
| Free shipping threshold | If close to free shipping, is the user nudged to add more? |
| Order summary | Visible throughout checkout? Especially on payment step? |
| Trust signals | SSL indicator, security badges, payment logos visible in checkout? |
| Error handling | Inline validation? Clear error messages? Fields retain values on error? |
| Back navigation | Can users go back without losing data? |
| Order editing | Can users edit cart contents from checkout without losing progress? |
| Mobile checkout | Optimized for mobile? Correct input types? No horizontal scrolling? |
| Distraction removal | Is site navigation removed or minimized during checkout? |
| Terms and conditions | Clear, not blocking? Checkbox not pre-checked (GDPR)? |

### 4. Pricing Page

For subscription products, service tiers, or B2B e-commerce with plan-based pricing.

| Criterion | What to Check |
|-----------|---------------|
| Plan comparison | Clear columns or cards comparing plan tiers? |
| Recommended plan | Is one plan visually highlighted as "Most Popular" or "Best Value"? |
| Price anchoring | Is the highest-priced plan shown first to anchor? (Reference `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md`.) |
| Feature matrix | Clear feature comparison grid? Checkmarks vs X marks? |
| Readability | Can users quickly identify differences between plans? |
| CTA per plan | Each plan has its own CTA button? Clear and action-oriented? |
| Annual vs monthly | Toggle available? Annual savings clearly shown (both % and $/year)? |
| Annual pre-selected | Is the annual billing (higher LTV) pre-selected or highlighted? |
| Money-back guarantee | Visible near pricing? Reduces purchase risk. |
| FAQ | Frequently asked questions near pricing to handle objections? |
| Enterprise/custom | "Contact us" option for enterprise needs? |
| Free trial/freemium | Is there a free tier or trial to reduce commitment barrier? |
| Social proof | Logos or testimonials near pricing? "Trusted by X companies"? |

### 5. Urgency and Scarcity Elements

Urgency and scarcity are powerful conversion levers, but fake urgency
destroys trust.

| Criterion | What to Check |
|-----------|---------------|
| Countdown timers | Present? Is the timer tied to a real event (sale end, limited offer)? |
| Timer legitimacy | Does the timer reset on page refresh? (If yes, it is fake. Flag this.) |
| Stock indicators | "Only X left in stock" -- real inventory data or manufactured scarcity? |
| Limited time offers | "Sale ends Sunday" -- is the deadline real and consistent across pages? |
| Flash sale indicators | Banner or badge indicating a time-limited sale? |
| Social proof urgency | "12 people viewing this", "Bought 47 times today" -- real data or fabricated? |
| Booking pressure | "Only 2 rooms left at this price" -- hospitality/travel pattern. |
| Authenticity assessment | Overall, are urgency elements genuine or manipulative? |

**Urgency Authenticity Rating:**

| Rating | Meaning |
|--------|---------|
| Authentic | Urgency elements reflect real constraints (limited inventory, genuine deadline) |
| Mixed | Some real urgency mixed with embellished elements |
| Manufactured | Fake timers, artificial scarcity, dark patterns. Risk of trust damage. |

---

## Scoring

### E-commerce CRO Score Calculation

| Section | Weight | Notes |
|---------|--------|-------|
| Product Detail Page | 30% | Scored only if PDP is analyzed |
| Cart Page | 15% | Scored only if cart is analyzed |
| Checkout Flow | 20% | Scored only if checkout is analyzed |
| Pricing Page | 15% | Scored only if pricing page exists |
| Urgency & Scarcity | 10% | Authenticity matters -- fake urgency scores negatively |
| Cross-sell/Upsell | 10% | Quality and relevance of recommendations |
| **Total** | **100%** | |

If certain sections are not applicable (e.g., no pricing page), redistribute
weight proportionally across analyzed sections.

### Sub-Scores

Each section receives its own score (0-100):

| Score | Rating | Meaning |
|-------|--------|---------|
| 90-100 | Exceptional | Best-in-class e-commerce execution |
| 70-89 | Strong | Solid with clear optimization opportunities |
| 50-69 | Moderate | Common e-commerce gaps. Notable revenue being left on the table. |
| 30-49 | Weak | Major e-commerce conversion barriers. Urgent fixes needed. |
| 0-29 | Critical | Fundamental issues blocking purchases. Significant rework required. |

---

## Output Format

```markdown
## E-commerce CRO Analysis: [URL]

**Platform Detected:** [Shopify/WooCommerce/Magento/BigCommerce/Custom]
**Page Type:** [PDP/Cart/Checkout/Pricing/Multiple]
**E-commerce CRO Score:** [Score]/100 -- [Rating]
**Date:** [Current date]

### Executive Summary
[2-3 sentences: what this e-commerce experience does well, what is costing
conversions, and the single highest-impact opportunity]

### Score Breakdown

| Section | Score | Rating | Key Issue |
|---------|-------|--------|-----------|
| Product Detail Page | /100 | | |
| Cart Page | /100 | | |
| Checkout Flow | /100 | | |
| Pricing Page | /100 | | |
| Urgency & Scarcity | /100 | | |
| Cross-sell/Upsell | /100 | | |

### Critical Issues (Revenue Impact)
- [P0] [Finding] -- Estimated revenue impact: [High/Medium/Low]
- ...

### High Priority Issues
- [P1] [Finding] -- [Section]
- ...

### Detailed Findings

#### Product Detail Page
[Findings with specific observations, element-by-element]

#### Cart Page
[Findings...]

#### Checkout Flow
[Findings...]

#### Pricing Page
[Findings...]

#### Urgency & Scarcity
[Findings with authenticity rating]

### Platform-Specific Recommendations
[Recommendations tailored to the detected platform, e.g., Shopify apps,
WooCommerce plugins, Magento extensions]

### Recommendations (Prioritized)

1. **[Critical]** [Action item with specific guidance and expected revenue impact]
2. **[High]** [Action item...]
3. **[Medium]** [Action item...]
4. **[Low]** [Action item...]

### Quick-Win A/B Tests

| Test | Page | Hypothesis | Expected Impact | ICE Score |
|------|------|-----------|-----------------|-----------|
| [Name] | [PDP/Cart/Checkout] | If we [change], then [metric] will [improve] because [reason] | [Est. %] | [Score] |
```

---

## Cross-References

- **Single page audit:** Use the `cro-page` sub-skill for a general page-level CRO audit
- **Full funnel analysis:** Use the `cro-funnel` sub-skill to analyze the entire purchase funnel end-to-end
- **Form optimization:** Use the `cro-forms` sub-skill for detailed checkout form analysis
- **Trust signals:** Use the `cro-trust` sub-skill for deep trust and social proof evaluation
- **Copy evaluation:** Use the `cro-copy` sub-skill to audit product descriptions and CTA copy
- **Competitor comparison:** Use the `cro-benchmark` sub-skill to compare against competitor e-commerce experiences
- **Tracking audit:** Use the `cro-tracking` sub-skill to verify enhanced e-commerce tracking is configured
- **Quality gates:** Read `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md` for e-commerce-specific pass/fail thresholds
- **Psychology:** Read `${CLAUDE_SKILL_DIR}/../cro/references/psychology-principles.md` for scarcity, anchoring, and social proof principles
- **Benchmarks:** Read `${CLAUDE_SKILL_DIR}/../cro/references/conversion-benchmarks.md` for e-commerce conversion rate benchmarks
- **Proven ecommerce tests:** Read `${CLAUDE_SKILL_DIR}/../cro/references/proven-tests-ecommerce.md` for 25 data-backed Shopify/ecommerce A/B tests (PDP, cart, checkout, pricing, mobile) with documented lift percentages and ICE scores
- **Proven general tests:** Read `${CLAUDE_SKILL_DIR}/../cro/references/proven-tests-general.md` for universal CRO tests (page speed, copy clarity, mobile-first, accessibility) applicable to ecommerce
