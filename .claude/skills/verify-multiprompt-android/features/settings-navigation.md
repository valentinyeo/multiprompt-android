# Settings navigation

The signed fixture uses a disposable offline account and local SSH server. It does not complete OTP or contact a real account provider.

Dispatch `verify.yml` with `feature=settings-navigation.md` and `release_choices=true`. Set `candidate=true` and `expected_commit` to the exact workflow SHA for the signed branch build. For a published APK, leave `candidate=false` and supply its exact `expected_commit`. The preparation checks package, manifest commit, download URL, size, hash and fixture signer.

For the Android 301 negative control, use its exact published commit and `expect_settings_missing=true`. The workflow requires the actual missing-settings or retired-destination assertion. An infrastructure or fixture failure is not an accepted control.

The journey checks sessions and settings, explicit host-key trust, mapping and Automatic across restart, renewal browser handoff and signout. The artifact contains screenshots, XML, transcript, SSH evidence, APK digest and certificates. Browser handoff is not completed authentication.
