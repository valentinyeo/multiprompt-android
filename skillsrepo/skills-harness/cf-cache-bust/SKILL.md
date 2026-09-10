---
name: cf-cache-bust
description: After any deploy to a Cloudflare-fronted site, purge the CF edge, prewarm Valentin's actual POP from his Windows box, and hand him a cache-busted URL so he sees the new version without Ctrl+Shift+R. Invoke as /cf-cache-bust [path ...]. Reads per-repo config from .claude/cf-cache-bust.yml in cwd.
---

# /cf-cache-bust

Valentin's recurring pain: I deploy, he reloads, sees the old version, we burn 10 minutes on hard-refresh theatre. This skill ends that loop by purging at the source, prewarming through his actual edge POP, and giving him a bust-param URL.

## When to invoke

**Automatically** after every deploy to any site listed in a `.claude/cf-cache-bust.yml`. Do not wait for Valentin to complain. If I just ran `wrangler deploy`, `vercel`, `npm run deploy`, pushed to a branch that triggers Pages, or edited a Worker — run this skill before telling Valentin "done".

Also run on explicit request: `/cf-cache-bust`, `/cf-cache-bust /some/path`, or "he can't see the change."

## Bootstrap — read or create config

On invocation, read `.claude/cf-cache-bust.yml` from cwd.

**If missing → interactive bootstrap, don't error.** Valentin works across many repos and can't predict which ones will need CF purging. The skill must onboard a new repo in one round-trip:

1. Detect hostname. Try in this order:
   - `wrangler.toml` → `routes` / `workers_dev` host
   - `vercel.json` / `.vercel/project.json`
   - `package.json` deploy scripts
   - git remote / directory name as last hint
2. If a hostname is detected, look it up against Cloudflare: `GET /zones?name=<apex>` using whichever of `CLOUDFLARE_API_TOKEN`, `CF_API_TOKEN_YEO_UX`, `CF_API_TOKEN_PRIVAT` is set. Try each token until one returns the zone.
3. Ask Valentin **one consolidated question** with the detected values prefilled:

   ```
   No cf-cache-bust config in this repo. Bootstrap?
     hostname:       <detected or ASK>
     zone_id:        <resolved or ASK>
     api_token_env:  <token env var that found the zone, or ASK>
     default_paths:  ["/"]         (edit if needed)
   Type `y` to save, or paste overrides as `key=value` lines.
   ```
4. On `y` → write `.claude/cf-cache-bust.yml`, proceed with the purge.
5. On overrides → merge, re-validate the zone lookup, save, proceed.
6. If detection fails completely → ask Valentin directly for hostname. Once given, re-run zone lookup across all known tokens. Only error if the user refuses to supply a hostname.

The config file is always written before the first purge — subsequent runs in the same repo skip bootstrap.

Config schema:

```yaml
# .claude/cf-cache-bust.yml
project_name: funeralprints
zone_id: "<cloudflare zone id>"           # dash.cloudflare.com → zone → API → Zone ID
hostname: fp.crolab.org                   # primary hostname to prewarm
api_token_env: CF_API_TOKEN_YEO_UX         # env var holding an API token with Zone.Cache Purge
default_paths:                            # URLs to purge + prewarm when no path arg is given
  - "/"
  - "/overview"
bust_param: v                             # query param name appended for the browser URL (default: v)
windows_prewarm: true                     # use `lh shell` to curl from Windows POP (default: true)
service_worker: false                     # if true, also emit a Clear-Site-Data hint
notes: |
  fp.crolab.org is a Pages project fronted by Cloudflare.
```

Token must exist in the environment. Known candidates on Valentin's VPS: `CLOUDFLARE_API_TOKEN` (default), `CF_API_TOKEN_YEO_UX`, `CF_API_TOKEN_PRIVAT`. During bootstrap, try each automatically. If `api_token_env` is set but empty, error loudly with the dash URL to mint a scoped token (`Zone.Cache Purge` for this zone only).

## What the skill does (in order)

### 1. Resolve paths to purge

