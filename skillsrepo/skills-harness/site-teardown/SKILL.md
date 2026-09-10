---
name: site-teardown
description: Fetch a website's HTML/CSS/JS, deconstruct design patterns, explain techniques, and adapt elements into new projects. Use when cloning, analyzing, or learning from existing sites.
---

# Site Teardown

Deconstruct any website to extract design patterns, animation techniques, and layout strategies by fetching actual source code rather than guessing from screenshots.

## When to Use

- User provides a URL and wants to "clone", "match", "tear down", or "learn from" it
- User asks "how did they build this effect?" about a live website
- User wants to adapt a specific section (hero, nav, footer, pricing) from a reference site
- Complements `frontend-design` skill: teardown extracts the recipe, frontend-design executes it

## Teardown Workflow

### 1. Fetch the Source

Run `scripts/teardown.mjs <url>` to extract HTML, linked CSS, and JS references:

```bash
node ~/.claude/skills/site-teardown/scripts/teardown.mjs https://example.com
```

Output goes to `/tmp/teardown-<domain>/` with `index.html`, `styles.css`, `scripts.txt`, and `analysis.md`.

For JS-rendered SPAs where static fetch returns empty `<body>`, fall back to browser tools:
1. Open URL in Playwriter/dev-browser
2. Run `document.documentElement.outerHTML` to capture rendered DOM
3. Save to `/tmp/teardown-<domain>/rendered.html`

### 2. Analyze Design Patterns

Read the fetched files and identify:

| Category | What to Extract |
|----------|----------------|
| Layout | Grid/flexbox structure, breakpoints, container widths |
| Typography | Font families, scale ratio, line heights, font loading |
| Color | Palette, gradient definitions, dark/light tokens |
| Spacing | Padding/margin system, consistent spacing units |
| Animation | Libraries (GSAP, Framer Motion, AOS), triggers, easing |
| Components | Nav style, hero pattern, card layouts, CTA designs |
| Effects | Glass morphism, parallax, scroll reveals, noise textures |

Reference `references/design-vocabulary.md` for terminology.
Reference `references/anti-patterns.md` to flag AI slop patterns to avoid.

### 3. Present Findings

```markdown
## Site Teardown: [domain]

### Overall Vibe
[1-2 sentences: dark/light, minimal/maximal, corporate/playful]

### Key Techniques
1. [Technique] — how it works, what CSS/JS powers it
2. ...

### Reusable Patterns
- [Pattern]: [code snippet or approach]

### What Makes It Feel Premium
- [Specific detail that elevates the design]
```

### 4. Adapt Into Project

When adapting elements into the user's project:
- Extract only the relevant CSS/HTML pattern, not the entire page
- Adapt colors/fonts to match project's existing design system
- Preserve animation libraries but simplify if overkill
- Check `references/anti-patterns.md` before generating output

## Inspiration Sources

When user needs design inspiration before teardown, reference `references/inspiration-sources.md` for curated directories (AWWWARDS, godly.website, Dribbble, 21st.dev, CodePen).

## Integration With Other Skills

- **Before**: `frontend-design` to establish design direction
- **After**: `animate` for motion, `polish` for final pass, `bolder`/`quieter` to adjust intensity
- **Components**: Check 21st.dev before building from scratch
