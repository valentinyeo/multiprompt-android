# Cancel dictation

**Cancel dictation** drops the recording and puts the composer back. The composer holds no transcript from that recording, and the host receipt file does not gain a sent line.

Dispatch `verify.yml` with `feature=dictation-cancel.md`. The driver uses the same debug host import, fingerprint compare, and **Trust and connect** path as the other session journeys.

## Journey

1. Grant microphone permission and turn the emulator microphone on. Silence from `AudioRecord` still counts. The debug APK for this journey is built with `-PdictationListenUrl=ws://10.0.2.2:8765/v1/listen`. `fixture.sh` starts a stand-in on the runner at `127.0.0.1:8765` for this journey only. The emulator reaches it as `10.0.2.2`. The stand-in accepts the key check and the WebSocket and sends no transcript. Reject TCP to `api.deepgram.com` and `api.eu.deepgram.com` so a missed override cannot reach Deepgram.
2. Open `verify-session`. Require **Start dictation** and the empty placeholder `Message verify-session`. Capture `composer-before`.
3. Tap **Start dictation**. The key sheet asks for **API key**. Type a 40-character value created on the runner at that moment. Tap **Save and dictate**.
4. Require **Cancel dictation** and **Stop dictation**, and require the composer placeholder to be gone. Capture `dictation-bar`.
5. Tap **Cancel dictation**. Require **Start dictation**, no **Cancel dictation**, and the empty placeholder again. Capture `composer-after-cancel`. The host receipt file must be unchanged and must not contain `VERIFY_RECEIVED`.

## Gotchas

The runner creates the 40-character value when the sheet is open and does not write it into the repo or the evidence files. No Deepgram credential is stored here. The stand-in is not Deepgram and it sends no transcript, so this journey does not prove a spoken transcript. It proves the cancel control during dictation, an empty composer after cancel, and that nothing was sent. Release builds keep `wss://api.eu.deepgram.com` and `wss://api.deepgram.com` and do not read the debug listen URL.

The emulator must start its microphone. If recording never starts, **Cancel dictation** never stays on screen and this journey stops. The app before this change has no **Cancel dictation** control, so the parent run of this same script stops while waiting for it.

Account OTP, a signed release build, and a physical phone are outside this recipe.
