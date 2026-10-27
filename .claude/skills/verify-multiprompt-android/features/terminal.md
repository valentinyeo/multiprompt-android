# Live terminal fallback

The embedded terminal opens an independent SSH PTY attached to the selected tmux session when native reader controls are not enough.

## Sub-features

- `terminal-open`: explicit reader-menu terminal entry and real tmux client attachment.
- `terminal-shortcuts`: double-tap title/output or use a session card's terminal action (not automated).
- `terminal-zoom`: starts at readable system body size; pinch 8–28 sp saved per device, sideways pan for wide panes (not automated).
- `terminal-history`: swipe up or down into in-place tmux scrollback without desktop copy mode; keep keyboard visibility and reading position during incoming output; **Jump to live** or overscroll newest to return (automated).
- `terminal-input`: keyboard-open and keyboard-closed history navigation and return to live view (automated); typing is not exercised.

## How to get to it (user POV)

Open a session reader, then **Session actions → Open live terminal**. Double-tapping reader title or output is an alternate route. Session-card actions also offer the live terminal. In terminal, **Show keyboard**, **Open shell history** and **Back to the live view** control the view.

## Driving it with adb and UI Automator

Preconditions: fresh fixture; dispatch `feature=terminal.md`. Driver imports the real key, compares/trusts the host and opens `verify-session` in the reader first.

- Require native `VERIFY_READY_528` and capture `reader-ready`.
- `tap_desc 'Session actions'`; `tap_text 'Open live terminal'`; `wait_for_text 'SSH · tmux attached'`.
- Independently run the read-only `sudo -u verifyssh tmux list-clients -F '#{client_name} #{client_session}'`; require a client attached to `verify-session` and save `tmux-clients.txt`.
- Capture `terminal-attached` PNG/XML. Visually inspect the terminal pixels for fixture text; XML proves controls/status but does not represent every terminal cell.
- Seed 500 numbered lines in the real tmux shell before attachment. Capture tmux history independently and require the first and last lines.
- With keyboard closed and open, swipe up into history, scroll back to `SHELL_HISTORY_0001`, append new shell output, and require that first line stays visible while the new output stays outside the historical snapshot.
- Require the keyboard still shown after entering history when open, then tap **Jump to live**. Inspect both `shell-scroll-closed.mp4` and `shell-scroll-open.mp4`, plus first-line, anchored and live PNG/XML pairs.

## Gotchas

Status text alone does not prove a real attachment; require the runner-side client record and screenshot. The terminal uses a separate connection and tmux ignore-size, so it must not resize the desktop pane. This recipe does not yet measure resize behavior, type terminal input, pinch/pan or exercise double-tap alternatives. Do not report those paths as covered. The driver never substitutes a terminal emulator screenshot for native reader proof.
