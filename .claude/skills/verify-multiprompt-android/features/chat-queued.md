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

## Large-image catchup

After the first answer, the driver sends FOLLOWUP through the composer. A 1 MiB image record separates its queued transcript row from its answer. The unchanged history cursor must keep the earlier rows and clear the delivered label, not jump to a new tail.

The fixture prints progress every second without running a provider. The monitor compares shell and chat for 120 seconds, requires both answers within three seconds, rejects delivered Queued labels, and saves shell-and-chat.mp4 plus sample timings. The shell pane and Android recording show the same live SSH/tmux fixture session. This is emulator proof, not a recording of Valentin's phone.
