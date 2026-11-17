# Restart on the newest version

The inbox menu **Restart all idle chats** asks the host to restart every idle hosted chat. Inside one chat, **Session actions → Restart on newest version** asks the host to restart that chat. The host records the request in its restart file. A chat that is answering restarts when the reply ends. This journey uses one local Claude-shaped session, `verify-session`, with host id `verify-restart-1`. A sleeper process stands in for the live host so the reader records the request and does not start a real agent.

Dispatch `verify.yml` with `feature=chat-restart.md`. The driver uses the same debug host import, fingerprint compare, and **Trust and connect** path as the other session journeys.

## Journey

1. On Inbox, require no restart file. **Session list actions → Restart all idle chats**. Require the restart file to appear with a time at or after the tap. The inbox stays on `verify-session` and shows no restart failure. Capture `restart-idle-menu` and `restart-idle-done`.
2. Open `verify-session`. **Session actions → Restart on newest version**. Require the same restart file's time to move later. The chat stays on `Message verify-session` and shows no restart failure. Capture `restart-menu` and `restart-done`.

## Gotchas

A missing restart file means nothing was requested. The file is created only after a restart is accepted. Probes that can miss use `if`, because the driver runs with `set -e`. The fixture never calls a live model or a real key. The console receipt file is not the restart record.

The app before this change has neither menu label, so the parent run of this same script stops when it waits for **Restart all idle chats**.

## Not proven

A busy chat that waits until its reply ends, more than one server, and the agent process actually swapping onto a newer binary. Account OTP, a signed release build, and a physical phone are outside this recipe.
