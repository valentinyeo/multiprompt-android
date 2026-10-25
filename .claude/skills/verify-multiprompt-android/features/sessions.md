# Discover and search sessions

Inbox lists the real tmux sessions on saved trusted hosts, with output previews and search across sessions. Users can refresh, change buckets and open a native reader.

## Sub-features

- `sessions-discover`: authenticated SSH discovery of `verify-session`.
- `sessions-search`: matching query and clear/close search (automated).
- `sessions-reader-entry`: tap a session card (covered by reader and terminal recipes).
- `sessions-manage`: Inbox/Waiting/Archive buckets, reminders and session actions (not automated).
- `sessions-frequent`: **Frequent** uses account-mapped input within 24 h, otherwise three 10 s watch visits within 6 h; not every discovered session qualifies (not automated).
- `sessions-badge`: new assistant message count, not just an unread dot; requires an agent transcript (not automated).
- `sessions-settings`: **Session list actions → Settings** opens **Session list settings**; see [split switch journey](session-settings.md), blocked without OTP/layout.
- `sessions-muted`: a tab with `muted: true` in the account layout never appears in any Inbox split, bucket, count or unread badge, yet stays in the workspace list (not automated).
- `sessions-new`: New session host/folder/mode picker (not automated).

## How to get to it (user POV)

Inbox opens after account sign-in or debug offline entry. Return from Hosts using **Sessions**. The overflow's **Refresh** updates discovery; **Search sessions** opens search. Tap a session card to read it. **Switch session bucket**, **Session actions**, and **New session** expose organization and creation paths.

## Driving it with adb and UI Automator

Preconditions: fresh fixture; use `feature=sessions.md`. The driver performs the entire hosts recipe through compare/trust and public-key authentication first.

- `wait_for_text verify-session` and `shot sessions` prove the real fixture is discovered. `ssh-auth.txt` independently proves authentication.
- `tap_desc 'Search sessions'` opens search. `fill 'Search all sessions' verify-session` enters the fixture name through adb input. `wait_for_text '1 result across all splits'`, `wait_for_text verify-session` and `shot search-match` capture query and matching result.
- `tap_desc 'Close session search'` removes the query and search field; the driver asserts `Search all sessions` is absent. `shot search-cleared` captures the original discovered list again.
- After the search is cleared the driver asserts the Inbox row has no per-row `Session actions` button, long-presses the `verify-session` row, waits for `Mark unread` and `End session`, takes `shot row-long-press-actions`, then presses back to close the menu. The row shows the name, a dim one-line preview and the time.
- Results start beneath the search box and above the keyboard; current automation closes the keyboard before its shot, so inspect that layout manually. Inspect the XML as well as the screenshot: the typed query alone must not be mistaken for a matching card.

## Gotchas

The app prefers a useful tmux window title over the session name. The fixture explicitly names its window `verify-session` and disables automatic renaming; otherwise Python becomes the visible `python3` label. Search can match previews as well as names. A matching screenshot must contain the actual session card, not just the search input. Discovery uses real tmux, not a seeded session cache. Empty-query, no-match, refresh failure, stale cache, buckets, creation and management paths remain unverified by this recipe. Every invocation starts empty; do not reuse a previously imported host or shared emulator.
