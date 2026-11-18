# Reader rename and display controls

Goal: exercise eight previously uncovered controls from SOFT-2653.
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

Retain `READER_RENAME_DISPLAY_CONTROLS_OK` in `reader-controls-proof.txt`.
Keyboard Done, font reset, account rename and other reader controls are not claimed.
