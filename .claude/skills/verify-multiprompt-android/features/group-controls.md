# Group menu controls

Raise Android feature-map coverage with 15 previously uncovered group controls for SOFT-2675. Run `verify.yml` with `feature=group-controls.md` on a fresh GitHub-hosted emulator.

## Setup

Use the normal explicit SSH host trust, then Settings → Sign in → **Continue as fixture account**. This completes the real state/PKCE callback against a disposable local account, not production email OTP. Map account host 1 to **Verify** through Host mapping. Return to Inbox and choose Workshop. The account fixture gives real tmux sessions `verify-session` and `pin-00` the groups **Original** and **Other**.

## Journey

Every checkpoint retains numbered PNG/XML and a named JSON assertion. Account changes must reach the independent fixture before the journey continues.

1. Long-press `verify-session` and choose **Move to group**. Require existing groups, **New group**, **No group** and **Cancel**. Cancel and reopen, then dismiss with Android Back. Require unchanged assignments.
2. Choose **New group**, enter **Discarded**, cancel and reopen. Dismiss with Android Back. Require Original and Other still visible and no saved Discarded assignment.
3. Create **Focus**. Require the new group heading, no Original heading and an account receipt assigning only `verify-session` to Focus.
4. Long-press Focus to rename it. Enter **Cancelled**, cancel, reopen and dismiss. Require the original heading and unchanged account assignment.
5. Rename Focus to **Renamed** with **Rename**. Rename again to **Keyboard** using the field's keyboard Done action. Require each new heading, disappearance of the old heading and the matching account receipt.
6. Move `verify-session` to existing **Other**, then choose **No group**. Check the resulting headings and independent assignments after each action; `pin-00` must remain in Other.
7. Long-press Other and tap **Ungroup**. Require no Other heading and both bots without groups in the account receipt. Relaunch and require both rows without any former group heading.

## Evidence

`tools/account-controls/proof.py` rejects missing visible text, missing screenshots and incorrect assignments. Named `*-host.json` files validate the account server's actual accepted layout. Authenticated HTTP PUTs append full layouts to `device-approval-receipts.txt`, retained after cleanup. Cancelled operations require unchanged assignments and no discarded names.

`group-controls-proof.txt` appears only after the complete journey passes. Inspect PNG/XML, JSON assertions, raw HTTP receipts and `driver-transcript.txt` at the exact `app-source-sha.txt`. The registry forces this journey for group-menu changes and edits to this recipe. New-group keyboard submission is not added to the control map in this batch.
