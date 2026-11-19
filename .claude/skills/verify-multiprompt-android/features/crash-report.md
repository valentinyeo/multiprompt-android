# Crash report for every app exit

Dispatch `verify.yml` with `feature=crash-report.md`. The GitHub-only fixture is a local account authority at `http://10.0.2.2:8787` that also preserves every `POST /v1/crash-reports` body in `crash-reports.jsonl`.

## Journey

1. **Sign in** through the fixture browser, which leaves the real app token in Keystore-backed storage.
2. Plant four exits with the debug-only `DebugCrashActivity`, started as `am start -n <package>/dev.multiprompt.companion.DebugCrashActivity --es exit uncaught|native|kill|anr`. The activity acts on the main thread and ends the app process: `uncaught` throws `IllegalStateException("IGSH-2840 crash")` from the looper, `native` sends `SIGSEGV`, `kill` sends `SIGKILL`, `anr` blocks the main thread while the driver injects key events so input dispatch times out. The driver waits for `pidof` to drop the package between plants.
3. Relaunch the app after each exit.
4. Require the fixture to receive one crash report per exit with a summary naming that exit: `java.lang.IllegalStateException: IGSH-2840 crash`, `Native crash`, `Signaled exit` and `ANR`.
5. Require every received body to carry exactly the service fields `id`, `captured_at`, `app_version`, `android_version`, `thread`, `summary` and a non-empty `stack_trace`.

The driver keeps `crash-exits.txt`, `crash-reports-received.txt` and `crash-exit-summaries.json` as evidence. It plants every exit before it asserts, so a parent build records each kind it never reports as `MISSING`, and `PLANT_ALIVE` when a plant did not end the process.

## Not covered

Low-memory kills need real LMK pressure and are not planted here; `REASON_LOW_MEMORY` is covered by the JVM mapping test. Retracing a release crash with the published R8 mapping is manual. A fatal signal that the emulator records as `Signaled exit` instead of `Native crash` fails the journey rather than passing silently.
