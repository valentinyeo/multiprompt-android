---
name: ship
description: The hypertasks PR + merge + preview protocol (formerly /pr-protocol). Invoke when a session is about to open a PR, is wondering whether it may merge/deploy, needs to show Valentin a feature, or Valentin says "/ship", "how do PRs work here", "should I merge", "can I ship this". Tells the session how to self-merge safely under the one-deploy-in-flight slot rule, how previews work, and how to surface a change for review without jamming the deploy line.
---

# hypertasks PR + merge + preview protocol

Many Claude sessions run on the `hypertasks` repo at once. There is ONE production line (`staging` branch = prod), so the only hard rule is **one deploy in flight at a time**. There is NO central integrator anymore (2026-07-10): **any session may merge its own green, safe PR** — but only when the deploy slot is free, and it then owns watching that deploy live and rolling back if it breaks. Reviewing is per-PR: every branch gets its own isolated preview URL. Full strategy: https://analytics.hypertask.app/previews.

## Every session: branch → PR → make reviewable

1. Do the work on a branch off `origin/staging`.
2. Open a PR with base `staging`.
3. **Enable auto-merge immediately:** `gh pr merge --auto --squash`. Repo setting `allow_auto_merge` only unlocks the button; each PR still needs this or it will sit green without merging. (Skip only for low-trust producers — they must leave auto-merge off.)
4. Move the ticket to its review lane and assign Valentin (userId 6).
5. Make it reviewable: your branch auto-builds to its own preview URL (`hypertasks-prod-git-<branch>-hypertaskai.vercel.app`); opening the PR is enough. If the PR changes a **visible screen**, put a before/after screenshot on the ticket/PR — Valentin approves from the picture, he can’t read code. A visible change with no screenshot is not reviewable.

## Self-merge — allowed, under the slot rule

You may merge your own PR when ALL of these hold. If any fails, leave it green for Valentin or another session and stop.

**1. It’s green.** All three required checks pass: `ci-build`, `claude-review`, `next-public-secrets`. `mergeStateStatus` is `CLEAN` (not `DIRTY`/`BLOCKED`). Also confirm the Vercel prod build on the PR is not `ERROR` — it isn’t a required check, but a failing one breaks the staging deploy after merge.

**2. It’s in the safe class.** Bugfix, plumbing, copy, isolated UI, revert-ready. **NOT self-mergeable — these wait for Valentin’s explicit yes:** auth, billing, Stripe, schema/Prisma migrations, security, or any big/high-blast-radius change. When unsure, treat it as needing Valentin.

**3. The deploy slot is free.** Before merging, check there is no `staging` deployment currently `BUILDING`/`QUEUED`/`INITIALIZING`, and the most recent one is `READY`:
```
TOKEN=$(grep -oP 'VERCEL_TOKEN=\K.*' ~/.config/val-staging/credentials.env | tr -d '"')
curl -s "https://api.vercel.com/v6/deployments?teamId=team_yureFlJZ6ibwebaOOkKc5whs&projectId=prj_oEok2iMNFPzj6AWe1KQBaIAcPaAf&limit=5" \
  -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import json,sys; [print(x['readyState'], x['meta'].get('githubCommitRef')) for x in json.load(sys.stdin)['deployments']]"
```
If a `staging` build is in flight, **wait** — do not merge on top of it. Poll headlessly (do not reload a browser tab). Slot is free = top `staging` entry is `READY`.

**4. You then own the deploy end-to-end.** Merge (squash), watch YOUR merge commit build to `READY` on `staging`, confirm `https://app.hypertask.ai` is up and the change is actually live. If it breaks: revert immediately (`git revert` on `staging`, or promote the previous Vercel deployment) and say so loudly. Never merge and walk away from a red deploy.

**Clash avoidance.** Right before you merge, re-check `mergeStateStatus` is still `CLEAN` — another session’s merge may have landed and made yours `DIRTY` (conflicting). If so, rebase onto `origin/staging` and let checks re-run before merging. Two sessions seeing a free slot at the same instant is a small residual race; if you merge and immediately see another `staging` build start that isn’t yours, one of you backs off and re-verifies.

> **Planned upgrade — GitHub merge queue.** The robust fix for the slot rule is to enable GitHub’s merge queue on `staging`: PRs enter the queue and it merges + deploys them strictly one at a time, re-running checks, with no manual slot-checking and no race. Once it’s on, self-merge becomes “add to merge queue” and steps 3–4’s manual serialization is handled by the platform. Not enabled yet — until then, follow the manual slot rule above.

## Previews — the review surface

- **One preview per branch/PR.** Isolated URL, reads the live prod DB (safe to view, not for destructive testing), and can **never** affect production — only a merge to `staging` deploys live.
- **Capacity:** effectively unlimited environments (one per branch); ~12 builds run concurrently; ~3-4 min each. The number of previews was never the limit — the old jam was the single build slot + Sentry + a double-building mirror, all now removed.
- **Show, don’t make him hunt:** attach before/after screenshots to the PR/ticket so 20 sessions’ work can be skimmed as images in one place, live-opened only when needed. An aggregating review dashboard is planned (see /previews).
- **Always log Valentin in before showing him a preview — never leave him on `/login`.** Previews are SSO-protected AND the app itself needs a session, so a bare preview URL dumps him on the sign-in screen. Authenticate him first so he lands on the feature. Only exception: the feature under review is the login/auth flow. Recipe (verified 2026-07-09): fetch the project's `protectionBypass` secret from the Vercel API; `POST {preview}/api/auth/send-email-link` with header `x-vercel-protection-bypass: <secret>` + body `{"email":"valentin@hypertask.ai"}` to get a `verificationCode` (must be triggered on the preview host); `POST {preview}/api/auth/verify-code {"code":...}` with the same header and grab the `nookies_user` + `ht_session` Set-Cookie values; inject both via `zsb cdp Network.setCookie` for the preview host; then `zsb navigate` to the target. Full step-by-step in the hypertasks repo `AGENTS.md`.

## Merge gate (what "green" means)

A PR is mergeable when its three required GitHub checks pass: `ci-build` (tsc + lint, on GitHub runners), `claude-review`, `next-public-secrets`. The Vercel prod build is NOT a required check.

## Status

1. **Preview login fix — DONE (HTPR-4050 / PR #1371, merged 2026-07-09).** Server self-fetches use the request origin, so per-branch previews log in and load boards correctly instead of calling production.
2. **Per-branch previews — LIVE (2026-07-09).** The Vercel "Ignored Build Step" was flipped so every branch builds its own preview (doc-only pushes still skip). Push a branch, get a preview. Note the shared build slot: preview builds and prod (staging) builds share one 12-build pool on the `hypertasks-prod` project, so a heavy preview burst can briefly queue a prod deploy; whoever is merging can cancel stale previews to fast-track a prod merge, and moving previews to a separate Vercel project is the planned decoupling if contention bites.
3. **Auto-screenshots + review dashboard — planned.** Until then, put the preview URL + a manual before/after screenshot on the ticket.

## Emergencies

A genuine production emergency (site down / active data loss): act immediately even if a deploy is in flight, and say so loudly.

Full context: repo `CLAUDE.md` + `AGENTS.md` (Session roles), and https://analytics.hypertask.app/pipeline + https://analytics.hypertask.app/previews.
