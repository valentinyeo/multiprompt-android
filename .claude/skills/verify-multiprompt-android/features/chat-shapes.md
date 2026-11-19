# Chats that compacted, fresh-started or resumed

Dispatch `verify.yml` with `feature=chat-shapes.md`. The phone stays on **Use without account (debug)**.

## Journey

The shared seed starts three supervised chats on the fixture host, each one shaped like a real Claude chat whose bundled reader has to pick the live transcript:

| Inbox row | Shape | Seeded identity |
|---|---|---|
| `live-compacted-2715` | compacted chat: the Claude session id changed after compaction | config `sid` `c02715c0-…` differs from the latest `init` event `c02715c1-…`; both transcripts exist |
| `live-resumed-2715` | resumed chat: a new chat id resumed an older Claude session | config `sid` `c02715e0-…` has no transcript of its own; the resumed session `c02715e1-…` does |
| `live-fresh-2715` | fresh-started chat: the chat id is the Claude session id | own transcript only, with a neighbouring chat in the same folder |

1. From Inbox, open `live-compacted-2715` and require `COMPACTED_AFTER_2715`.
2. Forbid the pre-compaction reply `COMPACTED_BEFORE_2715` and the neighbouring chat's `COMPACTED_NEIGHBOUR_2715`.
3. Open `live-resumed-2715` and require `RESUMED_READY_2715`; forbid `RESUMED_NEIGHBOUR_2715`.
4. Open `live-fresh-2715` and require `FRESH_READY_2715`; forbid `FRESH_NEIGHBOUR_2715`.

Every assertion is the phone's own text, captured as numbered PNG/XML plus `<shape>-chat.json` and `<shape>-chat-proof.txt`. The same journey on an APK whose bundled reader predates the live-session fix fails on step 1 at `PRODUCT_ASSERTION_FAILED`.

The seed itself is checked before any action: `seed-counts.json` lists each shape with its session, chat id, native session, transcript and latest marker, and `tools/chat-shapes/fixture.py check` re-reads the transcripts, host config and event log.

## Not covered

The seeded chats have no live agent process, so sending a prompt, approvals, streaming and the daily live probe are not this journey. Compaction of a real provider and the desktop app's own compaction are simulated by shape, not triggered.
