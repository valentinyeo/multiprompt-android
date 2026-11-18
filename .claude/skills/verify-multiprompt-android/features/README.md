# multiprompt Android verification map

Generated from [registry.json](registry.json). Run `node .github/scripts/pstack-verifier/coverage.cjs generate` after editing the registry. CI rejects drift.

Read [SKILL.md](../SKILL.md) before dispatching. Use a fresh GitHub-hosted emulator per journey. Never build or run Android locally. Debug SSH fixtures do not prove account OTP, live providers or signed-release behavior.

A product change must name every changed outcome. The second-AI verifier requires the matching successful head journey, exact APK source SHA, action and result citations into the retained artifact, and independent side effects where required. Smoke alone cannot prove a product change. A new behavior needs a new outcome; extending an old recipe does not imply coverage.

## Features

- [Switch AI](ai-switch.md): Claude-to-Pi handover, reattached chat, and a new phone message with independent provider receipts.
  - Outcomes: `ai.switch`. Runner: `verify.yml` (debug). Priority evidence: `ai-switch-state.json` (3500 characters), `ai-switch-host.json` (3000 characters).
- [App name and update](app-name.md): published signed Android in-app update proof.
  - Outcomes: `app-name.journey`. Runner: `app-name.yml` (dedicated). Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Late account bot pictures](bot-photo-refresh.md): hosted ViewModel and Compose regressions; seeded picture, not live account sync.
  - Outcomes: `bot-photo-refresh.journey`. Runner: `test.yml` (dedicated). Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Cancel waiting messages](chat-cancel.md): exact cancellation, surviving order, host receipt and delivered actions, with a recording.
  - Outcomes: `chat-cancel.journey`. Runner: `verify.yml` (debug).
- [Chat choices](chat-choices.md): latest-reply option taps and live question number keys, with independent SSH/tmux receipts.
  - Outcomes: `chat-choices.journey`. Runner: `verify.yml` (debug).
- [Codex transcript parity](chat-codex.md): Dispatch `feature=chat-codex.md`. The real Android APK connects through SSH to independent Hax, native Codex, and Claude fixture sessions. Hax and Codex write the same JSONL schemas consumed by desktop. Their terminal output deliberately excludes every transcript reply, ruling out terminal-scrape success.
  - Outcomes: `codex.transcript`, `reader.lifecycle`. Runner: `verify.yml` (debug). Priority evidence: `reader-ui.json` (6000 characters), `hax-hidden-before.json` (128 characters), `hax-hidden-after.json` (128 characters), `hax-resumed.json` (128 characters), `claude-hidden-before.json` (128 characters), `claude-hidden-after.json` (128 characters), `reader-second-close-receipt.txt` (512 characters), `codex-receipt.txt` (256 characters).
- [Queued messages](chat-queued.md): Queued host messages stay visible once, receive correlated consumption receipts and become answered without stale delivery labels.
  - Outcomes: `chat-queued.journey`. Runner: `verify.yml` (debug). Priority evidence: `00-queue-ui.json` (1500 characters), `00-queue-receipts.txt` (1500 characters), `queue-pending.json` (1500 characters), `queue-waiting-final.json` (1500 characters), `queue-followup-final.json` (1500 characters), `queue-uncertain-final.json` (1500 characters), `catchup-result.json` (1500 characters).
- [Claude reactions](chat-reactions.md): quoted sends, one badge, restart identity, fallback and signed update offers.
  - Outcomes: `chat-reactions.journey`. Runner: `verify.yml` (debug). Priority evidence: `reaction-receipts.txt` (1500 characters), `reaction-transport.json` (1500 characters), `reactions-result.json` (3500 characters), `10-reactions-mixed.xml` (5000 characters).
- [Legacy resync](chat-resync.md): historical held-queue proof; not host Reset proof.
  - Outcomes: `chat-resync.journey`. Runner: `verify.yml` (debug).
- [Shared chat store](chat-shared.md): synthetic SSH store admission and host Reset, retaining both drafts.
  - Outcomes: `chat-shared.journey`, `chat.link-recovery`. Runner: `verify.yml` (debug). Priority evidence: `shared-before-reset.json` (1000 characters), `shared-after-reset.json` (1000 characters), `shared-codex-before-restart.json` (1000 characters), `shared-codex-after-reset.json` (1000 characters), `05-shared-after-reset.xml` (3000 characters), `07-shared-codex-after-reset.xml` (3000 characters), `shared-terminal.txt` (1000 characters), `shared-result.txt` (500 characters).
