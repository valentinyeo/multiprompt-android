# Pin a chat to the top

Long-press a chat and choose **Pin to top**. That chat moves above the other chat in Inbox, stays there after the app is closed and opened, and returns to its old place when you choose **Unpin**. Nothing is sent to the host.

Dispatch `verify.yml` with `feature=inbox-pin.md`. The driver uses the same debug host import, fingerprint compare, and **Trust and connect** path as the other session journeys. The fixture starts `verify-session` and `verify-other`. The default Inbox draws the newer chat at the bottom, so the driver pins whichever chat is lower on screen.

## Journey

1. On Inbox, require both `verify-session` and `verify-other`. Record which name is above the other. Capture `inbox-before-pin`.
2. Long-press the lower chat. Require **Pin to top**. Tap it. Require that chat's row to sit fully above the other row. Capture `inbox-pinned`.
3. Force-stop and relaunch. Require the same chat still above the other. Capture `inbox-pinned-relaunch`.
4. Long-press it. Require **Unpin**. Tap it. Require the original top and bottom names. Capture `inbox-unpinned`.
5. Require an accepted public-key login. A host receipt may be missing; a missing file means nothing was sent. Neither receipt may contain `VERIFY_RECEIVED:`.

## Gotchas

The app before this change has no **Pin to top** row, so the parent run of this same script stops when it waits for that label. Head must pass the whole journey.

The pin is stored on this phone. This recipe does not turn off **Newest sessions at bottom**, pin more than one chat, or open Favorites, groups, search, or Archive. It does not send a message, so it does not show whether a later send would pull the pin down. Account OTP, a signed release build, and a physical phone are outside this recipe.
