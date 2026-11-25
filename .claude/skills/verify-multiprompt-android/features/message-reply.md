# Reply to a message

Dispatch `verify.yml` with `feature=message-reply.md`. The shared fixture seeds 43 sessions. `verify-session` is a Claude transcript whose only assistant line is `REPLY_BUBBLE_418`. The pane prints `REPLY_READY_418` and never that bubble, so the long-press target is the transcript row. The fixture console behaves like a live Claude console: it turns terminal echo off, redraws an empty prompt box after every Enter, writes each marked prompt back into the same transcript as a user turn, and answers `REPLY_DELIVERED_418`.

## Journey

Every step drives the phone through `mpa`.

1. Open `Mention 2953` by its exact name. This is the chat the journey will mention; it is in the inbox without scrolling.
2. Capture its reader top bar, then choose its own bot look through **Session actions → Bot look**, **Face 3** and **Colour 4**, and capture the top bar again. Both captures are the same chat's own bot, at the same 28 dp size as a mention row.
3. Back to the inbox and open `verify-session` by its exact `Robot verify-session` description.
4. Long-press `REPLY_BUBBLE_418` with `mpa hold`, tap **Reply**, and require **Replying**, the bubble preview, and the **Cancel reply** description.
5. Tap **Cancel reply** and require the reply bar to disappear, in text and in descriptions.
6. Long-press the same bubble, tap **Reply**, type `REPLY_BODY_418` with `mpa fill`, and send it.
7. The host receipt file must contain the exact lines `> REPLY_BUBBLE_418` and `REPLY_BODY_418`.
8. Require the host answer `REPLY_DELIVERED_418`, then require the reply bubble is present and is not marked **Not sent**, never offers **Send again** or **Remove**, and carries no **Replying** bar. The phone shows those retry controls only when the host never accepted the prompt, so their absence is the sent proof.

`mpa proof` records each state as `reply-open.json`, `reply-cancelled.json` and `reply-sent.json`, with the text and description it checked. Host receipts come from the SSH fixture's stdin log, not a phone echo.

The label on the phone is **Replying**. The older **Reply to …** wording is not on this screen.

## Mentioning another chat

The fixture adds one live chat whose tmux session is `live-cl_mention-2953` and whose window name, the name the chat list row renders, is `Mention 2953`.

9. `mpa fill` types `@Mention` in the composer. The @ row must show `Mention 2953`, the name the chat list shows, and must not show the raw `live-cl_mention-2953`. `mention-session.txt` records `session_name|window_name` read from tmux, so the friendly name and the raw identity come from the host, not the phone.
10. The row must carry the `Robot live-cl_mention-2953` description, the identity the chat list uses for that chat, and its avatar must draw that chat's own bot: `tools/message-reply/bot-face.py` crops the avatar node bounds from the row's screenshot and from the two reader top bar captures, and requires the row to match the chosen look and the chosen look to differ from the chat's earlier bot. `mention-bot-face.json` records both crops, the pixel difference and the checks.
11. Tapping the row inserts `@Mention 2953` in the composer.
12. Send. The host receipt must contain the exact line `@Mention 2953{live-cl_mention-2953}`: the visible name plus the tmux session the desktop resolves it to. The phone shows `@Mention 2953` and never the braces or the raw name.

`mention-list.json`, `mention-sent.json` and `mention-receipts.txt` retain that action, result and independent receipt. This proves the name, the bot face and the routing; it does not prove the account-layout name. A signed-in account supplies those names and the debug fixture has no account layout. `MentionNamesTest` covers that case on the JVM, where the @ names are compared with the inbox rows the account named.

The bot face has no accessibility identity of its own: the UI tree names the avatar node, and the drawn face is compared as pixels, the way `RobotFacesTest` and `robot-faces.md` compare bots. The same bot rendered twice differs by a mean of 0.1 per channel, one pixel of anti-aliasing by about 3.2, and two different bots by about 59; the 6.0 match threshold and the 15.0 changed threshold sit between those bands. A row showing its own hash-default bot instead of the chosen look fails the match check, so the earlier IGSH-3036 defect cannot pass again.

## Mentioning the typing chat

Still inside `verify-session`, which is chat A, and with the fixture window name `verify-session` as its chat-list name.

13. `mpa fill` types `@verify-session`, so the mention query starts with `@` and the list opens for the typing chat's own name. The scoped read `--select text --value verify-session --inside Bot` requires one mention row carrying that name. The row is only offered after the fix; before it, the same read finds nothing because the typing chat was filtered out of the list.
14. `mpa tap text Bot` taps that row's label, which is inside the row's own button, so the pick runs through `ChatMention.pick` and lands in `mentionPicks`, the same path as the X pick above. The composer then reads `@verify-session`.
15. Send. The host receipt must contain the exact line `@verify-session{verify-session}`, so the phone marks the typing chat itself with its tmux session and the shared desktop send still decides delivery. No wire note is added on the phone.

The bare `@` list contents, including the typing chat, are proved on the JVM by `ChatMentionTest.theListIsEveryChatOnThisHostIncludingTheTypingChat`; the phone proves that row is offered and picked.

`self-mention-list.json` and `self-mention-sent.json` retain the offered row and the sent state; `mention-receipts.txt` is captured after this send, so it carries both the X line and the self line. `MentionDecisionTest` keeps the phone from adding a wire note or choosing recipients.

The fixture console answers every prompt it receives, so this journey proves both lines reach the host through the normal send path, not that chat A answers a line that also mentions X. Whether A replies depends on desktop MUBO-454 on the host.

## Not covered

Swipe-right reply uses the same bar, but this driver long-presses. A second finger is not required. A bot picture or an account-synced look is not chosen here; the face and colour picker covers the appearance the mention row received in IGSH-3036.
