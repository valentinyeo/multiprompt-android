# Speed check

A pull-request phone check that catches a slow update before it merges. It builds the base revision (the pull request's merge base) and the head revision, installs both on the same emulator in one job, seeds the 40-session inbox and the long chat, and compares four user paths:

1. Cold open of the app, first frame (`am start-activity -W -S` `TotalTime`).
2. Rendering the inbox list after the debug build's sign-in screen.
3. A fixed six-swipe fling scroll of the inbox list.
4. Opening the long chat (`cl-long-chat`, 1,882 transcript rows): the worst of a four-second open window and an eight-second steady window, so a per-composition remeasure shows up while opening and while the chat just sits there.

Each round installs that side's APK with app data kept, force-stops the app, resets `dumpsys gfxinfo`, starts the activity and records the launch `TotalTime`, then the 90th percentile frame time and the janky frame percentage of each screen. Base and head alternate per round; one unrecorded warmup round per side absorbs install and JIT warmth. The verdict uses the median of three rounds.

The app is signed out on every cold start of a debug build, so each round taps **Use without account (debug)** exactly as a person does. The host is trusted once, during setup, through the same sheet and the real SSH key.

## Fails when head is clearly worse

A metric fails when the head median is worse than the base median by more than the percentage **and** by more than the absolute floor, so emulator noise inside the floor cannot fail a merge. Frames rendered on the fixed scroll is the one metric where fewer is worse: a stalling app renders fewer frames in the same six swipes.

| Journey | Worse by more than | And more than |
|---|---|---|
| Cold open of the app, first frame | 25% | 150 ms |
| Inbox open, 90th percentile frame | 25% | 25 ms |
| Fling scroll of the inbox, frames rendered | 25% fewer | 10 frames |
| Fling scroll of the inbox, 90th percentile frame | 25% | 20 ms |
| Open the long chat, 90th percentile frame | 25% | 20 ms |

Any failing metric exits 3 with `SPEED_CHECK_SLOW`. Missing rounds exit 4 with `SPEED_CHECK_INCOMPLETE`, which is never green. An equal result exits 0 with `SPEED_CHECK_OK`. When the base and head builds are byte identical the job reports `SPEED_CHECK_IDENTICAL` and times nothing, because no reading could differ. The same revision, the same APK file or missing APKs exit 2 with a setup message.

`dumpsys gfxinfo` on the shared CI emulator renders about 5 frames a second, so the 90th percentile sits on that floor and the janky percentage reads 100% on both sides. Those two readings are kept per round because a real stall lifts them further, but the frame count and the launch time are the readings with usable headroom: the noise between two builds of the same source measured 2-4%. Thresholds live in `report.py` and the numbers land in `info.txt`, `samples.csv`, `report.json`, `report.md` and the per-round `frames-*.txt` in the `speed-check-<run id>` artifact.

## When it runs

`pstack-verifier.yml` schedules it only when the pull request changes a path listed in `speedCheck.paths` of [features/registry.json](../../.claude/skills/verify-multiprompt-android/features/registry.json): the inbox and chat list UI, the reader, their data path, the shared reader assets, or this check's own harness. A failed speed job fails the `pstack-verifier` check with the reason in its comment. Every other pull request skips it.

## Replay the MUBO-298 slowdown

PR #574 (MUBO-289) added a `SideEffect` in `ChatHistoryList.kt` that called `requestScrollToItem(0)` after every composition, forcing a full lazy-list remeasure per snapshot. PR #577 reverted it. The replay compares the revision before #574 with the revision just before the revert:

```bash
gh workflow run speed-check.yml -R valentinyeo/multiprompt-android --ref mubo-299-speed-check \
  -f base_sha=207b5d7823730a1047cd42d95a2563b0f6cf385d \
  -f head_sha=0337351a4a50f0b1eea64de2249c1faf26c18c5e
```

That run must fail with `SPEED_CHECK_SLOW`. The measured revisions are separate worktrees, so the workflow, this script and the shared emulator action always come from the revision the workflow ran on, never from the revision being timed.

`tools/speed-check/report.test.py` replays the same comparison against a fake `adb` that reports the base as fast and the head as slow, so the verdict logic stays pinned in `tools/check.sh source` without a device.

Builds and measurements happen on GitHub only. Never build or boot the app on the shared server.
