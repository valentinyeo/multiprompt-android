# Restore the signed robot release gate

Goal: let the required `robot-faces.md` release gate open the correct Inbox row without weakening its assertions or changing the app.

## Evidence and cause

Downloaded and inspected both `release-proof` artifacts, including PNGs, XML, driver transcripts, tmux captures, recording metadata and APK identities.

- [Failed release 44371972659, attempt 2](https://github.com/valentinyeo/multiprompt-android/actions/runs/44371972659/attempts/2), [artifact 14466992065](https://github.com/valentinyeo/multiprompt-android/actions/runs/44371972659/artifacts/14466992065), source `3e0b2df2cfcc75be8c90d5e6f3c53e9ca8b64911`.
- [Passing release 44351957790](https://github.com/valentinyeo/multiprompt-android/actions/runs/44351957790), [artifact 14452366855](https://github.com/valentinyeo/multiprompt-android/actions/runs/44351957790/artifacts/14452366855), source `962eecada57538692a8a35622379640bc083ea65`.

Both `tmux-final.txt` files contain `VERIFY_READY_528`. The failed recording is readable, with 798 video frames; recording is not the failed assertion.

The failed `03-robot-inbox.xml` exposes `Robot verify-session` inside a clickable row at `[0,0][1080,124]`. Its scroll container spans the whole screen, including behind the fixed Inbox header at `[0,0][1080,253]`. The planner accepts the already-clipped XML bounds as fully visible. Its overlap check considers clickable controls only, missing the blank, nonclickable header surface and status-bar area. It emits `tap 540 62`.

The driver transcript records that tap, then `Timed out waiting for VERIFY_READY_528` and `PRODUCT_ASSERTION_FAILED`. `04-exit-1.png` and XML still show Inbox. The screenshot number is the failure capture, not evidence that the chat-title assertion ran.

The passing artifact has the same row at `[0,264][1080,443]`; the planner emits `tap 540 353`. `04-reader-ready.png` and XML show the open chat and ready text, followed by the robot chat-title proof.

PR #434 changed no app source (`git diff 962eecad 3e0b2df2 -- app` is empty). Its driver and fixture changes are confined to `chat-cancel.md`; the common planner is unchanged. This is a pre-existing shared-driver clipping bug exposed by a different list scroll position, not an app regression or a cancel-fixture leak.

Attempt 1 does not establish a recorder shutdown hang. Its job API records the recording step starting at 19:16:41Z and cancellation at 19:25:27Z. Its last driver actions are still signed setup, not the robot assertion or recorder shutdown. The overall job began at 18:38:50Z. Do not infer the same mechanism from elapsed time alone.

## Repair and replay

The shared planner subtracts full-width foreground edge panels from scroll viewports, using existing drawing-order resolution. It scrolls the complete row below the header before tapping, and keeps swipes out of the fixed header and bottom controls. Disabled, hidden and background panels do not shrink the viewport.

The exact two release XML trees are retained in `tools/journey-actions/fixtures/`. The regression test fails on the parent with `AssertionError: 'tap 540 62' != 'swipe 540 527 540 1352'`. The repaired planner emits the backward swipe, while the passing-release control still emits `tap 540 353`. The test also executes the old planner from `3e0b2df2` against the failed XML and requires its bad tap.

No app code, cancel behavior, release workflow, recording implementation or gate assertion changed. No `release.yml` dispatch is permitted for this branch. Hosted signed proof uses `verify.yml`, `feature=robot-faces.md`, `released_robots=true`, `candidate=true`.

## Board reporting fault

Agent-token assignment and fault notes were refused with `helpers never write to the board; report to your runner`. Sanitized feedback with category `call_failed` was also refused. No personal-token or helper-role workaround was used. The runner must mirror this fault and the release evidence onto SOFT-2730 and the Multiprompt Android view.
