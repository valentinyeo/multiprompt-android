# Live terminal fallback

The embedded terminal opens an independent SSH PTY attached to the selected tmux session when native reader controls are not enough.

## Sub-features

- `terminal-open`: explicit reader-menu terminal entry and real tmux client attachment.
- `terminal-shortcuts`: double-tap title/output or use a session card's terminal action (not automated).
- `terminal-input`: keyboard interaction, history and return to live view (not automated).

## How to get to it (user POV)

Open a session reader, then **Session actions → Open live terminal**. Double-tapping reader title or output is an alternate route. Session-card actions also offer the live terminal. In terminal, **Show keyboard**, **Open shell history** and **Back to the live view** control the view.

## Driving it with adb and UI Automator

Preconditions: fresh fixture; dispatch `feature=terminal.md`. Driver imports the real key, compares/trusts the host and opens `verify-session` in the reader first.

- Require native `VERIFY_READY_528` and capture `reader-ready`.
- `tap_desc 'Session actions'`; `tap_text 'Open live terminal'`; `wait_for_text 'SSH · tmux attached'`.
- Independently run the read-only `sudo -u verifyssh tmux list-clients -F '#{client_name} #{client_session}'`; require a client attached to `verify-session` and save `tmux-clients.txt`.
- Capture `terminal-attached` PNG/XML. Visually inspect the terminal pixels for fixture text; XML proves controls/status but does not represent every terminal cell.

## Gotchas

Status text alone does not prove a real attachment; require the runner-side client record and screenshot. The terminal uses a separate connection and tmux ignore-size, so it must not resize the desktop pane. This recipe does not yet measure resize behavior, type terminal input, open history or exercise double-tap alternatives. Do not report those paths as covered. The driver never substitutes a terminal emulator screenshot for native reader proof.
