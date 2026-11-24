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
  - Outcomes: `bot-photo-refresh.journey`. Runner: `android-full.yml` (dedicated). Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Cancel waiting messages](chat-cancel.md): canonical queued/cancelled states, untouched terminal draft, surviving receipt order and delivered actions, with a recording.
  - Outcomes: `chat-cancel.journey`. Runner: `verify.yml` (debug). Priority evidence: `cancel-waiting.json` (3000 characters), `cancel-cancelled.json` (3000 characters), `cancel-delivered.json` (5000 characters).
- [Chat choices](chat-choices.md): latest-reply option taps and live question number keys, with independent SSH/tmux receipts.
  - Outcomes: `chat-choices.journey`. Runner: `verify.yml` (debug).
- [Codex transcript parity](chat-codex.md): Dispatch `feature=chat-codex.md`. The real Android APK connects through SSH to independent Hax, native Codex, and Claude fixture sessions. Hax and Codex write the same JSONL schemas consumed by desktop. Their terminal output deliberately excludes every transcript reply, ruling out terminal-scrape success.
  - Outcomes: `codex.transcript`, `reader.lifecycle`. Runner: `verify.yml` (debug). Priority evidence: `reader-ui.json` (6000 characters), `hax-hidden-before.json` (128 characters), `hax-hidden-after.json` (128 characters), `hax-resumed.json` (128 characters), `claude-hidden-before.json` (128 characters), `claude-hidden-after.json` (128 characters), `reader-second-close-receipt.txt` (512 characters), `codex-receipt.txt` (256 characters).
- [Queued messages](chat-queued.md): Queued host messages stay visible once, receive correlated consumption receipts and become answered without stale delivery labels.
  - Outcomes: `chat-queued.journey`. Runner: `verify.yml` (debug). Priority evidence: `00-queue-ui.json` (1500 characters), `00-queue-receipts.txt` (1500 characters), `queue-pending.json` (1500 characters), `queue-waiting-final.json` (1500 characters), `queue-followup-final.json` (1500 characters), `queue-uncertain-final.json` (1500 characters), `catchup-result.json` (1500 characters).
- [Claude reactions](chat-reactions.md): quoted sends, one badge, restart identity, fallback and signed update offers.
  - Outcomes: `chat-reactions.journey`. Runner: `verify.yml` (debug). Priority evidence: `reaction-receipts.txt` (1500 characters), `reaction-transport.json` (1500 characters), `reactions-result.json` (3500 characters), `10-reactions-mixed.xml` (5000 characters).
- [Legacy resync](chat-resync.md): legacy held-queue proof: Reset retires unsent copies with no receipt claim and no send; the phone shows its reader transcript across Reset and never claims legacy rows, which this app version does not request.
  - Outcomes: `chat-resync.journey`. Runner: `verify.yml` (debug).
- [Shared chat store](chat-shared.md): synthetic SSH store admission and host Reset, retaining both drafts.
  - Outcomes: `chat-shared.journey`, `chat.link-recovery`. Runner: `verify.yml` (debug). Priority evidence: `shared-before-reset.json` (1000 characters), `shared-after-reset.json` (1000 characters), `shared-codex-before-restart.json` (1000 characters), `shared-codex-after-reset.json` (1000 characters), `shared-link-recovered.xml` (2500 characters), `shared-phone.json` (1000 characters), `shared-link-recovery.txt` (500 characters), `shared-terminal.txt` (1000 characters).
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
  - Outcomes: `claude.skills-model-effort`, `reader.follow-newest`. Runner: `verify.yml` (debug). Priority evidence: `native-menu-state.txt` (5000 characters), `native-receipts.txt` (2500 characters), `native-result.json` (1000 characters).
