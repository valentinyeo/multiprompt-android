# Settings navigation

The signed fixture uses a disposable offline account and local SSH server. It does not complete OTP or contact a real account provider.

Dispatch `verify.yml` with `feature=settings-navigation.md` and `release_choices=true`. Set `candidate=true` and `expected_commit` to the exact workflow SHA for the signed branch build. For a published APK, leave `candidate=false` and supply its exact `expected_commit`. The preparation checks package, manifest commit, download URL, size, hash and fixture signer.

For the Android 301 negative control, use its exact published commit and `expect_settings_missing=true`. The workflow requires the actual missing-settings or retired-destination assertion. An infrastructure or fixture failure is not an accepted control.

The journey checks sessions and settings, explicit host-key trust, mapping and Automatic across restart, renewal browser handoff and signout. The artifact contains screenshots, XML, transcript, SSH evidence, APK digest and certificates. Browser handoff is not completed authentication.

## M3 dispatch

Run these only after the branch is published. The baseline requires the M3 manifest to still identify Android 301. The published check requires the exact new manifest commit.

```sh
gh workflow run verify.yml --ref ceo/IGSH-1032 -f feature=settings-navigation.md -f channel=android-m3-latest -f release_choices=true -f released_chat=false -f expect_settings_missing=true -f expected_commit=0d255e99f15656bca388feb4558399139e363e88
gh workflow run verify.yml --ref ceo/IGSH-1032 -f feature=settings-navigation.md -f channel=android-m3-latest -f release_choices=true -f released_chat=false -f candidate=true -f expected_commit="$(git rev-parse HEAD)"
gh workflow run verify.yml --ref main -f feature=settings-navigation.md -f channel=android-m3-latest -f release_choices=true -f released_chat=false -f expected_commit="<exact-published-merge-sha>"
```

The candidate command must run from the reviewed feature checkout. Candidate `expected_commit` must equal the workflow commit. The published command must use the exact release manifest commit, not its ancestor.
