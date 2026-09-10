---
name: doc-artifacts
description: "Author and share HTML/markdown document artifacts for Valentin: format decision rule (HTML-first), rich-HTML requirements, hosting rules, and the mdview/htmlshare/repo-docs CLI reference. Use when writing any plan, report, exploration, wireframe, or doc a human will read."
---

## Document Artifacts (Default to HTML, MD-only when required)

Valentin works on a VPS CLI. He cannot read `.md` files in his terminal. Plain markdown is essentially invisible to him. HTML is also strictly richer — colors, tables, SVG diagrams, interactive controls, code highlighting, mobile responsiveness — per Thariq's "Unreasonable Effectiveness of HTML" post. Default to HTML.

### Format decision rule

**Default to HTML** for any document a human will *read* (not edit as source):
- Plans, specs, implementation plans, architecture docs
- Brainstorming outputs, exploration of options, mockups
- Reports, audits, reviews, post-mortems
- PR write-ups, code-explainer artifacts, design exploration
- Anything you would have previously written as a `PLAN.md`, `SPEC.md`, `NOTES.md`, `REPORT.md`, etc.

**Keep markdown ONLY for these load-bearing files:**
- `README.md`, `CLAUDE.md`, `SKILL.md`, `CONTRIBUTING.md`, `CHANGELOG.md` (tool/convention requirements)
- Commit messages, PR titles + descriptions, Slack messages, email bodies (platforms render MD, not HTML). EXCEPTION: Hypertask ticket descriptions/comments are HTML, never markdown — see rule 6h
- Source code (`.py`, `.ts`, `.go`, etc.) and inline code comments

When a file MUST be markdown (per the list above), still run `mdview` on it after writing/editing so Valentin can read it.

### Make HTML rich, not "markdown converted to HTML"

The whole point is to use what HTML can do. When generating HTML artifacts:
- Use inline SVG for diagrams, flowcharts, comparisons, before/after, architecture
- Use tables for any tabular data (don't render as plain lists)
- Use color/badges/severity tags to convey meaning at a glance
- Use collapsible sections, tabs, or sliders for explorations with multiple options
- Add small interactive controls (sliders, toggles, copy buttons) when they let Valentin tweak parameters
- Always: inline CSS + JS only (single self-contained file), mobile responsive, light + dark mode via `prefers-color-scheme`, system font stack
- Always: a one-line `<title>` and a meta header showing file purpose

### Hosting and sharing (mandatory)

**HARD RULE — what may be published, and where (2026-07-07, Valentin):**
- **NEVER publish internal, security, secret, credential, infra-mechanics, or in-repo docs to ANY public host** (not `yeoux.net`, not `hypertask.app`, not the R2 worker, nowhere). This includes things like login/bypass mechanics, env-var ids, tokens, deploy runbooks, architecture internals. They stay in the repo or local filesystem. If Valentin needs to read one, keep it in the repo and point him at the repo path, or paste the content in chat — do NOT host it.
- **Public sharing is ONLY for HTML explorations** — visual mockups, brainstorms, design/report artifacts meant to be shown. Not for anything sensitive.
- **All explorations/wireframes/artifacts host on `hypertask.app`. `yeoux.net` is BANNED for every session — never PUT to it, never link it (HTPR-4104, wired 2026-07-10).** `mdview`/`htmlshare`/`repo-docs` are already repointed: they upload to `https://hypertask.app/explorations/<id>` and print that URL. The whole apex is Zero-Trust gated to Valentin, so shared links are private-by-default (safe even though sensitive docs still must never be hosted — see the rule above). If a CLI errors with "missing credentials", the creds live at `~/.config/hypertask-app/credentials.env`.
- **`hypertask.app` has NO subdomains — the whole apex is ONE Astro+daisyUI app (repo `~/projects/hypertask-analytics`, worker `hypertask-clients`), with a shared top navbar on every page.** Everything is a path: `/` overview + revenue/product/ops dashboards, `/wiki` = the repo openwiki (served from the app's bundled markdown), `/explorations` = explorations index, `/explorations/<id>` = one uploaded artifact (old `/x/*` 301s there). `analytics.hypertask.app` 301s here. Do NOT create `*.hypertask.app` subdomains and do NOT bolt on a new design — add a page/path inside `src/layouts/Base.astro`. `htmlshare`/`mdview`/`repo-docs` upload to `/explorations/<id>` and print that URL (R2 bucket `hypertask-app-content`, bound `CONTENT`). Never hand-build a share URL — run the CLI, which returns a verified link.

Three CLIs at `~/.local/bin/`:

| Command | Purpose | Output |
|---|---|---|
| `mdview <file.md>` | Render any markdown file to HTML with sticky TOC + syntax highlighting, upload to hypertask.app | `https://hypertask.app/explorations/<id>` |
| `htmlshare <file.html>` | Upload an existing HTML file to hypertask.app as-is | `https://hypertask.app/explorations/<id>` |
| `repo-docs [path]` | Scan a repo for all `.md` + `.html` files, render each, build a searchable index page, upload everything | Index URL under `https://hypertask.app/explorations/` |

URL scheme: the three CLIs print `https://hypertask.app/explorations/<7-char-id>` (served by the `hypertask-clients` worker (repo `~/projects/hypertask-analytics`) from R2 bucket `hypertask-app-content`). The id is a deterministic hash of the source path (re-running overwrites the same key). To unpublish: PUT an empty body to the same `/explorations/<id>` with the creds. **`yeoux.net` and the old `weekly-reports-api…workers.dev` R2 worker are RETIRED for Valentin — do not use them.**

**MANDATORY workflow after writing or editing ANY md/html file:**
1. Write/edit the file as normal
2. Immediately run the matching CLI (`mdview` for `.md`, `htmlshare` for `.html`)
3. Print the returned URL to Valentin as a plain-text clickable link
4. NEVER hand him just a VPS file path for a doc — he can't open it (rule 9c)
5. **Auto-open in `zsb` if a pane is available.** After sharing the URL, if a zsb pane is open, open the shared URL in a NEW tab via `zsb navigate "<url>" --new-tab` so Valentin sees it immediately without clicking. If no pane, just give the URL. Applies to every newly generated HTML artifact (reports, plans, wireframes, mockups). (`lh play` is retired — never use it.)

For ephemeral HTML artifacts (one-off plans/brainstorms not tied to a repo), write to `/tmp/<slug>.html` and share. Don't pollute project repos with throwaway HTML.

For project-canonical docs (architecture, design docs that belong in the repo), write to the repo AND share. The repo gets the source-of-truth file, Valentin gets the readable URL.

### When in doubt

Ask yourself: "Will Valentin or another human read this?" If yes, HTML (or share via `mdview` if it must be MD). If no (it's code/config/commit/SKILL.md), regular file, no share needed.


