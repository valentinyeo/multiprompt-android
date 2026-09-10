---
name: open-case
description: Put the full context of the current thread's case on screen in the zsb browser pane — always the associated Hypertask ticket, plus related pages (PR, dashboards, docs) and any relevant assets this thread produced. Opens only what's missing (dedupes against already-open tabs). Requires the zsb pane; if it isn't open it tells Valentin to open it and stops, never falling back to another browser. Use when Valentin says "/open-case", "open the case", "open this ticket and everything for it", or "show me the full picture for this thread".
---

# open-case

Lay out the full context of the current thread in the **zsb** pane so Valentin sees the whole case at a glance: the ticket (always), its related pages, and any assets this thread produced that are worth seeing. Open only what isn't already open.

## Hard rules

- **zsb only.** This targets the docked Edge pane Valentin can see. NEVER fall back to `lh play`, agent-browser, Playwright, `start`, or the OS browser. If there is no pane, stop and ask him to open it.
- **Always open the ticket.** Related pages and assets are best-effort and context-dependent, but the case's Hypertask ticket must end up open.
- **Open only the delta.** List current tabs first and skip URLs already open. Never create duplicates. (If the ticket is already open, reload it in place instead of opening a second copy.)

## Steps

1. **Require the pane.** Run `zsb tabs`. If it errors (`no browser pane open for session ...`), STOP. Tell Valentin: "No zsb pane. Open it first (Ctrl+Shift+O on Windows), then re-run /open-case." Do NOT open anything anywhere else, and do NOT continue.
2. **Resolve the case ticket.** Identify the ticket this thread is about — the session title (`PREFIX-NNN | ...`), else the ticket worked/discussed in the thread. Build its URL `https://app.hypertask.ai/detail/project-<pid>/<num>` (prefix→project: HTPR=15, PERT=1534, IGSH=2060, INNE=339; if unsure, confirm with `hypertask tasks list --project <pid> --limit 1`). This URL is mandatory. If you genuinely can't identify the ticket, ask Valentin for the `PREFIX-NNN` rather than guessing.
3. **Gather related pages + assets (your judgment).** From THIS thread's context, collect the real URLs that complete the picture:
   - the PR(s) for this work (`https://github.com/<owner>/<repo>/pull/<n>`),
   - dashboards, docs, or hosted artifacts referenced or created here (yeoux.net short links, `htmlshare`/`mdview` outputs, deploy URLs, hosted screenshots),
   - anything else this thread centered on that is a real URL and helps understand the case.
   EXCLUDE things Valentin can't open: local/VPS file paths, and VPS-only `localhost:<port>` dev servers that aren't tunneled (note them in your report instead of opening). Only include an asset if opening it actually helps the current context.
4. **Dedupe → delta.** Parse the open URLs from `zsb tabs` (`.result.tabs[].url`). The delta = your target URLs minus those already open. If the ticket is already open, drop it from the "new tab" set but reload it in place.
5. **Open the delta.** For each missing URL: `zsb navigate "<url>" --new-tab`. Open the ticket so it ends up present (reload in place if already open). A `zsb navigate` without `--new-tab` drives the active pane tab — use `--new-tab` for everything except an in-place ticket reload.
6. **Report.** List what you opened, what was already open (skipped), and anything you deliberately didn't open (e.g. a non-tunneled localhost dev server) with one line why. Always include the ticket URL.

## Notes

- Batch: if several URLs are missing, open each in its own new tab.
- Keep it to URLs that matter for the case — don't dump every link ever mentioned, just the ones that give the full current picture.
