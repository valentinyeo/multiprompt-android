---
name: cro-ux
description: >
  UX heuristic evaluation against Nielsen's 10 heuristics plus mobile-specific
  and e-commerce best practices. Assigns severity ratings per issue. Use when
  user says "UX audit", "usability check", "heuristic evaluation", "UX review",
  or "user experience analysis".
argument-hint: "<url>"
allowed-tools: Read, Grep, Glob, Bash, WebFetch
---

# CRO UX Heuristic Evaluation

Perform a structured UX evaluation using Nielsen's 10 usability heuristics,
supplemented with mobile-specific criteria and conversion-focused UX
assessment. Every issue receives a severity rating (0-4) and a clear
recommendation.

---

## Process

1. **Fetch the page** using `${CLAUDE_SKILL_DIR}/../cro/scripts/fetch_page.py`. Store the full HTML.
2. **Take screenshots** if Playwright is available:
   - Desktop at 1440px width
   - Tablet at 768px width
   - Mobile at 375px width
   Use screenshots for visual hierarchy, layout, and responsive behavior
   evaluation.
3. **Load UX heuristics framework** by reading `${CLAUDE_SKILL_DIR}/../cro/references/ux-heuristics.md`.
   This provides the detailed evaluation criteria and severity scale.
4. **Evaluate each heuristic** systematically against the page content and
   screenshots.
5. **Evaluate mobile-specific criteria** separately.
6. **Evaluate conversion-specific UX** patterns.
7. **Compile the issue table** with severity, description, and recommendation.
8. **Calculate the UX Score** (0-100).
9. **Generate the report.**

---

## Severity Scale

Every issue MUST be assigned a severity rating using this scale:

| Severity | Label | Definition | Action |
|----------|-------|------------|--------|
| 0 | Not a problem | Evaluator disagrees this is a usability issue | No action needed |
| 1 | Cosmetic | Cosmetic problem only. Fix if extra time is available. | Low priority |
| 2 | Minor | Minor usability problem. Causes slight delay or confusion. | Medium priority |
| 3 | Major | Major usability problem. Causes significant user difficulty. Impacts conversion. | High priority fix |
| 4 | Catastrophic | Usability catastrophe. Users cannot complete the task. Must be fixed before launch. | Critical fix |

---

## Analysis Sections

### 1. Nielsen's 10 Heuristics

Evaluate each heuristic independently. For each, note specific violations found
on the page with their severity.

#### H1: Visibility of System Status

The system should always keep users informed about what is going on through
appropriate feedback within reasonable time.

| What to Check | Look For |
|----------------|----------|
| Loading states | Spinners, skeleton screens, progress bars during async operations |
| Progress indicators | Multi-step processes show current step and total steps |
| Button feedback | Buttons change state on click (loading, disabled, confirmed) |
| Form submission | Clear feedback after form submit (success message, redirect, inline confirmation) |
| Navigation state | Current page highlighted in navigation, breadcrumbs present |
| Real-time feedback | Search results update as user types, filters apply visibly |

#### H2: Match Between System and Real World

The system should speak the users' language, with words, phrases, and concepts
familiar to the user, rather than system-oriented terms.

| What to Check | Look For |
|----------------|----------|
| Language | Natural language vs technical jargon, industry-appropriate terminology |
| Logical order | Information organized in a natural, predictable order |
| Metaphors | Icons and symbols match real-world conventions (trash can for delete, etc.) |
| Cultural fit | Date formats, currency, units match target audience's locale |
| Conceptual model | Does the interface match how users think about the task, not how the system works? |

#### H3: User Control and Freedom

Users often choose system functions by mistake and need a clearly marked
"emergency exit" to leave the unwanted state.

| What to Check | Look For |
|----------------|----------|
| Undo | Can users undo actions? Is there a clear undo mechanism? |
| Back navigation | Does the browser back button work as expected? No broken history? |
| Exit routes | Can users close modals, dismiss overlays, cancel processes easily? |
| Cancel | Long processes offer a cancel option? Form data preserved on back? |
| Escape key | Modals and overlays close with Escape? |

#### H4: Consistency and Standards

Users should not have to wonder whether different words, situations, or actions
mean the same thing. Follow platform conventions.

