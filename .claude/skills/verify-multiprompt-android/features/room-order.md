# Room message order

A room shows the first send above the second. After the first agent has answered, the second agent's reply to the later send includes that earlier answer, oldest first, and then the new message.

Dispatch `verify.yml` with `feature=room-order.md`. The driver uses the same debug host import, fingerprint compare, and **Trust and connect** path as the other session journeys. It then stores a local debug login for account `verify-rooms` and a fixture runtime with two Claude agents, Alpha and Beta. No account server and no live model is contacted for the room itself. The room is named **Order room**.

## Journey

1. On Inbox, open **Order room**. Require **No messages yet**. The host submit log is missing or empty.
2. Send `one`. Require Alpha's copy in the submit log and the message `one` on screen. Beta has not accepted it yet: the fixture refuses Beta's first attempt once.
3. Send `two`. Require `one` above `two`, and Beta's reply below `two`. That reply contains `Room messages since your last turn, oldest first:`, `alpha: one`, and ends with `two`.
4. Require the submit log to show Alpha `one` then `two`, and Beta `one` before the context-wrapped `two`.

## Gotchas

The app before this change delivers Beta `two` without the earlier answer, so the parent run of this script stops while waiting for `Room messages since your last turn, oldest first:`. A missing submit log means nothing has been sent. Beta does not take `one` until Alpha has taken the later send, so a refresh before that send still lists only Alpha.

The room keyboard covers Send while it is still opening. The driver closes the keyboard and taps Send again until the draft leaves the composer. Text still inside the composer is not a message row.

Account OTP, a live Claude session, mention-only delivery, an agent that stays offline until later, restart without a repeat send, and the longest message that still fits in one delivery are outside this recipe.
