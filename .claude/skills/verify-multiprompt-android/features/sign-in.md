# Sign in

Sign in opens the account service in a Custom Tab for Cloudflare Access email OTP, then returns through the app callback. It is distinct from SSH public-key authentication.

## Sub-features

- `account-entry`: Sign in screen and browser handoff (automated).
- `account-return`: OTP, state/PKCE callback and persistent user login (not automated).
- `account-sync-entry`: Sign in to sync from the session overflow menu (not automated).

## How to get to it (user POV)

A fresh install opens Sign in. After entering the debug-only offline mode, **Session list actions → Sign in to sync** opens the same account flow. A signed-in user's menu instead offers sign out.

## Driving it with adb and UI Automator

Preconditions: fresh GitHub emulator and debug APK; doctor passes on the Sign in activity.

- Dispatch `gh workflow run verify.yml -R valentinyeo/multiprompt-android --ref <branch> -f feature=sign-in.md`.
- The driver waits for text `Sign in to see your servers`, captures `sign-in`, and calls `tap_text 'Sign in'`.
- It requires a resumed Chrome activity and captures `access-browser` PNG/XML plus browser activity metadata. Inspect the browser screenshot and XML for the actual account/Access destination; a browser process alone is not OTP proof.
- A human with an authorized account can finish email OTP and inspect the app's returned account state. CI never fabricates the callback or tokens.

## Gotchas

CI cannot receive OTP. This recipe verifies handoff only, and logs that limitation. All SSH recipes use the visible **Use without account (debug)** button, gated twice by `BuildConfig.DEBUG`; it changes only local navigation, never signed-in state or tokens. Release and m3 keep mandatory sign-in. Account APIs remain protected. A browser on top intentionally fails the app-resumed doctor. An unavailable account service is a blocked sign-in journey, not a reason to replace the page with a mock.
