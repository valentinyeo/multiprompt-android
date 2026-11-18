# Replies after a desktop chat host restarts

Run `verify.yml` with `feature=host-restart.md`.
Outcome `reader.host-restart` covers SOFT-2681.

1. Open the desktop-hosted Claude chat through real SSH.
2. Send `PHONE_BEFORE_2681` and require its reply.
3. Kill only the disposable fixture's recorded host process.
4. Start its replacement with the same chat identity and published runtime.
   Drop the fixture's recorded history channel to force reader catch-up.
5. Send `DESKTOP_AFTER_2681` through the desktop host CLI.
6. Require `REPLY_DESKTOP_AFTER_2681` on the still-open phone.
7. Send `PHONE_AFTER_2681` and require its reply.

Retain each action and result as PNG/XML pairs. Independent receipts
must show a new provider process, the same chat identity, all three
messages, and each reply exactly once in saved history.
A missing post-restart reply fails with
`HOST_RESTART_REPLY_NOT_VISIBLE_2681` at a product assertion.

The host, SSH transport, saved history and Android app are real.
The provider protocol is deterministic. No live host is modified.
