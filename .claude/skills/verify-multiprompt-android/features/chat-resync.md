# Reset this chat

Historical legacy held-queue journey only.
For Claude and Codex, Reset this chat resets shared waiting copies.
This recipe cannot prove shared-store Reset.
Use [shared chat store](chat-shared.md) instead.

The current app never asks the reader for legacy held rows.
`ChatHistoryProtocol.messageProvider` gives every agent the chat screen supports a provider, and `ChatHistoryConnection` sends `held = provider == null`.
`chat_history_reader.py` therefore returns no held rows, so this phone build renders none.
Only a build from before IGSH-1271 can show them.
This journey therefore proves the Reset round trip on a host that still carries a legacy queue: the reader keeps its transcript before, after and across a second Reset, the phone draft survives byte for byte, no prompt is sent, and the fixture runs the bundled reader's own recovery pass to settle the two receipt-backed IDs.

## Sub-features

- Open "Reset this chat" through the chat menu.
- Reload chat without changing source identity.
- Settle two receipt-backed host queue IDs.
- Keep two genuine pending IDs queued.
- Preserve the draft's exact UTF-8 bytes.
- Show feedback without promising everything settled.
- Repeat Reset this chat without extra claims or sends.

## How to get to it

Open `verify-session` and require **Message verify-session** before opening the menu. Then open its three-dot menu.
Tap Reset this chat.

## Hosted fixture

Dispatch `verify.yml` with `feature=chat-resync.md`.
Host import and fingerprint comparison run first.
SSH, tmux and phone gestures are real.
No real provider runs in this fixture.

The queue lives under `/home/verifyssh/.local/state/multiprompt/held-messages/`.
Its session directory uses the canonical tmux hash.
It is never an Android-private database.
Only a fresh GitHub fixture can seed it.
The unmodified bundled reader creates its schema.

Four stable IDs carry distinct synthetic bodies.
`recovery-head` starts submitted, visibly Sending.
Its 201-character body ends with ASCII space.
`pending-one` and `pending-two` start queued.
`accepted-fourth` starts queued, behind both pending IDs.
Initially, no transcript receipt exists for them.
The driver records each row through scrolling.
They needn't all fit on one screen.

The fixture holds the canonical worker lock.
Its terminal independently reports busy throughout.
Neither gate gets released during this journey.
Foreground recovery must work despite that lock.
Busy alone no longer blocks Claude handoff.
An explicit non-dim terminal draft blocks readiness.
Each snapshot checks its exact text.

## Receipt event

The driver records the initial server state.
It types and captures `draft stays here`.
It opens the real Resync menu.
Then it requests the fixture's explicit receipt event.

Initial viewing can advance recovery cursors.
The event therefore rearms a legacy snapshot.
One SQLite transaction holds off concurrent recovery.
It sets `recovery_scan=NULL` and `user_version=1`.
It preserves every row's original origin and inode.
That legacy snapshot enters the evidence.

Next, it appends two synthetic main-chain user receipt rows.
The head receipt drops only trailing ASCII space.
System padding separates the two receipts.
Padding exceeds the canonical recovery scan slice.
The fourth queued ID gets the other receipt.
Only legacy `scan` advances to the new EOF.
Origins stay unchanged; recovery cursors stay NULL.
The appended snapshot enters evidence before commit.

The driver then taps Resync chat.
The app does not request legacy held rows, so it never triggers legacy recovery itself.
The fixture runs the reader's own `drain_held_queue` when the driver asks for the after state, inside the already-appended receipt window.
This journey does not attribute recovery to Resync chat.

## Required evidence

Server checks use IDs, not truncated text.
Both accepted IDs must become delivered.
Each must own exactly one receipt claim.
Neither may remain in the held set.
Exactly two genuine pending IDs must remain queued.
Both must remain in the held set.
No global absence-of-Queued assertion is valid.

Phone checks require the reader transcript row **FIRST REPLY** before the first Reset, after it, and after a second Reset.
Legacy held rows are not rendered by this app version, so this recipe never claims a phone-side queued, sending or delivered label.
The draft editor must still hold the captured bytes at each of those three points.

Draft files compare exact bytes using `cmp`.
SID, inode, tmux target and origins stay fixed.
Provider input bytes and line counts stay zero.
Worker lock and independent busy checks stay true.
A second Resync must preserve those facts.
Receipt claims and all server facts stay identical.

Artifacts retain screenshots, XML and toast captures.
The pill check proves feedback, not its wording.
Server snapshots omit bodies, receipt digests and credentials.
Phone captures contain only synthetic fixture bodies.
Reader, fixture, corpus and APK hashes remain available.
The workflow records app-source SHA and emulator metadata.

## Controls and signed proof

Use the same harness branch for both controls.
First run current DEBUG as the positive control.
Then set DEBUG `app_ref=ac6a6c879d6d950c25ab4179f7443bcd51fa0670`.
That control must fail receipt-ID settlement.
Require `RESYNC_RECEIPT_ID_NOT_SETTLED`, not missing menu failure.
An unrelated infrastructure failure proves nothing.

For signed current coverage, set `released_chat=true`.
Set `candidate=true` for the signed branch candidate.
Candidates ignore `app_ref`; don't call them old controls.
For published coverage, leave `candidate=false`.
Set `expected_commit` to the published manifest SHA.
Existing manifest, APK and signer checks remain mandatory.
Same-signer instrumentation seeds only the offline account.
It never seeds the host's queue database.

### M3 signed dispatch

Use the pushed harness branch for both.
Candidate builds the branch, not `app_ref`.

```sh
gh workflow run verify.yml --repo valentinyeo/multiprompt-android --ref ceo/IGSH-1226 -f feature=chat-resync.md -f released_chat=true -f candidate=true -f channel=android-m3-latest
```

Published proof requires the exact manifest commit.
Replace `PUBLISHED_M3_COMMIT` with its full SHA.
Run only after M3 publication.

```sh
gh workflow run verify.yml --repo valentinyeo/multiprompt-android --ref ceo/IGSH-1226 -f feature=chat-resync.md -f released_chat=true -f candidate=false -f channel=android-m3-latest -f expected_commit=PUBLISHED_M3_COMMIT
```

Both use the existing release signing key.
M3 proof remains unverified until GitHub succeeds.

## Coverage limits

This proves synthetic Android/SSH recovery behavior only.
It does not prove real-provider prompt consumption.
It does not prove production queue cleanup.
It does not prove all older-writer coexistence.
Android still bootstraps its bundled reader.
No shared installed runtime is claimed.
The canonical engine corpus remains separate evidence.
