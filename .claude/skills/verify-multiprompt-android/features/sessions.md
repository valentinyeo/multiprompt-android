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
- `sessions-muted`: a tab with `muted: true` in the account layout never appears in any Inbox split, bucket, count or unread badge, yet stays in the workspace list and in Favorites, and a search finds it so its menu can Unmute it (not automated).
- `sessions-new`: New session host/folder/mode picker (not automated).

## How to get to it (user POV)

Inbox opens after account sign-in or debug offline entry. Return from Hosts using **Sessions**. The overflow's **Refresh** updates discovery; **Search sessions** opens search. Tap a session card to read it. **Switch session bucket**, **Session actions**, and **New session** expose organization and creation paths.

## Driving it with adb and UI Automator

Preconditions: fresh fixture; use `feature=sessions.md`. The driver performs the entire hosts recipe through compare/trust and public-key authentication first.

- Before any action, the driver checks `seed-counts.json` against the live tmux session names and the long transcript's measured bytes and rows. Normal setup provides 42 sessions and a transcript larger than 18 MiB with 1,882 rows; `ssh-auth.txt` independently proves authentication.
- Capture the initial inbox XML, then choose the first name from `seed-counts.json` absent from that dump. Record its name, exact `Robot <name>` description, expected reader text and initial snapshot in `seed-deep-choice.json`. If every seeded name is present, fail setup with `SEED_DEEP_SETUP_FAILED`. Scroll/tap the chosen description, require its named composer and fixture reply, and retain `seed-deep-target.xml`. The long-chat target uses the generator's latest-reply marker; console targets use `VERIFY_READY_528`. Return to Inbox.
- Open **Search sessions** and type the runtime-chosen session name, not the host label. Require `1 result across all splits` and the exact `Robot <name>` row, then retain `seed-search.xml` and the `search-match` PNG/XML pair. Query text alone is not a matching row.
- Close search, require the search field and one-result count to disappear, and reveal the same chosen session again. Retain `search-cleared`.
- This journey proves the 42-session SSH seed/readback, one runtime-selected reader and its exact search result. It does not claim every seeded row rendered, a long-chat soak, transcript performance or session-menu behavior. The failed full-inbox swipe sweep and all-rows assertion were removed.
- Results start beneath the search box and above the keyboard; current automation closes the keyboard before its shot, so inspect that layout manually. Inspect the XML as well as the screenshot: the typed query alone must not be mistaken for a matching card.

## Gotchas

The app prefers a useful tmux window title over the session name. The fixture explicitly names its window `verify-session` and disables automatic renaming; otherwise Python becomes the visible `python3` label. Search can match previews as well as names. A matching screenshot must contain the actual session card, not just the search input. Discovery uses real tmux, not a seeded session cache. Empty-query, no-match, refresh failure, stale cache, buckets, creation and management paths remain unverified by this recipe. Every invocation starts empty; do not reuse a previously imported host or shared emulator.
