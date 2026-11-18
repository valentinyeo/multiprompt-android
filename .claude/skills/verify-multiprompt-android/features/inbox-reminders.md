# Inbox buckets and reminders

Goal: cover 21 controls outside PR #418 for [SOFT-2670](https://app.hypertask.ai/detail/project-2060/2670).
Dispatch `verify.yml` with `feature=inbox-reminders.md` on a fresh GitHub-hosted emulator.
Use the shared disposable SSH fixture and its real `verify-session` row.

## Folder bar regression (SOFT-2706)

Before the reminder cases, block HTTP through an emulator-local closed proxy port and seed an offline debug account with twelve folders against the already-trusted disposable host. No production account or data is used. Select **Folder 12**, restart the app, and assert the selected pill without first revealing it. Repeat selection and restart for **Folder 06**, then return to **All**.

`folder-bar-reopened.json` and `folder-bar-middle-reopened.json` require the complete clickable pill and both text paddings inside the horizontal viewport, clear of the edge fades. Screenshot pixels at both rounded ends must match the solid selected fill. Every state keeps its numbered PNG/XML pair. The unchanged parent must fail a product assertion after restart, retaining the hidden selected folder and the old solid bar as the before screenshot.

In the bug-fix break-it replay, the journey finishes in the fixture reader with a non-empty draft. The shared stress driver checks rotation, background/return, another chat and slow networking on that same APK.

## Journey

Each assertion saves a numbered PNG/XML pair and a JSON result through `tools/inbox-controls/ui.py`.
Taps use `tap_text` or `tap_desc`; row long presses resolve the robot description after the shared driver reveals the exact search result. This avoids confusing the session title with the identical search-box text.

1. Open **Session list actions → Refresh**. Find `verify-session` with inbox search and require exactly one result before capturing the visible inbox row, without the menu. Compare independent SSH command logs before and after and require a new `list-sessions` command.
2. Tap **Switch session bucket**. Require **Inbox**, **Waiting**, **Archive** and **Unread only**. Press Android **Back** and require the menu gone. Select **Waiting**, **Archive** and **Inbox**, asserting each destination. Enable and disable **Unread only**, asserting the **Inbox · Unread** title and its removal.
3. Search all sessions for `verify-session` and require **1 result across all splits** before the row action. Keep that exact search during reminder cases and restore it after viewport changes. Long-press `verify-session` and tap **Remind Me…**. Require **Remind me**, the session name, presets and buttons. Tap **Cancel** and require the unchanged inbox row. Reopen and dismiss with Android **Back**; require the unchanged row again.
4. Reopen in a short viewport. Require **In 30 minutes**, with **Custom time** absent. Scroll to **Custom time** and require **In 30 minutes** absent. Cancel and restore the viewport.
5. Reopen the reminder separately for **Later today**, **In 30 minutes**, **In 1 hour**, **In 2 hours**, **In 5 hours**, **Tomorrow · 9:00 AM** and **In 2 days · 9:00 AM**. Tap each exact preset. Require the sheet gone and the session in **Waiting**. Read the saved reminder through read-only `run-as`; independently validate its deadline against Android's clock and timezone. Return the session to open and assert it in the inbox before the next case.
6. Enter `in 3 hours` in **Custom time**, capture its exact draft, and tap **Set reminder**. Require Waiting and the persisted three-hour deadline. Restore the session. Repeat with `in 4 hours`, submitting through keyboard **Done** with `KEYCODE_ENTER`. Require Waiting and the four-hour deadline, then restore the session.

## Evidence and limits

`inbox-reminders-proof.txt` is written only after every assertion passes.
`inbox-refresh-host.txt` contains post-Refresh command excerpts from the independent host log. Full before/after logs remain alongside it. The short viewport uses the already-focused search editor, verifies it is cleared before typing, and reads back the exact query before hiding the keyboard. `reminder-*-preferences.xml` and `reminder-*-time.json` record local persisted deadlines, not host receipts.
The registry requires this journey for reminder and bucket changes and for edits to this recipe.
Inspect the PNGs, XML, JSON, SSH logs and transcript at the exact head APK source SHA after cleanup.
This proves local reminder creation and cancellation, not notification delivery or waiting until the deadline.
No reminder action writes to the SSH host.
