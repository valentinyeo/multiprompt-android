# Speed check

A pull-request check that catches a slow update before it merges. It counts, deterministically, how much lazy-list and inbox-row work the app does. No emulator, no frame timing, no noise.

## What it counts

Two probes, four readings.

`app/src/test/.../ui/ChatListRemeasureTest.kt` renders the real `ChatHistoryList` with a 120-reply chat in a Robolectric Compose test. It registers a snapshot apply observer and counts every applied lazy-list measure result: `Snapshot.registerApplyObserver` sees the write `LazyListState.applyMeasureResult` makes to its layout info state, one per lazy-list remeasure. Two readings, five snapshots each:

1. **idle** — five recompositions that bring no new reply. The budget is **0**. A chat list that re-anchors itself after every composition (a `SideEffect { listState.requestScrollToItem(0) }`, the MUBO-298 slowdown from PR #574) remeasures five times here and fails.
2. **live** — five replies arriving while the chat sits at the bottom. Head must stay within **25% plus two** remeasures of the base revision.

`app/src/test/.../ui/ChatListSnapshotTest.kt` renders the real `SessionsScreen` with an 80-row inbox and feeds it 50 reader snapshots that change no row, plain and pinned-next-to-a-group. It counts the compositions of the real `SessionCard` row through a Compose `CompositionTracer`, and the lazy-list remeasures of the inbox list. Two readings, 50 snapshots each:

3. **inbox_rows** — inbox row compositions. The budget is **0**. An inbox whose pin or archive callback captures the whole `AppUiState` (the MUBO-140 slowdown from PR #549) recomposed 600 rows across 50 snapshots and fails.
4. **inbox_remeasures** — inbox lazy-list remeasures. The budget is **0**.

The chat probe writes `CHAT_LIST_REMEASURES idle=<n>` and `CHAT_LIST_REMEASURES live=<n>` to `app/build/chat-list-remeasures.txt` and to the test output. The inbox probe writes `CHAT_LIST_SNAPSHOTS row=SessionCard snapshots=50 recompositions=<n> remeasures=<n>` to `app/build/chat-list-snapshots.txt` and to the test output. Both write before they assert, so a failing run still produces its numbers.

## Fails when head is worse

`tools/speed-check/report.py` judges the four readings and exits:

| Reading | Fails when |
|---|---|
| idle | head remeasures more than 0 times from five recompositions without a new reply |
| live | head needs more than 25% plus two remeasures over the base reading for five replies |
| inbox_rows | head recomposes any inbox row from fifty reader snapshots that change no row |
| inbox_remeasures | head remeasures the inbox list from fifty reader snapshots that change no row |
| any | a head reading is missing, or the head probe tests failed |

Every breached reading is named in the verdict, so one run says everything that is slow. `SPEED_CHECK_SLOW` exits 3, `SPEED_CHECK_INCOMPLETE` exits 4 and is never green, `SPEED_CHECK_OK` exits 0. The report, all four numbers and both test logs land in the `speed-check-<run id>` artifact, and the table lands in the job summary summarised in one line.

`tools/speed-check/report.test.py` pins the verdict, including the MUBO-298 and MUBO-307 replays below, in `tools/check.sh source`.

## When it runs

Both probes are normal unit tests, so `tools/check.sh unit` runs them on every pull request through `test.yml`. Their zero budgets are the ratchet there.

`speed-check.yml` adds the base-against-head comparison, and `pstack-verifier.yml` schedules it only when the pull request changes a path listed in `speedCheck.paths` of [features/registry.json](../../.claude/skills/verify-multiprompt-android/features/registry.json): the inbox and chat list UI, the reader, their data path, the shared reader assets, either probe, or this check's own harness. The job checks out the base and head revisions as separate worktrees, copies both probes into each, and runs `:app:testDebugUnitTest --tests '*ChatListRemeasureTest*' --tests '*ChatListSnapshotTest*'` on each. A failed speed job fails the `pstack-verifier` check with the reason in its comment. Every other pull request skips it.

Revisions older than PR #583 keep the inbox list state inside `SessionsScreen`, so `tools/speed-check/inbox-list-state.py` gives `SessionsScreen` that state as a parameter in the worktree of an old revision, as PR #583 did for its own historical controls. On a revision that already takes it, the script changes nothing.

## Replay the MUBO-298 slowdown

PR #574 (MUBO-289) added a `SideEffect` in `ChatHistoryList.kt` that called `requestScrollToItem(0)` after every composition. Each call invalidates the lazy measure scope, so the chat list remeasured once per composition and the app crawled. PR #577 reverted it. The replay compares the revision before #574 with the revision that carries the `SideEffect`:

```bash
gh workflow run speed-check.yml -R valentinyeo/multiprompt-android --ref mubo-299-speed-check \
  -f base_sha=207b5d7823730a1047cd42d95a2563b0f6cf385d \
  -f head_sha=0337351a4a50f0b1eea64de2249c1faf26c18c5e
```

That run must fail with `SPEED_CHECK_SLOW`: the base writes `idle=0` and the `SideEffect` head writes `idle=5`.

## Replay the MUBO-307 slow inbox

PR #549 (MUBO-140) made the pin and archive callbacks in `ui/MultipromptApp.kt` capture the whole `AppUiState`, so every reader snapshot recomposed all 80 inbox rows. PR #583 (MUBO-307) fixed the callbacks and added the snapshot probe, so the replay puts the fix on the base side and the last revision that carried the slowdown on the head side. Both revisions build the probe: #549's own merge predates the pinned-callback state the probe reads, so its parent cannot build it.

```bash
gh workflow run speed-check.yml -R valentinyeo/multiprompt-android --ref mubo-448-slow-inbox \
  -f base_sha=60aff06fb0ff51f3456bb3d2eb5dc68b7b11c04e \
  -f head_sha=4165b1aa3920c252a45cb735400c50908d03a5c4
```

That run must fail with `SPEED_CHECK_SLOW` on the inbox metric alone: the base writes `inbox_rows=0` and `inbox_remeasures=0`, the head writes `600` and `50` for 50 snapshots, and both keep `idle=0`. The pull request that adds the inbox gate is measured the other way round, against `origin/main`, and must report `SPEED_CHECK_OK` with `0` on both inbox readings.

The measured revisions are separate worktrees, so the workflow, the probes and the verifier always come from the revision the workflow ran on, never from the revision being measured.

An emulator frame-time check of the same slowdown was tried first and abandoned: the shared CI emulator renders about 4 frames a second for every build, so `dumpsys gfxinfo` read 100% janky for both revisions (PR #582). Frame time cannot separate these builds there; the remeasure and recomposition counts can.
