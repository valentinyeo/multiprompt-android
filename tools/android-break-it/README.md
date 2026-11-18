# Break Android repairs before merge

A bug-fix journey must pass normally, then pass again under five stresses on the same hosted emulator. Retain a screenshot, UI XML and text evidence for each checkpoint. Missing checkpoints fail rather than count as coverage.

The checkpoints rotate during a draft, background and return, switch chats and back, throttle the emulator network, and send while a reply is still streaming. The normal journey's product assertions must still pass after these actions. `fill` issues fresh per-character input commands because Android drops the tail of an old, slow key-event batch during a busy stream; exact final readback remains mandatory.

The independent verifier reads the retained checkpoint evidence. A broken or missing checkpoint overrides a model PASS. This PR also schedules the stressed replay in its own verifier run, without claiming an Android parent repro for a tooling-only change.

Hosted controls use PR 408's doubled-reply repair at `75c43b90864ef8479ba1dda52a1f04d4d0e3d968`. A second run plants draft loss during activity recreation in a complete checkout of that revision. The normal journey must pass before rotation rejects that candidate.

The retained `rotation-draft.xml` comes from run 44202379025; the editor kept its text but lost focus and placeholder text. `rotation-lost.xml` comes from planted run 44200077830. The read-only `editor-value` oracle accepts the former and rejects the latter without tapping or refilling.
