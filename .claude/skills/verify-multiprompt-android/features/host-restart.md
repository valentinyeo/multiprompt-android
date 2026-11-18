# Follow the live conversation after a desktop chat restarts

Run `verify.yml` with `feature=host-restart.md`.
Outcome `reader.host-restart` covers SOFT-2681.

1. Open the desktop-hosted Claude chat through real SSH.
   The stable host ID differs from Claude's native session ID.
   An unrelated transcript also exists under the stable host ID.
2. Send `PHONE_BEFORE_2681` and require its reply.
3. Kill only the disposable fixture's recorded host process.
   Restart it with a different native session, keeping the host ID unchanged.
4. Send `DESKTOP_AFTER_2681` through the desktop host CLI.
5. Remove private session hints from the disposable fixture.
   Leave `config.native_session` absent to model an older desktop host.
   Drop the recorded history channel after removing those hints.
   Require `REPLY_DESKTOP_AFTER_2681` on the still-open phone.
   The reader must use the latest `init` event's `detail.session`.
6. Publish `config.native_session` with the current native session ID.
7. Send `PHONE_AFTER_2681` and require its reply.
8. Reject `FOREIGN_OLD_SESSION_2681` in both post-restart screen captures.

Retain action and result PNG/XML pairs. Independent receipts must show a new
provider process, the same host chat identity, different native session IDs,
all three messages, and each reply exactly once in the native transcripts.
A missing post-restart reply fails with `HOST_RESTART_REPLY_NOT_VISIBLE_2681`
and `PRODUCT_ASSERTION_FAILED` only after independently finding the saved reply.
Setup failures do not count.

The break-it replay holds the desktop reply open as stream events until
an independent store read confirms the phone follow-up was admitted. It retains
submission and completion times in `host-restart-stream-host.json`.

The host, SSH transport, transcripts, and Android app are real. The provider
protocol is deterministic. Metadata edits affect only the GitHub fixture.
No live host is modified.
