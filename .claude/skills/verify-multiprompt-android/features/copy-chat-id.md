# Copy chat ID

The chat menu row **Copy chat ID** copies the host chat id. A chat with no host id copies its tmux name instead. The copied text is not on screen until it is pasted into the composer.

Dispatch `verify.yml` with `feature=copy-chat-id.md`. The driver uses the same debug host import, fingerprint compare, and **Trust and connect** path as the other session journeys. The fixture sets `@mp_host_sid` on `verify-session` to `copy-chat-id-2371` and leaves `verify-name` without that option.

## Journey

1. On Inbox, require both `verify-session` and `verify-name`.
2. Open `verify-session`. The composer is empty, and `copy-chat-id-2371` is not on screen. Open **Session actions**, require **Copy chat ID**, and tap it. Capture `hosted-id`.
3. Focus the composer and paste. Require the composer text to be `copy-chat-id-2371`. Capture `composer-hosted-id`. The host receipt file must not contain `VERIFY_RECEIVED:copy-chat-id-2371`. A missing receipt file means nothing was sent.
4. Go back to Inbox. Open `verify-name`. The composer is empty. Open **Session actions**, tap **Copy chat ID**, and paste. Require the composer text to be `verify-name`. Capture `composer-tmux-name`. That receipt file must not contain `VERIFY_RECEIVED:verify-name`.

## Gotchas

`verify-session` shows the name `verify-session` while the copied value is the host id, so a paste of the title is not success. `verify-name` has no host id, so the copied value is the tmux session name. The app before this change has no **Copy chat ID** row, so the parent run of this same script stops when it waits for that row.

The toast **Chat ID copied** is not part of this proof. Emulator dumps often miss toasts. Account OTP, a signed release build, and a physical phone are outside this recipe. A chat with neither a host id nor a tmux name is outside this recipe. The clipboard's human label is outside this recipe.
