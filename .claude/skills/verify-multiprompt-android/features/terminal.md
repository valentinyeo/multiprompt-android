# Live terminal fallback

The embedded terminal opens an independent SSH PTY attached to the selected tmux session when native reader controls are not enough.

## Sub-features

- `terminal-open`: explicit reader-menu terminal entry and real tmux client attachment.
- `terminal-shortcuts`: double-tap title/output or use a session card's terminal action (not automated).
- `terminal-zoom`: starts at readable system body size; pinch 8–28 sp saved per device, sideways pan for wide panes (not automated).
- `terminal-history`: swipe up or down into in-place tmux scrollback without desktop copy mode; keep keyboard visibility and reading position during incoming output; **Jump to live** or overscroll newest to return (automated).
- `terminal-input`: command entry, Ctrl+C, Up history recall and Tab completion on interactive Bash, plus keyboard-open and keyboard-closed scrollback navigation (automated). Alt, other arrows and Esc behavior remain manual.

## How to get to it (user POV)

Open a session reader, then **Session actions → Open live terminal**. Double-tapping reader title or output is an alternate route. Session-card actions also offer the live terminal. In terminal, **Show keyboard**, **Open shell history** and **Back to the live view** control the view.

## Driving it with adb and UI Automator

Preconditions: fresh fixture; dispatch `feature=terminal.md`. For the published APK, also set `released_terminal=true` and `expected_commit` to its manifest commit; the existing same-signer instrumentation seeds only an isolated offline account session. Driver imports the real key, compares/trusts the host and opens `verify-session` in the reader first.

- Require native `VERIFY_READY_528` and capture `reader-ready`.
- `tap_desc 'Session actions'`; scroll the sheet until **Open live terminal** is visible; `tap_text 'Open live terminal'`; `wait_for_text 'SSH · tmux attached'`.
- Require descriptions **Esc key**, **Tab key**, **Ctrl key**, **Alt key** on the initial key row. The **Up key** is exercised below after horizontal scrolling if needed; other arrow behavior remains manual.
- Independently run the read-only `sudo -u verifyssh tmux list-clients -F '#{client_name} #{client_session}'`; require a client attached to `verify-session` and save `tmux-clients.txt`.
- Capture `terminal-attached` PNG/XML. Visually inspect the terminal pixels for fixture text; XML proves controls/status but does not represent every terminal cell.
- Seed 500 numbered lines in the real tmux shell before attachment. Capture tmux history independently and require the first and last lines.
- With keyboard closed and open, swipe up into history, scroll back to `SHELL_HISTORY_0001`, append new shell output, and require that first line stays visible while the new output stays outside the historical snapshot.
- Require the keyboard still shown after entering history when open, then tap **Jump to live**. Inspect both `shell-scroll-closed.mp4` and `shell-scroll-open.mp4`, plus first-line, anchored and live PNG/XML pairs.
- After scrollback proof, replace only the fixture pane's Python echo process with interactive Bash without startup files. Set a stable `VERIFY_SHELL> ` prompt and disable saved history. No shell commands under test are injected through tmux.
- Tap the terminal area between **Show keyboard** and the key row using XML-derived bounds. Run `adb shell input text 'echo%sTYPED_\$\(\(40+2\)\)'` and `adb shell input keyevent 66`. Require one exact `TYPED_42` output line and a fresh prompt in independent runner-side `tmux capture-pane -p -t verify-session` output.
- Type `sleep%s300`, Enter, wait two seconds, and require a real `sleep 300` process. Tap **Ctrl key**, type `c`, and require `^C`, a fresh prompt and an empty runner-side `pgrep -f "sleep 300"` result within five seconds.
- Scroll the key row horizontally if needed, tap **Up key** twice and Enter. Require exactly two `TYPED_42` output lines. This also proves Ctrl was consumed rather than remaining active for subsequent typing.
- Create `/tmp/verify_tabfile_unique.txt` on the runner. Type `ls%s/tmp/verify_tabf`, tap **Tab key** and Enter. Require the exact `/tmp/verify_tabfile_unique.txt` output line, not just the echoed command.
- Each check fails with a step-specific message. Inspect numbered `shell-input-typed`, `shell-input-sleep`, `shell-input-interrupt`, `shell-input-history` and `shell-input-tab` PNG/XML pairs, independent `shell-input-*.txt` pane/process captures and `shell-input-result.txt`. `terminal-proof.py` polls each resulting state for at most five seconds; the workflow tests its rejection of command echoes, stale history and a surviving sleep before driving the emulator.

## Terminal input on a real shell

Use an interactive shell in a disposable tmux session, not the automated Python console fixture. On GitHub's emulator or the phone, open its live terminal and tap **Show keyboard**.

- Require one horizontally scrollable row above the keyboard with **Esc**, **Tab**, **Ctrl**, **Alt**, **Up**, **Down**, **Left**, **Right**. Check descriptions **Esc key**, **Tab key**, **Ctrl key**, **Alt key**, **Up key**, **Down key**, **Left key**, **Right key**. Scroll the row to reach keys offscreen. The terminal must retain typing focus when a key is tapped.
- Type `echo HELLO`, press Enter, and see `HELLO` as command output followed by the prompt. Save a screenshot and independently capture the tmux pane on the host.
- Type `sleep 100`, press Enter, tap **Ctrl key**, then type `c`. Require the prompt to return before the sleep finishes and Ctrl to appear off. Type another `echo HELLO` and press Enter to prove Ctrl did not remain active.
- Tap **Up key** and see the last command at the prompt. Tap **Down key** to return to an empty prompt. Run `sleep 100` again, interrupt with Ctrl then `c`, and tap **Up key** to recall `sleep 100`.
- Clear the recalled command with Ctrl then `c`. Run `touch MULTIBOT_TAB_PROOF`, type `cat MULTIBOT_TAB_`, then tap **Tab key**. Require the completed `MULTIBOT_TAB_PROOF` filename at the prompt. Press Enter and require a fresh prompt.
- Type `echo LEFT RIGHT`, tap **Alt key**, then type `b`. In a shell with readline's default Emacs bindings, require the cursor to move back one word and Alt to appear off. Tap Alt twice to prove it can be cancelled without typing.
- Tap **Left key** and **Right key** to check cursor movement. In vim, use **Esc key** to leave insert mode. Arrows use the standard `ESC [` sequences because termlib exposes no application-cursor-mode query.
- Hide and reopen the keyboard. Require the row to remain reachable without covering terminal output. Save action/result captures for each step and report any shell-specific key bindings or steps not exercised.

## Gotchas

Termlib 0.1.0 clears sticky modifiers after a processed input event, not after each character inside a multi-character IME commit. Single-letter Ctrl+C follows that boundary; predictive keyboards committing a whole word can modify the whole commit. This remains unverified on the phone.

Status text alone does not prove a real attachment; require the runner-side client record and screenshot. The terminal uses a separate connection and tmux ignore-size, so it must not resize the desktop pane. This recipe does not yet measure resize behavior, pinch/pan or exercise double-tap alternatives. Typed input uses adb key events, not predictive IME commits; phone keyboard behavior remains unverified. Do not report those paths as covered. The driver never substitutes a terminal emulator screenshot for native reader proof.
