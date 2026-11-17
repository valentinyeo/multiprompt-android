# Stop while the chat works

While a chat is working and the message box is empty, **Stop** replaces **Send prompt**. Typing brings **Send prompt** back. Clearing that text brings **Stop** back. **Stop** interrupts the session.

Dispatch `verify.yml` with `feature=session-stop.md`. The driver uses the same debug host import, fingerprint compare, and **Trust and connect** path as the other session journeys. The fixture is the usual console, waiting at input. Working comes from the server file the reader already polls, `~/.local/state/multiprompt/tab-state.json`, with `verify-session` set to `active` and later `done`. No model, account, or API key is involved.

## Journey

1. Open `verify-session`. Require `VERIFY_READY_528` and **Send prompt**. Require no **Stop**. The host receipt file is missing or holds no received or interrupted line. Capture `composer-send-idle`.
2. Mark the session `active`. Require **Stop**, enabled, and no **Send prompt**. Capture `composer-stop`. The app before this change keeps **Send prompt** here, so the parent run stops while it waits for **Stop**.
3. Type `KEEP_DRAFT` and do not send it. Require **Send prompt**, enabled, and no **Stop**. The receipt file still has no received or interrupted line. Capture `composer-send-draft`.
4. Clear the box. Require the placeholder `Message verify-session`, then **Stop** again. The receipt file still has no received or interrupted line. Capture `composer-stop-again`.
5. Tap **Stop**. Require `VERIFY_INTERRUPTED_528` in the fixture receipt file and on screen. Capture `composer-interrupted`.
6. Mark the session `done`. Require **Send prompt** and no **Stop**. Capture `composer-send-after-stop`.

## Gotchas

`/home/verifyssh/receipts.txt` is created only after the console handles input or an interrupt. A missing file means nothing was sent. Probes that can fail stay inside `if` or a checker that treats a missing file as quiet, because the driver runs with `set -e`.

**Stop dictation** is a different control. This recipe never starts dictation. Sending the typed draft, an attached file, and a send already in flight are outside this recipe. Account OTP and a signed release build are outside this recipe.