- If paths were passed as args → use those.
- Else → use `default_paths` from config.
- Always normalize to full URLs: `https://<hostname><path>`.

### 2. Purge at Cloudflare

```bash
curl -sS -X POST \
  "https://api.cloudflare.com/client/v4/zones/${ZONE_ID}/purge_cache" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  --data "{\"files\":[\"https://${HOST}/\",\"https://${HOST}/overview\"]}"
```

Prefer `files:[...]` over `purge_everything:true` (faster, safer, no collateral). Only fall back to `purge_everything` if the user explicitly says so.

Check response `success: true`. On failure, show the full API error and stop — **do not claim the cache was busted**.

### 3. Prewarm through Valentin's actual edge POP

Valentin is currently in London (LHR POP). A `curl` from the VPS goes through a different POP, so it doesn't prove his browser will see fresh content. Use `lh shell` so the request originates from his Windows box:

```bash
lh shell "curl -sI -H 'Cache-Control: no-cache' 'https://${HOST}${PATH}' | Select-String -Pattern 'cf-cache-status|cf-ray|age|last-modified'"
```

Run twice, 2s apart:
- **First request:** expect `cf-cache-status: MISS` (or `EXPIRED`/`BYPASS`). This is the fill.
- **Second request:** expect `cf-cache-status: HIT`. Record the `cf-ray` — its suffix (e.g. `LHR`) confirms the POP.

If `lh shell` is unreachable, fall back to `dev-browser` hitting the URL and reading response headers via `page.evaluate()`. Note loudly that the prewarm was not from Valentin's POP — it will still work, just slightly slower on his first click.

### 4. Emit the bust URL

Append a unique query param so the browser's own disk cache cannot serve a stale hashed HTML:

```
https://${HOST}${PATH}?${bust_param}=${git-short-sha-or-epoch}
```

Prefer the current git short SHA. If not in a git repo, use `date +%s`.

### 5. Service-worker / app-cache warning

If `service_worker: true` in config, add this line to the final report:

> ⚠️ This site ships a service worker. The fresh HTML is live on the edge, but Valentin's SW may still serve stale assets until it updates. Suggest DevTools → Application → Service Workers → Update, or add `?${bust_param}=...` which forces SW bypass on hashed routes.

## Output

Exact shape to return:

```
✅ Cloudflare cache busted — ${project_name}

Purged (${N} URLs):
- https://${host}/
- https://${host}/overview

Prewarmed via Valentin's POP (${cf-ray-suffix}):
- GET /          MISS → HIT (age=0)
- GET /overview  MISS → HIT (age=0)

Open this to confirm (cache-busted):
https://${host}/?v=${sha}

${optional SW warning}
```

If any step failed, lead with the failure — never bury it.

## Failure modes and fallbacks

| Failure | What to do |
|---|---|
| `CF_API_TOKEN_*` env var missing | Stop. Print the dashboard URL to mint a scoped token. |
| `success: false` from purge API | Print the error JSON. Stop. |
| `lh shell` unreachable | Fall back to `dev-browser` or VPS curl; flag that POP wasn't verified. |
| 2nd request still `MISS` | Retry once after 3s. If still MISS, warn — Cloudflare may be bypassing cache (no-cache origin headers, `Cache-Control: private`, etc.). |
| No git repo (for SHA) | Use `date +%s` as bust value. |
| `purge_everything` requested | Confirm with Valentin once before firing — affects all URLs on the zone. |

## Integration with `verify`

`verify` proves **I** see the change on the VPS. `cf-cache-bust` proves **Valentin** will see it in his browser. They're complementary. If a workflow invokes both: run `verify` first (confirm deploy succeeded), then `cf-cache-bust` (confirm edge is clean for him).

## Execution rules

- Never skip the prewarm step to save time — that's the whole point.
- Never say "cache is cleared, try Ctrl+Shift+R now." The skill's contract is that Ctrl+Shift+R should not be necessary.
- Plain-text URLs only. No markdown links.
- When a new project needs this, add `.claude/cf-cache-bust.yml` before running — no silent autodetection.
