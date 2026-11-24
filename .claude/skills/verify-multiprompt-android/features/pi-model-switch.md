# Switch Pi models

Proves `pi.model-switch`: a Pi chat lists its models after a failed load, retries the load, switches to another model, and the next phone reply comes from the model the host was told to use.

Dispatch `verify.yml` with `feature=pi-model-switch.md`. The driver uses the same debug host import, fingerprint compare and **Trust and connect** path as the other session journeys. The fixture starts a hosted Pi chat named `live-verify-session` with a sleeping pane, matching a native Pi live chat, and keeps its window label at `verify-session`.

A disposable Pi RPC process replaces the external provider without changing the app or its host runtime. It starts on `opencode-go/deepseek-v4.1-flash`, records every `set_model` control and every prompt it receives with the model in use, and answers with the model in the reply. The model list comes from a disposable `~/.local/share/zigshell/mp_complete.py` helper: its first answer is an empty list, every later answer is two models. That is the failed load the app retries. Setup requires the Pi host start and the recorded tmux identity before the emulator launches.

Every prompt comes from the phone. A Pi chat does not show a reply that the host finished before the phone held the chat: the app carries the message-store events from its own snapshot cursor, and only the Claude path replays older ones. So the journey's first send produces the model in use, and each fixture reply streams in full, then waits on a hold file that the journey removes once the phone shows it. The host's settled receipt gates the next step.

## Journey

1. Open `verify-session`. Require the composer `Message verify-session`. Send `PHONE_MODEL_BEFORE_287` and require `PI_MODEL_BEFORE_287 opencode-go/deepseek-v4.1-flash`, the reply that names the model in use. The send is retried only while the draft is still in the composer, so a rejected send cannot pass as a reply. Capture `pi-model-before` while the reply streams, then release the hold and require the host's settled receipt.
2. Tap **Attach**, require **Add image**, then tap **Model**. Require **No Pi models on this server. Tap to retry.** and no `claude-sonnet-4-6`, so the first load really failed. Capture `pi-model-retry`.
3. Tap the retry row. Require `deepseek-v4.1-flash`, `opencode-go`, `claude-sonnet-4-6` and `anthropic`. Capture `pi-model-picker`.
4. Tap the sheet's **Model** back row. Require **Add image** and no `claude-sonnet-4-6`. Capture `pi-model-back`. Reopen **Model** and require the list again. Capture `pi-model-reopened`.
5. Tap `claude-sonnet-4-6`. Require **Add image** gone, proving the sheet closed. Capture `pi-model-picked`.
6. Send `PHONE_MODEL_SWITCH_287` from the same composer, require `PI_MODEL_AFTER_287 anthropic/claude-sonnet-4-6`, capture `pi-model-reply`, then release the hold and require the second settled receipt.

`pi-model-switch-receipts.txt` is the independent host side. It must hold an empty model answer first and listed answers after it, one `set_model` with `provider=anthropic` and `modelId=anthropic/claude-sonnet-4-6` for this session, the first phone prompt on the initial model and the second on the chosen model. `pi-model-switch-proof.json` joins those receipts to the before and reply text the phone showed. `pi-model-switch-state.json` extracts the checked text from each capture.

## Gotchas

Every capture keeps a PNG and its XML. The retry capture forbids the second model name, so a model list that arrives without the retry fails. A reply that still names the initial model fails the product assertion instead of passing on a tapped row.

Do not send a prompt and then wait for a finished reply. When the phone rebuilds its message replica just after a submit (its message-status check sees no row for a moment), a reply that already completed is behind the new snapshot cursor and never reaches the screen. The streamed text survives that rebuild. That is why the fixture holds completion and the journey asserts on the streaming reply.

The loading row is display-only and transient; this recipe does not assert it. Live provider authentication, the pinned model list of a real Pi install, and signed releases are outside this recipe.
