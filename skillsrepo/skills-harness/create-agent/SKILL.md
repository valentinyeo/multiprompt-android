---
name: create-agent
description: Scaffold a new Hypertask @mention bot (agent) using hypertask-agent-kit. Creates an AgentMail-backed, Gemini-driven bot that listens for @mentions on a Hypertask board and replies based on a knowledge directory. Invoke via /create-agent.
version: 1.0.0
---

# /create-agent — scaffold a new Hypertask bot

This skill wraps the `hypertask-agent-kit` repo (`~/projects/hypertask-agent-kit/`,
GitHub `valentinyeo/hypertask-agent-kit`) so Valentin can add new bots without
re-remembering all the pieces.

## What a bot is

A bot listens on a Hypertask board for `@<bot-name>` mentions, fetches the
ticket context, reads a knowledge directory, and posts an HTML comment back
to the ticket. Architecture: AgentMail inbox → CF tunnel → `agent-kit` Zig
server → fire-and-forget handler script → Gemini → Hypertask comment.

The two existing inne bots (`inne-wiki`, `inne-analytics`) are the reference
implementations — they run on the bespoke `inne-hooks` service today. New
bots should be built on the kit.

## MANDATORY: Interactive Setup Gate

Before doing any work, collect these from the user via `AskUserQuestion` if
they were not provided inline:

| Field | Example | Why |
|---|---|---|
| **Client name** | `inne`, `baybella`, `vetsak` | Picks the per-client deployment dir (`~/agent-kit-<client>/`) |
| **New client or existing?** | `new` / `existing` | If new, run `agent-kit-init` first (needs tunnel) |
| **Agent name** | `inne-wiki`, `bay-designer` | Becomes AgentMail inbox + route path + handler dir |
| **Hypertask project ID** | `339` (inne) | Where the bot posts comments |
| **Ticket prefix** | `INNE`, `BAY` | Used to parse `PREFIX-XXXX` out of mention emails |
| **Knowledge directory** | `/home/valentin/projects/inne-cro/wiki` | What the LLM can read |
| **One-line purpose** | "compliance AB-test copy checker" | Used in the prompt skeleton |

**For NEW client only, additionally collect:**
- Tunnel hostname (e.g. `inne-hooks.crolab.org` or `bay-hooks.crolab.org`)
- Tunnel ID (`cloudflared tunnel create agent-kit-<client>` → copy ID)
- Port (default `8912`)

If anything is missing, ASK. Never guess these.

## The Steps

### 1. Repo check
```bash
ls ~/projects/hypertask-agent-kit/bin/agent-kit-add-agent \
   ~/projects/hypertask-agent-kit/bin/agent-kit-init
```
If missing, tell the user to clone: `gh repo clone valentinyeo/hypertask-agent-kit ~/projects/hypertask-agent-kit`.

### 2. New client → init
Only if client is NEW (no `~/agent-kit-<client>/` dir yet):

```bash
# First: ask user to create the CF tunnel manually (they pick the name/hostname)
cloudflared tunnel create agent-kit-<client>
# User notes the tunnel ID, then:
~/projects/hypertask-agent-kit/bin/agent-kit-init <client> <hostname> <tunnel-id> <port>
```

Then walk the user through:
- `cloudflared tunnel route dns <tunnel-id> <hostname>`
- Adding `AGENTMAIL_API_KEY` to `~/agent-kit-<client>/secrets.env`
- `systemctl --user enable --now cloudflared-agent-kit-<client>.service agent-kit-<client>.service`

Do NOT proceed to step 3 until the user confirms the tunnel is up (`curl https://<hostname>/health` returns 200).

### 3. Add the agent

```bash
~/projects/hypertask-agent-kit/bin/agent-kit-add-agent \
  ~/agent-kit-<client> \
  <agent-name> \
  <hypertask-project-id> \
  <ticket-prefix> \
  <knowledge-dir>
```

