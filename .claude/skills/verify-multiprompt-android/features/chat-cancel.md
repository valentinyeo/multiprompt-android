# Cancel waiting messages

Run `verify.yml` with `feature=chat-cancel.md`.
Use `release_choices=true` for the published signed APK.
Use `candidate=true` with that switch for a signed branch candidate.

## Sub-features

- Three messages submitted through the real composer while dispatch is held.
- Visible bubble and canonical `queued` row for each message.
- Cancelling the middle message through its long-press menu.
- `cancelled` canonical state and disappearance of that message only.
- Delivery of the two survivors in order, with their replies and delivered actions.
- Phase JSON, PNG/XML pairs, the screen recording, receipt file and APK identity retained.

## How to get to it (user POV)

The real composer submits three messages while the fixture holds dispatch.

Require three canonical `queued` rows and one visible bubble per message.
Cancel the middle message through its long-press menu. Require its canonical
`cancelled` state and disappearance, two surviving queued rows, no receipt,
and an unchanged `FIXTURE_OWNED_DRAFT` terminal capture.

Release the fixture and require first, third receipts only, in order.
Both survivors must reach `started` with exact ID/attempt connector receipts
backed by transcript byte ranges. Require their visible replies and no
cancel action after receipt.

## Driving it with mpa

Every run first executes the shared real-size chat step in scripts/real-chat-screen.sh: it opens live-real-2781 and requires the newest assistant reply on the rendered screen after open and after scrolling.

The fixture owns the worker lock, just like `chat-queued.md`, and uses the
bundled canonical message store, dispatch and Claude connector. Only the
terminal handoff is simulated. It records no receipt before release.

Keep phase JSON, PNG/XML pairs, the screen recording, receipt file and APK
identity.

## Gotchas

The current shared-message UI deliberately
has no Queued, Cancelled or Delivered labels; do not assert legacy labels.

The release account is an isolated offline fixture, not production.
