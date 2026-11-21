# Session gestures

Dispatch `verify.yml` with `feature=session-gestures.md`, `released_chat=true`
and `expected_commit=<published commit>`. Add `candidate=true` before merging.

The signed APK opens two ephemeral SSH sessions through the real host form.
Swipe both ways without changing the session. Wrapped code stays where it is
when dragged sideways and opens no reply. Swipe a message to reply ten times,
cancelling each reply. Back must return to the inbox, retaining the second
session wherever it sits in the list.

Retain the uninterrupted `session-gestures.mp4`, before/after code screenshots,
reply screenshot, inbox screenshot, gesture-result.json and APK identity.
The account seed is offline; no production sign-in is exercised.
