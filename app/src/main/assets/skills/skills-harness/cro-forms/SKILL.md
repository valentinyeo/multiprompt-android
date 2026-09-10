---
name: cro-forms
description: >
  Form optimization analysis covering field count, labels, validation patterns,
  multi-step vs single-step, smart defaults, error messaging, and mobile input
  types. Use when user says "form optimization", "form UX", "form conversion",
  "reduce form friction", or "form abandonment".
argument-hint: "<url>"
allowed-tools: Read, Grep, Glob, Bash, WebFetch
---

# CRO Form Optimization

Analyze every form on a page for conversion friction, usability issues, and
optimization opportunities. Forms are where conversion intent meets friction --
every unnecessary field, confusing label, or poor validation pattern costs
completions.

---

## Process

1. **Fetch the page** using `${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py`. Store the full HTML.
2. **Extract form elements** using `${CLAUDE_SKILL_DIR}/../cro/scripts/parse_cro.py`. This identifies every
   `<form>` element, its fields, labels, buttons, validation attributes, and
   surrounding context (trust signals near the form, privacy text, etc.).
3. **Load form UX principles** by reading `${CLAUDE_SKILL_DIR}/../cro/references/ux-heuristics.md` (form
   section) and `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md` (field count limits by business
   type and form purpose).
4. **Analyze each form** individually against the criteria below.
5. **Calculate the Form Optimization Score** (0-100) for each form, then an
   overall page score.
6. **Generate the report** with per-form findings and specific recommendations.

---

## Analysis Sections

### 1. Form Inventory

Before analyzing individual forms, document what exists.

| Question | What to Record |
|----------|---------------|
| How many forms are on the page? | Count all `<form>` elements plus form-like interactive patterns (e.g., inline signups without `<form>` tags) |
| What is each form's purpose? | Contact, signup, newsletter, search, checkout, login, quote request, booking, etc. |
| Where is each form located? | Above the fold? In a modal? Sidebar? Footer? End of page? |
| Which form is the primary conversion form? | The one most closely tied to the page's conversion goal |
| Field count per form | Total fields, required fields, optional fields |

---

### 2. Field Analysis

Every field is friction. Each one must justify its existence.

| Criterion | What to Check | Guidance |
|-----------|---------------|----------|
| Total field count | Compare against limits in `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md` | Lead gen: 3-5 fields max. Newsletter: 1-2 fields. Checkout: minimize. Quote: 5-7 fields max. |
| Required vs optional | Are "optional" fields truly needed? Could they be collected later? | Every optional field should be questioned. If it is rarely filled, remove it. |
| Field types | Are correct HTML5 input types used? (`email`, `tel`, `number`, `url`, `date`, `search`) | Wrong types mean wrong mobile keyboards and no native validation |
| Field order | Most to least personal? Easiest fields first? | Name/Email first (low commitment), then phone/company (higher commitment). Never start with high-friction fields. |
| Hidden fields | Any hidden fields? Honeypot for spam? UTM tracking? | Acceptable for spam prevention and tracking. Flag if they add friction. |
| Redundant fields | Are any fields collecting the same information differently? | "Name" vs "First Name" + "Last Name" -- combine if full name is sufficient |
| Phone number | Is it required? Should it be? Format requirements clear? | Phone is the highest-friction field. Make optional unless absolutely necessary. |
| Address fields | Required? Auto-complete enabled? Address lookup integration? | Address auto-complete (Google Places) dramatically reduces friction. |
| Dropdown vs radio | Dropdowns for 5+ options, radio buttons for 2-4 options | Radio buttons are faster -- the user sees all options without clicking |
| Free text vs structured | Are free text fields used where a dropdown/select would work? | Structured inputs reduce errors and cognitive load |

**Field count benchmarks** (from `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md`):

| Form Type | Optimal | Maximum | Beyond Maximum |
|-----------|---------|---------|----------------|
| Newsletter signup | 1 (email) | 2 (email + name) | Every field beyond 2 kills signups |
| Free trial signup | 2-3 | 4 | Multi-step if > 4 fields needed |
| Contact/quote request | 3-5 | 7 | Split into multi-step above 5 |
| B2B lead gen | 4-6 | 8 | Progressive profiling preferred |
| E-commerce checkout | 6-8 | 12 | Guest checkout essential. Multi-step. |
| Account creation | 2-3 | 5 | Social login as alternative |

