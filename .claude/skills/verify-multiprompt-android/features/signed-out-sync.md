# Signed-out sync row

Dispatch `verify.yml` with `feature=signed-out-sync.md`. The phone stays on **Use without account (debug)**.

## Journey

1. Open `verify-session`.
2. **Session actions → More**. Require the row **Sign in to sync**.
3. Do not tap that row. Dismiss the menu and require the row to disappear.
4. The signed-in row **Sync: signed in — sign out** must be absent.

## Not covered

Inbox **Session list actions** does not contain **Sign in to sync**. Completing sign-in, OTP, and comparing another device are not this journey.
