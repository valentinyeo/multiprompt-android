---
name: claude-history
description: "Reference for the claude-history CLI: list/render/search/delete/resume session transcripts, context-mode integration flags. Use when digging up what a past session did."
---

- **claude-history CLI** (`~/.local/bin/claude-history`) — TUI for browsing/searching Claude Code conversation history. Run directly via Bash with `--plain --no-tools` to keep output lean.
  - `claude-history -L` — list conversations for current workspace only
  - `claude-history -L --plain --no-tools` — plain text list, no tool noise (keep output lean)
  - `claude-history --show-dir` — print conversation storage directory
  - `claude-history -p` — print selected conversation file path
  - `claude-history -i` — print selected session ID
  - `claude-history --render <file> --plain --no-tools` — render a specific JSONL session as plain text
  - `claude-history --delete <SESSION_ID>` — delete a session by UUID
  - `claude-history -c` — resume selected conversation in Claude Code
  - `claude-history update` — self-update
  - **Running large session lists:** For conversation listings and rendered sessions, always use `--plain --no-tools` flags to keep output lean. If output is still large, delegate to a subagent via the Agent tool with model `haiku` for a summary pass.

