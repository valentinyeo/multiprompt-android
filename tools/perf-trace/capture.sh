#!/usr/bin/env bash
set -euo pipefail

PKG=${PKG:-dev.multiprompt.companion}
SECS=${SECS:-30}
: "${OUT:?Set OUT to a new directory outside the repository}"
[[ "$PKG" =~ ^[a-zA-Z0-9_.]+$ ]] || { echo 'Invalid PKG' >&2; exit 2; }
[[ "$SECS" =~ ^[1-9][0-9]*$ ]] && (( SECS <= 600 )) || { echo 'SECS must be 1..600' >&2; exit 2; }
ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
OUT=$(python3 - "$ROOT" "$OUT" <<'PY'
from pathlib import Path
import sys
root, out = map(lambda value: Path(value).resolve(), sys.argv[1:])
if out == root or root in out.parents:
    sys.exit('OUT must be outside the repository')
print(out)
PY
)
command -v adb >/dev/null
adb get-state >/dev/null
umask 077
mkdir -p "$(dirname "$OUT")"
mkdir "$OUT"
adb devices -l > "$OUT/devices.txt"
adb shell dumpsys package "$PKG" > "$OUT/package.txt"
grep -q "Package \[$PKG\]" "$OUT/package.txt" || { echo "Package not installed: $PKG" >&2; exit 1; }
adb shell getprop > "$OUT/getprop.txt"
adb shell dumpsys window > "$OUT/window-before.txt"
adb shell dumpsys power > "$OUT/power-before.txt"
adb shell dumpsys thermalservice > "$OUT/thermal-before.txt"
adb shell dumpsys battery > "$OUT/battery-before.txt"
adb shell atrace --list_categories > "$OUT/atrace-categories.txt"
adb shell cat /proc/uptime > "$OUT/device-uptime-before.txt"
python3 - "$OUT/metadata.json" "$PKG" "$SECS" <<'PY'
import json, sys, time
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({
    'package': sys.argv[2], 'requested_seconds': int(sys.argv[3]),
    'host_start_unix_ns': time.time_ns(), 'build_mode': 'inspect package.txt flags; not assumed',
    'warm_or_cold': 'uncontrolled; record launch actions in actions.jsonl',
    'sampling_hz': 100, 'measurement': 'instrumented baseline, not a speedup comparison',
    'actions': 'Record host Unix timestamps, device uptime, commands and observed screen results in actions.jsonl'
}, indent=2) + '\n')
PY
cat > "$OUT/config.pbtxt" <<EOF
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
duration_ms: $((SECS * 1000))
flush_period_ms: 1000
data_sources {
  config {
    name: "linux.ftrace"
    target_buffer: 0
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "sched/sched_process_exit"
      ftrace_events: "sched/sched_process_free"
      ftrace_events: "task/task_newtask"
      ftrace_events: "task/task_rename"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "binder/binder_transaction"
      ftrace_events: "binder/binder_transaction_received"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "input"
      atrace_categories: "dalvik"
      atrace_categories: "am"
      atrace_categories: "wm"
      atrace_categories: "binder_driver"
      atrace_categories: "network"
      atrace_apps: "$PKG"
    }
  }
}
data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 0 } }
data_sources {
  config {
    name: "linux.process_stats"
    target_buffer: 0
    process_stats_config { scan_all_processes_on_start: true proc_stats_poll_ms: 1000 }
  }
}
data_sources {
  config {
    name: "linux.perf"
    target_buffer: 1
    perf_event_config {
      timebase { frequency: 100 timestamp_clock: PERF_CLOCK_BOOTTIME }
      callstack_sampling { scope { target_cmdline: "$PKG" } }
    }
  }
}
EOF
REMOTE="/data/misc/perfetto-traces/multiprompt-$(date +%s)-$$.perfetto-trace"
adb shell dumpsys gfxinfo "$PKG" reset > "$OUT/gfxinfo-reset.txt"
finish() {
  status=$?
  trap - EXIT
  adb shell dumpsys gfxinfo "$PKG" framestats > "$OUT/gfxinfo-framestats.txt" 2>&1 || true
  adb shell dumpsys thermalservice > "$OUT/thermal-after.txt" 2>&1 || true
  adb shell dumpsys battery > "$OUT/battery-after.txt" 2>&1 || true
  printf '%s\n' "$status" > "$OUT/capture-exit.txt"
  exit "$status"
}
trap finish EXIT
printf 'Recording %ss to %s. Drive the app now and log actions.\n' "$SECS" "$OUT"
date +%s.%N > "$OUT/perfetto-host-start.txt"
timeout "$((SECS + 90))" adb shell perfetto --txt -c - -o "$REMOTE" < "$OUT/config.pbtxt" > "$OUT/perfetto.log" 2>&1
date +%s.%N > "$OUT/perfetto-host-end.txt"
adb shell cat /proc/uptime > "$OUT/device-uptime-after.txt"
adb pull "$REMOTE" "$OUT/trace.perfetto-trace" > "$OUT/pull.log" 2>&1
test -s "$OUT/trace.perfetto-trace"
adb shell rm -f "$REMOTE"
printf 'Trace ready: %s/trace.perfetto-trace\n' "$OUT"
