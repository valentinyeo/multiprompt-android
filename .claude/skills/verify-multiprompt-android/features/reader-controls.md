# Reader rename and display controls

Goal: keep the eight SOFT-2653 controls covered and add 40 controls for SOFT-2669.
Run `verify.yml` with `feature=reader-controls.md` on a fresh GitHub-hosted emulator.
The SSH fixture reuses the native Claude transcript and receipt-writing console under
a Claude-named executable. No account or provider credentials are required.

1. Open verify-session → Session actions → Rename. Fill Name with
   `VERIFY_CANCELLED_2653`, assert the editor text and Rename session sheet, and capture
   `reader-controls-rename-draft.json` with its XML/screenshot pair.
2. Tap Cancel. Assert the original reader title and no rename sheet. Reopen Rename and
   assert the original `verify-session` draft, then press Android Back. Assert the reader
   returns and compare independent host receipts with the before file: neither exit sent
   a rename.
3. Reopen Rename, fill `VERIFY_RENAMED_2653`, capture the draft, and tap Rename.
   Assert the new reader title and no sheet. Independently require exactly one
   `VERIFY_RECEIVED:/rename VERIFY_RENAMED_2653` in `reader-controls-host.txt` and the
   exact `verify-session|VERIFY_RENAMED_2653` tmux window state in
   `reader-controls-window.txt`.
4. Open Session actions → Display and assert Show terminal details and Use default font
   size. If the system starts in light mode, turn Sunlight mode off first. Toggle details
   on, capture the returned reader, reopen Display and assert Hide
   terminal details. Toggle back and assert Show terminal details.
5. Tap Sunlight mode on, capture the reader, reopen Display and assert Sunlight mode off.
   Toggle off, reopen and assert Sunlight mode on. Every result retains text assertions,
   JSON and a numbered XML/screenshot pair. Display settings have no host write.

## Batch 3 reader menus

Continue in the renamed reader. `reader-menu-drive.sh` records every result as a
numbered PNG/XML pair and `reader-menu-<phase>.json`. Assertions require visible
text in the active window, exact editor values, disabled address rows and absence
of the previous sheet. `controls.json` lists all 40 action/result pairs.

6. Open Session actions → More. Open Estimate accuracy, tap Close, reopen and
   dismiss with Android Back. Both exits restore the reader without the sheet.
7. Open More → Dictation API key. Fill API key with `not-a-key` and tap Save and
   dictate. Require the exact 40-character format error. Cancel, reopen and require
   an empty editor and disabled save button. Dismiss with Android Back.
   This proves invalid-key rejection, not successful authentication or dictation.
8. Open More → Screencast2 upload key. Cancel, reopen, then dismiss with Android
   Back. Neither route starts an upload or saves credentials.
9. Open Pin a page…, fill `https://example.com/igsh-2669`, cancel and reopen.
   Require the address is empty. Dismiss, reopen, fill again and tap Pin page.
   Require the page card, restart the app and require that same card persists.
10. Tap Open on the card. Require Page inside the reader, then Android Back
    returns to the transcript. Remove the card and require its address, Open and
    Remove are gone. The existing reader toolbar obscures Page actions and Back
    to chat on both parent and head in run 44221494739. Those four controls stay
    uncovered. External page content and browser handoff are not claimed.
11. Open Attach → Snippets, require Save current text, then tap the Snippets back
    row. Require Add image and Add file. Tap Add file and require Android's real
    Recent document picker; Android Back restores the reader without sending.
12. Open Computer. Require Computer, View only and Computer actions. Open its
    menu, require the disabled `127.0.0.1:6080` row, then dismiss it. Open Viewer
    address, fill `127.0.0.1:6081`, cancel and reopen. Require the original address.
    Dismiss, reopen, save the new address and return to chat. Reopen Computer and
    require the new disabled address row.
13. Choose Open full screen from Computer actions. Require Exit full screen and
    no header. Exit and require the header returns. Choose Hide computer and
    require the reader. The separate bottom Open full screen button sits under
    Android's navigation bar in run 44225849084 and stays uncovered. These checks
    prove local viewer-address save/readback and menu state, not a working tunnel.
    Control, keyboard, sleep/wake and successful VNC rendering remain uncovered.
14. Toggle Voice Mode on, reopen Session actions and require its row checkmark.
    Toggle off, reopen and require no checkmark on that row.
15. Open End tmux session and require the confirmation title and both buttons.
    Cancel, reopen, then dismiss with Android Back. Both return to the reader.
    Compare host receipts before and after, and independently require the same
    live tmux session and window in `reader-menu-end-host.txt`.
16. Return to Inbox and open Session list actions. Require Refresh and Settings,
    dismiss with Android Back, and require Inbox without those menu rows.
17. Reopen the reader, then More → Release notes. Require App updates and no
    reader actions.

Retain `READER_RENAME_DISPLAY_CONTROLS_OK` in `reader-controls-proof.txt` and
`READER_MENU_40_CONTROLS_OK` in `reader-menu-proof.txt`. Inspect the JSON, XML,
PNGs, driver transcript and host receipts after cleanup at the exact head SHA.
End-session exits must leave host receipts and the real tmux session unchanged.

PRs editing these menus require `reader-controls.md` through the outcome registry
and `pstack-verifier.yml`'s call to `verify.yml`. Recipe edits also force this
journey even when the model omits it. Account sync, legacy chat-title menus,
Keyboard Done, font reset and live provider credentials are not claimed.
