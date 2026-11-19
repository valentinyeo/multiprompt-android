# Queued messages become answered

Run `verify.yml` with `feature=chat-queued.md`. Android submits through the real composer, SSH host-key trust and canonical shared-message protocol. The provider is simulated; no model runs.

The fixture holds the canonical worker lock for its own Claude SID. While its terminal draft or an unconsumed input blocks the provider, it suspends only that fixture SID's admission deadlines. This keeps a bounded human-driven journey from testing the unrelated 15-second admission lease. It never changes message IDs, attempts, delivery states or receipt facts directly. Canonical dispatch and connector polling still own those transitions. The send service is replaced only at the simulated provider-admission boundary, where inputs are independently logged.

## Journey

1. Send `WAITING_ONE` and `WAITING_TWO`. Require two distinct canonical IDs and attempts in `queued` state, no acceptance/start receipts, two visible phone bubbles, and zero provider inputs. Current Android deliberately hides Queued/Sending/Delivered labels; the queue assertion reads the real host store rather than inferring state from label text.
2. Clear the fixture-owned terminal draft. Canonical dispatch hands the first input to the simulated provider. Require `sent/queued`, no consumption receipts, exactly one provider input, and both phone bubbles still visible once.
3. Clear only the native queue hint, reopen the chat and publish an unrelated assistant marker. Require unchanged IDs, attempts, states and input count. Do not use **Reset this chat** as a refresh; shared Reset cancels queued work and has its own `chat-shared.md` journey.
4. Explicitly consume each input. Consumption appends one main-chain `queued_command` transcript row carrying the dispatch marker. Only the unmodified canonical connector can correlate its ID and attempt and move it to `started`. Require one accepted and one started fact at the same real transcript offset for each consumed input. Then publish **Both messages received.** and require the answer in Android.
5. Repeat with `FOLLOWUP`, then publish a reply after a 1 MiB image record. Keep the existing 120-second shell/chat recording and three-second catchup assertion.
6. Send `UNCERTAIN_ONE` and `UNCERTAIN_TWO`, consume neither, and reopen twice with unrelated markers. Require stable `sent/queued`, no new receipt claims, no duplicate provider inputs and preservation of the phone draft. Earlier consumed IDs and claims remain unchanged.

`queue-evidence.py` reads only `shared_messages`, `connector_facts` and the real JSONL transcript. It rejects started states without exact ID/attempt receipts, wrong transcript bodies and duplicate claims. `mpa queue` combines that host state with exactly-one visible phone bubbles. The final artifact retains all five stable IDs, four provider inputs, three consumption receipts, XML/PNG pairs and the recording. `00-queue-ui.json` keeps the verified queued and answered UI states compact enough for the independent verifier; `00-queue-receipts.txt` retains the original provider-input log. Each UI state also names its retained source XML.

For signed M3 candidates, use `released_chat=true`, `candidate=true`, `channel=android-m3-latest`. Published mode requires its exact manifest commit. This synthetic provider does not prove real Claude or native Codex/Hax pickup, process-death draft durability, physical-device behavior or universal exactly-once delivery.