---

### 3. Labels and Placeholders

How fields are labeled directly impacts completion rate.

| Criterion | What to Check | Best Practice |
|-----------|---------------|---------------|
| Label position | Where are labels relative to fields? | Top-aligned labels are fastest to complete (Matteo Penzo eye-tracking study). Left-aligned is acceptable. Inline/placeholder-only is NOT recommended. |
| Placeholder text | Are placeholders used AS labels, or as supplementary hints? | Placeholders should supplement labels, never replace them. They disappear on focus, losing context. |
| Label clarity | Are labels unambiguous? "Name" could mean full name, first name, or username. | Be specific: "Full Name", "Email Address", "Phone Number (optional)" |
| Help text | Is there supplementary text for complex fields? | Below the field, in smaller text. Show format examples: "e.g., +1 (555) 123-4567" |
| Required indicators | How are required fields marked? | Asterisk (*) with a legend explaining it. OR mark optional fields instead (if most are required). |
| Consistency | Are all labels formatted the same way? Same casing, same punctuation? | Title Case or Sentence case -- pick one and stick to it. |
| Action labels | Does the form describe what it does? | "Request a Quote", "Create Your Account" -- not just a bare form with no context |

---

### 4. Validation and Errors

Validation is where most form abandonment happens. Bad error handling is a
conversion killer.

| Criterion | What to Check | Best Practice |
|-----------|---------------|---------------|
| Inline validation | Does validation happen in real-time as the user fills fields? | Real-time validation on blur (when leaving a field) is ideal. On-keystroke can be annoying. |
| Error message clarity | Are messages specific and helpful? | "Please enter a valid email address" > "Invalid input" > "Error" |
| Error message position | Where do errors appear? | Inline, directly below the problem field. NOT only at the top of the form. |
| Field-level vs form-level | Are errors shown per field or only after submit? | Per-field inline validation is far superior to form-level error summary |
| Recovery ease | Do fields retain their values when an error occurs? | Fields MUST retain values on error. Clearing the form on error is a catastrophic UX failure. |
| Input formatting | Does the form auto-format inputs? (Phone numbers, credit cards) | Auto-format or accept multiple formats. Don't reject "555-1234" because you wanted "5551234". |
| Success state | Is there a clear success indication per field? | Green checkmark or border on valid fields provides positive reinforcement |
| Error prevention | Are obvious errors prevented before they happen? | Date pickers instead of text, country dropdowns instead of text, input masks for phone/card |
| Email validation | Strict or permissive? Does it catch typos? | Catch common domain typos ("gmial.com" -> "Did you mean gmail.com?"). Do not over-validate. |

---

### 5. Layout and Design

Form layout affects completion speed and error rate.

| Criterion | What to Check | Best Practice |
|-----------|---------------|---------------|
| Column layout | Single column or multi-column? | Single column is strongly recommended. Multi-column increases errors and slows completion by 15-25% (CXL research). |
| Visual grouping | Are related fields grouped? (Name fields together, address fields together) | Use fieldsets, whitespace, or subtle borders to group related fields |
| Progress indication | For multi-step forms: is a progress bar or step indicator shown? | Show current step, total steps, and allow going back to previous steps |
| White space | Adequate spacing between fields? Form feels breathable? | Minimum 8px between fields. 16-24px between field groups. |
| Scannability | Can the user visually scan the entire form and estimate effort? | If the form looks long and intimidating, users abandon before starting |
| Mobile stacking | Do multi-column forms properly stack to single column on mobile? | Always. No horizontal scrolling. No side-by-side fields on mobile. |
| Form width | Appropriate width for content? | Match field width to expected input length. Email fields should be wider than zip code fields. |
| Visual hierarchy | Is the form the most prominent element in its page section? | The form should not compete with surrounding content for attention |

---

### 6. CTA and Submission

The submit button is the final step. Getting it wrong can undo all prior good
form design.

