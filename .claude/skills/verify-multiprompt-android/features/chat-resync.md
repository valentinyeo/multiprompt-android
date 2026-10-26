# Resync chat

## Sub-features

- A "Resync chat" entry in the three-dot chat menu.
- It reloads the whole chat from the session and replaces the list on screen.
- Every stale Queued label is cleared.
- The unsent draft stays in the message box.
- A short "Synced" note shows when it is done.
- Nothing is sent to the session again.

## How to get to it (user POV)

Open a chat, tap the three dots, tap Resync chat.

## Driving it with the CI journey

Dispatch `verify.yml` with `feature=chat-resync.md`.
The existing host-import and fingerprint comparison run first. The fixture session records every message it receives in its transcript with a "[phone] " prefix, so the app cannot match the sent text to the recorded row and keeps showing Queued after the reply. That is the stuck state from the ticket.

The driver sends two messages, waits for both replies, and requires exactly two Queued labels. It types a draft, opens the menu, taps Resync chat and requires the Synced toast to appear, no Queued label, both replies and both recorded rows on screen, and the draft unchanged. The receipts must hold exactly the two messages.

For the negative control, set `app_ref` to the commit before the feature. The same journey must fail with `IGSH1011_RESYNC_MISSING` after opening the real menu.

## Gotchas

No real Claude provider runs in CI. SSH, tmux, transcript discovery, UI gestures and message transport are real.
The Synced note is a toast and the UI dump cannot see toasts. The driver takes screenshots right after the tap and compares them with a settled one: the toast pill must add dark pixels that are gone later. The screenshots are kept as `toast-*.png`. The window list dump is not used because it knocks the CI emulator offline.
The stuck state is made by the fixture, not by the app. If the app starts clearing Queued on its own for this case, change the fixture to another mismatch that the app cannot resolve.
