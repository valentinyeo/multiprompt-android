# A prompt a hook blocked shows Not sent at once

Dispatch `verify.yml` with `feature=send-blocked.md`. The phone stays on **Use without account (debug)**.

## Sub-features

- The fixture host records the prompt the phone sent as a `UserPromptSubmit` hook block: a `system` transcript row with `promptSubmitEnding.promptKept=false`, the same shape as `app/src/test/resources/reader/MUBO-246/blocked-by-hook.jsonl`.
- The phone's message turns **Not sent** seconds after that row lands, with the blocked reason under it.
- The label did not come from the ten-minute waiting limit: the host store row says `blocked_by_hook`, and the elapsed time is far below the 600-second wait.
- The blocked prompt adds no delivered bubble of its own.

## How to get to it (user POV)

1. Open `verify-session` and require `Ready for queued messages.`
2. Send `BLOCKED_BY_HOOK_266`. The fixture holds its own terminal draft, so the canonical store keeps the message queued: require `BLOCKED_BY_HOOK_266` once on screen and no **Not sent**. The fixture then clears its draft, the store dispatches, and the fixture records the terminal receipt. Require the store row `sent` with no receipt. Capture `blocked-draft` and `blocked-quoted`.
3. The fixture appends the hook-blocked `system` row for that exact prompt and stamps the time it landed. Require **Not sent** with the detail `Chat blocked this message, nothing was sent`, exactly one copy of the message and no second delivered bubble of it. Capture `blocked-not-sent`.
4. Require the store row `failed` with a receipt whose reason is `blocked_by_hook`, and require the wall-clock time from the landed row to the visible label to stay inside the limit.
5. Leave the chat, reopen `verify-session`, and require the same single **Not sent** message with the same detail and no duplicate copy.

Every assertion is the phone's own text. The numbered PNG/XML pairs, `send-blocked-before.json`, `send-blocked-store.json` and `send-blocked-host.json` are retained, alongside the fixture receipts.

## Driving it with mpa

Every run first executes the shared real-size chat step in scripts/real-chat-screen.sh: it opens live-real-2781 and requires the newest assistant reply on the rendered screen after open and after scrolling.

The fixture owns the simulated Claude pane and the message worker lock, exactly like `chat-queued.md`. `block:PROMPT` appends the block row and its timestamp; the phone's own poll reads the store row the canonical connector wrote, so no fixture code rewrites a delivery state. The receipt, the transcript row and the store row are independent of the Android APK.

The visible label limit is 8000 ms from the recorded row to the first UI dump that shows **Not sent**. A break-it replay (throttled to 128000 bit/s with 150-300ms latency) allows 60000 ms, still an order of magnitude below the 600-second waiting limit the fixed code removes.

## Gotchas

The unblocked path still marks a message **Not sent** after the ten-minute waiting limit. That limit is what makes the pre-fix phone leave a blocked prompt looking pending; this journey fails as `BLOCKED_PROMPT_NOT_SENT_TOO_SLOW` when the label arrives that way.

Do not use **Reset this chat**: shared Reset cancels queued work and has its own `chat-shared.md` journey.

This does not prove a real Claude `UserPromptSubmit` hook, real provider pickup or a signed release build. Those live in `chat-codex.md` and the signed routes.
