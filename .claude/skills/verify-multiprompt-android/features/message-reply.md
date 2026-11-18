# Reply to a message

Dispatch `verify.yml` with `feature=message-reply.md`. The shared fixture seeds 42 sessions. `verify-session` is a Claude transcript whose only assistant line is `REPLY_BUBBLE_418`. The pane prints `REPLY_READY_418` and never that bubble, so the long-press target is the transcript row.

## Journey

1. Open `verify-session` by its exact `Robot verify-session` description.
2. Long-press `REPLY_BUBBLE_418`, tap **Reply**, and require **Replying**, the bubble preview, and **Cancel reply**.
3. Tap **Cancel reply** and require the reply bar to disappear.
4. Long-press the same bubble, tap **Reply**, type `REPLY_BODY_418`, and send it.
5. The host receipt file must contain the exact lines `> REPLY_BUBBLE_418` and `REPLY_BODY_418`.

The label on the phone is **Replying**. The older **Reply to …** wording is not on this screen.

## Not covered

Swipe-right reply uses the same bar, but this driver long-presses. A second finger is not required.
