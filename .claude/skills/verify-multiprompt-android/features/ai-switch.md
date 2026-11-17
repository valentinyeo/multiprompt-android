# Switch AI

Proves `ai.switch` for [SOFT-2538](https://app.hypertask.ai/detail/project-2060/2538), closing the phone-journey gap from [PR #373](https://github.com/valentinyeo/multiprompt-android/pull/373).

Dispatch `verify.yml` with `feature=ai-switch.md`. The fresh GitHub emulator imports the ephemeral SSH key through Android's document picker, compares the host fingerprint, and connects through the normal UI. The fixture starts a Claude-shaped transcript and enables its host through the real host CLI. A disposable Pi RPC process replaces the external provider, without changing the app or its host runtime.

## Journey

1. Open `verify-session`. Require `CLAUDE_BEFORE_SWITCH_2538`. Capture `ai-switch-before`.
2. Open **Session actions**, then **Switch AI**. Require **Codex** and **Pi**, without **Claude**. Capture `ai-switch-picker`.
3. Tap **Pi**. Require `Switched AI: Claude to Pi` and `PI_HANDOVER_READY_2538` in the reattached chat. Capture `ai-switch-reattached`.
4. Type `PHONE_AFTER_SWITCH_2538`. Require that exact editor value. Capture `ai-switch-draft`.
5. Tap **Send prompt**. Require `PI_REPLY_PHONE_AFTER_SWITCH_2538`. Capture `ai-switch-reply`.
6. Reopen **Session actions → Switch AI**. Require **Claude** and **Codex**, without **Pi**. Capture `ai-switch-current-pi`.

Every capture retains PNG and XML. `ai-switch-state.json` extracts and checks the observed UI text from each capture. `ai-switch-provider-receipts.jsonl` independently records the handover and phone message received by Pi. `ai-switch-host.json` checks the changed host identity, disabled old configuration, enabled Pi configuration, and unchanged tmux chat. Both receipts must target the new Pi session. The old Claude console must receive nothing.

The registry removes `ai.switch` from the missing-coverage baseline. The verifier schedules this journey for matching AI-switch product changes and changes to this recipe, even when the model selects unrelated journeys.

Live provider authentication, Codex, reverse switching, busy refusal, and signed releases are outside this recipe.
