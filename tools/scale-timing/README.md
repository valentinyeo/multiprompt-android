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

## Single-key typing and editing

Dispatch `scale-timing.yml --ref <branch> -f typing=true` for the same 40-chat scale seed.
The device test opens the 54 MB chat through Inbox search and waits for its newest marker.
Five trials each insert 60 characters one at a time, move the cursor to offset 30, and insert
10 more characters. Every trial checks the exact text. Leaving and reopening checks draft retention.
The JVM composer regression separately requires zero reader/message-list compositions for short edits,
without the live reader updates and field-height changes counted by the scale run.

`typing/keys.json` retains every key's Main-thread CPU time, process allocation delta,
FrameMetrics durations and named composition counts. `typing-table.md` reports each trial separately.
Keys use Android input events and the activity uses its normal frame clock, not a Compose test clock.
The Main CPU window ends on the first post-input frame callback after the changed text composes.
Exact text is read back after every key outside that window. Idle windows expose background CPU.
Allocation counts include the in-process input driver, not just the text field. These debug measurements compare
the same harness and are not a signed-release or physical-phone latency claim. FrameMetrics remains
secondary because this emulator uses software rendering. No drawn frame or fewer than 350 measured
keys makes the measurement incomplete, not fast.

A separate sampled Main-thread method trace follows the timed trials, so profiling overhead cannot
inflate the reported keys. `typing-profile.py` renders its exclusive and inclusive CPU attribution.
Compare at least five trials per side and report their spread; an effect inside that spread is inconclusive.
The first run chooses between screen invalidation, draft persistence and skill lookup using CPU and
composition counts, rather than assuming that the largest-looking function is the bottleneck.

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

## Where the milliseconds go

A step's wall clock answers none of "is this the app" on its own, because this harness's own `adb` and `mpa`
calls are inside it. `tools/scale-timing/runtime.py` keeps every `adb` and `mpa` call with its own start and
end, so each row can be split into `driver ms` (the harness) and `wait ms` (the app and the emulator drawing).
A resolved `mpa tap` costs two `uiautomator dump` calls and one input event; the `driver-alone` step times a raw
dump, a single `mpa read` and a resolved tap on a control that changes nothing, so a tap's dump count and its
input-event share are checked arithmetically instead of assumed. The step taps only through `mpa`, which
resolves the control first, so it adds no raw coordinate tap to the journey ratchet.

App CPU is the app process's own time from `/proc/<pid>/stat`, sectioned by [sampler.sh](sampler.sh) reading
`/proc/<pid>/task/*/stat` per thread, so the table can name the thread that spent it. The `idle-inbox` step sits
on the Inbox for 20 s with no harness action, which is what the app costs while the owner only reads the list.

Cold launch is split by logcat marks: `Start proc`, the app's own `MpaStartup` debug markers at the end of
`Application.onCreate`, after the ViewModel is built and when the first session list is applied, then `Displayed`
and `Fully drawn`, the fixture relay's first listSessions request and reply, and the first moment the harness sees
Inbox rows. The markers are debug-only lines with no behaviour change. `at ms` counts from the start of the step
and `driver ms` is the harness calls inside each stage.

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
