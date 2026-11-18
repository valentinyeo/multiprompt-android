# Settings, host mapping and update navigation

Goal: cover 19 controls outside PR #418 for [SOFT-2670](https://app.hypertask.ai/detail/project-2060/2670).
Dispatch `verify.yml` with `feature=settings-controls.md` on a fresh GitHub-hosted emulator.
The shared driver trusts the disposable SSH fixture through the real host-key sheet.

## Journey

Each assertion saves a numbered PNG/XML pair and a JSON result through `tools/inbox-controls/ui.py`.
All taps resolve an enabled text or description through the shared reachability driver.

1. Open **Session list actions → Settings**. Assert the title, **Sign in** and **Host mapping**, with **Large** absent. Scroll to reveal **Theme**, **Default Reader font size** and **Large**; require **Sign in** to leave the viewport.
2. Choose **Sunlight**, **Dark** and **System**. Assert the selected choice after each tap. Choose **Small**, **Large** and **Normal** and assert each selection. Tap **Back** and require the inbox without settings. Restart and reopen settings; require **System** and **Normal** still selected.
3. Open **Dictation key**. Require **Deepgram dictation**, **Save key** and **Cancel**. Cancel without entering a key and require settings again. This claims only the settings entry, not the shared key-sheet controls owned by PR #418.
4. Open **Host mapping**. Choose slot 1, require **Automatic** and **Back**, then tap **Back** and require the host list. Press Android **Back** and require settings. Reopen mapping in a short viewport; require the first host row with **Done** absent. Scroll to **Done**, require the first host row absent, then tap **Done** and require settings without the sheet.
5. Open **Update** and require **App updates**. Tap the header **Refresh**, require updates to remain visible, and independently require a new SSH `list-sessions` command. Open its navigation menu and require **Sessions** and **✓ Update**. Dismiss with Android **Back** and require the menu gone. Reopen and choose **✓ Update**; require updates without its menu. Reopen and choose **Sessions**; require the inbox.
6. Reopen settings and tap its version label. Require **App updates**, without settings.

## Evidence and limits

`settings-controls-proof.txt` is written only after every assertion passes.
The registry makes this journey required for settings, host mapping and update-menu changes, and for edits to this recipe.
Inspect the PNGs, XML, JSON and driver transcript at the exact head APK source SHA after cleanup.
Settings and mapping change local navigation or preferences. Header Refresh reads the SSH host; `settings-refresh-host.txt` retains post-action command excerpts, with full before/after logs alongside it. Theme and font selection captures receive priority in the verifier evidence. No control writes to the host.
Account-only layout switches, workspace colours, real dictation credentials, downloads and signed updates are not covered.