| Criterion | What to Check | Best Practice |
|-----------|---------------|---------------|
| Submit button text | What does the button say? | Specific, action-oriented, first-person: "Get My Free Quote" >> "Submit" >> "Send" |
| Button prominence | Size, color, contrast? | High contrast with surrounding elements. Full width on mobile. Minimum 44px height. |
| Button placement | Where is it relative to the last field? | Directly below the last field, left-aligned (same axis as the fields) |
| Post-submission experience | What happens after clicking submit? | Inline success message with next steps > redirect to thank-you page > nothing (worst) |
| Loading state | Does the button show a loading state during submission? | Show spinner or "Submitting..." text. Disable double-click. |
| Confirmation | Does the user receive confirmation? (Email, screen, both?) | Immediate on-screen confirmation. Follow-up email for important submissions. |
| Error state on submit | If submission fails, what happens? | Clear error message with guidance. Do NOT clear the form. Keep all entered data. |

---

### 7. Friction Reducers

Advanced patterns that reduce form friction and increase completion rates.

| Pattern | What to Check | Impact |
|---------|---------------|--------|
| Social login / SSO | For signup forms: Google, Apple, Microsoft sign-in options? | Can increase signup conversion by 20-40% by eliminating field entry entirely |
| Auto-fill support | Are `autocomplete` HTML attributes set correctly? | Allows browsers to auto-fill name, email, address, phone, credit card |
| Smart defaults | Are sensible defaults pre-selected? (Country, currency, plan) | Reduces decisions the user must make |
| Conditional logic | Do fields show/hide based on previous answers? | Reduces visible field count and makes the form feel shorter |
| Save and continue | For long forms: can users save progress and return? | Essential for complex applications, quotes, or multi-day processes |
| Guest checkout | For e-commerce: can users buy without creating an account? | Forced account creation is the #1 cart abandonment reason (Baymard Institute) |
| Progressive profiling | Collect minimal info now, ask for more later as relationship builds? | Better than asking for everything upfront |
| Address auto-complete | Google Places or similar for address fields? | Dramatically reduces typing and errors for address entry |
| Password requirements | For account creation: are requirements shown proactively? Strength meter? | Show requirements before the user starts typing, not as error messages |

---

### 8. Mobile Form UX

Mobile forms have unique challenges. More than half of form fills start on
mobile.

| Criterion | What to Check | Best Practice |
|-----------|---------------|---------------|
| Input type attributes | `type="email"` for email, `type="tel"` for phone, `type="number"` for numeric, `type="url"` for URLs? | Correct input types trigger the right mobile keyboard, dramatically improving speed |
| Tap target sizes | Are fields and buttons at least 44x44px? | Apple and Google both recommend 44px minimum. Smaller targets cause mis-taps. |
| Keyboard optimization | Does the keyboard change appropriately per field type? | Number pad for phone, @ keyboard for email, URL keyboard for URLs |
| Scroll on error | When a validation error occurs, does the page scroll to the error? | Users on mobile cannot see the error if it is above the viewport |
| Sticky submit button | Is the submit button visible without scrolling on long forms? | Sticky bottom CTA keeps the action always accessible |
| Zoom prevention | Does the form trigger unwanted zoom on iOS? (Inputs < 16px font size cause auto-zoom) | Set font-size to at least 16px on form inputs to prevent iOS zoom |
| Touch gestures | Can users swipe between steps on multi-step forms? | Optional but improves perceived performance |
| Dropdown alternatives | On mobile, native selects look different per OS. Considered alternatives? | Bottom sheet pattern is often better than native select on mobile |

---

### 9. Privacy and Trust (Near Form)

Trust elements directly adjacent to the form have outsized impact on completion.

| Element | What to Check | Impact |
|---------|---------------|--------|
| Privacy statement | Text like "We respect your privacy" or "No spam, ever" near submit button? | Reduces form anxiety. Should be within 50px of the submit button. |
| Privacy policy link | Link to full privacy policy near the form? | Required by GDPR. Should open in new tab, not navigate away. |
| Data usage explanation | Brief note on what happens with the submitted data? | "We'll use your email to send your free guide and weekly tips. Unsubscribe anytime." |
| Security indicators | Lock icon, "Secure form" text, encryption mention? | Especially important for forms collecting payment or sensitive data |
| "No credit card" | For free trials: "No credit card required" text near the submit button? | One of the highest-impact single text additions for SaaS trial forms |
| Testimonial near form | Customer testimonial or review widget positioned next to the form? | Provides social validation at the moment of maximum decision anxiety |
| Anti-spam assurance | "We'll never share your information" or similar? | Addresses the unspoken fear behind every email field |

---

## Scoring

### Form Optimization Score Calculation

