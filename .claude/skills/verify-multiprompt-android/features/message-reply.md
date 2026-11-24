# Reply to a message

Dispatch `verify.yml` with `feature=message-reply.md`. The shared fixture seeds 43 sessions. `verify-session` is a Claude transcript whose only assistant line is `REPLY_BUBBLE_418`. The pane prints `REPLY_READY_418` and never that bubble, so the long-press target is the transcript row. The fixture console behaves like a live Claude console: it turns terminal echo off, redraws an empty prompt box after every Enter, writes each marked prompt back into the same transcript as a user turn, and answers `REPLY_DELIVERED_418`.

## Journey

Every step drives the phone through `mpa`.

1. Open `verify-session` by its exact `Robot verify-session` description.
2. Long-press `REPLY_BUBBLE_418` with `mpa hold`, tap **Reply**, and require **Replying**, the bubble preview, and the **Cancel reply** description.
3. Tap **Cancel reply** and require the reply bar to disappear, in text and in descriptions.
4. Long-press the same bubble, tap **Reply**, type `REPLY_BODY_418` with `mpa fill`, and send it.
5. The host receipt file must contain the exact lines `> REPLY_BUBBLE_418` and `REPLY_BODY_418`.
6. Require the host answer `REPLY_DELIVERED_418`, then require the reply bubble is present and is not marked **Not sent**, never offers **Send again** or **Remove**, and carries no **Replying** bar. The phone shows those retry controls only when the host never accepted the prompt, so their absence is the sent proof.

`mpa proof` records each state as `reply-open.json`, `reply-cancelled.json` and `reply-sent.json`, with the text and description it checked. Host receipts come from the SSH fixture's stdin log, not a phone echo.

The label on the phone is **Replying**. The older **Reply to …** wording is not on this screen.

## Mentioning another chat

The fixture adds one live chat whose tmux session is `live-cl_mention-2953` and whose window name, the name the chat list row renders, is `Mention 2953`.

7. `mpa fill` types `@Mention` in the composer. The @ row must show `Mention 2953`, the name the chat list shows, and must not show the raw `live-cl_mention-2953`. `mention-session.txt` records `session_name|window_name` read from tmux, so the friendly name and the raw identity come from the host, not the phone.
8. Tapping the row inserts `@Mention 2953` in the composer.
9. Send. The host receipt must contain the exact line `@Mention 2953{live-cl_mention-2953}`: the visible name plus the tmux session the desktop resolves it to. The phone shows `@Mention 2953` and never the braces or the raw name.

`mention-list.json`, `mention-sent.json` and `mention-receipts.txt` retain that action, result and independent receipt. This proves the name and the routing; it does not prove the account-layout name. A signed-in account supplies those names and the debug fixture has no account layout. `MentionNamesTest` covers that case on the JVM, where the @ names are compared with the inbox rows the account named.

## FYI and cc copies

The fixture gives `live-cl_mention-2953` the tmux option `@mp_host_sid a1b2c3d4-1008-4000-8000-000000002953` and an enabled `claude` host config, so the phone sees it as a live chat with a host identity.

10. `mpa fill` types `cc @Mention 2953 the build is red` and sends it. The sent bubble keeps `cc @Mention 2953 the build is red`, never shows the tmux session, and never carries the note: the phone does not annotate its own chat.
11. The shared message store must gain a row for provider `claude` and sid `a1b2c3d4-1008-4000-8000-000000002953` whose text is exactly `Valentin mentioned you in verify-session (FYI only; read along, do not act unless asked). Context:\n\ncc @Mention 2953 the build is red`. `mention-copy-evidence.py` reads `store.sqlite3` as root and writes `mention-copy.json`; `mention-copy-sent.json` retains the phone screen. This is the desktop's own read-along note (`src/chat_mention.zig:789`) handed to the mentioned chat the way the desktop hands it over (`src/chat_send.zig:696`), through the shared message store the phone already uses for its own sends.

The phone sends no wire note and never picks a recipient for the channel it is typing in. It reads the FYI/cc classification from the same shapes the desktop fixture pins in `tools/fixtures/MUBO-247/cases.json`; `MentionDecisionTest` replays those 26 cases on the JVM.

## Not covered

Swipe-right reply uses the same bar, but this driver long-presses. A second finger is not required.
