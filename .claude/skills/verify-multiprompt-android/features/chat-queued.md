# Claude queue delivery while busy

The hosted journey uses a simulated Claude provider. SSH, host-key trust, tmux, Android composer, and the APK's embedded reader are real. No model runs. The provider owns its prompt, input log, native queue and transcript; it does not decide engine delivery states.

The provider starts busy with `FIXTURE_OWNED_DRAFT` in its terminal prompt. The phone sends `WAITING_ONE` and `WAITING_TWO`. Read-only server SQLite evidence must show two distinct client-generated IDs in `queued` state. Both unique bodies must show Queued, and the independent input log must be empty. The driver checks the draft remains intact, then asks the provider to clear only that draft. Its redraw matches the canonical busy prompt: `Working... (esc to interrupt)`, `❯ `, and the separator.

Each stdin handoff is logged once and immediately gets a main-chain `queued_command` transcript receipt. The provider redraws a native queue-edit hint before that receipt. The first ID must become Delivered while the second remains Queued; the provider then consumes its first native entry and redraws busy-empty. The second must become Delivered. The driver checks the bodies, states, input order and exact receipt count before any assistant reply. SQLite is never modified by the fixture.

Afterward, FOLLOWUP receives its own input and receipt. A 1 MiB image JSONL record precedes its assistant answer. The 120-second shell/chat monitor, latency check, strict host trust, independent input log and screen recordings remain. Evidence includes SQLite snapshots, transcript, inputs, screenshots, recording, and `queue-result.json` with harness and APK provenance.

Dispatch `.github/workflows/verify.yml` with `feature=chat-queued.md`. For the signed branch APK, set `released_chat=true` and `candidate=true`; it builds from the workflow revision. For an unchanged-harness old-app control, use the debug journey with `app_ref` pinned to the old Android commit. The control must fail specifically at the first busy-empty handoff; the earlier run 40998253286 used a different harness and is not that control. A signed candidate ignores `app_ref`; do not use it to claim an old-app comparison.

This branch embeds generation 2. Generation 3 must be pinned separately in the integration branch. The readiness reference is desktop commit `920dffe80a424393d74e5bd0b8514b6bddb2df12`: busy-empty is eligible; a nonempty draft and queue-edit or selection hints remain blocked. This journey covers Claude, not native Codex/Hax managed queues.
