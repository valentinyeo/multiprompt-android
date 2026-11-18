# Account upload key

Signed-in Android users select an image without entering a Screencast2 key. SOFT-2682.

## Preconditions

Dispatch `verify.yml` with `feature=screencast-account.md`. The hosted account fixture uses the same browser callback and PKCE exchange as `device-approval.md`. It generates an upload key at runtime and returns it only to authenticated device requests. No production account, email OTP or real upload key is used.

## Drive

1. Import and trust the normal SSH fixture host.
2. Open **Session list actions → Settings → Sign in**. Choose **Continue as fixture account** in the browser. Require **Switch / renew login**.
3. Return to **Inbox → All → verify-session**.
4. Seed one generated PNG in Android's photo library.
5. Open **Attach → Add image**. Require Android's **Photos** picker without opening **Screencast2 uploads** or typing a key.
6. Select the generated photo. Require one selected image. Stop before confirming the picker to avoid uploading fixture data to the public service.

## Evidence

`driver-transcript.txt` records the sign-in, **Add image** and photo selection. `screencast-signed-in.json`, `screencast-picker.json` and `screencast-selected.json` retain the resulting screens. `screencast-account-receipts.txt` independently records the verified browser PKCE exchange and authenticated 200 key fetch, without the key. Retain numbered PNG/XML pairs and the exact APK source SHA.

This proves account-key discovery and image selection, not a successful Screencast2 upload. JVM tests cover 404, network errors, refresh rejection, encrypted-store cache retention and signed-out manual fallback.
