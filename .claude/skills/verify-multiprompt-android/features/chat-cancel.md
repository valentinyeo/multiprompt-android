# Cancel waiting messages

Run `verify.yml` with `feature=chat-cancel.md`.
Use `release_choices=true` for the published signed APK.
Use `candidate=true` with that switch for a signed branch candidate.

The real composer sends three messages while the terminal is busy.
Cancel the middle message through its long-press menu.
Require Cancelled on that message and two remaining Queued labels.
Release the terminal and require first, third receipts only.
Require Delivered labels and no cancel action after receipt.
Keep PNG/XML pairs, the screen recording, receipt file and APK identity.
The release account is an isolated offline fixture, not a production login.
