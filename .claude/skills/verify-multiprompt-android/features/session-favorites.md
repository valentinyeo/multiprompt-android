# Session favorites on the signed APK

Use `verify.yml` with `feature=session-favorites.md`, `released_chat=true`, and a fresh GitHub emulator. For m3 set `channel=android-m3-latest`. Use `candidate=true` for the signed branch candidate. For the published APK set `candidate=false` and `expected_commit` to the exact published manifest commit. The old 0.1.299-m3 control uses `3ae3ba89ce907fb45fc30c514e447fb1a9cc8d40`; it must reach the menu and fail with `FAVORITE_DESKTOP_WORDS_MISSING`, showing **Unstar** instead of **Unfavorite**. A failure before that marker is not the control.

The signed APK is unchanged. Published mode checks its manifest commit, URL, size, hash, package, version and signing certificate. Candidate mode checks its built APK identity and signing certificate, and records the branch commit; it has no published manifest. A same-signer instrumentation APK seeds a disposable offline account. The existing emulator proxy blocks account traffic. Import and trust the one generated `Verify` SSH host through the app. The fixture runs two real tmux sessions, `verify-session` and `verify-keep`, under `verifyssh`. After host import, instrumentation checks that sole fixture profile and seeds a valid local account-layout cache with both sessions favorited. It refuses an existing layout store. The app restarts to load it.

## Journey

1. Open **Favorites**. Capture both sessions.
2. Long-press `verify-session`. Capture **Unfavorite**, the Desktop word.
3. Tap **Unfavorite**. Within ten seconds, require that session absent and `verify-keep` still visible in the selected Favorites split.
4. Read the local persisted edited layout with read-only same-signer instrumentation. Require `verify-session=false`, `verify-keep=true`, and both tabs retained. Only these booleans and names appear in the JSON evidence.
5. Restart the fixture app, open **All**, and long-press `verify-session`. Capture **Favorite**, the Desktop word. Tap it.
6. Open **Favorites**. Require both sessions visible. Independently snapshot both persisted booleans as true. Restart and require both visible again.

7. Long-press `verify-session`. Open **Archive session**. Check the exact title, warning, and buttons. Capture `confirmation-archive`. Tap **Cancel**. Require both sessions visible. Require both tmux sessions alive.
8. Long-press `verify-session` again. Open **End session**. Check the exact title, warning, and buttons. Capture `confirmation-end`. Press system Back. Require Favorites selected. Require both sessions visible. Require both tmux sessions alive.

9. Verify the resolved ETA log is absent. Open **Estimate accuracy**. Capture its empty explanation. Close it and return to Reader.
10. Enable the guarded generic-console history fixture. Confirm its actual alternate-screen live marker. Open **Session history**. Capture the first numbered page. Load older and capture another page.
11. Tap **Back to live**. Confirm the live marker returns. Reopen history and press system Back. Confirm the live marker returns again. Check `pane_in_mode=0` after each close. This guard does not prove a transition.
12. Return to **Favorites**. Require both sessions and persisted favorites. Compare neighbor pane and receipts byte-for-byte. Require both tmux sessions alive.

Run the signed candidate first. Run published proof after release. Inspect the uninterrupted `release-choices.mp4`, numbered PNG/XML pairs, `favorites-removed-state.json`, `favorites-restored-state.json`, `favorites-result.txt`, SSH authentication, APK identity, certificate, hash, manifest and source commit. Inspect `information-eta-path.txt` and `information-receipts.txt`. Inspect the named information tmux captures. Missing capture, invalid fixture, missing menu or unexpected state fails the journey.

This proves the signed app frontend and isolated offline cache only. It does not prove production sign-in, production sync, a physical phone, or a matching Desktop screenshot. The separate `account-journey.yml` builds a debug APK against an isolated real account service and independently reads its layout; it is not signed-release proof. A candidate is not published-release proof.

These cancellation checks cover Inbox only. Reader confirmation and production accounts remain unproven.

History proof uses the generic console only. Native Claude and Codex remain unproven. Physical phones and day-scale stability remain unproven. Clipboard contents are not verified.
