---
name: warm-claude
description: "Reference for the warm-claude CLI: orchestrate/exec/status/overdrive/reset commands, JSON reporting requirements (overdrive levels, cache hit rate, savings), rebuild instructions. Use when draining tickets through warm session slots."
---

- **warm-claude CLI** (`~/.local/bin/warm-claude`) — Manages warm Claude Code sessions with prompt cache reuse. **Always use `--json` and present the results to Valentin** — he wants to see overdrive levels, cache hit rates, and cost savings.
  - `warm-claude orchestrate --project <id> --slots <n> --cwd <path> --json [--tickets T1,T2] [--section "Name"]` — Drain tasks through warm session slots. `--tickets` limits to specific tickets. `--section` pulls from a specific column (default: auto-detect Todo/Ready).
  - `warm-claude exec --slot <n> --task <ticket> --cwd <path>` — Execute a single task in a warm slot
  - `warm-claude status` — Show slot states (warm/cold, overdrive level, session IDs)
  - `warm-claude overdrive` — Show overdrive levels with progress bars
  - `warm-claude reset --slot <n> | --all` — Reset slot state
  - **After running, always report:** overdrive level per slot, cache hit %, total cost, estimated savings. Parse the JSON `cache_hit_rate`, `estimated_savings_usd`, `slots[].overdrive`, and `tasks[].cache_hit_pct` fields.
  - Overdrive levels: Cold (0%) → Warm (40%) → Hot (60%) → Blazing (70%) → Overdrive (80%) → Supernova (85%). Higher = more cache reuse = cheaper.
  - Source: `~/projects/warm-claude-cli` (Zig). Rebuild: `cd ~/projects/warm-claude-cli && zig build -Doptimize=ReleaseSafe && cp zig-out/bin/warm-claude ~/.local/bin/`

