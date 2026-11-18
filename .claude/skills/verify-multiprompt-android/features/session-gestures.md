# Session gestures

Dispatch `verify.yml` with `feature=session-gestures.md`, `released_chat=true`
and `expected_commit=<published commit>`. Add `candidate=true` before merging.

The same regression must reject the original reply handler on GitHub.
The signed APK uses the shared 42-session seed. Open `verify-session` by the
exact `Robot verify-session` description, scrolling until it is visible.
`verify-session` still carries the wide code line and `GESTURE_REPLY_TARGET`.
Swipe both ways without changing the session. Scroll wide code left, then
right without opening a reply. Swipe a message to reply ten times, cancelling
each reply. Back must return to the inbox. Reveal `Robot cl-long-chat` and
`Robot verify-session` by those exact descriptions.

Retain the uninterrupted `session-gestures.mp4`, before/after code screenshots,
reply screenshot, inbox screenshot, gesture-result.json and APK identity.
The account seed is offline; no production sign-in is exercised.
