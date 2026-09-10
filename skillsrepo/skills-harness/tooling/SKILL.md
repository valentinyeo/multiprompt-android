---
name: tooling
description: Inventory all available CLIs, MCP servers, comms channels, and browser tools with live connectivity status. Run with /tooling.
version: 1.0.0
---

# Tooling Inventory

Run `scripts/check-tools.js` to detect CLI tools and connectivity. Combine with MCP server discovery from session context.

## Execution

1. Run: `node ~/.claude/skills/tooling/scripts/check-tools.js`
2. Parse the JSON output into tables
3. Scan deferred tools in session context for MCP servers — group by prefix
4. Present combined report

## Output Format

Present 5 sections as tables with columns: **Tool | Status | Notes**

### Sections
1. **CLI Tools** — `hypertask`, `gws`, `multiclock`, `lh`, `gh`, `wrangler`, `warm-claude`, etc.
2. **MCP Servers** — from deferred tools list, grouped by prefix (e.g. `mcp__posthog__*` → PostHog)
3. **Comms** — Gmail (work/personal), Telegram bot, AgentMail
4. **Browser** — `lh play`, `dev-browser`, `agent-browser`, Playwright MCP
5. **Infra** — `docker`, `ssh`, `node`, `python3`, `git`, etc.

### Status Values
- `OK` — found + connectivity verified
- `INSTALLED` — found, no connectivity test
- `MISSING` — not in PATH
- `ERROR` — found but test failed (show error snippet)

## MCP Server Discovery

Parse deferred tool names. Group by server prefix, count tools per server:
- `mcp__posthog__*` → PostHog
- `mcp__playwriter__*` → Playwriter
- `mcp__plugin_context-mode_*` → Context Mode
- `mcp__claude_ai_Hypertask__*` → Hypertask MCP (note: CLI preferred)
- Other `mcp__*` → list with count

## Connectivity Details

See `references/connectivity-tests.md` for specific check commands per tool.
