# Replies after a desktop chat host restarts

Run `verify.yml` with `feature=host-restart.md`.
Outcome `reader.host-restart` covers SOFT-2681.

1. Open the desktop-hosted Claude chat through real SSH.
   Its host ID differs from Claude's native session ID.
   The shared store retains 40 accepted messages outside the current transcript.
2. Send `PHONE_BEFORE_2681` and require its reply.
3. Kill only the disposable fixture's recorded host process.
4. Use the published reader's archive fallback for the replacement native session.
   The host ID stays unchanged. Saved archive timestamps lack timezone offsets,
   matching the live host. Drop the recorded history channel to force catch-up.
5. Send `DESKTOP_AFTER_2681` through the desktop host CLI.
6. Require `REPLY_DESKTOP_AFTER_2681` on the still-open phone.
   Old accepted messages must not push the reply off-screen.
7. Send `PHONE_AFTER_2681` and require its reply.

Retain action and result PNG/XML pairs. Independent receipts must show a new
provider process, the same host chat identity, different native session IDs,
all three messages, and each reply exactly once in saved history.
A missing post-restart reply fails with `HOST_RESTART_REPLY_NOT_VISIBLE_2681`
and `PRODUCT_ASSERTION_FAILED`. Setup failures do not count.

The break-it replay also holds the desktop reply open as stream events until
an independent store read confirms the phone follow-up was admitted. It retains
submission and completion times in `host-restart-stream-host.json`.

The host, SSH transport, history, and Android app are real. The provider protocol
is deterministic. No live host is modified.
