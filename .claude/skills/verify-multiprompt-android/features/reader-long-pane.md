# A long terminal pane in the reader

Prove `reader.long-pane` for [SOFT-2840](https://app.hypertask.ai/detail/project-2060/2840). Valentin's release app 0.1.609 closed with "Multi Bot closed because this app has a bug" on a Samsung SM-S948B running Android 17. The uploaded crash is an `IllegalArgumentException` thrown on the main thread while the phone measured a Compose frame.

The reader for a pane without a Claude history is one scrolling `Column` that composes every block of the captured output. A watch pane that logs a status line and a `key=value` line per cycle produces one collapsed card per line, so a pane of 2950 lines becomes 2950 measured cards in a single column.

Dispatch `verify.yml` with `feature=reader-long-pane.md` on a fresh GitHub-hosted emulator. The phone stays on **Use without account (debug)**.

## Seed

`fixture.sh` starts `live-long-pane-2840` on the fixture host before the app launches. `tools/long-pane/fixture.py` writes a 2952-line pane, 1475 cycles of

```
⏺ behind=24 age=24m tag=v0.1.24
behind=24 age=24m tag=v0.1.24
```

followed by a divider and the tail marker `LONG_PANE_TAIL_2840`. The pane keeps tmux history of 8000 lines, so `capture-pane -S -5000` returns all of it. `long-pane-seed.json` records the fixture's own line, byte and cycle counts; `long-pane-capture-seeded.txt` is the host's own capture, and the driver fails unless it holds 2950 lines or more, ends with the tail marker and is not in alternate-screen mode.

The alternating lines are what the reader splitter does with agent output: a status line starting with `⏺` becomes an activity block, an assignment line becomes a code block, and each one is a separate `ReaderCollapsibleBlock` card.

## Journey

Every step keeps a numbered PNG/XML pair and a `long-pane-*.json` or `logcat-*.txt` result. A crash is a product failure: the driver reads logcat after each step and fails on `FATAL EXCEPTION` or `Can't represent a size`.

1. Inbox lists `live-long-pane-2840`. Open it. Require the composer `Message live-long-pane-2840` and the newest output `LONG_PANE_TAIL_2840` on screen.
2. Force-stop and relaunch. Require Inbox again, reopen the pane and require `LONG_PANE_TAIL_2840` again.

`long-pane-proof.txt` contains `LONG_PANE_READER_JOURNEY_OK` only after every step passes.

## Not covered

The pane is synthetic. It reproduces the shape and the size of a long watch pane, not Valentin's own process or its exact lines. Android 17 and Samsung firmware cannot run on the hosted API 35 emulator, so a device-version trigger stays untested here.

## Evidence

Inspect `long-pane-reader` and `long-pane-reopened` PNG/XML pairs, `logcat-reader.txt`, `logcat-relaunch.txt`, `logcat-reopened.txt`, `long-pane-capture-seeded.txt`, `long-pane-seed.json` and `driver-transcript.txt` in `verify-reader-long-pane.md-<run-id>` after cleanup.
