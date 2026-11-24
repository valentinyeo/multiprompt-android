# Follow the live conversation after a desktop chat restarts

Run `verify.yml` with `feature=host-restart.md`.
Outcomes `reader.host-restart` (SOFT-2681) and `chat.restart-newest` (MUBO-2).

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
9. From **Session actions**, tap **Restart on newest version**.
   Require the host runtime to rotate via `op: "host_restart"` (new `started` timestamp and incremented `rotate` event in host log) while keeping `REPLY_PHONE_AFTER_2681` and the chat name on screen.
10. Seed a second hosted Claude chat `live-working-mubo2` with an active in-flight turn (`inflight` set) while `live-verify-session` is idle.
11. Return to **Inbox**, open **Session list actions**, and tap **Restart all idle chats**.
    Require `live-verify-session` to rotate again while `live-working-mubo2` keeps the exact same `started` timestamp, `agent_pid`, zero `rotate`/`restart_now`/`restart_waiting` events, and no `.restart.json` file.

Retain action and result PNG/XML pairs. Independent receipts must show a new
provider process, the same host chat identity, different native session IDs,
all three messages, each reply exactly once in the native transcripts, and
the phone-driven single-chat and Inbox batch restarts in `phone-restart-state.json`
and `phone-restart-host.json`.
A missing post-restart reply fails with `HOST_RESTART_REPLY_NOT_VISIBLE_2681`
and `PRODUCT_ASSERTION_FAILED` only after independently finding the saved reply.
Setup failures do not count.

The break-it replay holds the desktop reply open as stream events until
an independent store read confirms the phone follow-up was admitted. It retains
submission and completion times in `host-restart-stream-host.json`.

The host, SSH transport, transcripts, and Android app are real. The provider
protocol is deterministic. Metadata edits affect only the GitHub fixture.
No live host is modified.
