# Phone timing at the owner's chat scale

The owner's phone is slow with about 40 live chats whose transcripts reach 54 MB and 30k JSONL rows. The
shared emulator fixtures are tiny, so every run there looks clean. These tools seed the fixture host at the
owner's scale and time the four steps a person actually feels: cold launch to Inbox, open the largest chat to
its newest row, ten flings in that chat, and switching through five big chats.

Frame timing is not the signal. The GitHub emulator draws about 4 fps for every build ([PR #582](https://github.com/valentinyeo/multiprompt-android/pull/582)), so a frame check cannot tell a fast build from a slow one.
The primary readings are wall clock per step, the app process's own CPU time from `/proc/<pid>/stat`
`utime + stime`, and the reader's own traffic: bytes and response time for every history and sync call,
counted by the fixture host. `dumpsys gfxinfo` totals are kept as a secondary reading only.

## Two seeds

| Seed | Host contents | Where it comes from |
| --- | --- | --- |
| `small` | The default fixture: 40 `pin-`/`row-` sessions, `cl-long-chat` (18 MiB), `live-real-2781` and the three chat shapes | `scripts/fixture.sh` + `mpa seed` |
| `scale` | 40 synthetic Claude chats, sizes following the owner's distribution: 33 at 1.3 MB, six at 10.9-15 MB, one at 54 MB and 30k rows; 160 transcripts in total | [tools/scale-chat/fixture.py](../scale-chat/fixture.py) |

`tools/scale-chat/fixture.py` writes synthetic text only, with the shapes real transcripts have: long
assistant replies, fenced code blocks, `tool_use`/`tool_result` rows for Bash, Read, Edit and Grep, and many
user turns. Each chat has its own project folder, one live transcript and three older ones, a config, state
and event log, so the app resolves it exactly like a live Claude chat. Every chat ends with a marker row
(`SCALE_CHAT_LATEST_4704_NN`) so the timing job can prove the newest message reached the screen.

Roughly 171 MB is written under `/home/verifyssh`; generation takes a few seconds.

## Reading every reader call

`tools/scale-timing/relay.py` installs as the fixture user's SSH forced command. It runs the app's real
command unchanged, forwards every byte, and logs one JSON line per protocol line: request number, reply
bytes, and milliseconds from the request to the reply. That is how the table knows the bytes and response
time of each history call and each tmux listing without adding logging to the app.

## Dispatch

```sh
gh workflow run scale-timing.yml -R valentinyeo/multiprompt-android --ref <branch>
gh workflow run scale-timing.yml -R valentinyeo/multiprompt-android --ref main -f app_ref=9374e19f
```

`app_ref` builds the app from that commit in its own worktree, the historical-control pattern in
`tools/historical_control.py`: the old revision is built as a whole, never copied into the current app. The
fixture, the seed and the driver still come from the dispatching ref, because they are harness, not app.

One dispatch runs both seeds as parallel jobs, then a third job downloads both measurements and renders the
table into the run summary. `timing-<seed>-<ref>` holds the measurement, the screenshots and the relay log;
`timing-table` holds the rendered table and `timings.json`.

## What the numbers mean

- Wall clock per step includes the driver's own UI dumps (`uiautomator dump` costs one to three seconds on
  this emulator). It is comparable between runs of the same job and never a product latency claim.
- App CPU time is the honest signal. It is measured on the app process only, in clock ticks, and does not
  care how slowly the emulator draws.
- History bytes are reported three ways: the step total, the mean per call, and the median per call. The
  reader writes a reply in many small protocol lines, so the median line is tiny and the total is what the
  phone actually paid. A chat with a 54 MB transcript should send a tail page, not the whole file; a jump
  there is a regression.
- The switch table splits one switch into its four driver actions: `back` to the Inbox, waiting for the Inbox
  rows, the tap that resolves the row (scrolling the list when the row sits below the fold), and the wait for
  the newest message. A slow switch that is mostly `tap` is the driver scrolling a long Inbox, not the app
  rendering the chat.
- The `small` seed only has two chats above 1 MB (`cl-long-chat` and `live-real-2781`), so its five switch
  targets are mostly the tiny fixture chats. Compare the two `open-largest` rows and the CPU columns for a
  size claim, and read the transcript bytes next to each switch row before calling one seed slower.
- A step is a median of one run per seed. Treat a difference smaller than the run-to-run spread as noise, and
  rerun before believing it.
