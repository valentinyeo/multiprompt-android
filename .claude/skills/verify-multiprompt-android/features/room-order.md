# Room message order

A room keeps two sends in post order and the later agent's delivery carries the other agent's earlier answer before the new message, the way the desktop room engine already does.

Dispatch `verify.yml` with `feature=room-order.md`. The driver uses the same debug host import, fingerprint compare and **Trust and connect** path as the other session journeys. It then stores a local debug login for account `verify-rooms` and installs a fixture room runtime on the host with two Claude agents, Alpha and Beta. No account server and no live model is contacted for the room itself. The room is named **Order room**.

## Journey

1. Resume the app until Inbox lists **Order room**. Open it. Require **No messages yet** and an empty host submit log.
2. Send `one`. Require Alpha's copy in the submit log and the message `one` on screen, with no earlier answer block and no `two`. Beta has not accepted it yet: the fixture refuses Beta's first attempt once.
3. Send `two`. Require `one` above `two`, and Beta's reply below `two`. That reply contains `Room messages since your last turn, oldest first:`, contains `alpha: one`, and ends with `two`.
4. Require the submit log to show Alpha `one` then `two`, and Beta `one` before the context-wrapped `two`.

The screen and the submit log are both assertions: the app shows the order and the host independently records the same order and context.

## Gotchas

The app before this change delivers Beta `two` without the earlier answer, so the parent run of this script stops while waiting for `Room messages since your last turn, oldest first:`. A missing submit log means nothing has been sent. Beta does not take `one` until Alpha has taken the later send, so a refresh before that send still lists only Alpha.

The fixture login is stored already expired. Launch refreshes it at `account.multiprompt.dev`. A live `invalid_grant` clears that login and the screen returns to Sign in, so **Order room** never stays up. Before relaunch the runner rejects TCP port 443 to every address that name resolves to. The refresh fails as a network error, the local login stays, and the room still loads through the fixture SSH host.

Rooms load only when the app resumes (`RoomLoadPolicy`), and that load is bounded by the shared server link timeout. On the first cold launch the link itself takes longer than that to answer on a busy runner, so the room row can be missing from an Inbox that already lists sessions. The driver sends the app Home and brings it back, up to four times, and waits for the room row after each resume. A missing row after those resumes is a real failure, not a timing one.

`mpa fill 'Message Order room' …` dismisses the keyboard before Send is tapped, so the room keyboard never covers the button. Text still inside the composer is not a message row.

Account OTP, a live Claude session, mention-only delivery, an agent that stays offline until later, restart without a repeat send, and the longest message that still fits in one delivery are outside this recipe.

## Evidence

`room-ordered.xml` is the phone screen after both sends, carrying Beta's context reply. `room-submits.txt` is the host submit log, with Alpha's two deliveries and Beta's ordered pair. `room-inbox`, `room-empty` and `room-first-message` keep the numbered PNG/XML pairs for the screens before the second send.
