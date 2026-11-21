# Fresh start keeps one inbox row and the new conversation

Dispatch `verify.yml` with `feature=session-fresh-start.md`. Outcome `session.fresh-start` covers SOFT-3000 and IGSH-2474.

## Sub-features

- One tmux-named inbox row after desktop Fresh start even when Claude's native session id changes and an older transcript file remains on the host.
- Opening the chat shows the handover first, then the new reply, with no stale cached or pre-fresh transcript on screen.
- Force-stop, relaunch, and reopening the chat keep the same single row and the same new conversation.

## How to get to it

The hosted fixture enables a supervised Claude chat on `live-fresh-start-3000` with stable host id `c0030000-3000-4000-8000-000000003000`.

1. Import and trust the ephemeral SSH host through the real debug forms.
2. From **Inbox**, seed an older on-device cache file that still contains `OLD_STALE_3000`, then replay desktop **Fresh start** on the fixture: same tmux session name, new Claude conversation id, archived pre-fresh transcript, handover `HANDOVER_3000`, divider, and live reply `FRESH_REPLY_3000`.
3. Require exactly one enabled inbox row named `live-fresh-start-3000`; forbid a second row, blank row, or renamed duplicate tied to the new native id alone.
4. Open the chat. Require `HANDOVER_3000` before `FRESH_REPLY_3000`. Forbid `OLD_STALE_3000`, `HOST_GHOST_STALE_3000`, and any ghost row preview carrying the old reply.
5. Return to **Inbox**, force-stop and relaunch the app, re-enter **Use without account (debug)** if the sign-in gate shows, wait for the `Robot live-fresh-start-3000` row, reopen **All**, and repeat the single-row and on-screen transcript checks.
6. Go back to **Inbox**, open the chat again from the row avatar, and require the same order and absence of stale text.

`session-fresh-start-host.json` and `fresh-start-state.json` retain independent host transcript choice, tmux `@mp_host_sid`, and rendered phone order. `fresh-start-inbox-*.json` records the exact enabled row bounds per phase.

## Hosted fixture

`tools/session-fresh-start/fixture.py` seeds the pre-fresh Claude transcript on `NATIVE_BEFORE`, a neighbour ghost transcript on the stable host sid, and host config/state on the real bundled reader. `apply-fresh-start` archives the old conversation, writes the handover file, rotates to `NATIVE_AFTER`, appends a fresh `init` event, and leaves all three jsonl files in the project folder so duplicate-row regressions stay visible.

`session-fresh-start-fixture.py check` replays seed and fresh-start without the phone. `tools/session-fresh-start/fresh-start.test.py` runs in `tools/check.sh source`.

## Gotchas

Relaunch shows the sign-in gate: the debug build keeps the saved host but not the **Use without account (debug)** choice, so `session-fresh-start-drive.sh` taps that button again and waits for the row avatar, not for the **Inbox** label. The avatar is the row; the label is only the bucket name in the top bar.

SOFT-2996: inbox rows must stay keyed to the host tmux chat name, never Claude's own session id. The fixture keeps `@mp_host_sid` on `live-fresh-start-3000` across the rotation and leaves the old native jsonl on disk like a long desktop chat. Plain `/clear` and desktop **Fresh start** both replace the live transcript; this journey simulates **Fresh start** only, but the row-identity rule is the same.

The phone chat menu has no **Resync chat** item; **Reset this chat** resets the host conversation instead of re-reading it. This journey therefore reopens the chat, which is the phone's own way to read the transcript again.

This journey does not tap the phone **Fresh start** menu item, prove a live model handover, or cover signed or account builds. A product failure that drops, duplicates, or revives the inbox row should fail honestly; note the step in the PR body instead of weakening the assertions.

## Not covered

Live provider compaction, OTP, and production queue behaviour stay outside this recipe.