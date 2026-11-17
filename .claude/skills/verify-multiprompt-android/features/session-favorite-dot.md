# Starred yellow dot

A starred chat that has been read shows a yellow dot on its row in every list except Favorites. The dot is the `Session favorite dot` label on that row. Unstarring the chat removes the dot. The Favorites list shows the starred chat with no dot.

Dispatch `verify.yml` with `feature=session-favorite-dot.md`. The driver uses the same debug host import, fingerprint compare, and **Trust and connect** path as the other session journeys. The fixture runs two tmux sessions, `verify-session` and `verify-keep`.

## Journey

1. On All, require both chats and no yellow dot. Capture `favorite-dot-before`.
2. Point the emulator HTTP proxy at a local server that refuses account traffic. Seed an offline debug login and a local layout: `verify-session` is starred in workspace Fixture, `verify-keep` is not. No production account is contacted.
3. Relaunch. On All, require the yellow dot only on `verify-session`. Capture `favorite-dot-all`.
4. Search `verify-session`. Require the same dot on that one row. Capture `favorite-dot-search`. Close search.
5. Open Fixture. Require the dot only on `verify-session`. Capture `favorite-dot-workspace`.
6. Open Favorites. Require `verify-session`, no `verify-keep`, and no yellow dot. Capture `favorite-dot-favorites`.
7. Long-press `verify-session` and tap **Unfavorite**. On All, require both chats and no yellow dot. Capture `favorite-dot-unstarred`. Favorites shows no starred chats.
8. On All, long-press `verify-session` and tap **Favorite**. Require the yellow dot again. Capture `favorite-dot-restarred`. Favorites shows the chat and still no yellow dot. Capture `favorite-dot-favorites-again`.
9. The host receipt files must not contain a sent line. A missing receipt file means nothing was sent.

## Gotchas

The app before this change has no `Session favorite dot` label, so the parent run of this same script stops when it waits for that label after the offline star. The seed uses the head instrumentation against whichever app is installed.

Unread still hides this dot and shows the blue unread count instead. This fixture console is not waiting for a reply, so **Mark as unread** cannot light that badge here. Frequent does not list these chats. The label proves the row rule chose the dot; the screenshot is not a separate colour measurement. Account OTP, a signed release build, production sync, and a physical phone are outside this recipe.
