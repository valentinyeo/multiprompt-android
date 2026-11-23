# A chat that cleared shows only what came after the clear

Dispatch `verify.yml` with `feature=chat-clear.md`. The phone stays on **Use without account (debug)**.

## Sub-features

- A phone message that fails in the host store and shows **Not sent** with **Send again** and **Remove**.
- A `/clear` in the same chat: the agent starts a new transcript while the chat keeps its identity.
- The failed message is gone, its actions are gone, and the host store no longer offers it for sending.
- A message typed after the clear stays, also after leaving and reopening the chat.

## How to get to it (user POV)

1. Open `verify-session` and require `Ready for queued messages.`
2. Send `CLEARED_BEFORE_2474`. The fixture holds its draft, so the message stays queued in the canonical store.
3. The fixture makes the store expire the message on its own waiting-too-long rule, which is the same failed row a dead agent produces. Require `Not sent` on the phone with exactly one copy of the message and both **Send again** and **Remove**.
4. The fixture clears the chat: it writes a new transcript and swaps it in at the same path, so the source inode changes while the chat id stays. Require `CLEARED_READY_2474`, then require `CLEARED_BEFORE_2474`, `Send again` and `Remove` to be absent. The store must report the old message `cancelled` or `failed`, never queued or sent.
5. Send `AFTER_CLEAR_2474` and require it on screen. Leave the chat, reopen `verify-session` and require the old message still absent with `AFTER_CLEAR_2474` still visible exactly once and no resend actions.

Every assertion is the phone's own text. The numbered PNG/XML pairs, `00-clear-state.json` and the two store snapshots are retained.

## Driving it with mpa

Every run first executes the shared real-size chat step in scripts/real-chat-screen.sh: it opens live-real-2781 and requires the newest assistant reply on the rendered screen after open and after scrolling.

The fixture owns the simulated Claude pane and the message worker lock, exactly like `chat-queued.md`. `fail-messages` ages the row past the store's waiting limit and lets the store expire it, so the row keeps the store's own reason; it never rewrites a delivery state. `clear-transcript` replaces the transcript file, which is what Claude Code does on `/clear`; it does not touch the message store. The phone's own poll detects the replaced source.

## Gotchas

The current shared-message UI has no Queued, Sent or Delivered labels; only the failed bubble shows `Not sent`.

Do not use **Reset this chat**: shared Reset cancels queued work and has its own `chat-shared.md` journey.

This does not prove a real Claude `/clear`, a compaction handover, or provider-side pickup. Those live in `chat-shapes.md` and `chat-queued.md`.