| What to Check | Look For |
|----------------|----------|
| UI patterns | Consistent button styles, link styles, form elements across pages |
| Terminology | Same concept uses same word everywhere (don't mix "cart" and "basket") |
| Layout | Consistent page structure, navigation position, footer content |
| Interaction patterns | Same gestures/clicks produce same results throughout |
| External consistency | Follows web conventions (logo links to home, underlined text is clickable) |

#### H5: Error Prevention

Even better than good error messages is a careful design that prevents problems
from occurring in the first place.

| What to Check | Look For |
|----------------|----------|
| Input constraints | Form fields restrict invalid input (date pickers vs free text, dropdown vs free text) |
| Confirmation dialogs | Destructive actions require confirmation? |
| Smart defaults | Forms pre-fill sensible defaults? Country/region auto-detected? |
| Inline validation | Fields validate before submit? Email format, required fields? |
| Clear instructions | Ambiguous inputs have help text or examples? |

#### H6: Recognition Rather Than Recall

Minimize the user's memory load by making objects, actions, and options visible.

| What to Check | Look For |
|----------------|----------|
| Visible options | Navigation shows available sections vs hiding them in deep menus |
| Recently viewed | E-commerce: recently viewed products? Search: recent searches? |
| Contextual help | Tooltips, info icons, inline help where decisions are made |
| Labels | All interactive elements have visible labels (not just icons) |
| Search | Search is visible and prominent, not hidden behind an icon on desktop |

#### H7: Flexibility and Efficiency of Use

Accelerators -- unseen by the novice user -- may speed up interaction for expert
users. The system should cater to both inexperienced and experienced users.

| What to Check | Look For |
|----------------|----------|
| Shortcuts | Keyboard shortcuts for power users? |
| Search | Effective search with filters, suggestions, recent queries? |
| Quick actions | Common tasks accessible in minimal clicks? |
| Personalization | Content adapts to returning users? Saved preferences? |
| Direct access | Can users bookmark and share deep links to specific content? |

#### H8: Aesthetic and Minimalist Design

Dialogues should not contain information that is irrelevant or rarely needed.

| What to Check | Look For |
|----------------|----------|
| Content hierarchy | Clear visual hierarchy -- most important content is most prominent |
| Whitespace | Adequate spacing between elements? Page does not feel cluttered? |
| Distraction audit | Elements that do not support the conversion goal? Competing CTAs? Unnecessary nav links? |
| Information density | Right amount of content per screen? Not overwhelming, not sparse? |
| Focus | Each page section has a clear purpose and visual focus point |

#### H9: Help Users Recognize, Diagnose, and Recover from Errors

Error messages should be expressed in plain language (no codes), precisely
indicate the problem, and constructively suggest a solution.

| What to Check | Look For |
|----------------|----------|
| Error clarity | Plain language, not error codes or technical messages |
| Specificity | "Email address is missing" > "Required field" > "Error" |
| Recovery guidance | Error message tells user HOW to fix the problem |
| Visibility | Error messages appear near the problem element, not just at top of page |
| Persistence | Error messages stay visible until the problem is resolved |
| 404 pages | Custom 404 with search, navigation, and helpful links? |

#### H10: Help and Documentation

Even though it is better if the system can be used without documentation, it
may be necessary to provide help and documentation.

| What to Check | Look For |
|----------------|----------|
| FAQ | Frequently asked questions available? Easy to find? Covers buying objections? |
| Tooltips | Complex features have contextual help? |
| Onboarding | New user guidance for complex products? |
| Contact options | Help is accessible when needed (chat, email, phone)? |
| Search | Help content is searchable? |

---

### 2. Mobile UX

Evaluate mobile-specific usability. These are IN ADDITION to the 10 heuristics.

| Criterion | Severity Guide | What to Check |
|-----------|---------------|---------------|
| Thumb zone optimization | 3 if critical CTAs outside thumb zone | Interactive elements in bottom 2/3 of screen? Primary actions within easy reach? |
| Tap target sizes | 3 if < 44px on primary CTAs | All tappable elements at least 44x44px? Adequate spacing between targets? |
| Touch gestures | 2 if missing expected gestures | Swipe for carousels? Pull-to-refresh where expected? Pinch-to-zoom on images? |
| Mobile navigation | 3 if nav is broken or confusing | Hamburger menu discoverable? Back button works? Menu items tappable? Depth manageable? |
| Viewport behavior | 4 if horizontal scroll present | No horizontal scrolling? Content fits viewport? No fixed-width elements breaking layout? |
| Input types | 2 if wrong keyboards shown | Email fields trigger email keyboard? Phone fields trigger numeric? URL fields trigger URL keyboard? |
| Font sizes | 2 if below 16px body | Body text at least 16px? No need to pinch-zoom to read? |
| Image handling | 2 if images overflow | Images responsive? No images wider than viewport? |
| Sticky elements | 2 if they obscure content | Sticky headers/CTAs don't cover too much screen? Can be dismissed? |
| Orientation | 1 if landscape breaks layout | Page works in both portrait and landscape? |

---

### 3. Conversion-Specific UX

UX patterns that directly impact conversion rates.

| Criterion | What to Check |
|-----------|---------------|
| Path to conversion | How many clicks/taps from entry to conversion goal? Map the path. Fewer is better. Target: 3 or fewer. |
| Distraction audit | Count elements that compete with the primary CTA: secondary nav links, banners, popups, social icons, footer links. Each is a potential leak. |
| Cognitive load | How many decisions does the user need to make on this page? Simplify choices. Hick's Law: more options = slower decisions. |
| Information architecture | Is the page structured to guide the user toward conversion? Does content flow logically from awareness to decision? |
| Error recovery on forms | When a form submission fails, are field values preserved? Is the error visible without scrolling? Can the user fix and resubmit easily? |
| Popup/overlay assessment | Popups present? Timing appropriate? Easy to dismiss? Mobile-friendly? Do they help or hurt conversion? |
| Page length | Is the page length appropriate for the conversion goal? Long-form for high-consideration purchases, short for low-friction signups. |
| Social login/SSO | For signup/login pages: are social login options available? Reduces friction significantly. |
| Exit intent | Any exit-intent strategy? (Popup, offer, reminder) -- evaluate if appropriate and well-executed. |

---

## Scoring

### UX Score Calculation

| Section | Weight |
|---------|--------|
| Nielsen's 10 Heuristics (combined) | 60% |
| Mobile UX | 20% |
| Conversion-Specific UX | 20% |
| **Total** | **100%** |

**Heuristic sub-scoring:** Each of the 10 heuristics receives a score 0-100
based on severity and count of violations found. The combined heuristic score
is the average of all 10.

**Severity impact on score:**
- Severity 4 issue: -25 points from that heuristic
- Severity 3 issue: -15 points from that heuristic
- Severity 2 issue: -8 points from that heuristic
- Severity 1 issue: -3 points from that heuristic
- Minimum score per heuristic: 0

### Score Interpretation

| Score | Rating | Meaning |
|-------|--------|---------|
| 90-100 | Exceptional | Excellent usability. Minor cosmetic improvements only. |
| 70-89 | Strong | Good usability with specific areas for improvement. |
| 50-69 | Moderate | Usability issues are impacting user experience and likely conversion. |
| 30-49 | Weak | Significant usability problems. Users struggle with key tasks. |
| 0-29 | Critical | Severe usability failures. Users likely abandon before converting. |

---

## Output Format

```markdown
## CRO UX Evaluation: [URL]

**Business Type:** [Detected type]
**UX Score:** [Score]/100 -- [Rating]
**Date:** [Current date]
**Issues Found:** [Total count] ([Severity 4: X] [Severity 3: X] [Severity 2: X] [Severity 1: X])

### Executive Summary
[2-3 sentences on overall UX quality and the most impactful issues]

### Score Breakdown

| Section | Score | Issues |
|---------|-------|--------|
| H1: System Status | /100 | X issues |
| H2: Real World Match | /100 | X issues |
| H3: User Control | /100 | X issues |
| H4: Consistency | /100 | X issues |
| H5: Error Prevention | /100 | X issues |
| H6: Recognition vs Recall | /100 | X issues |
| H7: Flexibility | /100 | X issues |
| H8: Minimalist Design | /100 | X issues |
| H9: Error Recovery | /100 | X issues |
| H10: Help & Docs | /100 | X issues |
| Mobile UX | /100 | X issues |
| Conversion UX | /100 | X issues |

### Issue Table

| ID | Heuristic | Severity | Description | Recommendation |
|----|-----------|----------|-------------|----------------|
| UX-001 | H8 | 4 | [Description] | [Fix] |
| UX-002 | Mobile | 3 | [Description] | [Fix] |
| ... | ... | ... | ... | ... |

(Sorted by severity descending, then by heuristic number.)

### Conversion Path Analysis
**Entry -> Conversion:** [Step-by-step path with click count]
**Distraction count:** [Number of competing elements]
**Cognitive load assessment:** [Low/Medium/High with reasoning]

### Top 5 Recommendations (Prioritized by Conversion Impact)

1. **[Severity 4]** [Specific recommendation with implementation guidance]
2. **[Severity 3]** [Recommendation...]
3. **[Severity 3]** [Recommendation...]
4. **[Severity 2]** [Recommendation...]
5. **[Severity 2]** [Recommendation...]

### A/B Test Opportunities
| Test | Hypothesis | Heuristic | Expected Impact |
|------|-----------|-----------|-----------------|
| [Name] | If we [fix], then [metric] will [improve] because [UX principle] | H[X] | [Est.] |
```

---

## Cross-References

- **Full page analysis:** Use the `cro-page` sub-skill for complete page-level conversion audit
- **Copy analysis:** Use the `cro-copy` sub-skill for detailed copywriting evaluation
- **Form optimization:** Use the `cro-forms` sub-skill for deep form-specific UX analysis
- **Trust signals:** Use the `cro-trust` sub-skill for trust and credibility evaluation
- **A/B testing:** Use the `cro-testing` sub-skill to turn UX findings into test hypotheses
- **UX heuristics reference:** Read `${CLAUDE_SKILL_DIR}/../cro/references/ux-heuristics.md` for expanded evaluation criteria
- **Quality gates:** Read `${CLAUDE_SKILL_DIR}/../cro/references/quality-gates.md` for minimum UX requirements by business type