- [Robot inbox and chat](robot-faces.md): Run `verify.yml` with `feature=robot-faces.md`. For the signed candidate, set `released_robots=true` and `candidate=true`. For the published update, set `released_robots=true` and `expected_commit` to the released SHA.
  - Outcomes: `robot-faces.journey`. Runner: `verify.yml` (signed). Inputs: `{"released_robots":true,"candidate":true}`. Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Inbox draft label](session-drafts.md): unsent composer text shows Draft before the inbox preview, and sending that text removes it.
  - Outcomes: `inbox.draft`. Runner: `verify.yml` (debug).
- [Starred yellow dot](session-favorite-dot.md): a starred chat shows a yellow dot in All, search and its workspace, and no dot in Favorites.
  - Outcomes: `inbox.favorite-dot`. Runner: `verify.yml` (debug). Priority evidence: `favorite-dot-all.json` (400 characters), `favorite-dot-search.json` (400 characters), `favorite-dot-workspace.json` (400 characters), `favorite-dot-favorites.json` (400 characters), `favorite-dot-unstarred.json` (400 characters), `favorite-dot-restarred.json` (400 characters), `favorite-dot-favorites-again.json` (400 characters).
- [Session favorites](session-favorites.md): signed APK, isolated SSH sessions and offline favorite cache; no production sync.
  - Outcomes: `session-favorites.journey`. Runner: `verify.yml` (signed). Inputs: `{"released_chat":true,"candidate":true}`. Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Session gestures](session-gestures.md): no session swipes, a code drag that starts no reply, ten replies, back to inbox on the signed APK.
  - Outcomes: `session-gestures.journey`. Runner: `verify.yml` (signed). Inputs: `{"released_chat":true,"candidate":true}`. Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Pinned sessions](session-pins.md): signed scrolling in both list orders. Use `session-favorites.md` with `pins_scroll=true`.
  - Outcomes: `session-pins.journey`. Runner: `verify.yml` (signed). Inputs: `{"feature":"session-favorites.md","pins_scroll":true,"released_chat":true}`. Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Session reader](session-reader.md): Native output, Send, Enter/approve and Interrupt, with independent console receipts, on the shared real-size restarted chat whose rendered newest reply is checked after open and after scrolling.
  - Outcomes: `reader.send-enter-interrupt`. Runner: `verify.yml` (debug). Priority evidence: `08-send-draft.xml` (1500 characters), `10-send-result.xml` (2500 characters), `11-enter-action.xml` (500 characters), `12-enter-result.xml` (1500 characters), `13-interrupt-action.xml` (500 characters), `14-interrupt-result.xml` (1500 characters), `receipts.txt` (1000 characters), `tmux-final.txt` (1000 characters).
- [Session routing](session-routing.md): Inbox and swipe switching, typed and simulated dictated sends, independent SSH receipts.
  - Outcomes: `session-routing.journey`. Runner: `verify.yml` (debug).
- [Session settings](session-settings.md): full-screen split switches; saving is blocked without account OTP and a pulled layout.
  - Outcomes: `session-settings.journey`. Runner: `verify.yml` (manual). Prerequisites: Account email OTP and a pulled account layout.
- [Stop while the chat works](session-stop.md): Stop replaces Send while a chat is working and the box is empty. Typing brings Send back. Stop interrupts the session.
  - Outcomes: `reader.stop-while-working`. Runner: `verify.yml` (debug).
- [Useful inbox summaries](session-summaries.md): Android inbox rows show the waiting question, current work, or the first sentence of the last assistant reply. Model footers and shell prompts never become summaries.
  - Outcomes: `inbox.summaries`. Runner: `verify.yml` (debug).
