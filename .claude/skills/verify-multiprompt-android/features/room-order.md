# Room message order

A room keeps two sends in post order and the later agent's delivery carries the other agent's earlier answer before the new message, the way the desktop room engine already does.

Dispatch `verify.yml` with `feature=room-order.md`. The driver uses the same debug host import, fingerprint compare and **Trust and connect** path as the other session journeys. It then stores a local debug login for account `verify-rooms` and installs a fixture room runtime on the host with two Claude agents, Alpha and Beta. No account server and no live model is contacted for the room itself. The room is named **Order room**.

## Journey

1. Reveal **Order room** in Inbox with `mpa tap text 'Order room' --reveal`, scrolling the list rather than only reading its visible rows. Resume and retry if the room has not loaded. Open it. Require **No messages yet** and an empty host submit log.
2. Send `one`. Require Alpha's copy in the submit log and the message `one` on screen, with no earlier answer block and no `two`. Beta has not accepted it yet: the fixture refuses Beta's first attempt once.
3. Send `two`. Require `one` above `two`, and Beta's reply below `two`. That reply contains `Room messages since your last turn, oldest first:`, contains `alpha: one`, and ends with `two`.
4. Require the submit log to show Alpha `one` then `two`, and Beta `one` before the context-wrapped `two`.
5. Return to Inbox and open **Existing room**, created with a message before the phone launch. Require its old `EXISTING_BEFORE_139` message. Send `EXISTING_SEND_139` through **Message Existing room** and **Send**. Require both messages on screen and Gamma's host receipts in the same order.

The screen and the submit log are both assertions: the app shows the order and the host independently records the same order and context.

## Gotchas

The app before this change delivers Beta `two` without the earlier answer, so the parent run of this script stops while waiting for `Room messages since your last turn, oldest first:`. A missing submit log means nothing has been sent. Beta does not take `one` until Alpha has taken the later send, so a refresh before that send still lists only Alpha.

The fixture login is stored already expired. Launch refreshes it at `account.multiprompt.dev`. A live `invalid_grant` clears that login and the screen returns to Sign in, so **Order room** never stays up. Before relaunch the runner rejects TCP port 443 to every address that name resolves to. The refresh fails as a network error, the local login stays, and the room still loads through the fixture SSH host.

Inbox uses a lazy, reversed session list. Its room strip can sit beyond the visible sessions, so `mpa read --select contains` cannot establish that a room is absent. The driver uses the scrolling action resolver to reveal the exact room row before asserting and opening it. If the room has not loaded, it backgrounds and resumes the app for another room load, with four bounded reveal attempts. Missing rooms still fail the journey.

Phone startup publishes its normal reader runtime over the fixture runtime. After opening the room, the driver activates the fixture again without changing the room database or its messages. `room-runtime.txt` records the replaced selection and requires the bundled bridge to report `live_send: true` before any send.

`mpa fill 'Message Order room' …` dismisses the keyboard before Send is tapped, so the room keyboard never covers the button. Text still inside the composer is not a message row.

Account OTP, a live Claude session, mention-only delivery, an agent that stays offline until later, restart without a repeat send, and the longest message that still fits in one delivery are outside this recipe.

## Evidence

`room-ordered.xml` is the phone screen after both sends, carrying Beta's context reply. `room-submits.txt` is the host submit log, with Alpha's two deliveries and Beta's ordered pair. `room-inbox`, `room-empty` and `room-first-message` keep the numbered PNG/XML pairs for the screens before the second send.

`room-existing-open` captures the pre-existing message before sending. `room-existing.xml` and `room-existing.json` prove that the old room opens and shows the new send. `room-existing-submits.txt` independently records Gamma receiving `EXISTING_BEFORE_139` then `EXISTING_SEND_139`. The corresponding actions are `mpa tap text 'Existing room'` and `mpa fill 'Message Existing room' EXISTING_SEND_139` in `driver-transcript.txt`.
