# Chats that compacted, fresh-started or resumed

Dispatch `verify.yml` with `feature=chat-shapes.md`. The phone stays on **Use without account (debug)**.

## Journey

The shared seed starts four supervised chats on the fixture host, each one shaped like a real Claude chat whose bundled reader has to pick the live transcript:

| Inbox row | Shape | Seeded identity |
|---|---|---|
| `live-compacted-2715` | compacted chat: the Claude session id changed after compaction | config `sid` `c02715c0-…` differs from the latest `init` event `c02715c1-…`; both transcripts exist |
| `live-resumed-2715` | resumed chat: a new chat id resumed an older Claude session | config `sid` `c02715e0-…` has no transcript of its own; the resumed session `c02715e1-…` does |
| `live-fresh-2715` | fresh-started chat: the chat id is the Claude session id | own transcript only, with a neighbouring chat in the same folder |
| `live-mirror-2779` | long tool-heavy restarted chat, read from its history mirror | 2.8 MB mirror of 921 rows, 72% tool results with 4010-character details, three `init` events, two handover files and accepted/delivered dispatch rows; the newest assistant reply `MIRROR_NEWEST_2779` sits just above 140 tool rows that fill the last 512 KB |

1. From Inbox, open `live-resumed-2715` and require `RESUMED_READY_2715`; forbid `RESUMED_NEIGHBOUR_2715`.
2. Open `live-fresh-2715` and require `FRESH_READY_2715`; forbid `FRESH_NEIGHBOUR_2715`.
3. Open `live-mirror-2779` and require `MIRROR_NEWEST_2779`. A 512 KB tail window of that chat holds tool rows only, so the phone must still render the newest assistant reply instead of an empty history.
4. Open `live-compacted-2715` and require `COMPACTED_AFTER_2715`.
5. Forbid the pre-compaction reply `COMPACTED_BEFORE_2715` and the neighbouring chat's `COMPACTED_NEIGHBOUR_2715`.

The compacted chat is the last step, so its tap, screen and proof are the tail of `driver-transcript.txt` as well as rows in `<shape>-chat.json` and `<shape>-chat-proof.txt`. Every assertion is the phone's own text, captured as numbered PNG/XML.

## Pre-fix reader check

Before the journey, the same job runs `tools/chat-shapes/repro.py` over one seeded home twice: once with the bundled reader from before #425 (`af4d3699`) and once with the reader built into this run's APK. `pre-425-reader.txt` keeps both lines:

- `PRE_425_READER_2715_RED` lists `COMPACTED_BEFORE_2715` for the compacted chat and no transcript at all for the resumed chat.
- `CURRENT_READER_2715_GREEN` lists `COMPACTED_AFTER_2715` and `RESUMED_READY_2715`.

The step fails if either line stops holding, so the reproduction cannot rot. `tools/chat-shapes/chat-shapes.test.py` runs the same pair in the hosted source checks.

The seed itself is checked before any action: `seed-counts.json` lists each shape with its session, chat id, native session, transcript and latest marker, and `tools/chat-shapes/fixture.py check` re-reads the transcripts, host config and event log. For the mirror shape it also asserts the size, the tool share, the newest visible reply and that the last 512 KB holds no user or assistant row.

## Not covered

The phone cache from an older position is seeded by the shared real-size step (`tools/real-chat/phone-cache.py`) that runs in this journey before the shapes open.

The seeded chats have no live agent process, so sending a prompt, approvals, streaming and the daily live probe are not this journey. Compaction of a real provider and the desktop app's own compaction are simulated by shape, not triggered.
