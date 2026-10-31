# Android app name and update

Use `app-name.yml` on public GitHub Actions. Never run this proof locally. The workflow is report-only and nonrequired. It does not block Android releases.

## Profiles

Pull requests automatically run CONTROL. CONTROL upgrades an older published APK. Its target is the published base commit. No candidate app is built. Expect `PUBLISHED_UPDATE_CONTROL_OK`. This marker is not a branding pass.

Manual and nightly runs use RELEASE. The published target must say **Multi Bot**. Expect `PUBLISHED_NAME_UPDATE_OK`.

## Manual release proof

Run after the target reaches normal publication. For reproducibility, pin its actual merge commit. Run 41164560934 contains the Android 306 baseline. The baseline input is otherwise optional.

```bash
gh workflow run app-name.yml --ref main -f baseline_run=41164560934 -f expected_commit=ACTUAL_TARGET_MERGE_COMMIT
```

Use one watcher for the dispatched run. Limit watching to 1800 seconds.

```bash
timeout 1800 gh run watch RUN_ID --interval 300 --exit-status
```

## Evidence and scope

Initial setup installs the baseline with ADB. The target is never ADB-installed. The old app downloads its published update. It uses **Download and update**. Android's installer completes the update.

The proof checks unchanged UID. It pulls the installed APK. It checks its hash and certificate. It checks App Info and Update screens. Evidence includes XML, PNG, and continuous video. Failures retain diagnostic captures. Inspect artifacts after cleanup.

The signed-in account is an isolated fixture. It is retained across the update. It is not production OTP. The proxy blocks account connections. Only approved GitHub CONNECT destinations pass.

Notification offers are not assumed. This covers Android, not a physical phone. Desktop requires separate proof. It does not prove production login. It does not cover malformed APK negatives. It does not prove provider stability.