- [Working dots on the signed release](chat-working.md): quiet tools, lock/unlock, real SSH reconnect, recording and timed visible-state samples. Uses `working-dots.yml`.
  - Outcomes: `chat-working.journey`. Runner: `working-dots.yml` (dedicated). Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Copy chat ID](copy-chat-id.md): the chat menu copies the host chat id, or the tmux name when the chat has no host id.
  - Outcomes: `chat.copy-id`. Runner: `verify.yml` (debug).
- [Changed host key](host-changed-key.md): compare a rotated identity, persist Not now and refuse authentication.
  - Outcomes: `host-changed-key.journey`. Runner: `verify.yml` (debug). Priority evidence: `host-key-ui.json` (3000 characters), `host-key-auth.json` (2000 characters), `ssh-auth.txt` (1500 characters).
- [Retired hosts](hosts-retired.md): a saved host at a retired server address is gone after relaunch; account sync never adds it back.
  - Outcomes: `hosts-retired.journey`. Runner: `verify.yml` (debug).
- [Hosts](hosts.md): add VPS, import encrypted-at-rest key, compare/trust and retain the host identity.
  - Outcomes: `hosts.journey`. Runner: `verify.yml` (debug). Priority evidence: `hosts-ui.json` (4000 characters), `ssh-auth.txt` (1500 characters), `host-fingerprint.txt` (300 characters).
- [Launcher icon](launcher-icon.md): the robot launcher icon in the emulator's app drawer, with the themed-icon layer present in the built APK.
  - Outcomes: `launcher-icon.journey`. Runner: `verify.yml` (debug).
- [Native Claude controls](native-skills.md): slash filtering, shared plus entry, actual session model/effort and retained image picker.
  - Outcomes: `claude.skills-model-effort`. Runner: `verify.yml` (debug). Priority evidence: `native-menu-state.txt` (5000 characters), `native-receipts.txt` (2500 characters), `native-result.json` (1000 characters).
- [Robot inbox and chat](robot-faces.md): Run `verify.yml` with `feature=robot-faces.md`. For the signed candidate, set `released_robots=true` and `candidate=true`. For the published update, set `released_robots=true` and `expected_commit` to the released SHA.
  - Outcomes: `robot-faces.journey`. Runner: `verify.yml` (signed). Inputs: `{"released_robots":true,"candidate":true}`. Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Inbox draft label](session-drafts.md): unsent composer text shows Draft before the inbox preview, and sending that text removes it.
  - Outcomes: `inbox.draft`. Runner: `verify.yml` (debug).
- [Starred yellow dot](session-favorite-dot.md): a starred chat shows a yellow dot in All, search and its workspace, and no dot in Favorites.
  - Outcomes: `inbox.favorite-dot`. Runner: `verify.yml` (debug). Priority evidence: `favorite-dot-all.json` (400 characters), `favorite-dot-search.json` (400 characters), `favorite-dot-workspace.json` (400 characters), `favorite-dot-favorites.json` (400 characters), `favorite-dot-unstarred.json` (400 characters), `favorite-dot-restarred.json` (400 characters), `favorite-dot-favorites-again.json` (400 characters).
- [Session favorites](session-favorites.md): signed APK, isolated SSH sessions and offline favorite cache; no production sync.
  - Outcomes: `session-favorites.journey`. Runner: `verify.yml` (signed). Inputs: `{"released_chat":true,"candidate":true}`. Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Session gestures](session-gestures.md): no session swipes, bidirectional code scroll, ten replies, back to inbox on the signed APK.
  - Outcomes: `session-gestures.journey`. Runner: `verify.yml` (signed). Inputs: `{"released_chat":true,"candidate":true}`. Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Pinned sessions](session-pins.md): signed scrolling in both list orders. Use `session-favorites.md` with `pins_scroll=true`.
  - Outcomes: `session-pins.journey`. Runner: `verify.yml` (signed). Inputs: `{"feature":"session-favorites.md","pins_scroll":true,"released_chat":true}`. Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Session reader](session-reader.md): native output, Send, Enter/approve and Interrupt, with independent console receipts.
  - Outcomes: `reader.send-enter-interrupt`. Runner: `verify.yml` (debug). Priority evidence: `08-send-draft.xml` (1500 characters), `10-send-result.xml` (2500 characters), `11-enter-action.xml` (500 characters), `12-enter-result.xml` (1500 characters), `13-interrupt-action.xml` (500 characters), `14-interrupt-result.xml` (1500 characters), `receipts.txt` (1000 characters), `tmux-final.txt` (1000 characters).
- [Session routing](session-routing.md): Inbox and swipe switching, typed and simulated dictated sends, independent SSH receipts.
  - Outcomes: `session-routing.journey`. Runner: `verify.yml` (debug).
