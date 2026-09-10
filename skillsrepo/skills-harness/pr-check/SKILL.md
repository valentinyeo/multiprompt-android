---
name: pr-check
description: Report how many PRs went live (merged to staging = production) in the last 1h/2h/12h/24h with a short performance assessment. Invoke on /pr-check.
---

# pr-check

1. Run `pr-check` (in `~/.local/bin`). It prints merge counts per window, the 24h merged list, and open-PR states (red-checks / conflict / parked / green-or-pending).

2. Reply as a SIMPLE BULLETED LIST only, nothing else:

- **1h:** N
- **2h:** N
- **12h:** N
- **24h:** N
- **Unreleased PRs:** total open PRs, with the state split in parentheses (red-checks / conflict / parked / green-or-pending)
- **My queue:** blocked-column count + pending manager deliveries
- **Human gate:** count in Valentin Review

Then TWO more bullet blocks, always, never omitted (they are the whole point of the report). Copy them from the `MERGED LIVE` and `DOING NOW` sections the script prints. No tables:

**Merged live per agent** (1h / 2h / 12h / 24h), one line per agent. The QA agent's line is tickets checked, not PRs.

**Doing now**, one line per agent: the ticket it is working plus **queue depth**, or why it is idle. Queue depth is always a number, never "queue empty": `queue N` counts tickets sitting in that agent's own start sections (its `HT_START_SECTIONS`), assigned to it or unassigned, so it is the work the agent can actually pull right now. The trailing counts (`N handed to worker`, `N blocked`, `N in review`) only appear when non-zero. An agent that reads **IDLE with a non-zero queue is a fault**, not a lull: say so and name it as the blocker. When an agent has been on the SAME ticket for over 90 minutes, say so with the elapsed time, because a single slow turn is the usual reason a window reads zero.

- **Assessment:** one or two sentences max. Compare against the 2 tickets-live/hour work-hours floor. If a window is 0 or the trend stalls, name the single biggest blocker from the open-PR states (e.g. "9 PRs red on claude-review") and what is being done about it. No headers, no tables, no PR list unless he asks.