- [Sessions](sessions.md): Default seed readback: 46 real tmux sessions including a 3 MB tool-heavy restarted chat, runtime-selected off-screen reader entry, an exact search result for that same session below the search box and above the keyboard, and local chat pin/unpin at the visual top of the Inbox list across replies and app restart, plus archiving the pinned chat opening the row below it. Does not assert all 46 rows render.
  - Outcomes: `inbox.search`, `legacy.igsh-414`, `inbox.pin-chat`. Runner: `verify.yml` (debug). Priority evidence: `seed-counts.json` (2500 characters), `seed-check.json` (1200 characters), `seed-search.xml` (2000 characters), `seed-deep-target.xml` (1000 characters), `seed-deep-choice.json` (900 characters), `search-keyboard.json` (1200 characters), `row-long-press-actions.xml` (1500 characters), `pin-chat-state.json` (1400 characters).
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
  - Outcomes: `claude.remote-control`. Runner: `verify.yml` (debug). Priority evidence: `remote-control-open.json` (400 characters), `remote-control-connected.json` (900 characters), `remote-control-host.json` (3500 characters), `remote-control-before.txt` (200 characters).
- [AI usage](ai-usage.md): Open AI usage, compare Claude and Codex numbers with independent SSH receipts, then close. SOFT-2542.
  - Outcomes: `ai.usage`. Runner: `verify.yml` (debug). Priority evidence: `ai-usage-menu.json` (1500 characters), `ai-usage-open.json` (2000 characters), `ai-usage-closed.json` (1000 characters), `ai-usage-proof.txt` (200 characters), `ai-usage-host.json` (2000 characters), `ai-usage-receipts.txt` (3000 characters).
- [Skills within messages](skill-pills.md): insert a skill mid-message, check composer and sent pills, remove a pill and send the remaining text, refuse a file path and pill a built-in command. SOFT-2543, MUBO-146.
  - Outcomes: `skills.pills`. Runner: `verify.yml` (debug). Priority evidence: `skill-pills-state.json` (6000 characters), `skill-pills-receipts.txt` (1500 characters).
- [Expand and scroll a chat table](chat-table.md): Open a wide chat table, scroll sideways and down to a known far cell, then close. SOFT-2544.
  - Outcomes: `chat-table.expand`. Runner: `verify.yml` (debug). Priority evidence: `table-inline.json` (1200 characters), `table-open.json` (1200 characters), `table-sideways.json` (1200 characters), `table-down.json` (1200 characters), `table-closed.json` (1200 characters), `table-proof.txt` (200 characters).
- [Find and send Claude clear](clear-ranking.md): Type /cl, check name-first command ranking, select /clear and send the exact command with independent host receipts. SOFT-2545.
  - Outcomes: `claude.clear-ranking`. Runner: `verify.yml` (debug). Priority evidence: `clear-query.json` (1800 characters), `clear-selected.json` (700 characters), `clear-sent.json` (700 characters), `clear-receipts.txt` (700 characters), `clear-host.json` (700 characters), `clear-before.txt` (200 characters), `clear-selected-host.txt` (200 characters), `clear-proof.txt` (200 characters).
- [Signed-out sync row](signed-out-sync.md): Reader Session actions, then More, shows Sign in to sync. The journey does not sign in.
  - Outcomes: `legacy.igsh-413`. Runner: `verify.yml` (debug). Priority evidence: `reader-sync.json` (200 characters), `sync-menu-proof.txt` (80 characters).
- [Bot look choices](bot-look.md): Choose all faces and colours, scroll, dismiss and retain the local appearance after restart. SOFT-2653.
  - Outcomes: `bot.look`. Runner: `verify.yml` (debug). Priority evidence: `bot-look-top.json` (1000 characters), `bot-look-bottom.json` (1500 characters), `bot-look-retained-face.json` (1500 characters), `bot-look-retained-auto.json` (1500 characters), `bot-look-proof.txt` (200 characters).
- [Chat version label](reader-version.md): Open a chat, check the version label beside Session actions, tap it, and open App updates.
  - Outcomes: `legacy.igsh-426`. Runner: `verify.yml` (debug). Priority evidence: `reader-version.json` (400 characters), `reader-updates.json` (400 characters).
