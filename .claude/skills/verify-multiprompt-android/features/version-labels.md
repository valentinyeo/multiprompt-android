# Version label

Dispatch `verify.yml` with `feature=version-labels.md`. Debug entry already checks the sign-in label. This journey checks the same `Version <version>, open release notes` description after **Use without account (debug)**.

## Journey

1. On Inbox, open **Session list actions**. Require one version label there. Tap it. Require **App updates** and `Installed: <version>`.
2. Back to Inbox. **Session list actions → Settings** requires the label on **Session list settings**.
3. Open `verify-session`. Require the label on the reader.
4. **Session actions → Open live terminal**. Require the label on the shell and `SSH · tmux attached`.

## Not covered

Hosts and the host editor no longer have this label. The journey does not download an APK.
