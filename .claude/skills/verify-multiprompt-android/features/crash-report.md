# Crash report for every app exit

Dispatch `verify.yml` with `feature=crash-report.md`. The GitHub-only fixture is a local account authority at `http://10.0.2.2:8787` that also preserves every `POST /v1/crash-reports` body in `crash-reports.jsonl`.

## Journey

1. **Sign in** through the fixture browser, which leaves the real app token in Keystore-backed storage.
2. Plant an uncaught exception, a native crash, a system kill and an ANR with the debug-only `DebugExitReceiver` actions and `am kill`. Each one ends the app process.
3. Relaunch the app after each exit.
4. Require the fixture to receive one crash report per exit with a summary naming that exit: the exception message, `Native crash`, `Signaled exit` and `ANR`.
5. Require every received body to carry exactly the service fields `id`, `captured_at`, `app_version`, `android_version`, `thread`, `summary` and a non-empty `stack_trace`.

The driver keeps `crash-exits.txt`, `crash-reports-received.txt` and `crash-exit-summaries.json` as evidence.

## Not covered

Low-memory kills need real LMK pressure and are not planted here; `REASON_LOW_MEMORY` is covered by the JVM mapping test. Retracing a release crash with the published R8 mapping is manual. The native exit can be recorded by Android as `Signaled exit` instead of `Native crash` on emulators that route the fatal signal differently; the journey then fails rather than passing silently.
