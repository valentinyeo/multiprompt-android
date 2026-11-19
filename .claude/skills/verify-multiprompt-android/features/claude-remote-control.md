# Claude remote control

Proves `claude.remote-control` for [SOFT-2541](https://app.hypertask.ai/detail/project-2060/2541), closing the phone-journey gap from [PR #366](https://github.com/valentinyeo/multiprompt-android/pull/366).

Dispatch `verify.yml` with `feature=claude-remote-control.md`. Use a fresh GitHub-hosted emulator. The shared driver imports the disposable SSH key through Android's document picker, compares the host fingerprint, and connects through the normal UI.

Remote control has no separate Android screen or connect button. The bundled reader enables it automatically when starting the hosted Claude process. This journey opens an existing desktop Claude chat and sends its first phone turn, which starts that process through the real SSH reader. Setup only enables the host configuration and seeds the prior desktop transcript. It does not start the host or inject any remote-control request. The empty provider receipt before phone use is retained.

## Journey

Every step drives the phone through `mpa`.

1. Open **verify-session** from the inbox. Require `CLAUDE_DESKTOP_SESSION_2541`. Capture `remote-control-open` PNG/XML.
2. Type `PHONE_REMOTE_CONTROL_2541` with `mpa fill`, which clears the field and reads the typed value back. Require the exact editor value. Capture `remote-control-draft` PNG/XML.
3. Tap **Send prompt**. Require `REMOTE_CONTROL_CONNECTED_2541 desktop_counter=1`. Capture `remote-control-connected` PNG/XML. The disposable Claude stream process refuses to answer unless the app's bundled reader first sends `remote_control` with `enabled: true`. It independently records that frame, its request ID and the received phone message, and updates a file in the desktop working directory.
4. Send `DESKTOP_REMOTE_CONTROL_2541` through the real desktop host CLI, not adb or an app state setter. Require `DESKTOP_REPLY_2541 desktop_counter=2` on the phone. Capture `remote-control-desktop-reply` PNG/XML. Both turns must use the original Claude session ID and tmux chat.

`remote-control-open.json`, `remote-control-connected.json` and the other `mpa proof` records hold each screenshot's text assertions and the XML they came from. `remote-control-host.json` checks the raw provider receipts in order, both exact messages, original session identity, enabled connection, and independently read desktop counter. `remote-control-before.txt` proves there was no remote-control activity during fixture setup. Cleanup retains the raw receipts and desktop file even after failure.

The registry owns this journey, removes `claude.remote-control` from the missing-coverage baseline, and schedules it for matching product changes or edits to this recipe. Picker regressions cover omitted model choices and full three-journey selections. Citation regressions require action, resulting UI, independent host receipt and exact APK SHA.

This proves Android's bundled remote-control protocol against a disposable Claude-compatible process. Live Anthropic authentication, the claude.ai remote-control website and signed releases are not exercised.
