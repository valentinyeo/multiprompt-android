# Workspace and group controls

Prove the inbox workspace sheet and chat group sheet controls for [MUBO-288](https://app.hypertask.ai/detail/project-13734/288): **New workspace** with its name field, **Create**, **Cancel** and back dismissal, the group long press that opens **Rename group**, its **Name** field, back dismissal, **Rename**, and **Ungroup**.

Dispatch `verify.yml` with `feature=workspace-group-controls.md` on a fresh GitHub-hosted debug emulator.

## Preconditions

The GitHub-only account fixture listens at `http://10.0.2.2:8787`. The journey signs in through the real browser handoff by choosing **Continue as fixture account**, which completes the app callback and PKCE exchange. No token or layout is injected into the app. This does not prove production email OTP; the local account fixture is the only account used.

The fixture serves an initial account layout with one workspace, **Studio**, holding two seeded chats: `verify-session` in group **Original** and `pin-00` in group **Other**. The journey maps SSH host slot 1 to the shared fixture host through **Settings → Host mapping**, so the chats belong to that workspace without any app-side setter.

## Journey

Every step keeps a numbered PNG/XML pair, and each screen claim is checked with `mpa proof`, which fails on a missing required or present forbidden text. The stored layout is read twice per change: the app's own `shared_prefs/account_layout.xml` through `run-as`, and the layout the account fixture actually accepted (`layout_put` receipts), both through `tools/account-layout/layout.py`.

1. Sign in from **Session list actions → Settings → Sign in**. Require **Switch / renew login**, then map slot 1 in **Host mapping** to **Verify · verifyssh@10.0.2.2** and require the row to read **Verify (verifyssh)**.
2. Return to the inbox, wait for the **Studio** split, select it and require the group headings **ORIGINAL** and **OTHER** in that order, with no rename sheet.
3. Open **Session list actions → New workspace**. Require the sheet with **Cancel** and **Create**. Type **Discarded** into **Name** and require the field to read it back. Tap **Cancel**: the sheet is gone and the stored layout still holds only **Studio**.
4. Open it again and press back. Require the sheet gone and the stored layout unchanged, so dismissal never creates a workspace.
5. Open it again, type **Review**, tap **Create**. Require the sheet gone, the **Review** split selectable, its empty split text, and both the app and the account fixture now storing **Studio** and **Review**.
6. Return to **Studio** and long-press the **ORIGINAL** heading. Require the **Rename group** sheet with **Ungroup**, **Cancel** and **Rename**, and the field showing **Original**. Press back: the sheet closes, **ORIGINAL** stays and the stored group is still **Original**.
7. Long-press **ORIGINAL** again, replace the name with **Renamed** and tap **Rename**. Require **RENAMED** on screen, **ORIGINAL** gone, and the stored group for `verify-session` renamed for both the app and the account fixture.
8. Long-press **RENAMED** and tap **Ungroup**. Require the sheet gone, **RENAMED** gone, **RECENTS** and **OTHER** present, and the stored layout holding no group for `verify-session` while `pin-00` keeps **Other**.

## Evidence

`group-headings.json`, `new-workspace-open.json`, `workspace-created.json`, `rename-group-open.json` and `group-renamed.json` are the screen results. `*-layout.json` files are the app's own stored pending layout, and `*-host.json` files are the matching layout the account fixture accepted. `workspace-group-proof.txt` holds `WORKSPACE_GROUP_CONTROLS_OK` only after every check passes.

Artifacts also keep `device-approval-receipts.txt`, `driver-transcript.txt`, the numbered captures and `logcat.txt`. Inspect the screenshots for the sheet contents and the group headings rather than trusting the job conclusion.

## Not covered

`Move to group`, `New group`, workspace colours, `Arrange splits`, workspace rename and moving chats between workspaces keep their existing gaps. This journey does not prove production email OTP, a signed release APK or a physical phone.
