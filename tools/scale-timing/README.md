# Frozen phone speed benchmark

One dispatch runs **five repetitions of five scenarios on each seed**. No app source is changed by the harness.

```sh
gh workflow run scale-timing.yml -R valentinyeo/multiprompt-android --ref mpa/mubo-555 -f mode=frozen -f app_ref=5ed79144693c2a93813a9b774fc0dd0f6f4a9f08
```

Later rounds dispatch this same frozen harness revision, changing only `app_ref` to the complete app commit.
The measured revision is built in its own worktree; the driver and fixtures stay at the dispatch revision.
Never change the workload, repeat count, durations, seeds, metric definitions or emulator configuration during
an optimization round. A changed harness fingerprint requires a new baseline, not a comparison with the old one.
`mubo-555-v3` identifies this schema. `harness` hashes the timing scripts, fixture generators, shared phone
driver, emulator/build actions and workflow.

## Runner entry point

The workflow installs both debug APKs, starts the existing SSH fixture, seeds the host, installs `stream.py` as
`/home/verifyssh/frozen-stream.py`, and selects targets with `targets.py`. Its one measurement entry point is:

```sh
python3 tools/scale-timing/frozen.py run --seed "$TIMING_SEED" --app-sha "$(cat "$EVIDENCE/app-source-sha.txt")" \
  --targets "$EVIDENCE/timing-targets.json" --out "$EVIDENCE/timing" --evidence "$EVIDENCE" \
  --fixture "$FIXTURE" --expected-version "$EXPECTED_VERSION"
```

Run this only on GitHub's hosted runner. Local execution of `run` is refused. This is the existing **debug**
fixture configuration, not a release-build phone latency claim. Both sides must use exactly this configuration.
The two seeds run on separate hosted runners; their five trials run in the same fixed scenario order, not
alternating seeds. Repeat the dispatch at the same app SHA to check runner-to-runner drift before trusting a close result.

| Scenario | Fixed work and resulting-state proof |
| --- | --- |
| `cold_start` | Force-stop, remove only the debug app's transcript disk cache, start MainActivity, require Inbox robot rows. Host profile and trust remain intact. |
| `chat_switch` | Start in the largest chat, go back to Inbox, open the second-largest chat, require its newest marker. |
| `idle_inbox` | Confirm Inbox outside the window, then leave it untouched for 20 seconds. |
| `typing_long_chat` | Open the largest chat outside the window; clear, type the same fixed draft through `mpa fill`, require exact readback. Never send. |
| `streaming_reply` | Clear the draft outside the window; append 40 assistant rows to the open largest chat at 2 Hz for 20 seconds. Require the host receipt, nonzero in-window traffic and the final marker on the phone. Truncate only those appended fixture rows after each repetition. |

Each repetition starts a fresh process and removes the transcript cache. Preparation, screenshots, stream
readback and cleanup are outside the measured window. Raw trial JSON, logcat and gfxinfo remain in the artifact.
`results.json` contains only stable ordered keys: schema, harness, app SHA, seed, repeats, then scenarios.
Every metric has `median`, `min`, `max`; there are no timestamps, PIDs or run IDs in that comparison file.

## Metrics and comparison

- **App CPU ms** reads `utime + stime` from `/proc/<pid>/stat`; **Main ms** reads the same fields from
  `/proc/<pid>/task/<pid>/stat`. Cold launch starts at zero. A disappeared/restarted process, unreadable
  counter, incomplete step or missing gfx histogram fails the run instead of becoming a fast zero.
- **Frames over 16 ms (secondary)** sums the `dumpsys gfxinfo` histogram bins strictly above 16 ms. It is
  not the refresh-rate-dependent janky counter. The emulator draws about 4 fps, so this cannot rank builds alone.
- **listSessions/min** counts relay command opens containing the actual `__MP_TMUX_BEGIN__` envelope,
  not every tmux channel or every response line. **bytes/min** counts all application-protocol reply bytes
  forwarded by the relay, not SSH encryption overhead. Both use the exact measured wall window.
- **Errors** totals crashes, ANRs, failed steps, non-allowlisted app-PID logcat E lines and nonzero relay exits. The
  relay records an app-closed output pipe separately; its subsequent cleanup exit is not a command failure.
  Unexpected signal exits still count. The components remain in each trial. A crash can also emit E lines,
  so this is an error-signal count, not unique incidents.
- **Platform warnings** separately counts the narrow allowlist of Android platform E lines logged under the app PID:
  `FrameTracker` `force finish cuj, time out: J<IME_INSETS_(SHOW|HIDE)_ANIMATION::...@<package>>`. Every other app-PID E
  line still counts in **Errors**.
- **SSH logins/min** counts the fixture sshd's own `Accepted publickey` lines for `verifyssh` that appeared in the
  measured window, divided by the window, so a login is measured instead of inferred from the relay or from app code.
  The fixture sshd logs at `LogLevel VERBOSE` but writes those lines without a timestamp, so
  `tools/scale-timing/authwatch.py` follows `$FIXTURE/sshd.log` and stamps every new line with the UTC time it
  appeared (within one 0.1 s poll). The workflow makes that log readable, and `frozen.py` keeps the stamped copy in a
  temporary directory that is not published. `tools/scale-timing/logins.py` counts the stamped lines inside the
  window and keeps only the user, the time and the key fingerprint of each counted line in the trial records of
  `samples.json`; `logins.test.py` and `authwatch.test.py` prove lines outside the window, lines for other users,
  several logins in one trial, a raw untimestamped sshd line and a line written in two pieces. sshd writes the accept
  before the relay records the channel open, so a login that straddles a window edge is counted in the earlier window.
  A run whose relay opened channels but captured no login at all fails with `BENCHMARK_SSH_LOGINS_UNMEASURED`
  rather than publishing a fast zero, and an unreadable auth log fails the trial with `BENCHMARK_AUTH_LOG_UNREADABLE`,
  while the workflow preflight fails in seconds when the log is missing or unreadable.
- **Driver time stays separate** in each trial's `driver` and `wall_ms`. CPU never includes the `mpa`, adb,
  UI-automation or fixture processes. Do not subtract driver wall time from app CPU: they overlap, with different units.
  Wall time includes UI dumps and is not published as app latency. Idle/stream windows avoid UI dumps entirely.

Download `timing-table`, which holds both seeds' raw evidence, per-seed `results.json` and `report.md`.
The same median/range table appears in the job summary. Compare matching-seed results with:

```sh
python3 tools/scale-timing/frozen.py compare baseline/results.json candidate/results.json --out comparison.json
```

Every metric is lower-is-better. Candidate max below baseline min is `better`; candidate min above baseline
max is `worse`; overlapping or touching ranges are `within noise`. Different harness hashes, schemas, seeds
or repeat counts are rejected. Keep error and work receipts beside any claimed improvement; reduced work or
increased errors is not a speedup. The range test is conservative, not a statistical significance claim.

Sensitivity is established by contrasting small and scale at the **same app SHA**. A metric separates only
when the scale minimum exceeds the small maximum; record the median gap and each full range. Stability
requires a second dispatch at that SHA and unchanged harness. Report inconclusive metrics honestly.
The larger fixture increases transcript/Inbox parsing and network work; it does not imply every metric must grow.
The fixed debug build, slow emulator drawing and UI driver limit what can be inferred about a real phone.

## Older diagnostic mode

Dispatch `-f mode=diagnostic` for the original single-sample stage/thread breakdown below. It is not the frozen benchmark.

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
