# Read and control a tmux session

The native reader streams output and lets a user send a prompt, send Enter/approve, or interrupt the remote process without opening the fallback terminal.

## Sub-features

- `reader-output`: live native output from the SSH/tmux stream.
- `reader-send`: compose and send a message with a server-side receipt.
- `reader-enter`: send an empty Enter from the actions menu.
- `reader-interrupt`: interrupt the real foreground tmux process.
- `reader-extras`: Chat View is a full transcript in one scroll, with model/effort/ETA/waiting state in the top-bar subtitle, no Conversation/status row, and horizontally scrollable tables/nested lists; needs a supported agent transcript on the host (not automated).
- `reader-reply`: right-swipe a message bubble or long-press → **Reply**; **Reply to …** above the composer, **Cancel reply**, send Markdown quote (not automated).
- `reader-history`: **Session actions → Session history** opens alternate-screen agent history (not automated).
- `reader-tools`: attachments, speech-gated Deepgram dictation, search and font controls need their own evidence (not automated).

## How to get to it (user POV)

Tap `verify-session` in Inbox. **Message this session** is the composer and **Send prompt** sends it. **Session actions → Enter / approve** and **Interrupt session** operate the remote foreground process. **Open live terminal** in that menu, or double-tapping the title/output, opens fallback terminal. The New session picker's existing-session mode and account workspaces can also open sessions when configured; these alternate entries are not covered here.

## Driving it with adb and UI Automator

Preconditions: fresh fixture; dispatch `feature=session-reader.md`. The driver first performs the complete real host import and trust journey, then opens the discovered session card.

- `tap_text verify-session`; `wait_for_text VERIFY_READY_528`; `shot reader-ready` capture a native reader of the real console.
- `fill 'Message this session' VERIFY_SENT_528`; `shot send-draft`; `tap_desc 'Send prompt'`. Require both the visible `VERIFY_RECEIVED:VERIFY_SENT_528` and the same line in the fixture's receipts; capture `send-result`.
- `tap_desc 'Session actions'`; capture `enter-action`; `tap_text 'Enter / approve'`. Require `VERIFY_ENTER_528` in receipts and native output; capture `enter-result`.
- Open **Session actions** again; capture `interrupt-action`; `tap_text 'Interrupt session'`. The actual process must receive SIGINT and record `VERIFY_INTERRUPTED_528`; capture `interrupt-result` after the same text appears in the app.
- `receipts.txt` and `tmux-final.txt` independently corroborate all three mutations. Cleanup retains them, SSH logs and all PNG/XML pairs.

## Gotchas

The console accepts text instead of executing it as shell commands. Receipts are written only by the remote foreground process, never by the driver. Merely seeing locally echoed input is not send proof. Enter and Interrupt are separate actions and need separate receipts. The driver does not call SshRepository or send tmux keys directly. Reader attachment must remain live after each action. Account OTP, alternate-screen history, agent-specific approvals, dictation and other extras need their own external preconditions and are not covered. Offline mode is debug-only and does not confer account permissions.
