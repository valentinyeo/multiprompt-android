# Shared chat store

Run `verify.yml` with `feature=chat-shared.md`.
The public fixture uses synthetic chat histories.
It covers Claude and Codex.
The exact bundled reader initializes the store first.
Android connects afterward.
Artifacts retain engine identity and bundle hash.
They also retain the initial snapshots.
Separate worker locks prevent native provider pickup.

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
Its failure must be `SHARED_HOST_ADMISSION_MISSING` or `SHARED_HOST_RESET_NOT_APPLIED`.
The workflow verifies app source and initialized store.
It also requires the specific failure marker.
Missing labels cannot satisfy this control.
Neither can database or fixture failures.
Run signed M3 candidate and published APK separately.

This tests Android, SSH and the canonical store.
Native provider pickup needs separate proof.
Desktop UI needs separate proof.
Production sessions need separate proof.
