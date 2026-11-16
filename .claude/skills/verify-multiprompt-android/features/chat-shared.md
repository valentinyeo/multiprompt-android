# Shared chat store

Run `verify.yml` with `feature=chat-shared.md`.
The public fixture uses synthetic chat histories.
It covers Claude and Codex.
The complete generation-22 reader and connector bundle initializes the store first.
`tools/shared-fixture-runtime.py` extracts revision `bebcb708b7ae9338230c2c5b04fa769484f46ccd` and verifies its exact 224961-byte bundle and SHA-256 before installing it. It never relabels the current runtime as generation 22.
Android connects afterward.
Artifacts retain engine identity and bundle hash.
They also retain the initial snapshots.
Separate worker locks prevent native provider pickup. While those isolated fixture workers hold work, they suspend only their provider/SID admission deadlines; Reset and all delivery states remain canonical. The app may subsequently select its newer compatible runtime, whose actual generation and hash are retained.

For each provider, Android submits through SSH.
A second reader submits another message.
Both queued IDs must reach the host store.
Both attempt IDs must also appear.
**Resync chat** must invoke shared Reset.
Both queued copies must disappear from Android.
The host epoch advances once.
IDs and attempts stay unchanged.
The Claude phone and terminal drafts must survive.
The Codex phone process restarts before Reset.
Its host rows must remain identical after restart.
A neighboring SID must remain unchanged.

The negative control builds Android 303.
Set `app_ref=9198aad3721905b09931cdd85628988fe0bd3292` and `expect_shared_missing=true`.
Use debug and `android-latest`.
Android 303 can still send phone messages.
Shared history misses the second client's message.
Failure marker: `SHARED_REMOTE_MESSAGE_NOT_VISIBLE`.
Host and screen evidence are required.
The existing `SHARED_HOST_ADMISSION_MISSING` and `SHARED_HOST_RESET_NOT_APPLIED` markers remain valid.
The workflow verifies app source and initialized store.
It also requires the specific failure marker.
Missing labels cannot satisfy this control.
Neither can database or fixture failures.
Run signed M3 candidate and published APK separately.

This tests Android, SSH and the canonical store.
Native provider pickup needs separate proof.
Desktop UI needs separate proof.
Production sessions need separate proof.

## Dropped chat link

Outcome `chat.link-recovery` uses this same head journey.
After the transcript opens, the driver drops fixture SSH traffic on port 2222 in both directions.
The phone must show a host error and **Retry** instead of silently waiting.
The driver taps **Send** once while the link is down.
The waiting bubble must appear, and an independent host-store read must show no admission.
The driver restores traffic without another Send or Retry tap.
Exactly one host row must appear, the waiting label and error must disappear, and the draft must clear only after admission.

Inspect `shared-link-down-host.json`, `shared-phone.json`, the `shared-link-*` XML and PNG captures, firewall counters, and `driver-transcript.txt`.
The EXIT trap restores the fixture traffic even on failure.
The historical missing-shared-store negative control skips this new probe.
This proves a dropped SSH link, not physical mobile data, process-death recovery, or background sending.
