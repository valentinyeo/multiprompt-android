---
name: video-brief
description: Hand a just-shipped feature to the video pipeline. Invoke as /video-brief in the session that BUILT the feature (it has the richest context). Drafts the announcement-video script + demo brief from this session's knowledge, posts it as an HTML comment on the feature's Hypertask ticket, and moves the ticket to the "Video Production" column on the product board. Use when Valentin says "/video-brief", "queue this for a video", "add the video script to the ticket", or a feature just went live and deserves an announcement video.
---

# /video-brief — queue a shipped feature for an announcement video

You are the session that built this feature, so YOU write the brief: the video-producer
session will film from your words without your context. Be concrete, not generic.

Authoritative style/process: `openwiki/feature-announcement-videos.md` (hypertasks repo)
+ `~/projects/hypertask-videos/RUNBOOK.md`. The brief must fit that locked style.

## Steps

1. **Identify the ticket.** Use this session's current ticket (the one the feature shipped
   under). If the session has no ticket, ask which one. Never invent one.

2. **Draft the brief from session context.** Required parts, in this order:

   - **VO script — exactly 4 lines**, each ONE short spoken sentence (they are generated
     as single-sentence TTS takes):
     1. The user problem (never open with the word "Hypertask").
     2. The fix in one line.
     3. How it works — name the keyboard shortcut/command explicitly ("press I",
        "type /gif"); keyboard usage is a first-class selling point.
     4. CTA, exactly: "See it now on hypertask.ai."
   - **Intro-card headline**: 2–6 words, benefit-led, mark ONE word/phrase to be purple.
     It doubles as the YouTube thumbnail at Inter 168px, so keep it short and punchy.
   - **Demo beats**: 2–4 numbered beats for a ~25s screen capture. What is on screen,
     which keys are pressed, and the money moment (the payoff shot). Include what must
     be SEEDED on the demo board first (thread content, task metadata, GIFs, dense
     description) — filming happens ONLY on Northwind App (project 2093), day mode,
     never a customer or internal board. Never show the same GIF/content twice.
   - **Where it lives**: UI location, entry points, shortcuts, and the relevant source
     files/PR so the producer can verify behavior.
   - **YouTube title** suggestion: benefit first, shortcut last (pattern:
     "Hypertask writes a summary for every task. Just press I").

3. **Post the brief as a ticket comment** (HTML blocks per house rule — every element in
   `<p>`/`<ul>`, bold the load-bearing words, full `<a href>` URLs for any ticket/PR):

   ```
   hypertask comment add <PREFIX-NNN> --text '<p><strong>Queued for video production.</strong> Script + demo brief below; film per the openwiki feature-video page.</p><p><strong>Feature:</strong> ...</p><p><strong>Where:</strong> ... (shortcut: <strong>...</strong>)</p><p><strong>VO script:</strong></p><ul><li>...</li><li>...</li><li>...</li><li>See it now on hypertask.ai.</li></ul><p><strong>Intro headline:</strong> "..." (purple: <strong>...</strong>)</p><p><strong>Demo beats (Northwind, project 2093):</strong></p><ul><li>Seed: ...</li><li>Show: ...</li><li>Money moment: ...</li></ul><p><strong>YouTube title:</strong> ...</p><p>PR: <a href="https://github.com/...">#NNN</a> · Origin session: <code>claude --resume <this-session-uuid></code></p>'
   ```

   The resume string lets the producer session reach back into your context if the brief
   is ambiguous. Use THIS session's UUID (the `<id>.jsonl` filename under
   `~/.claude/projects/<project-slug>/`).

4. **Move the ticket**: `hypertask tasks move <PREFIX-NNN> --section "Video Production"`
   (product board project 15, section id 4468). This replaces the move to Done — the
   producer session moves it to Done after the video is published. If the ticket lives on
   a different board with no such column, leave the column unchanged and say so.

5. **Confirm tersely**: ticket id + full URL (`https://app.hypertask.ai/detail/project-15/<num>`),
   one line saying the brief is posted and the ticket is queued. Reload the ticket tab in
   zsb if it is open.

## Producer side (for reference, not this session's job)

A VIDEO PRODUCER session drains the column: picks the top ticket in "Video Production",
reads the brief, seeds/films/composes/publishes per the openwiki page, posts the YouTube
link as a comment, moves the ticket to Done.
