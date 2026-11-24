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
- `sessions-new`: With one trusted host, open New session, choose its home folder, reveal the start-agent sheet and use Back without creating a session (automated). Agent creation and other host/mode choices are not automated.
- `sessions-pin`: Pinning a chat from the chat menu (**Pin to top**) moves it to the visual top of the Inbox list in both sort orders, keeps it there when another chat gets a new reply, survives app restart, and **Unpin** restores normal order (automated). Archiving a pinned chat opens the chat that sits below it, not the newest chat (automated). With grouping on, pinned chats leave their group for a **Pinned** section above every group, and that section keeps its own list key and offers no rename/ungroup sheet, so a user group named **Pinned** cannot collide with it; `SessionGroupsTest` asserts that section and `InboxOrderTest` / `ReaderSwipeOrderTest` assert the one top-to-bottom draw order that swipe and archive-next follow, pinned and unpinned rows alike, because a group layout needs a signed-in account, which the debug journey cannot reach (JVM only).

## How to get to it (user POV)

Inbox opens after account sign-in or debug offline entry. Return from Hosts using **Sessions**. The overflow's **Refresh** updates discovery; **Search sessions** opens search. Tap a session card to read it. **Switch session bucket**, **Session actions**, and **New session** expose organization and creation paths.

## Driving it with adb and UI Automator

Preconditions: fresh fixture; use `feature=sessions.md`. The driver performs the entire hosts recipe through compare/trust and public-key authentication first.

- Before any action, the driver checks `seed-counts.json` against the live tmux session names and the long transcript's measured bytes and rows. Normal setup provides 45 sessions and a transcript larger than 18 MiB with 1,882 rows; `ssh-auth.txt` independently proves authentication.
- Capture the initial inbox XML, then choose the first name from `seed-counts.json` absent from that dump. Record its name, exact `Robot <name>` description, expected reader text and initial snapshot in `seed-deep-choice.json`. If every seeded name is present, fail setup with `SEED_DEEP_SETUP_FAILED`. Scroll/tap the chosen description, require its named composer and fixture reply, and retain `seed-deep-target.xml`. The long-chat target uses the generator's latest-reply marker; console targets use `VERIFY_READY_528`. Return to Inbox.
- Open **Search sessions** and type the runtime-chosen session name, not the host label. Require `1 result across all splits` and the exact `Robot <name>` row, then retain `seed-search.xml` and the `search-match` PNG/XML pair. Query text alone is not a matching row.
- Close search, require the search field and one-result count to disappear, and reveal the same chosen session again. Retain `search-cleared`.
- This journey proves the 45-session SSH seed/readback, one runtime-selected reader and its exact search result. It does not claim every seeded row rendered, a long-chat soak, or transcript performance. The failed full-inbox swipe sweep and all-rows assertion were removed.
- The driver types the session name and leaves the keyboard up. `search-keyboard.json` requires that exact `Robot <name>` row below the search field and above the keyboard frame from `dumpsys`. Closing search still requires the field and the one-result count to disappear. Inspect the XML as well as the screenshot: the typed query alone must not be mistaken for a matching card.
- After the search is cleared the driver asserts the Inbox row has no per-row `Session actions` button, long-presses the runtime-chosen session row, waits for `Mark as unread` and `End tmux session`, takes `shot row-long-press-actions`, then presses back to close the menu. The row shows the name, a dim one-line preview and the time.
- Next, `pin-chat-drive.sh` proves local chat pinning on Inbox with `mpa`, asserting on-screen row positions rather than list index: it opens a second session (`pin-00`, or `pin-01` if `pin-00` is the runtime-chosen session) so that session sits newest at the bottom with the runtime-chosen session directly above it (`pin-before`), long-presses the runtime-chosen row and taps **Pin to top**, then requires that row to be the topmost row on screen with the row below it and to be above every other seeded row (`pin-pinned`), sends a real prompt to the other session and waits for its `VERIFY_RECEIVED:PIN_OTHER_REPLY_140` reply, returns to Inbox and requires the replied chat at the newest end with the pinned row absent there and still topmost (`pin-after-reply`), force-stops and relaunches the app to require the pinned row still topmost (`pin-after-restart`), then long-presses the pinned row, confirms **Unpin** replaces **Pin to top**, taps **Unpin**, relaunches and asserts normal order returns (`pin-unpinned`), pins that chat again (`pin-archive-top`), opens it and taps **Session actions → Waiting**, requiring the reader to open the chat that sat directly below the pinned row (`pin-archive-next`), restores it from the Waiting bucket to the top of Inbox (`pin-after-restore`) and unpins it again, retaining the summary in `pin-chat-state.json`.
- After closing the row menu, tap **New session → Use this folder**. Require **Claude Code** and **Shell** in the start-agent sheet and capture `start-agent-picker`. The shared action planner reveals the complete **Back** control, scrolling its container if needed. Tap **Back**, require **Use this folder** again, and capture `start-agent-back-to-folder`. Compare independent tmux session lists before and after; opening and leaving the picker must not create an agent.

## Gotchas

The app prefers a useful tmux window title over the session name. The fixture explicitly names its window `verify-session` and disables automatic renaming; otherwise Python becomes the visible `python3` label. Search can match previews as well as names. A matching screenshot must contain the actual session card, not just the search input. Discovery uses real tmux, not a seeded session cache. Empty-query, no-match, refresh failure, stale cache, buckets, creation and management paths remain unverified by this recipe. Every invocation starts empty; do not reuse a previously imported host or shared emulator.
