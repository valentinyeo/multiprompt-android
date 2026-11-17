# Native Claude skills, model and effort

Run `verify.yml` with `feature=native-skills.md`. App-source changes also run this journey on pull requests.
Use `app_ref=2cd0154674ced41a3733ba1b0bd90faef168eb41` and `expect_native_missing=true` for the unchanged original-app control. It must fail `NATIVE_SLASH_PICKER_MISSING`.

The existing ephemeral SSH fixture runs a disposable Claude-shaped console, not a provider call.
It creates actual user/project/plugin skill directories and session metadata. Model and effort changes update its real tmux output.

## User path and checks

1. Connect through the normal key import and host fingerprint confirmation. Open `verify-session`.
2. Type `/`; require project `/review-native`, user `/personal-note` and enabled plugin `/qa:verify-native` above the box. Hidden skills stay absent.
3. Keep typing `rev`; require only the matching row. Tap it and verify exact `/review-native ` editor contents.
4. Send `/review-native investigation`; compare the independently recorded SSH receipt.
5. Type a draft; open **Attach → Skill**. Require the same rows and `/late-native`, created since the first send. Pick the plugin skill and send; exact native command retains the draft.
6. Keep another draft; open **Attach → Model** and tap **Sonnet**; the first page shows one row each for Model and Effort, never the options. Check actual tmux receipt and observed session label.
7. Open **Attach → Effort** and tap **High**. Check actual receipt and reopen **Attach** to require `Sonnet ›` and `High ›` on the rows and the ✓ mark inside Effort.
8. Tap the Effort back row; require Attach's Add image row. Pick Opus, Haiku and Fable through Attach → Model, then Low and Medium through Attach → Effort. Require the observed header and selected Attach row after every change, preserving the draft.
9. On a compact phone viewport, tap the chat title. Require the current Fable model row to be disabled. Scroll until Set effort: max becomes fully visible; it must have been hidden before scrolling. Dismiss with Android Back and require the intact draft.
10. Open Session actions. Scroll to reveal Delete, without tapping it; require that it was hidden before scrolling. Dismiss and require the intact draft. Restore the normal viewport.
11. Open Session actions → Change ›. Require the current Fable model row to be disabled. Tap ‹ Back; require Search in chat, then dismiss.
12. Exercise both the title menu and Session actions → Change ›. In each, tap Open model picker (/model), Use Claude Opus/Sonnet/Fable, and Set effort: low/medium/high/xhigh/max. Every command needs a new independent SSH receipt, resulting observed model/effort text, screenshot, intact draft and runner-side tmux capture. The simulated console acknowledges bare /model with Received /model.; this proves command delivery, not a live provider picker.
13. Restore Sonnet/high through the reader menu. Open **Attach → Add image**. Save the inert fixture upload setting through the real UI and require Android's Photos picker. Cancel; verify the draft stayed intact.

No upload request is made. Live upload-service authentication and uploading a selected image are not exercised. The unchanged image upload pipeline retains its unit coverage.

## Evidence

Inspect all `native-*.png/xml` pairs, `native-result.json`, `native-receipts.txt`, `native-tmux-final.txt` and `native-transcript.jsonl` in the uploaded artifact.
Also inspect `native-menu-state.txt`, the `native-menu-*.png/xml` pairs, per-action `native-menu-*-host.txt` captures and `native-menu-receipts.txt`. Scroll assertions compare hidden rows before the gesture with fully visible rows afterward. Final receipts must match all 31 expected commands in order, including the two native skills, with no bundled skill body or XML injection.
