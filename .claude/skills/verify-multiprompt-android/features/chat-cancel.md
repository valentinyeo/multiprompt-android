# Cancel a message Claude has not taken in yet

## Sub-features

- A message that still says Queued has a Cancel message action in its long-press menu.
- Cancelling removes only that message. Claude never receives its text.
- The other queued messages stay queued, in the same order, and Claude receives each exactly once.
- A message Claude has taken in has no Cancel message action.
- If Claude takes the message in first, the cancel is refused with a plain notice.

## How to get to it (user POV)

Send a message while the agent is busy. The bubble is dim and says Queued. Long-press it and choose Cancel message. The bubble disappears and Claude never sees it.

## Driving it with the CI journey

Dispatch `verify.yml` with `feature=chat-cancel.md`.
The existing host-import and fingerprint comparison run first. The fixture is a foreground process named claude on a raw terminal. It keeps a message queue, shows `Press up to edit queued messages` while the queue is not empty, pulls the whole queue into the prompt box on Up like Claude Code does, and records every message it really takes in.

The driver sends `CANCEL_KEEP_ONE`, `CANCEL_TARGET_TWO` and `CANCEL_KEEP_THREE` through the real composer and requires three Queued labels. It long-presses the middle message, requires the Cancel message action, and taps it. The middle message must disappear and two Queued labels must remain. The fixture then takes the queue in and replies. The driver requires the taken-in record to hold exactly `CANCEL_KEEP_ONE` then `CANCEL_KEEP_THREE`, no transcript row that delivers the cancelled text, zero Queued labels, and no Cancel message action on a message that was taken in.

For the negative control, set `app_ref` to the commit before the change. The same journey must fail with `IGSH1010_CANCEL_NOT_OFFERED`.

## Gotchas

No real Claude provider runs in CI. SSH, tmux, transcript discovery, UI gestures and message transport are real. The queue behaviour is a stand-in for Claude Code's, not the real program.
