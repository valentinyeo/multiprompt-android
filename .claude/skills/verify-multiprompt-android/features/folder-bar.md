# Inbox folder bar

Goal: the selected folder stays fully visible, overflow fades, and the list shows through the bar. SOFT-2706.
Dispatch `verify.yml` with `feature=folder-bar.md` on a fresh GitHub-hosted debug emulator.

## Journey

1. Use the shared real SSH host import and fingerprint trust flow. Block account HTTP with an emulator-local closed proxy port, then seed an offline account with nine folders, the supported maximum. No production data is used.
2. Select **Folder 09** through the active horizontal viewport. Capture its selected state.
3. Force-stop and reopen without selecting All or revealing the folder first. Require **Folder 09** selected and fully visible. The parent must fail this product assertion.
4. Select **All**, then **Folder 06**. Require the complete selected pill clear of both fading edges after each change. Restart again and check **Folder 06** without scrolling first.
5. Sign out through Settings and return through **Use without account (debug)**. Only the disposable fixture account is removed.
6. In the stressed replay, open the live fixture reader and run the existing AI-switch draft continuation. Require rotation, background/return, another chat and back, throttled networking and a send during streaming, with all five retained checkpoints and independent host receipts. Before streaming, require receipts containing both the Claude-to-Pi handover and `PHONE_AFTER_SWITCH_2538` on the switched Pi session, regardless of receipt order. Either missing text or a wrong provider/session fails; a receipt count alone is not proof. The final stream check counts an identical Pi command ID/body retry once and retains the raw frame count. A second message ID, changed body, wrong session, missing send, or duplicate rendered reply still fails.

## Evidence

`folder-bar-reopened.json` and `folder-bar-middle-reopened.json` retain observed pill/text/viewport bounds, fade clearance and selected-fill pixel samples. The checker requires both 24 dp text paddings and 12 dp clearances inside the scroll viewport. Both rounded-end samples must match the solid selected fill. Every state retains its numbered PNG/XML pair.

`folder-bar-proof.txt` exists only after the last and middle folder checks pass. The shared `relaunch_app` helper selects All, so restart assertions use a no-navigation restart instead. The short recipe keeps folder taps in the verifier's retained action excerpt.

Inspect screenshots for transparent gaps and fading overflow. This proves a debug GitHub emulator, not a physical phone, production account sync or a signed release APK.
