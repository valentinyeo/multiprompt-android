---
name: land
description: Take a fix or feature all the way to live without checking back. Invoke when Valentin says "/land", pastes a bug report, screenshot, or one-line feature ask with no other instruction, or says "just get this live", "take this all the way", "small fix", "ship this end to end". Drives ticket -> branch -> fix -> self-run adversarial Codex review (loop till resolved) -> merge -> deploy -> verify on production, then reports once. He has pre-assessed the risk; do not stop to ask him.

---

# /land — one ask in, live feature out

Valentin gives you a thing. You give him back a working feature on production. He answers nothing in between.

He **cannot read code** and he has **already assessed the risk before handing it to you.** So "review it" does not mean "show him a diff and wait" — it means *you* run an adversarial review yourself and resolve everything it finds. Everything here is revertible (`git revert` on `staging`, or promote the previous Vercel deployment), which is exactly why you don't ask: a bad land is undone in minutes, not escalated.

**Do not ask him what to do next.** Not "should I merge", not "want me to open the preview", not "which approach", not "this touches auth, are you sure". Resolve it yourself and say what you chose in the final report. The whole point of this command is that he explains the goal once.

Set a `/goal` for the ask so you cannot stop early.

## The one and only reason to flag him first

**Extraordinary, genuinely irreversible risk** — a change that a `git revert` would NOT undo: destructive schema migration or data delete/drop/truncate on the prod DB, a mass irreversible mutation, or a clear security hole you'd be shipping. That's it. Flag it, say why, wait.

If a revert fixes it, it is not extraordinary risk. Land it.

## Risk categories are NOT a stop — they are a review trigger

billing, auth, schema, security, or anything non-trivial → **do NOT stop and ask.** Run the adversarial Codex pass (below) and work until every real finding is resolved. That is the review Valentin would have asked for anyway; you do it, you don't hand it back.

## Ambiguity → pick and ship, don't ask

If the ask has two reasonable readings, build the most likely one, ship it, and state your assumption in the final report. He corrects or reverts if it's wrong. Only a truly unresolvable ambiguity (you genuinely cannot tell what feature he means) is worth one question — and even then, prefer shipping the likeliest reading.

## The pipeline

1. **Ticket first.** Reuse an existing one if it matches (`hypertask tasks search`), else create it on the product board (project 15). Never a PR with no ticket. Assign userId 6, move to In Progress.
2. **Read before you write.** Trace the real flow end to end. Grep every caller of what you're about to change — the fix goes where all callers route through, not on the one path the report names. Very often the hook you need already exists (a dead stub, an unused extension, a helper one file over). Find it before writing anything new.
3. **Shortest diff that actually fixes it.** Follow `karpathy-guidelines` and ponytail. Match the surrounding conventions even if you'd do it differently.
4. **Verify the behaviour yourself before the PR.** Run it. Drive the browser. See the thing happen. Never ship on "the types compile".
5. **Adversarial Codex review — mandatory, and you resolve it.** On anything beyond a one-line change, run `codex:codex-rescue` (or `codex exec review`) prompted to actively refute and find flaws. Then **loop**: fix every real finding, re-run, repeat until it comes back clean. Don't hand findings to Valentin — this pass replaces asking him. Findings that clearly predate your change: file a separate ticket, say so, don't expand scope. The bar to leave this step is "Codex has nothing real left," not "I triaged the first batch."
6. **PR, base `staging`.** Then follow `/ship` for the slot rule.
7. **Merge when green.** Do not ask again.
8. **Watch the deploy to READY**, then **verify the change on app.hypertask.ai** — the actual behaviour, in the running app, not just that the site loads. Merged is not done.
9. **Board hygiene:** live-confirmation comment on the ticket, move to Done, resume string per global rule 6d.

## Verifying live from this VPS

The VPS egress IP trips Vercel's bot challenge ("Failed to verify your browser"). Route the browser through the Hetzner SOCKS tunnel:

```
agent-browser --session <name> --proxy socks5://127.0.0.1:1080 open <url>
```

Check the tunnel exists first (`pgrep -af "ssh -f -N -D"`); one is usually already up on port 1080. Reuse it, don't kill it — other sessions share it.

Log in by minting an email-link JWT from the prod `JWT_SECRET` (Vercel env API), POSTing it to `/api/auth/verify-email-token`, and injecting the returned `nookies_user` + `ht_session` cookies. Account is always `valentin.yeo@gmail.com` (userId 6).

## Reporting back

One report, at the end, when it is live. Lead with what now works, in his words not yours. Then anything that differs from what he pictured, and anything you deliberately left out. Terse — he does not want the build log.
