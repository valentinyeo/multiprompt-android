# Switch Pi models

Proves `pi.model-switch` for [SOFT-2539](https://app.hypertask.ai/detail/project-2060/2539), closing the missing phone journey from [PR #374](https://github.com/valentinyeo/multiprompt-android/pull/374).

Dispatch `verify.yml` with `feature=pi-model-switch.md`. The fresh GitHub emulator imports the ephemeral SSH key through Android's document picker, compares the host fingerprint, and connects through the normal UI. The fixture enables and starts a Pi chat through the real host CLI. A disposable Pi RPC process and model-list helper replace the external provider and desktop discovery service. The APK and its host runtime remain unchanged.

## Journey

1. Open `verify-session`. Require `PI_BEFORE_MODEL_2539 opencode-go/deepseek-v4.1-flash`. Capture `pi-model-before`.
2. Tap **Attach**. Require **Model**. Capture `pi-model-plus`.
3. Tap **Model**. Require **deepseek-v4.1-flash**, **claude-sonnet-4-6**, and **anthropic**. Capture `pi-model-picker`.
4. Tap **claude-sonnet-4-6**. Require that the picker closes and the existing Pi reply remains visible. Capture `pi-model-selected`.
5. Type `PHONE_MODEL_2539`. Require that exact editor value. Capture `pi-model-draft`.
6. Tap **Send prompt**. Require `PI_AFTER_MODEL_2539 anthropic/claude-sonnet-4-6`. Capture `pi-model-reply`.

Every capture retains PNG and XML. `pi-model-switch-state.json` checks the observed text from each capture. `pi-model-switch-receipts.txt` independently records model discovery, the initial model, the `set_model` RPC, and the next phone message. The provider must receive exactly one switch to the chosen provider/model in the same Pi session, before receiving that phone message. The final reply uses the provider's current model, not a fixed success response.

The registry removes `pi.model-switch` from the missing-coverage baseline. The verifier requires this journey for matching Pi model-switch product changes and changes to this recipe, even when its model selects unrelated journeys.

Live provider authentication, failed discovery/retry, rejected switches, and signed releases are outside this recipe.