This writes `~/agent-kit-<client>/agents/<agent-name>/handler.sh`, appends a
route to `routes.json`, appends a secret placeholder to `secrets.env`, and
restarts the systemd unit.

### 4. External setup (the parts that can't be scripted)

Walk the user through each, in order. Confirm each one before moving on:

**4a. Create AgentMail inbox.** Use `curl` to call the AgentMail API:
```bash
curl -X POST https://api.agentmail.to/v0/inboxes \
  -H "Authorization: Bearer $AGENTMAIL_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"address":"<agent-name>"}'
```
Remind Valentin that AgentMail free plan is capped at 3 inboxes — check
current count first via `curl https://api.agentmail.to/v0/inboxes ...`.

**4b. Add webhook on the inbox.** Point it at:
```
https://<tunnel-hostname>/webhook/<agent-name>
```
In the AgentMail dashboard. Copy the Svix signing secret (`whsec_...`) and put
it in `~/agent-kit-<client>/secrets.env` replacing the placeholder:
```
export <AGENT_NAME_UPPER_UNDERSCORE>_WEBHOOK_SECRET="whsec_..."
```

**4c. Create the Hypertask user.** Either via the web UI (signup + add to
project + get JWT) or guide them through the existing inne pattern (they'll
know). JWT goes in `~/agent-kit-<client>/secrets/<agent-name>.json`:
```json
{"token": "<jwt>"}
```

**4d. Restart the kit:**
```bash
systemctl --user restart agent-kit-<client>.service
```

**4e. Optional cron fallback.** If you want the 1-minute AgentMail poll as a
backup (recommended — webhook deliveries sometimes fail):
```bash
crontab -e
# add: * * * * * /home/valentin/agent-kit-<client>/agents/<agent-name>/poll-mentions.sh
```

### 5. Customize the prompt

The generated `handler.sh` has a generic prompt block. Before the bot is
useful, open `~/agent-kit-<client>/agents/<agent-name>/handler.sh`, find the
`PROMPT=` block, and rewrite it for the agent's specific job. Use
`inne-cro/wiki/bin/handle-webhook.sh` as the reference (it's the fleshed-out
version for `inne-wiki`).

### 6. Test

Ask the user to `@<agent-name>` themselves on a throwaway ticket. Watch logs:
```bash
tail -f /tmp/<agent-name>-bot.log
```

If no reply after 60s, check:
1. `systemctl --user status agent-kit-<client>.service` — is the server up?
2. `journalctl --user -u agent-kit-<client>.service -n 50` — did the webhook hit?
3. AgentMail dashboard → the inbox → webhook delivery log — was it delivered?
4. Handler log above — did the script run and fail somewhere?

## Hard rules

- **Never edit `~/projects/inne-hooks/`** — it's the running production for
  the inne bots. Port them onto the kit ONLY when Valentin explicitly asks,
  and do it as a separate migration, not as a side effect of `/create-agent`.
- **Never delete AgentMail inboxes to make room.** The free-plan 3-inbox cap
  is already full (`valentin.yeo@`, `inne-wiki@`, `inne-analytics@`). If the
  user wants a new bot, they need to delete one first OR upgrade — ASK.
- **Never commit secrets.** `.gitignore` in the kit covers `secrets.env` and
  `secrets/`, but double-check `git status` before any commit in a client dir.
- **Always verify the tunnel is live before calling the bot done.**
  `curl https://<hostname>/health` must return `ok\n`.

## Reference files

- `~/projects/hypertask-agent-kit/README.md` — architecture + flow diagram
- `~/projects/hypertask-agent-kit/bin/agent-kit-init` — client scaffold script
- `~/projects/hypertask-agent-kit/bin/agent-kit-add-agent` — agent scaffold script
- `~/projects/hypertask-agent-kit/templates/handler.template.sh` — what gets generated
- `~/projects/inne-cro/wiki/bin/handle-webhook.sh` — fleshed-out reference prompt
