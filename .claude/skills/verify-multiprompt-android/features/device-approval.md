# Approve or deny a pending device

This journey drives the shipping approval screen and production `AccountAuth`, `DeviceKeyClient`, `DeviceApproval`, `DeviceKeyStore` and SSH implementations. It owns `legacy.igsh-449` for the changed approval UI.

## Preconditions

The GitHub-only fixture is a local account authority at `http://10.0.2.2:8787`. The APK's debug account origin points there. The browser offers **Continue as fixture account** for an already authenticated fixture account, then completes the real app callback and S256 PKCE exchange. No app login token, pending-device state or callback is injected. This does not prove production email OTP or Cloudflare Access; those remain sign-in prerequisites outside this control migration.

The normal SSH journey first imports the fixture host and requires explicit fingerprint trust. Two additional device keypairs stay outside uploaded evidence. One pending laptop starts unauthorized. The second represents a stale key left by an earlier partial approval.

## Drive

Dispatch `verify.yml` with `feature=device-approval.md`.

1. Open **Session list actions → Settings → Sign in**. Capture the fixture browser, choose **Continue as fixture account**, then require **Approve this device?** and **Fixture laptop**.
2. Compare the screen's SHA256 fingerprint with the independently generated pending-device public key. Record `device-approve-ui.json`. A separate SSH client using that device's private key must be rejected before approval.
3. Tap **Approve**. The account fixture refuses approval unless the phone has already installed the correct public key through SSH. A separate SSH client must now authenticate with that device key and print `APPROVED_DEVICE_SSH_OK`.
4. Require **Stale laptop**, compare its fingerprint and capture `device-deny-ui.json`. Confirm the stale key can authenticate, then tap **No**.
5. Require the prompt to disappear and return to **Session list settings**, where sign-in began. Require **Switch / renew login** to confirm the account remains signed in. Capture `device-decisions-ui.json`. The stale key must now fail public-key authentication. The account fixture refuses denial unless that exact key has been removed.

## Citations

Use actual `tap_text Approve` and `tap_text No` lines in `driver-transcript.txt`; use the three JSON snapshots extracted from the corresponding UI XML as resulting state. `device-approval-receipts.txt` records the verified PKCE exchange and decisions after inspecting the host's real `authorized_keys`. `device-approved-ssh.txt` and `device-after-denial.txt` independently prove the two connection outcomes. The full sshd log, numbered PNG/XML captures and public final authorized-key inventory remain available.

Cleanup stops only the recorded fixture server PID and removes the generated private keys. The retained artifact must identify the exact APK source SHA.
