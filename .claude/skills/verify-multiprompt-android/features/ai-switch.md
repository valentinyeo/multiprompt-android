# Switch AI

Proves `ai.switch` for [SOFT-2538](https://app.hypertask.ai/detail/project-2060/2538), closing the phone-journey gap from [PR #373](https://github.com/valentinyeo/multiprompt-android/pull/373).

Dispatch `verify.yml` with `feature=ai-switch.md`. The fresh GitHub emulator imports the ephemeral SSH key through Android's document picker, compares the host fingerprint, and connects through the normal UI. The fixture seeds a Claude transcript and starts its host through the real host CLI with a disposable Claude stream process. The `live-verify-session` tmux session uses a sleeping pane, matching native live chats; its window label stays `verify-session`. Setup requires a successful host start, the sleeping pane, and the expected hosted chat ID before the emulator launches. A disposable Pi RPC process replaces the external provider, without changing the app or its host runtime.

## Journey

1. Open `verify-session`. Require `CLAUDE_BEFORE_SWITCH_2538`. Capture `ai-switch-before`.
2. Open **Session actions**, then **Switch AI**. Require **Codex** and **Pi**, without **Claude**. Capture `ai-switch-picker`.
3. Tap **Pi**. Require `Switched AI: Claude to Pi` and `PI_HANDOVER_READY_2538` in the reattached chat. Capture `ai-switch-reattached`.
4. Type `PHONE_AFTER_SWITCH_2538`. Require that exact editor value. Capture `ai-switch-draft`.
5. Tap **Send prompt**. The fixture holds completion until the streamed reply is visible. Release it, replay the host's recorded ownership and completion events through the real message store, and append a history-only `PI_HISTORY_CAUGHT_UP_2569` marker after the archived reply. The replay supplies both representations regardless of snapshot timing; it neither sends another prompt nor appends another reply. Wait for that marker, proving the phone read the saved copy. Require exactly one `PI_REPLY_PHONE_AFTER_SWITCH_2538` reply, not two. Capture `ai-switch-reply`. This is the SOFT-2569 regression assertion.
6. Reopen **Session actions → Switch AI**. Require **Claude** and **Codex**, without **Pi**. Capture `ai-switch-current-pi`.
7. Close the picker. Send `PHONE_STREAM_2569`. Its fixture reply streams until the next phone send reaches the host store. While that reply is visible and unfinished, send `PHONE_DURING_STREAM_2569`. Release completion only after the host records that send. Background the app, capture the launcher, and return. Require the follow-up reply once.
8. Switch back to **Claude**. Require the second handover and send `PHONE_SECOND_SWITCH_2569`. Require its reply once. `ai-switch-stress-host.json` checks six provider receipts, the two new session IDs, the final enabled provider, and that the follow-up first reached the host before the streamed reply completed. The check reads the message's first host event, so a busy refusal that retries the same message after completion still passes while a follow-up first stored after completion fails.

Every capture retains PNG and XML. `ai-switch-state.json` extracts and checks the observed UI text from each capture. `ai-switch-provider-receipts.jsonl` independently records the handover and phone message received by Pi. `ai-switch-host.json` checks the changed host identity, disabled old configuration, enabled Pi configuration, and unchanged tmux chat. Both receipts must target the new Pi session. The old Claude console must receive nothing.

The registry removes `ai.switch` from the missing-coverage baseline. The verifier schedules this journey for matching AI-switch product changes and changes to this recipe, even when the model selects unrelated journeys. Its hosted head job is the timing anchor for the verifier verdict: `pick journeys`, the normal journey with its stressed replay, and the judge must fit the 20-minute budget from [IGSH-2714](https://app.hypertask.ai/detail/project-2060/2714).

For a bug-fix PR, the normal journey must pass before a second replay on the same emulator. During the first draft that replay rotates, backgrounds and returns, switches to `verify-breakit-other` and back, and throttles the emulator network. The existing streaming follow-up supplies the fifth checkpoint through `break_it_stream_send`. Original reply-count and provider-receipt assertions still run. The artifact's `break-it/` directory retains five PNG/XML/text checkpoints and the full replay transcript. A missing checkpoint or broken assertion fails the verifier, even if its model says PASS.

Live provider authentication, Codex, busy refusal, and signed releases are outside this recipe.
