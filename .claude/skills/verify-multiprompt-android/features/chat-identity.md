# Each phone message and its reply shows once

Dispatch `verify.yml` with `feature=chat-identity.md` on the PR branch.

## Sub-features

- One phone message above one reply, before and after a second send.
- The desktop Fresh start format replayed, including its divider and the phone ID as the native user ID.
- Transcript catch-up keeping a native ID for one reply and an archived `multiprompt_history` ID for the other.
- Force-stop, relaunch and reconnect keeping the same identities, counts and reply order.
- Historical controls over the pre-fix implementations, and the remaining legacy text-match paths.

## How to get to it (user POV)

The hosted emulator imports and trusts the ephemeral SSH host through the real Android forms, then opens `verify-session`.

1. Send `PHONE_BEFORE_2532` from the phone. The isolated SSH fixture reads the admitted phone ID and records an acceptance receipt, a native transcript user row, and host reply events. The transcript reply deliberately lags behind the live reply. Require one phone message above one `REPLY_BEFORE_2532`.
2. Send `PHONE_AFTER_2532`. The fixture replays the desktop Fresh start format with a divider, the phone ID as the native user ID, a timestamp without a timezone, and host reply events without native UUID aliases. Require the old reply above the divider, then one new phone message above one new reply.
3. Let the transcript catch up with both replies. The first keeps its native ID. The second uses the archived `multiprompt_history` format and a separate `history-` ID, as after switching AI. Reopen the chat and require the same order and counts despite competing card, transcript and host-stream representations.
4. Force-stop and relaunch Android. Choose **Use without account (debug)** again, then reopen the saved host's chat. Require the same identities, counts and reply order after reconnect.

## Driving it with mpa

Every run first executes the shared real-size chat step in scripts/real-chat-screen.sh: it opens live-real-2781 and requires the newest assistant reply on the rendered screen after open and after scrolling.

The fixture uses the real protocol store and transcript reader over SSH. It does not call app setters. It synthesizes provider output and the desktop Fresh start handover.

`projection-receipts-*.json` retains independent SSH-store acceptance IDs, host events and real JSONL transcript rows. `projection-state-*.json` records the visible order and counts derived from XML. The driver retains Send actions, draft and result PNG/XML pairs, and cleanup retains `receipts.txt`.

The registry supplies exact action, result and receipt citation hints to the independent verifier: the second send from `driver-transcript.txt`, the reconnect order from `projection-state-reconnect.json`, and the once-only acceptance counts in `projection-proof.txt`.

Equal bodies with different IDs, CRLF, ten delayed replies, native-fact overlap and cross-session isolation are covered by `MessageProjectionTest` on GitHub.

`tools/message-projection/check.test.py` rejects the actual source before #77 (`ce2d32d9`) and #312 (`e31c9c4d`). GitHub's historical JVM replay runs the collision, CRLF, retained reply, host-ID and Fresh start fixtures against the five pre-fix implementations. Compile failures are not accepted as red proof; each replay must produce its named assertion failure.

`tools/message-projection/baseline.json` names each remaining old text-match path separately. Shared sends without receipt aliases use timestamp plus normalized text. Legacy delivered sends use a delivery receipt plus source location and normalized text. Old host replies without phone ownership use completed normalized text. ID-less native local intentions remain explicitly unconfirmed and separate because text alone cannot establish delivery. New paths cannot expand those allowances.

## Gotchas

It does not prove the Fresh start menu action or a live model restart.

A conflicting receipt with the same phone token but different content never confirms or erases a local intention. That pre-existing recovery path keeps its journal-operation identity and Send again safeguards. It is not treated as a canonical echo.