- [Reader rename and display controls](reader-controls.md): Rename and display, plus 40 reader More, key-validation, pinned-page, attachment and Computer controls with screenshots and independent SSH receipts. SOFT-2669.
  - Outcomes: `reader.controls`. Runner: `verify.yml` (debug). Priority evidence: `reader-controls-renamed.json` (1500 characters), `reader-controls-details-on.json` (1500 characters), `reader-controls-details-off.json` (1500 characters), `reader-controls-sunlight-on.json` (1500 characters), `reader-controls-sunlight-off.json` (1500 characters), `reader-controls-host.txt` (1000 characters), `reader-menu-proof.txt` (100 characters), `reader-menu-end-host.txt` (300 characters).
- [Reply to a message](message-reply.md): Long-press a Claude bubble, cancel the reply bar, send a quoted reply with a host receipt, then mention another chat by its real name and route the mention by tmux name.
  - Outcomes: `legacy.igsh-418`, `chat.mentions`. Runner: `verify.yml` (debug). Priority evidence: `reply-open.json` (400 characters), `reply-cancelled.json` (200 characters), `reply-sent.json` (200 characters), `reply-receipts.txt` (800 characters), `mention-list.json` (400 characters), `mention-sent.json` (400 characters), `mention-receipts.txt` (400 characters), `mention-session.txt` (80 characters).
- [Settings, host mapping and update navigation](settings-controls.md): Choose and retain themes and font sizes; navigate settings, host mapping and update menus. SOFT-2670.
  - Outcomes: `settings.controls`. Runner: `verify.yml` (debug). Priority evidence: `settings-theme-Sunlight.json` (800 characters), `settings-theme-Dark.json` (800 characters), `settings-font-Small.json` (800 characters), `settings-font-Large.json` (800 characters), `settings-retained.json` (1000 characters), `settings-refresh-host.txt` (2000 characters), `mapping-scroll-bottom.json` (1000 characters), `settings-version.json` (1000 characters).
- [Inbox buckets and reminders](inbox-reminders.md): Refresh with independent SSH reads; switch inbox buckets and save every reminder preset and custom deadlines. SOFT-2670.
  - Outcomes: `inbox.reminders`. Runner: `verify.yml` (debug). Priority evidence: `inbox-refresh-host.txt` (2000 characters), `bucket-unread-on.json` (500 characters), `reminder-scroll-bottom.json` (1000 characters), `reminder-day2-time.json` (500 characters), `reminder-custom-time.json` (500 characters), `reminder-keyboard-time.json` (500 characters), `reminder-keyboard-waiting.json` (1000 characters), `inbox-reminders-proof.txt` (200 characters).
- [Account upload key](screencast-account.md): Signed-in image selection without typing a Screencast2 key, with authenticated account receipts.
  - Outcomes: `upload.account-key`. Runner: `verify.yml` (debug). Prerequisites: Local authenticated account fixture; live production email OTP and successful Screencast2 upload are not exercised. Priority evidence: `screencast-signed-in.json` (2000 characters), `screencast-picker.json` (2000 characters), `screencast-selected.json` (2000 characters), `screencast-account-receipts.txt` (1000 characters).
- [Replies after a chat host restart](host-restart.md): Restart a desktop-hosted chat mid-conversation and require desktop and phone replies without reopening it. SOFT-2681.
  - Outcomes: `reader.host-restart`, `chat.restart-newest`. Runner: `verify.yml` (debug). Priority evidence: `host-restart-state.json` (2500 characters), `host-restart-host.json` (6000 characters), `host-restart-processes.txt` (1000 characters), `phone-restart-state.json` (1500 characters), `phone-restart-host.json` (500 characters).
- [Live chat shows new messages](live-chat-freshness.md): The real-size restarted chat shows its newest rows on open, and a row appended on the host reaches the phone within seconds with a host receipt.
  - Outcomes: `reader.live-tail`. Runner: `verify.yml` (debug). Priority evidence: `live-chat-freshness.json` (2500 characters), `live-chat-host.txt` (1500 characters), `live-chat-freshness.txt` (200 characters).
