# Capture Android frame spikes

These tools collect an instrumented baseline. They do not change or install the app, restart adb, or establish a speedup.

## Capture

1. Put the Android SDK's `platform-tools` on `PATH`. Use the existing adb tunnel. Never run `adb kill-server`.
2. Run `adb devices -l`. For the real-phone baseline, require serial `9ad7a2e9`, state `device`, an unlocked screen, and installed package `dev.multiprompt.companion`. Inspect `adb shell dumpsys window` and `adb shell dumpsys package dev.multiprompt.companion`. Do not unlock a protected phone or change its build to obtain samples.
3. Set `ANDROID_SERIAL=9ad7a2e9` if multiple devices are listed. Leave the existing adb server configuration alone.
4. Choose a new output directory outside the checkout. Capture with:

```sh
OUT="$HOME/perf-evidence/phone-1" SECS=30 tools/perf-trace/capture.sh
```

`OUT` must not exist, but its parent can be created. `SECS` defaults to 30 and accepts 1 through 600. `PKG` defaults to `dev.multiprompt.companion`. Trace files and device dumps can contain private screen text, process names, and paths. Keep them private and outside Git.

While the command records, use a second terminal to drive the chat list, open a chat, scroll, type an unsent composer draft, and switch chats. Use `adb shell input`. Record each command's host Unix timestamp, device `/proc/uptime`, completion timestamp, exit status, and observed screen result in `OUT/actions.jsonl`. Record chat size, whether a response is streaming, and whether the launch was warm or cold. Do not send a test message into a live chat. Restore only the draft text you changed.

Repeat with fresh directories `phone-2` and `phone-3`. These three captures are diagnostic samples, not a controlled comparison. If using an emulator instead, label every report **emulator proxy, not the real phone**.

The directory contains the package/version dump, build properties, initial window/power state, thermal and battery dumps before/after, device uptime, supported atrace categories, trace config, Perfetto diagnostics, gfxinfo reset and framestats, capture exit status, and `trace.perfetto-trace`. Capture does not launch or force-stop the app. The temporary trace on the device is removed only after a successful pull.

The trace requests scheduler events, binder transactions, gfx/view/input/dalvik/am/wm/binder_driver/network atrace categories, app atrace, FrameTimeline, process stats, and 100 Hz app userspace callstacks. `binder_driver` is Android's binder atrace category. Categories and data sources vary by OS. Check `atrace-categories.txt`, `perfetto.log`, trace errors, and analyzer warnings. A successful recording does not prove every requested source was available. Release builds often deny callstack profiling unless profileable; do not treat missing samples as no CPU work. Native unwinds do not guarantee Java/Kotlin or Compose function names.

## Analyze

```sh
python3 -m pip install --user perfetto
python3 tools/perf-trace/analyze.py "$HOME/perf-evidence/phone-1/trace.perfetto-trace" \
  --out "$HOME/perf-evidence/phone-1/analysis"
```

The analyzer attempts the user install if `perfetto` is missing. On externally managed Python installations, use an isolated environment instead:

```sh
python3 -m venv "$HOME/perf-tools"
"$HOME/perf-tools/bin/pip" install perfetto
"$HOME/perf-tools/bin/python" tools/perf-trace/analyze.py /path/to/trace.perfetto-trace --out /path/to/analysis
```

TraceProcessor downloads its matching executable on first use. Analysis writes `analysis.json` and `summary.md` outside the checkout. It includes complete janky frame records, duration percentiles using linear interpolation, ranked slice overlap, scheduler states for the whole trace and janky intervals, full matching slice/sample stacks, and exclusive CPU-sample leaf functions. SSH, parsing, and Compose matches are case-insensitive name searches, not causal attribution.

App main threads are selected by exact package name or command line and `tid == pid`, including separate process lifetimes. Frame counts are app layer-frame records with positive duration and a known jank type other than `None`. Unknown and unfinished frames are excluded and reported. Overlapping janky intervals are merged before timing slices and states. Nested slice totals still overlap each other. Sample counts are not converted into milliseconds. Scheduler states retain `io_wait` and `blocked_function`; sleeping alone does not prove binder or disk IO.

## Vet the baseline

Before reporting milliseconds, inspect all of these:

- `package.txt`: versionName/versionCode, `DEBUGGABLE`/`PROFILEABLE` flags, and the actual release/debug package. Never assume the build mode from its name alone.
- Launch and actions: warm/cold state, transcript row/byte count, streaming activity, successful navigation and draft readback inside the trace interval. Missing actions make the baseline inconclusive.
- Thermal and battery dumps: throttling, charging, and drift across runs. Record the device refresh rate and background load.
- Coverage: app process, actual frame count, CPU sample count, resolved functions, scheduler coverage, trace errors/loss, and capture exit status. Missing data is unavailable, not zero.
- Interpretation: these traces add profiling overhead. Use them to locate CPU, runnable, or blocked time; measure unprofiled end-to-end waits separately. No limiter or no proof the work happened means inconclusive.
- Comparisons: use the same production build settings, data, and warmup. Run at least five alternating trials per side, report median/range, and reject differences inside run variation. Do not extrapolate a micro-cost into an app-wide speedup.

## Test

```sh
timeout 90 python3 -B tools/perf-trace/analyze.test.py
```

The tests use synthetic table fixtures, not phone measurements. They cover overlapping frames, nested slices, main-thread/process filtering, percentiles, scheduler time, stack categories, and missing data. With `perfetto` installed, an additional synthetic systrace smoke test runs the real TraceProcessor. The source-check phase runs the fixture tests on GitHub.
