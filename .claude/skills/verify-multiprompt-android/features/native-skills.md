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
5. Type a draft; open **Attach → Skill**. Require the same rows. Pick the plugin skill and send; exact native command retains the draft.
6. Keep another draft; open **Attach → Model → Use Claude Sonnet**. Check actual tmux receipt and observed session label.
7. Open **Attach → Effort → Set effort: high**. Check actual receipt and reopen **Model** and **Effort** to require observed `Current: Sonnet 5.5` and `Current: high`.
8. Open **Attach → Add image**. Save the inert fixture upload setting through the real UI and require Android's Photos picker. Cancel; verify the draft stayed intact.

No upload request is made. Live upload-service authentication and uploading a selected image are not exercised. The unchanged image upload pipeline retains its unit coverage.

## Evidence

Inspect all `native-*.png/xml` pairs, `native-result.json`, `native-receipts.txt`, `native-tmux-final.txt` and `native-transcript.jsonl` in the uploaded artifact.
The transcript must contain no bundled skill body or XML injection. Receipts must contain exactly two native skill commands, one model command and one effort command.