- [Session settings](session-settings.md): full-screen split switches; saving is blocked without account OTP and a pulled layout.
  - Outcomes: `session-settings.journey`. Runner: `verify.yml` (manual). Prerequisites: Account email OTP and a pulled account layout.
- [Stop while the chat works](session-stop.md): Stop replaces Send while a chat is working and the box is empty. Typing brings Send back. Stop interrupts the session.
  - Outcomes: `reader.stop-while-working`. Runner: `verify.yml` (debug).
- [Useful inbox summaries](session-summaries.md): Android inbox rows show the first sentence of the last assistant reply, or current work. Model footers and shell prompts never become summaries.
  - Outcomes: `inbox.summaries`. Runner: `verify.yml` (debug).
- [Sessions](sessions.md): Default seed readback: 42 real tmux sessions and a large transcript, runtime-selected off-screen reader entry, and an exact search result for that same session. Does not assert all 42 rows render.
  - Outcomes: `inbox.search`. Runner: `verify.yml` (debug). Priority evidence: `seed-counts.json` (1500 characters), `seed-check.json` (1500 characters), `seed-search.xml` (2000 characters), `seed-deep-target.xml` (1000 characters), `seed-deep-choice.json` (1000 characters), `row-long-press-actions.xml` (1500 characters), `start-agent-picker.xml` (1500 characters), `start-agent-back-to-folder.xml` (1000 characters).
- [Settings navigation](settings-navigation.md): sessions-first settings, host mapping and account handoff on the signed APK.
  - Outcomes: `settings-navigation.journey`. Runner: `verify.yml` (signed). Inputs: `{"release_choices":true,"candidate":true}`. Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Sign in](sign-in.md): real account browser handoff; OTP remains manual.
  - Outcomes: `sign-in.journey`. Runner: `verify.yml` (debug).
- [Terminal](terminal.md): real SSH PTY/tmux fallback attachment.
  - Outcomes: `terminal.journey`. Runner: `verify.yml` (debug).
- [Message identity and replies](chat-identity.md): Phone send receipts, delayed transcript catchup, Fresh start format and reconnect keep each message with its reply once.
  - Outcomes: `chat.identity`. Runner: `verify.yml` (debug). Priority evidence: `projection-receipts-before.json` (2500 characters), `projection-receipts-reconnect.json` (5500 characters), `projection-state-before.json` (500 characters), `projection-state-fresh.json` (500 characters), `projection-state-catchup.json` (500 characters), `projection-state-reconnect.json` (500 characters), `projection-proof.txt` (500 characters).
- [Approve a pending device](device-approval.md): Shipping Approve/No controls, real browser PKCE, HTTP decisions and independently verified SSH key installation/removal. Local account fixture; not live email OTP.
  - Outcomes: `legacy.igsh-449`. Runner: `verify.yml` (debug). Prerequisites: Local authenticated account fixture; live production email OTP is not exercised. Priority evidence: `device-approve-ui.json` (2000 characters), `device-deny-ui.json` (2000 characters), `device-decisions-ui.json` (2000 characters), `device-approval-receipts.txt` (1500 characters), `device-approved-ssh.txt` (500 characters), `device-after-denial.txt` (1000 characters).
- [Find words inside a chat](chat-search.md): search real chat replies, check highlighted matches, jump, wrap, handle no matches and close. SOFT-2540.
  - Outcomes: `chat-search.open`. Runner: `verify.yml` (debug). Priority evidence: `search-newest.json` (1000 characters), `search-previous.json` (1000 characters), `search-wrap-previous.json` (1000 characters), `search-wrap-next.json` (1000 characters), `search-next.json` (1000 characters), `search-no-match.json` (500 characters), `search-closed.json` (500 characters), `search-proof.txt` (200 characters).
- [Saved snippets](snippets.md): save, restart, replace a draft with a saved snippet, send it, and delete it permanently.
  - Outcomes: `snippets.save-reuse-delete`. Runner: `verify.yml` (debug). Priority evidence: `snippets-state.json` (3000 characters), `snippets-receipts.txt` (1000 characters).
- [Claude remote control](claude-remote-control.md): Open a hosted Claude chat, enable remote control from the phone, and share phone and desktop turns with independent host receipts. SOFT-2541.
  - Outcomes: `claude.remote-control`. Runner: `verify.yml` (debug). Priority evidence: `remote-control-state.json` (2000 characters), `remote-control-host.json` (3500 characters), `remote-control-before.txt` (200 characters).
