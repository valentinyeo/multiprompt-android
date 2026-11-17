# Expand and scroll a chat table

Prove `chat-table.expand` for [SOFT-2544](https://app.hypertask.ai/detail/project-2060/2544), adding the missing phone journey from [PR #379](https://github.com/valentinyeo/multiprompt-android/pull/379).

Dispatch `verify.yml` with `feature=chat-table.md` on a fresh GitHub-hosted emulator. The shared driver seeds only the disposable SSH host and key through instrumentation. It compares the host fingerprint and taps **Trust and connect**. Host-form entry and document-picker import are outside this recipe.

The independent SSH fixture writes a real Claude JSONL reply with a six-column, twenty-four-row Markdown table. The terminal prints only `TABLE_READY_2544`, never table cells. The app reads the reply through SSH; no chat state is injected into the app. No account, provider key or production data is involved.

## Journey

Every step retains a numbered PNG/XML pair and a `table-<state>.json` result. The assertions require the named cell fully inside its scroll view and screenshot, with visible text pixels. Off-screen cells do not count.

1. Open `verify-session`. Require `TABLE_READY_2544` and **Message verify-session**. Scroll the chat toward older content until **Plot** is fully visible. Require the inline table and **Expand table**, with `R01-C06 harvest` off screen. Capture `table-inline`.
2. Tap **Expand table**. Require the full-width, tall table sheet, **Garden harvest table**, **Close**, and `R01-C01 orchard`. Require `R01-C06 harvest` off screen. Capture `table-open`.
3. Swipe left inside the expanded table until `R01-C06 harvest` is fully visible. Require `R24-C06 harvest` off screen. Capture `table-sideways`.
4. Swipe up inside that table until the known bottom-right cell `R24-C06 harvest` is fully visible. Require `R01-C06 harvest` off screen. Capture `table-down`.
5. Tap **Close**. Require **Message verify-session**, **Session actions**, the inline **Plot** cell and **Expand table**. Require the expanded sheet and its close control absent. Capture `table-closed`. Require no text sent to the SSH host.

## Evidence

Inspect all five `*-table-*.png` screenshots alongside their XML, result JSON and `driver-transcript.txt` in `verify-chat-table.md-<run-id>` after cleanup. `table-proof.txt` contains `CHAT_TABLE_JOURNEY_OK` only after every step passes.

`tools/chat-table/fixtures/e4386ee1-open.xml` and its PNG retain the actual expanded phone screen from failed [run 44108093061](https://github.com/valentinyeo/multiprompt-android/actions/runs/44108093061/job/134130140740). The regression replays the driver's expand handshake against that screen. It accepts the icon-only **Close** description and rejects text saying Close without that control.

The registry routes table source changes to this journey. Hosted Node tests replay PR #379's actual table diff, reject its unrelated successful reader proof, and require the picker to schedule this journey even when the model omits it or fills all three optional slots. The control map links the expand, horizontal scroll, vertical scroll and close controls to this recipe.

## Not covered

Inline horizontal scrolling, Android Back dismissal, table links, terminal tables and account-only chats are outside this recipe.
