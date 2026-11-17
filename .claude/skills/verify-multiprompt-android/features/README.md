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
  - Outcomes: `chat-reactions.journey`. Runner: `verify.yml` (debug).
- [Legacy resync](chat-resync.md): historical held-queue proof; not host Reset proof.
  - Outcomes: `chat-resync.journey`. Runner: `verify.yml` (debug).
- [Shared chat store](chat-shared.md): synthetic SSH store admission and host Reset, retaining both drafts.
  - Outcomes: `chat-shared.journey`, `chat.link-recovery`. Runner: `verify.yml` (debug). Priority evidence: `shared-before-reset.json` (1000 characters), `shared-after-reset.json` (1000 characters), `shared-codex-before-restart.json` (1000 characters), `shared-codex-after-reset.json` (1000 characters), `05-shared-after-reset.xml` (3000 characters), `07-shared-codex-after-reset.xml` (3000 characters), `shared-terminal.txt` (1000 characters), `shared-result.txt` (500 characters).
- [Working dots on the signed release](chat-working.md): quiet tools, lock/unlock, real SSH reconnect, recording and timed visible-state samples. Uses `working-dots.yml`.
  - Outcomes: `chat-working.journey`. Runner: `working-dots.yml` (dedicated). Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Copy chat ID](copy-chat-id.md): the chat menu copies the host chat id, or the tmux name when the chat has no host id.
  - Outcomes: `chat.copy-id`. Runner: `verify.yml` (debug).
- [Changed host key](host-changed-key.md): compare a rotated identity, persist Not now and refuse authentication.
  - Outcomes: `host-changed-key.journey`. Runner: `verify.yml` (debug).
- [Retired hosts](hosts-retired.md): a saved host at a retired server address is gone after relaunch; account sync never adds it back.
  - Outcomes: `hosts-retired.journey`. Runner: `verify.yml` (debug).
- [Hosts](hosts.md): add VPS, import encrypted-at-rest key, compare/trust and retain the host identity.
  - Outcomes: `hosts.journey`. Runner: `verify.yml` (debug).
- [Launcher icon](launcher-icon.md): the robot launcher icon in the emulator's app drawer, with the themed-icon layer present in the built APK.
  - Outcomes: `launcher-icon.journey`. Runner: `verify.yml` (debug).
- [Native Claude controls](native-skills.md): slash filtering, shared plus entry, actual session model/effort and retained image picker.
  - Outcomes: `claude.skills-model-effort`. Runner: `verify.yml` (debug).
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
- [Sessions](sessions.md): authenticated tmux discovery and search.
  - Outcomes: `inbox.search`. Runner: `verify.yml` (debug).
- [Settings navigation](settings-navigation.md): sessions-first settings, host mapping and account handoff on the signed APK.
  - Outcomes: `settings-navigation.journey`. Runner: `verify.yml` (signed). Inputs: `{"release_choices":true,"candidate":true}`. Prerequisites: Use the recipe-specific signed workflow and inputs; debug drive.sh is not interchangeable.
- [Sign in](sign-in.md): real account browser handoff; OTP remains manual.
  - Outcomes: `sign-in.journey`. Runner: `verify.yml` (debug).
- [Terminal](terminal.md): real SSH PTY/tmux fallback attachment.
  - Outcomes: `terminal.journey`. Runner: `verify.yml` (debug).
- [Message identity and replies](chat-identity.md): Phone send receipts, delayed transcript catchup, Fresh start format and reconnect keep each message with its reply once.
  - Outcomes: `chat.identity`. Runner: `verify.yml` (debug). Priority evidence: `projection-receipts-before.json` (2500 characters), `projection-receipts-reconnect.json` (5500 characters), `projection-state-before.json` (500 characters), `projection-state-fresh.json` (500 characters), `projection-state-catchup.json` (500 characters), `projection-state-reconnect.json` (500 characters), `projection-proof.txt` (500 characters).
- [Find words inside a chat](chat-search.md): search real chat replies, check highlighted matches, jump, wrap, handle no matches and close. SOFT-2540.
  - Outcomes: `chat-search.open`. Runner: `verify.yml` (debug). Priority evidence: `search-newest.json` (1000 characters), `search-previous.json` (1000 characters), `search-wrap-previous.json` (1000 characters), `search-wrap-next.json` (1000 characters), `search-next.json` (1000 characters), `search-no-match.json` (500 characters), `search-closed.json` (500 characters), `search-proof.txt` (200 characters).

## Initial coverage gaps

These IDs are an inventory, not exemptions for future changes. Untouched gaps do not block this migration. Changing one requires its own journey proof. New uncovered IDs fail the ratchet even if another gap was removed. Signed, dedicated and manual routes remain explicit; the debug picker must not select them.

| Outcome | Existing case | Cleanup ticket |
|---|---|---|
| `snippets.save-reuse-delete` | [#376](https://github.com/valentinyeo/multiprompt-android/pull/376) | SOFT-2537 |
| `pi.model-switch` | [#374](https://github.com/valentinyeo/multiprompt-android/pull/374) | SOFT-2539 |
| `claude.remote-control` | [#366](https://github.com/valentinyeo/multiprompt-android/pull/366) | SOFT-2541 |
| `ai.usage` | [#377](https://github.com/valentinyeo/multiprompt-android/pull/377) | SOFT-2542 |
| `skills.pills` | [#378](https://github.com/valentinyeo/multiprompt-android/pull/378) | SOFT-2543 |
| `chat-table.expand` | [#379](https://github.com/valentinyeo/multiprompt-android/pull/379) | SOFT-2544 |
| `claude.clear-ranking` | [#381](https://github.com/valentinyeo/multiprompt-android/pull/381) | SOFT-2545 |
| `legacy.igsh-449` | IGSH-449 | IGSH-449 |
| `legacy.igsh-429` | IGSH-429 | IGSH-429 |
| `legacy.igsh-422` | IGSH-422 | IGSH-422 |
| `legacy.igsh-432` | IGSH-432 | IGSH-432 |
| `legacy.igsh-434` | IGSH-434 | IGSH-434 |
| `legacy.igsh-423` | IGSH-423 | IGSH-423 |
| `legacy.igsh-415` | IGSH-415 | IGSH-415 |
| `legacy.igsh-426` | IGSH-426 | IGSH-426 |
| `legacy.igsh-424` | IGSH-424 | IGSH-424 |
| `legacy.igsh-418` | IGSH-418 | IGSH-418 |
| `legacy.igsh-425` | IGSH-425 | IGSH-425 |
| `legacy.igsh-414` | IGSH-414 | IGSH-414 |
| `legacy.igsh-413` | IGSH-413 | IGSH-413 |
| `app-name.journey` | app-name.md | SOFT-2531 |
| `bot-photo-refresh.journey` | bot-photo-refresh.md | SOFT-2531 |
| `chat-working.journey` | chat-working.md | SOFT-2531 |
| `robot-faces.journey` | robot-faces.md | SOFT-2531 |
| `session-favorites.journey` | session-favorites.md | SOFT-2531 |
| `session-gestures.journey` | session-gestures.md | SOFT-2531 |
| `session-pins.journey` | session-pins.md | SOFT-2531 |
| `session-settings.journey` | session-settings.md | SOFT-2531 |
| `settings-navigation.journey` | settings-navigation.md | SOFT-2531 |