- [AI usage](ai-usage.md): Open AI usage, compare Claude and Codex numbers with independent SSH receipts, then close. SOFT-2542.
  - Outcomes: `ai.usage`. Runner: `verify.yml` (debug). Priority evidence: `ai-usage-menu.json` (1500 characters), `ai-usage-open.json` (2000 characters), `ai-usage-closed.json` (1000 characters), `ai-usage-proof.txt` (200 characters), `ai-usage-host.json` (2000 characters), `ai-usage-receipts.txt` (3000 characters).
- [Skills within messages](skill-pills.md): insert a skill mid-message, check composer and sent pills, remove a pill and send the remaining text. SOFT-2543.
  - Outcomes: `skills.pills`. Runner: `verify.yml` (debug). Priority evidence: `skill-pills-state.json` (4000 characters), `skill-pills-receipts.txt` (1000 characters).
- [Expand and scroll a chat table](chat-table.md): Open a wide chat table, scroll sideways and down to a known far cell, then close. SOFT-2544.
  - Outcomes: `chat-table.expand`. Runner: `verify.yml` (debug). Priority evidence: `table-inline.json` (1200 characters), `table-open.json` (1200 characters), `table-sideways.json` (1200 characters), `table-down.json` (1200 characters), `table-closed.json` (1200 characters), `table-proof.txt` (200 characters).
- [Find and send Claude clear](clear-ranking.md): Type /cl, check name-first command ranking, select /clear and send the exact command with independent host receipts. SOFT-2545.
  - Outcomes: `claude.clear-ranking`. Runner: `verify.yml` (debug). Priority evidence: `clear-query.json` (1800 characters), `clear-selected.json` (700 characters), `clear-sent.json` (700 characters), `clear-receipts.txt` (700 characters), `clear-host.json` (700 characters), `clear-before.txt` (200 characters), `clear-selected-host.txt` (200 characters), `clear-proof.txt` (200 characters).
- [Signed-out sync row](signed-out-sync.md): Reader Session actions, then More, shows Sign in to sync. The journey does not sign in.
  - Outcomes: `legacy.igsh-413`. Runner: `verify.yml` (debug). Priority evidence: `reader-sync.json` (200 characters), `sync-menu-proof.txt` (80 characters).
- [Version label](version-labels.md): The version label on Inbox, settings, the reader and the shell, and the Update screen it opens.
  - Outcomes: `legacy.igsh-426`. Runner: `verify.yml` (debug). Priority evidence: `inbox-version.json` (300 characters), `update-screen.json` (400 characters), `settings-version.json` (300 characters), `reader-version.json` (300 characters), `shell-version.json` (300 characters), `version-proof.txt` (80 characters).

## Initial coverage gaps

These IDs are an inventory, not exemptions for future changes. Untouched gaps do not block this migration. Changing one requires its own journey proof. New uncovered IDs fail the ratchet even if another gap was removed. Signed, dedicated and manual routes remain explicit; the debug picker must not select them.

| Outcome | Existing case | Cleanup ticket |
|---|---|---|
| `pi.model-switch` | [#374](https://github.com/valentinyeo/multiprompt-android/pull/374) | SOFT-2539 |
| `legacy.igsh-429` | IGSH-429 | IGSH-429 |
| `legacy.igsh-422` | IGSH-422 | IGSH-422 |
| `legacy.igsh-432` | IGSH-432 | IGSH-432 |
| `legacy.igsh-434` | IGSH-434 | IGSH-434 |
| `legacy.igsh-423` | IGSH-423 | IGSH-423 |
| `legacy.igsh-415` | IGSH-415 | IGSH-415 |
| `legacy.igsh-424` | IGSH-424 | IGSH-424 |
| `legacy.igsh-418` | IGSH-418 | IGSH-418 |
| `legacy.igsh-425` | IGSH-425 | IGSH-425 |
| `legacy.igsh-414` | IGSH-414 | IGSH-414 |
| `app-name.journey` | app-name.md | SOFT-2531 |
| `bot-photo-refresh.journey` | bot-photo-refresh.md | SOFT-2531 |
| `chat-working.journey` | chat-working.md | SOFT-2531 |
| `robot-faces.journey` | robot-faces.md | SOFT-2531 |
| `session-favorites.journey` | session-favorites.md | SOFT-2531 |
| `session-gestures.journey` | session-gestures.md | SOFT-2531 |
| `session-pins.journey` | session-pins.md | SOFT-2531 |
| `session-settings.journey` | session-settings.md | SOFT-2531 |
| `settings-navigation.journey` | settings-navigation.md | SOFT-2531 |
