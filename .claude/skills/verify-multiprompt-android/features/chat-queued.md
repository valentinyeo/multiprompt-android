# Sent messages stop saying Queued once the session replies

## Sub-features

- A message the session has not taken in stays dim with the Queued label.
- A message the session has taken in but not answered keeps the label.
- Once the session replies, every earlier message returns to normal style.
- Two messages sent in a row clear together.

## How to get to it (user POV)

Open a session chat and send a message while the agent is busy. The bubble is dim and says Queued. When the agent answers, the bubble turns normal.

## Driving it with the CI journey

Dispatch `verify.yml` with `feature=chat-queued.md`.
The existing host-import and fingerprint comparison run first. The fixture is a foreground process named claude that records every received message and writes a Claude Code style transcript.

The driver sends `WAITING_ONE` and `WAITING_TWO` through the real composer and requires two Queued labels. The fixture then writes the transcript lines Claude Code writes when it takes queued messages in, plus an unrelated queue removal. The driver requires both labels to remain. The fixture then writes an assistant reply. The driver requires zero Queued labels and both messages still visible.

For the negative control, set `app_ref` to the commit before the fix. The same journey must fail with `IGSH1008_QUEUED_STUCK`.

## Gotchas

No real Claude provider runs in CI. SSH, tmux, transcript discovery, UI gestures and message transport are real. Transcript lines are written by the fixture, not by a model.
