# Live chat shows new messages within seconds

Dispatch `verify.yml` with `feature=live-chat-freshness.md` on a fresh GitHub-hosted emulator.
Outcome `reader.live-tail` covers MUBO-168, whose fix shipped in #504 under IGSH-3022.

The shared real-size seed provides the live chat: `live-real-2781`, a synthetic Claude
transcript of about 3 MB and roughly 4,000 rows, 70% of them tool traffic, restarted three
times. The phone starts from an older cached position of a stale sibling transcript, so a
chat whose saved cursor still looks valid but points at old rows is what the reader opens.

## Journey

Every step keeps a numbered PNG/XML pair and a `live-chat-*.json` result. The phone's own
text is the assertion; a host frame or a reader reply never counts as screen proof.

1. Open `live-real-2781` from Inbox. Require the newest user row and the newest assistant
   reply, in that order, exactly once each and fully on screen. Forbid the stale sibling
   transcript's first row and the neighbouring chat. `live-chat-newest` keeps that screen.
2. Reopen the chat and require the same newest pair again, so the append below is measured
   on a screen that was already showing the live tail.
3. Append a user row on the fixture host with `LIVE_CHAT_APPEND_2781` through
   `tools/real-chat/fixture.py append`, timed from the write. Require it on the phone screen
   within 8,000 ms and record the measured milliseconds.
4. Append an assistant reply with `LIVE_CHAT_APPEND_REPLY_2781` the same way. Require it on
   the phone screen within the same limit and record its own measurement.
5. Require the previous newest pair and then both appended rows, in that order, each fully on
   screen once, with the stale sibling and the neighbouring chat still absent.
6. Read the host transcript back: the fixture `check` passes with the appended rows as the
   newest, and the transcript's last two lines carry both markers.

A row that never appears fails with `LIVE_CHAT_APPEND_MISSED`. A row that appears late fails
with `LIVE_CHAT_TOO_SLOW` and names the measured delay and the limit. Both are product
assertions, not setup failures.

## Evidence

`live-chat-newest.json` and `live-chat-appended.json` are the screen proof records for the two
screens. `live-chat-freshness.json` summarises both screens with `visible_ms` per appended role
and the limit, and repeats `LIVE_CHAT_NEWEST_WITHIN_SECONDS` as its verdict.
`live-chat-host.txt` is the host-side receipt: the append start time, the resulting row and
byte counts, and the measured visible delay per marker. `live-chat-host-tail.jsonl` keeps the
two rows as they sit in the transcript, and `live-chat-host-check.json` is the fixture's own
check of the seeded chat after the appends. `live-chat-freshness.txt` holds
`LIVE_CHAT_NEWEST_WITHIN_SECONDS` with both measurements.

The registry cites `live-chat-freshness.json` for the result and `live-chat-host.txt` for the
independent host side effect.

## Not covered

The appended rows are synthetic host writes, not a live provider turn: provider streaming,
approvals and assistant pacing stay with `session-reader.md`, `host-restart.md` and
`chat-identity.md`. Account chats, signed builds and desktop-side typed prompts are outside
this recipe.
