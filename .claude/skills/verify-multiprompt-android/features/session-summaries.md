# Useful inbox summaries

## Idle discovery and lifecycle

The registered `inbox-poll-drive.sh` step leaves Inbox untouched for 35 s, then counts real SSH list commands during another 22 s (one to three opens). It sends a new reply on the fixture host and requires its summary on the phone within 10 s, with pane capture and `inbox-message-latency.txt` as independent evidence. It then checks zero list requests during 12 s each in Settings, the reader and the background, and positive discovery after returning or resuming. `inbox-poll-windows.json` and the before/after command logs retain every window.

This exercises `MainActivity` user-input/resume callbacks, `MainViewModel` discovery/backoff and the Compose Inbox visibility signal. JVM `InboxPollingTest` proves exact intervals, user/list resets and no state emissions for identical lists.

Android inbox rows show the waiting question, current work, or the first sentence of the last assistant reply. Model footers and shell prompts never become summaries.

Dispatch `verify.yml` with `feature=session-summaries.md`. For the signed published APK also set `released_chat=true` and `expected_commit` to the published source SHA. The isolated SSH fixture emits a reply with a model footer, then current work, then a structured question box with its options and hint, then a new reply once the question is answered. The real inbox must display each summary, show the question text rather than its options while the question waits, drop that question from the row after the answer, and discover the last reply within ten seconds, without manual refresh. Evidence includes four phone screenshots, UI XML, source/APK hashes, a screen recording and `summary-result.txt`.
