---
name: ticket
description: Onboard a Hypertask ticket into this session and start working it, OR create a new ticket from free-form text, OR (when invoked with NO arguments in a thread already on a ticket) just resurface the current thread's ticket in the zsb pane without onboarding or creating anything. If $ARGUMENTS references an existing ticket (PREFIX-NNN or app.hypertask.ai URL) it onboards that; if $ARGUMENTS is free-form text it treats it (plus any screenshot/image URLs) as a bug/feature request and creates the ticket; if $ARGUMENTS is empty it reopens this thread's existing ticket in the browser. Onboard/create branches reply with "PREFIX-NNN | Full Title" + URL, open it in the browser, post this session's resume string as a comment, print the /rename line, move it to In Progress, and begin the work. Use when the user pastes a ticket id/URL, describes new work after /ticket, says "onboard this ticket" / "set me up on this ticket" / "make a ticket for this" / "open the current ticket", or invokes /ticket. Mirrors global rules 6e and 6g.
---

# Ticket onboarding (or creation)

`$ARGUMENTS` is one of three things. Run all five steps in order — **except the no-args resurface case (below), which short-circuits after opening the ticket.** Step 1 has three branches.

## 1. Get the ticket key (`PREFIX-NNN`), then reply

**No arguments → resurface the current thread's ticket.** When `$ARGUMENTS` is empty and this thread is already working a ticket, do NOT onboard or create anything — just put that ticket back on screen in zsb. This is the "open the ticket of the current thread" shortcut.

1. Identify the ticket from the session title (the `PREFIX-NNN | …` set via `/rename`) or the ticket this thread has been working on. Run `hypertask tasks get <PREFIX-NNN>` if you need the full title.
2. Reply with `PREFIX-NNN | <Full Title>` + URL.
3. Surface it in zsb as the visible pane tab: run `zsb tabs` first — if a tab already holds that ticket URL, reload it in place; otherwise `zsb navigate "<url>"` on the active pane tab (no `--new-tab` — the point is to bring it to where Valentin is looking). If there's no zsb pane, say so plainly.
4. **Stop here. Skip steps 2-5** — the resume string was already posted and the status already set earlier in this session; re-posting would duplicate. This branch only re-surfaces the ticket.

If you genuinely can't tell which ticket this thread is about (no renamed title, nothing worked yet), ask Valentin for the `PREFIX-NNN` rather than guessing.

**It's an existing ticket** when `$ARGUMENTS` contains a `PREFIX-NNN` id (e.g. `HTPR-3768`) or an `app.hypertask.ai/detail/project-<pid>/<num>` URL. Resolve it:

```
hypertask tasks get <PREFIX-NNN>
```

The CLI's `tasks get` needs the `PREFIX-NNN` key, not the bare number. URL only: the project id maps to a prefix (project 15 = HTPR); if unsure, `hypertask tasks list --project <pid> --limit 5`.

**Otherwise it's a create request** — the text after `/ticket` describes a bug or feature, optionally with screenshot/image URLs (e.g. `/ticket https://screencast2.com/hzMQU.png the write-with-AI replies are still not good`). Make the ticket, then treat it exactly like an onboarded one:

1. **Pull attachments out of the text.** Any `http(s)` image / PDF / screencast URL is an attachment, not title text. Each becomes a `--attach <url>` (rule 6b — real attachments, never embedded markdown).
2. **Search first (rule 6g).** `hypertask tasks search "<key words>"`. If an existing ticket clearly already covers it, onboard that one instead of creating a duplicate.
3. **Create it.** Default board: product board (project 15). If it's plainly a bug you'd normally file on the Bugs board, use project 1534 instead.
   ```
   hypertask tasks create --project 15 \
     --title "<concise title you draft from the request>" \
     --description "<the ask in Valentin's words + what the screenshot shows>" \
     --assignee 6 \
     --attach <url> [--attach <url> ...]
   ```
   You write the title and description — that's a judgment call, the model's job. Title: short and specific. Description: capture the real ask faithfully; it's team-facing, so keep it natural (humanizer applies). Grab the new `PREFIX-NNN` from the create output.

Either branch leaves you with a `PREFIX-NNN`. Reply on its own line, then the URL:

```
PREFIX-NNN | <Full Title>
https://app.hypertask.ai/detail/project-<pid>/<num>
```

(For a just-created ticket on project 15, `<pid>` = 15 and the prefix is HTPR.)

Reproduce the title verbatim, even if it contains an em dash. It's data, not your prose, so the no-em-dash rule does not apply, and the `/rename` line in step 4 must match it exactly.

## 2. Open it

- ZigShell: `zsb navigate "<url>"`.
- Else `lh play` (check `lh status` first) and open a **new** tab; on later runs reuse the tab you already opened, never a duplicate (tab-discipline, global rule 9b).
- If no visible browser is available (no zsb pane, Playwriter extension off), say so plainly. Do not open headless just to claim success.

## 3. Post the resume string

Once per ticket per session. If you already posted it for this ticket this session, skip.

Get this session's UUID from the transcript being written right now:

```
ls -t ~/.claude/projects/$(pwd | sed 's#/#-#g')/*.jsonl | head -1 | xargs -n1 basename | sed 's/\.jsonl$//'
```

Then post it as a plain `<code>` block (never embedded, per rule 6b/6d):

```
hypertask comment add <PREFIX-NNN> --text "<code>claude --resume <uuid></code>"
```

Then reload the ticket tab if it's open (rule 6c): `page.reload()` on the matching tab, never `goto`.

Caveat: the mtime lookup picks the most-recently-written transcript. With several concurrent sessions in the **same** repo it can grab the wrong one. If that's a risk, cross-check `~/.claude/sessions/<pid>.json` or confirm before posting.

## 4. Print the rename line

You cannot `/rename` the session yourself (no tool for it). Print the ready-to-paste line so Valentin fires it with one paste:

```
/rename PREFIX-NNN | <Full Title>
```

## 5. Start working the case

Onboarding done, now begin the actual work. Don't stop and wait for a "go".

- Move it to In Progress and assign Valentin (userId 6): `hypertask tasks update <PREFIX-NNN> --status "In Progress" --assignee 6`. On the product board (project 15) move sections instead: `hypertask tasks move <PREFIX-NNN> --section "In Progress"` then `hypertask tasks assign <PREFIX-NNN> --assignee 6` (`assign` is additive, won't wipe a reviewer). A ticket you just created in step 1 starts in Triage — move it to In Progress now. (Only when *onboarding an existing* product-board ticket sitting in Triage with no PR yet do you leave its column as-is.)
- Read the full ticket (description + comments via `hypertask comment list <PREFIX-NNN>`) so you have the whole picture.
- **Now that you understand the ticket, run the `intensity` skill** and print its three-line recommendation (model + effort for this ticket's work). Goal is highest quality per token, so Valentin can switch model/effort before the work runs. Do this once, right here — don't block on it, just surface it and continue.
- Apply the `karpathy-guidelines` skill, then do the work: reproduce/diagnose the bug or scope the feature, branch off `main` (never commit to `main`), implement, self-verify.

If the ticket is genuinely ambiguous (unclear what success looks like), ask one clarifying question first (Rule 1). Otherwise proceed autonomously.