- [Inbox folder bar](folder-bar.md): Selected last/middle folders remain fully visible on selection and restart; overflow fades over a transparent bar. SOFT-2706.
  - Outcomes: `inbox.folder-bar`. Runner: `verify.yml` (debug). Priority evidence: `folder-bar-reopened.json` (1800 characters), `folder-bar-middle-reopened.json` (1400 characters), `folder-bar-all.json` (1400 characters), `folder-bar-proof.txt` (200 characters).
- [Chats that compacted, fresh-started or resumed](chat-shapes.md): A compacted, a resumed and a fresh-started chat each show the transcript of their live Claude session, never the stale pre-compaction one. SOFT-2715.
  - Outcomes: `chat.shapes`. Runner: `verify.yml` (debug). Priority evidence: `compacted-chat.json` (700 characters), `compacted-chat-proof.txt` (700 characters), `resumed-chat-proof.txt` (700 characters), `fresh-chat-proof.txt` (700 characters), `code-block.json` (700 characters), `code-block-proof.txt` (700 characters), `pre-425-reader.txt` (1500 characters), `seed-counts.json` (3000 characters).
- [Fresh start keeps one inbox row and the new conversation](session-fresh-start.md): Desktop Fresh start keeps one tmux-named inbox row and shows the handover-led new transcript without stale cache after relaunch and resync. SOFT-3000.
  - Outcomes: `session.fresh-start`. Runner: `verify.yml` (debug). Priority evidence: `fresh-start-state.json` (1200 characters), `fresh-start-host.json` (4000 characters), `fresh-start-open-proof.txt` (700 characters), `fresh-start-applied.json` (1000 characters), `fresh-start-inbox-inbox-after-fresh-start.json` (400 characters), `session-fresh-start-seed.json` (1000 characters).
- [Crash report for every app exit](crash-report.md): signs in through the local account fixture, plants uncaught exception, native crash, system kill and ANR exits, relaunches and requires one uploaded crash report per exit with a summary naming it.
  - Outcomes: `crash-report.exits`. Runner: `verify.yml` (debug). Priority evidence: `crash-reports-received.txt` (3200 characters), `crash-exit-summaries.json` (900 characters), `crash-exits.txt` (1600 characters).
- [Cleared chat](chat-clear.md): A transcribed chat that starts fresh after /clear shows only what came after the clear, keeps a message typed after it, and never shows or sends an unsent or failed message from before it.
  - Outcomes: `chat-clear.journey`. Runner: `verify.yml` (debug). Priority evidence: `00-clear-before-unsent.xml` (1500 characters), `00-clear-after.xml` (1500 characters), `00-clear-reopened.xml` (1500 characters), `00-clear-state.json` (1500 characters), `clear-store-after.json` (1500 characters), `clear-store-final.json` (1500 characters).

## Initial coverage gaps

These IDs are an inventory, not exemptions for future changes. Untouched gaps do not block this migration. Changing one requires its own journey proof. New uncovered IDs fail the ratchet even if another gap was removed. Signed routes run parent/head candidates with their registered inputs. Dedicated and manual routes remain outside the PR picker.

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
| `legacy.igsh-425` | IGSH-425 | IGSH-425 |
| `app-name.journey` | app-name.md | SOFT-2531 |
| `bot-photo-refresh.journey` | bot-photo-refresh.md | SOFT-2531 |
| `chat-working.journey` | chat-working.md | SOFT-2531 |
| `robot-faces.journey` | robot-faces.md | SOFT-2531 |
| `session-favorites.journey` | session-favorites.md | SOFT-2531 |
| `session-gestures.journey` | session-gestures.md | SOFT-2531 |
| `session-pins.journey` | session-pins.md | SOFT-2531 |
| `session-settings.journey` | session-settings.md | SOFT-2531 |
| `settings-navigation.journey` | settings-navigation.md | SOFT-2531 |
