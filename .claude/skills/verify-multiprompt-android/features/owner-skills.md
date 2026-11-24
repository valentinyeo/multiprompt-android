# Skills for another server account

A Claude chat whose folder is `/home/skillowner/chat-work` lists that account's skills. The phone signs in as `verifyssh`. `skillowner` has a private skill, `/owner-note`. `verifyssh` has a different skill, `/login-note`, and cannot read the other account's skill file.

Dispatch `verify.yml` with `feature=owner-skills.md`. The driver uses the same debug host import, fingerprint compare and **Trust and connect** path as the other session journeys. The fixture starts a Claude-shaped console in the other account's folder and lets `verifyssh` use `sudo` on the throwaway runner, so the reader and the skills script can ask for the owning account. No provider and no API key are used.

## Journey

1. On Inbox, open `verify-session`. Require `VERIFY_READY_528`, and require its tmux session path to be `/home/skillowner/chat-work`. The host receipt file does not yet contain `/owner-note`.
2. Type `/`. Require `/owner-note` and require no `/login-note`. Capture `owner-skill-list`.
3. Tap `/owner-note`. The composer shows `/owner-note `. Capture `owner-skill-inserted`.
4. Type `kept` and tap **Send prompt**. Require `VERIFY_RECEIVED:/owner-note kept` in the fixture receipt file, and require no `/login-note` there.

## Independent host evidence

`owner-fixture.txt` records that `/owner-note` is readable by `skillowner` and not by `verifyssh`, and that the session folder is `/home/skillowner/chat-work`. `owner-sudo.txt` is the runner's sudo log: it shows the app running the skills script as `skillowner` with the fixed launcher, so a list that only `skillowner` can read reached the phone. `owner-receipts.txt` is the console's own record of the sent text.

## Parent

The app before this change reads skills as `verifyssh` and never switches to `skillowner`. The owner skill file is mode 600 inside mode 700 directories, so that app cannot see `/owner-note` and stops while it waits for it.

## Outside this recipe

A chat that already lives in the login account's own home, an account folder the login account cannot stat, a folder whose passwd home is not `/home/<name>`, plugin skills, a custom Claude config directory, refused or broken sudo answers, and accounts outside `/home` are covered by `tools/native-skills.test.py` only. Account OTP, a signed release build and a live model provider are outside this recipe.
