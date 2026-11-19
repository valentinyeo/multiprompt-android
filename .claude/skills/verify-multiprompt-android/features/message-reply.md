# Reply to a message

Dispatch `verify.yml` with `feature=message-reply.md`. The shared fixture seeds 42 sessions. `verify-session` is a Claude transcript whose only assistant line is `REPLY_BUBBLE_418`. The pane prints `REPLY_READY_418` and never that bubble, so the long-press target is the transcript row. The fixture console behaves like a live Claude console: it turns terminal echo off, redraws an empty prompt box after every Enter, writes each marked prompt back into the same transcript as a user turn, and answers `REPLY_DELIVERED_418`.

## Journey

Every step drives the phone through `mpa`.

1. Open `verify-session` by its exact `Robot verify-session` description.
2. Long-press `REPLY_BUBBLE_418` with `mpa hold`, tap **Reply**, and require **Replying**, the bubble preview, and the **Cancel reply** description.
3. Tap **Cancel reply** and require the reply bar to disappear, in text and in descriptions.
4. Long-press the same bubble, tap **Reply**, type `REPLY_BODY_418` with `mpa fill`, and send it.
5. The host receipt file must contain the exact lines `> REPLY_BUBBLE_418` and `REPLY_BODY_418`.
6. Require the host answer `REPLY_DELIVERED_418`, then require the reply bubble is present and is not marked **Not sent**, never offers **Send again** or **Remove**, and carries no **Replying** bar. The phone shows those retry controls only when the host never accepted the prompt, so their absence is the sent proof.

`mpa proof` records each state as `reply-open.json`, `reply-cancelled.json` and `reply-sent.json`, with the text and description it checked. Host receipts come from the SSH fixture's stdin log, not a phone echo.

The label on the phone is **Replying**. The older **Reply to …** wording is not on this screen.

## Not covered

Swipe-right reply uses the same bar, but this driver long-presses. A second finger is not required.
