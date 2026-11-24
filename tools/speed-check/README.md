# Speed check

A pull-request check that catches a slow update before it merges. It counts, deterministically, how much lazy-list work the chat list does. No emulator, no frame timing, no noise.

## What it counts

`app/src/test/.../ui/ChatListRemeasureTest.kt` renders the real `ChatHistoryList` with a 120-reply chat in a Robolectric Compose test. It registers a snapshot apply observer and counts every applied lazy-list measure result: `Snapshot.registerApplyObserver` sees the write `LazyListState.applyMeasureResult` makes to its layout info state, one per lazy-list remeasure.

Two readings, five snapshots each:

1. **idle** — five recompositions that bring no new reply. The budget is **0**. A chat list that re-anchors itself after every composition (a `SideEffect { listState.requestScrollToItem(0) }`, the MUBO-298 slowdown from PR #574) remeasures five times here and fails.
2. **live** — five replies arriving while the chat sits at the bottom. Head must stay within **25% plus two** remeasures of the base revision.

The probe writes `CHAT_LIST_REMEASURES idle=<n>` and `CHAT_LIST_REMEASURES live=<n>` to `app/build/chat-list-remeasures.txt` and to the test output, both before it asserts, so a failing run still produces its numbers.

## Fails when head is worse

`tools/speed-check/report.py` judges the two readings and exits:

| Reading | Fails when |
|---|---|
| idle | head remeasures more than 0 times from five recompositions without a new reply |
| live | head needs more than 25% plus two remeasures over the base reading for five replies |
| any | the head reading is missing, or the head probe test failed |

`SPEED_CHECK_SLOW` exits 3, `SPEED_CHECK_INCOMPLETE` exits 4 and is never green, `SPEED_CHECK_OK` exits 0. The report, both numbers and both test logs land in the `speed-check-<run id>` artifact, and the table lands in the job summary summarised in one line.

`tools/speed-check/report.test.py` pins the verdict, including the MUBO-298 replay below, in `tools/check.sh source`.

## When it runs

The probe is a normal unit test, so `tools/check.sh unit` runs it on every pull request through `test.yml`. The idle budget is the ratchet there.

`speed-check.yml` adds the base-against-head comparison, and `pstack-verifier.yml` schedules it only when the pull request changes a path listed in `speedCheck.paths` of [features/registry.json](../../.claude/skills/verify-multiprompt-android/features/registry.json): the inbox and chat list UI, the reader, their data path, the shared reader assets, the probe, or this check's own harness. The job checks out the base and head revisions as separate worktrees, copies this check's probe into both, and runs `:app:testDebugUnitTest --tests '*ChatListRemeasureTest*'` on each. A failed speed job fails the `pstack-verifier` check with the reason in its comment. Every other pull request skips it.

## Replay the MUBO-298 slowdown

PR #574 (MUBO-289) added a `SideEffect` in `ChatHistoryList.kt` that called `requestScrollToItem(0)` after every composition. Each call invalidates the lazy measure scope, so the chat list remeasured once per composition and the app crawled. PR #577 reverted it. The replay compares the revision before #574 with the revision that carries the `SideEffect`:

```bash
gh workflow run speed-check.yml -R valentinyeo/multiprompt-android --ref mubo-299-speed-check \
  -f base_sha=207b5d7823730a1047cd42d95a2563b0f6cf385d \
  -f head_sha=0337351a4a50f0b1eea64de2249c1faf26c18c5e
```

That run must fail with `SPEED_CHECK_SLOW`: the base writes `idle=0` and the `SideEffect` head writes `idle=5`. The measured revisions are separate worktrees, so the workflow, the probe and the verifier always come from the revision the workflow ran on, never from the revision being measured.

An emulator frame-time check of the same slowdown was tried first and abandoned: the shared CI emulator renders about 4 frames a second for every build, so `dumpsys gfxinfo` read 100% janky for both revisions (PR #582). Frame time cannot separate these builds there; the remeasure count can.
