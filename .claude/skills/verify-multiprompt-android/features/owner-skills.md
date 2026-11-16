# Skills for another server account

A Claude chat whose folder is `/home/skillowner/chat-work` lists that account's skills. The phone signs in as `verifyssh`. `skillowner` has a private skill, `/owner-note`. `verifyssh` has a different skill, `/login-note`, and cannot read the other account's skill file.

Dispatch `verify.yml` with `feature=owner-skills.md`. The driver uses the same debug host import, fingerprint compare, and **Trust and connect** path as the other session journeys. The fixture starts a Claude-shaped console in the other account's folder. No provider and no API key are used.

## Journey

1. On Inbox, open `verify-session`. Require `VERIFY_READY_528`. The tmux session path is `/home/skillowner/chat-work`.
2. Type `/`. Require `/owner-note` and require no `/login-note`. Capture `owner-skill-list`. The host receipt file is missing or does not yet contain that skill.
3. Tap `/owner-note`. The composer shows `/owner-note `. Capture `owner-skill-inserted`.
4. Type `kept` and tap **Send prompt**. Require `VERIFY_RECEIVED:/owner-note kept` in the fixture receipt file, and require no `/login-note` there.

## Parent

The app before this change reads skills as `verifyssh` and does not switch to `skillowner`. The owner skill file is mode 600 inside a mode 700 directory, so that app cannot see `/owner-note`. The parent run of this same script stops while it waits for `/owner-note`.

## Outside this recipe

A chat that already lives in the login account's own home, a folder the login account cannot stat, plugin skills, a custom Claude config directory, and accounts outside `/home` are covered by `tools/native-skills.test.py` only. Account OTP, a signed release build, and a live model provider are outside this recipe.
