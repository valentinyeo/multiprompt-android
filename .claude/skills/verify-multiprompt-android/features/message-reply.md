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
7. Long-press `REPLY_DELIVERED_418` with `mpa hold`, tap **Reply**, and type `/review REPLY_BODY_419`. The two new host lines must be exactly `/review REPLY_BODY_419` and then `> REPLY_DELIVERED_418`: the command line arrives first, so the host runs the skill, and the quote follows it. A reply that starts with plain text keeps the quote first, and a `/` inside the line stays in the body.

`mpa proof` records each state as `reply-open.json`, `reply-cancelled.json`, `reply-sent.json` and `reply-command.json`, with the text and description it checked. Host receipts come from the SSH fixture's stdin log, not a phone echo; `reply-command-receipts.txt` holds the lines the host received after the command reply.

The label on the phone is **Replying**. The older **Reply to …** wording is not on this screen.

## Mentioning another chat

The fixture adds one live chat whose tmux session is `live-cl_mention-2953` and whose window name, the name the chat list row renders, is `Mention 2953`.

8. `mpa fill` types `@Mention` in the composer. The @ row must show `Mention 2953`, the name the chat list shows, and must not show the raw `live-cl_mention-2953`. `mention-session.txt` records `session_name|window_name` read from tmux, so the friendly name and the raw identity come from the host, not the phone.
9. Tapping the row inserts `@Mention 2953` in the composer.
10. Send. The host receipt must contain the exact line `@Mention 2953{live-cl_mention-2953}`: the visible name plus the tmux session the desktop resolves it to. The phone shows `@Mention 2953` and never the braces or the raw name.

`mention-list.json`, `mention-sent.json` and `mention-receipts.txt` retain that action, result and independent receipt. This proves the name and the routing; it does not prove the account-layout name. A signed-in account supplies those names and the debug fixture has no account layout. `MentionNamesTest` covers that case on the JVM, where the @ names are compared with the inbox rows the account named.

## Not covered

Swipe-right reply uses the same bar, but this driver long-presses. A second finger is not required.