| Section | Weight |
|---------|--------|
| Field Analysis | 25% |
| Labels & Placeholders | 10% |
| Validation & Errors | 20% |
| Layout & Design | 10% |
| CTA & Submission | 15% |
| Friction Reducers | 10% |
| Mobile Form UX | 5% |
| Privacy & Trust (Near Form) | 5% |
| **Total** | **100%** |

**Per-form scoring:** Each form on the page receives its own score. The overall
page Form Optimization Score is the weighted average, with the primary
conversion form weighted at 70% and all other forms at 30%.

**Field count penalty:** If the total field count exceeds the maximum for the
form type (per `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md`), apply a -5 point penalty per
excess field to the overall score.

### Score Interpretation

| Score | Rating | Meaning |
|-------|--------|---------|
| 90-100 | Exceptional | Form is well-optimized. Minor refinements only. |
| 70-89 | Strong | Good form design with specific improvement opportunities. |
| 50-69 | Moderate | Form friction is likely costing completions. Address key issues. |
| 30-49 | Weak | Significant friction. Users are abandoning this form. Redesign needed. |
| 0-29 | Critical | Form is a conversion killer. Major issues blocking completion. |

---

## Output Format

```markdown
## CRO Form Analysis: [URL]

**Business Type:** [Detected type]
**Forms Found:** [Count]
**Overall Form Score:** [Score]/100 -- [Rating]
**Date:** [Current date]

### Executive Summary
[2-3 sentences on overall form quality and highest-impact issues]

### Form Inventory

| # | Form Purpose | Location | Fields | Required | Optional | Score |
|---|-------------|----------|--------|----------|----------|-------|
| 1 | [Purpose] | [Location] | [Count] | [Count] | [Count] | /100 |
| 2 | [Purpose] | [Location] | [Count] | [Count] | [Count] | /100 |

---

### Form [#1]: [Purpose]

#### Field Inventory

| # | Field | Type | Required | HTML Input Type | Issue |
|---|-------|------|----------|-----------------|-------|
| 1 | [Label] | [text/email/tel/...] | Yes/No | [Actual type attr] | [Issue or "OK"] |
| 2 | ... | ... | ... | ... | ... |

**Field Count Assessment:** [X fields vs Y recommended maximum]
**Recommendation:** [Keep all / Remove fields X, Y / Convert to multi-step]

#### Score Breakdown

| Section | Score | Key Finding |
|---------|-------|-------------|
| Field Analysis | /100 | |
| Labels & Placeholders | /100 | |
| Validation & Errors | /100 | |
| Layout & Design | /100 | |
| CTA & Submission | /100 | |
| Friction Reducers | /100 | |
| Mobile Form UX | /100 | |
| Privacy & Trust | /100 | |

#### Issues Found

| ID | Section | Severity | Description | Recommendation |
|----|---------|----------|-------------|----------------|
| F1-001 | Fields | [0-4] | [Issue] | [Fix] |
| F1-002 | Validation | [0-4] | [Issue] | [Fix] |
| ... | ... | ... | ... | ... |

[Repeat for each form on the page]

---

### Prioritized Recommendations

1. **[Critical]** [Specific action with expected impact]
2. **[High]** [Action...]
3. **[Medium]** [Action...]
4. **[Low]** [Action...]

### Form-Focused A/B Test Ideas

| Test | Hypothesis | Expected Impact | ICE Score |
|------|-----------|-----------------|-----------|
| [Name] | If we [change form element], then [completion rate] will [improve] because [reason] | [Est.] | [Score] |

### Fields to Remove or Defer

| Field | Current Status | Recommendation | Rationale |
|-------|---------------|----------------|-----------|
| [Field name] | Required | Remove / Make optional / Defer to later | [Why] |
```

---

## Cross-References

- **Full page analysis:** Use the `cro-page` sub-skill for complete page-level conversion audit
- **UX evaluation:** Use the `cro-ux` sub-skill for broader usability issues beyond forms
- **Copy analysis:** Use the `cro-copy` sub-skill for form micro-copy (labels, errors, button text)
- **Trust audit:** Use the `cro-trust` sub-skill for trust elements near and around forms
- **A/B testing:** Use the `cro-testing` sub-skill to turn form findings into prioritized test hypotheses
- **UX heuristics:** Read `${CLAUDE_SKILL_DIR}/../cro/references/ux-heuristics.md` for form-specific heuristic details
- **Quality gates:** Read `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md` for field count limits and form requirements by business type
