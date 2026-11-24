# MUBO-453 bisect probe

Scratch harness for the MUBO-453 phone spike bisect. Nothing here merges.

Two readings per revision, both driven from `.bisect/rounds.json`:

- **probe** (emulator): builds the debug APK at each revision, installs it, opens the seeded `cl-long-chat` chat and appends one assistant reply whose text keeps growing for ten seconds. It records the app main thread's `utime + stime` from `/proc/<pid>/task/<pid>/stat` across that fixed window, three times per side, alternating sides so runner and emulator drift hits both. Each window also asserts the phone rendered the last streamed step.
- **counters** (JVM): copies `ChatListRemeasureTest` and `ChatListSnapshotTest` from this branch into a worktree at each revision and runs them. Those are the merged speed-check probes.

`speed-check.yml` and the counters job both read the same on every revision: a new reply re-keys the chat follow in every build. They cannot see a streaming reply whose text grows, which is what this probe streams.

```
.bisect/rounds.json      pairs to measure, repeats, window and reply counts
tools/bisect-mubo-453/build.sh      build both sides' debug APKs in one job
tools/bisect-mubo-453/measure.sh    emulator stage: install, stream, read the counters
tools/bisect-mubo-453/counters.sh   JVM stage: chat list and inbox counters
tools/bisect-mubo-453/stream.py     the growing-reply writer, run as the fixture user
tools/bisect-mubo-453/report.py     table of the readings
```

Dispatch is unavailable for a workflow that is not on the default branch, so this workflow runs on pull_request from a draft PR on `bisect/mubo-453`.
