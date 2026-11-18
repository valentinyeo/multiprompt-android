# Workspace and selection controls

Raise Android feature-map coverage with 25 previously uncovered workspace and bot-selection controls for SOFT-2675. Run `verify.yml` with `feature=workspace-controls.md` on a fresh GitHub-hosted emulator.

## Setup

The normal driver trusts the disposable SSH host explicitly. Settings → Sign in opens the local authenticated account page. Tap **Continue as fixture account** to complete the real state/PKCE callback. Map account host 1 to **Verify** through **Host mapping**, return to Inbox and select **Workshop**. The account fixture starts with seven workspaces and two real tmux sessions, `verify-session` and `pin-00`, in Workshop. This does not prove production OTP or production account sync.

## Journey

Every checkpoint retains numbered PNG/XML and a named JSON assertion.

1. Open **New workspace**, enter **Discarded**, cancel, reopen and dismiss with Android Back. Require the sheet to close without a saved workspace. Enter **Review** and tap **Create**. Require its split and the independently received account layout.
2. Open **Arrange splits** at a reduced display height. Require Review at the top and Workshop initially hidden. Scroll to reveal Workshop and its Archive action. Archive Workshop, require **Archived workspaces** and **Restore**, restore it and require the archived section to disappear. Dismiss and reopen.
3. Tap Studio's **↑** and **↓** buttons. Read the independently received workspace order after each action. Tap **Automatic order**, check the current order is preserved, then **Done** and require Inbox. Automatic order currently republishes the layout; this recipe does not claim it sorts the saved workspace list.
4. Long-press `verify-session` and choose **Move to workspace**. Cancel and dismiss the sheet independently, retaining its original assignment. Move it to Studio. Require absence from Workshop, presence only in Studio and the corresponding account receipt. Return it to Workshop.
5. Choose **Select bots**. Tap `pin-00` to select and deselect it, requiring counts 2 and 1. Cancel selection and require the selection bar to disappear.
6. Select both bots and open **Move to…**. Cancel and dismiss the destination sheet separately; require both selections and assignments to remain. Move both to Studio. Require an empty Workshop, both bots in Studio and both assignments in the account receipt. Relaunch and require both bots still in Studio.

## Evidence

`tools/account-controls/proof.py` checks visible text, selected split and screenshot bounds. Named `*-host.json` files assert layouts read directly from the account fixture's atomic receipt file, not Android preferences. Successful authenticated HTTP PUTs also append full layouts to `device-approval-receipts.txt`, retained after cleanup. Cancelled operations have unchanged-layout assertions. Workspace archive/restore is local, so its evidence is the actual archived section and restored rows.

`workspace-controls-proof.txt` appears only after the complete journey passes. Inspect PNG/XML pairs, JSON assertions, HTTP receipts and `driver-transcript.txt` at the exact `app-source-sha.txt`. The registry forces this journey for workspace/selection menu changes and edits to this recipe.
