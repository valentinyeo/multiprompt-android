# Sign in to Pi from a chat

Proves `chat.pi-login` for [MUBO-86](https://app.hypertask.ai/detail/project-13734/86).

The registry schedules a separate `pi-login.md` head journey through the existing `ai-switch.md` driver input. This reuses the hosted Pi process, SSH setup, and chat handover without adding a workflow. For manual dispatch, use `verify.yml` with `feature=ai-switch.md`.

## Journey

1. Switch the fixture chat to Pi through **Session actions → Switch AI**. Send `PI_LOGIN_PROMPT_2276`. The disposable Pi process emits `extension_ui_request` with `method=open_url`. The real host writes a `text.done` event whose item is `pi-login-1`.
2. Return to Inbox. Open **verify-session** while that login is pending. Require **Sign in to Pi**, **Open sign-in link**, and `https://pi.example/verify-device`. Retain `pi-login-pending.json` and `ai-switch-pi-login` PNG/XML. Independently read the host message store into `pi-login-host.json`; require the host login item and exact prompt URL.
3. Tap **Open sign-in link** through `mpa`. Read Android's activity records, not app state. Require the resolved browser to be foreground, launched by the app with `android.intent.action.VIEW` and the exact HTTPS URL. Retain `pi-login-browser.json`, the raw `pi-login-browser-activity.txt`, and a browser PNG/XML.
4. Return to the chat. Send `PI_LOGIN_DONE_2276`. The Pi process reports a successful login through the host protocol. Require the host result text and no **Open sign-in link** button. Retain `pi-login-completed.json` and `ai-switch-pi-logged-in` PNG/XML.
5. Send `PI_LOGIN_AFTER_2276`. Require `PI_REPLY_PI_LOGIN_AFTER_2276` and no sign-in button. Retain `pi-login-after.json` and `ai-switch-pi-after-login` PNG/XML.

`pi-login-drive.sh` owns these phone actions. `pi-login-browser.py` rejects a background browser, a different URL, or a browser launched by another package. The surrounding AI-switch driver retains its reply-count and provider-receipt assertions.

This proves the host signal, card, HTTPS handoff, and result handling against a disposable Pi-compatible process. The reserved fixture URL does not authenticate with a live provider. Live credentials and OTP are outside this journey.
