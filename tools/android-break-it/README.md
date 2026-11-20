# Break Android repairs before merge

The nightly `android-full` run passes each journey normally, then passes it again under five stresses on the same hosted emulator; a pull request never runs the replay. Retain a screenshot, UI XML and text evidence for each checkpoint. Missing checkpoints fail rather than count as coverage.

The shared session-list readiness event runs the checkpoints before the selected driver's actions, not from a particular text-entry helper. It opens an isolated fixture chat, rotates with an exact unsent draft, backgrounds and returns, switches chats and back, throttles the emulator network, and sends a follow-up while a real host reply remains streaming. Independent host events must show follow-up admission before stream completion. The selected journey then runs unchanged under the throttle on the same APK and emulator. Even search journeys retain all five checkpoints without sending to their search fixture.

`sign-in.md`, `launcher-icon.md` and `hosts-retired.md` never connect to a chat. Their declared requirements in `break-it.cjs` are rotation, background/return and network, checked on their sign-in screen. Every other journey, including unknown future drivers, requires all five checkpoints. Missing proof still fails closed.

`fill` issues fresh per-character input commands because Android drops the tail of an old, slow key-event batch during a busy stream; exact final readback remains mandatory. Inspector rejection is retained separately from driver exit and timeout in `result.json` and `inspector.json`.

The emulator's numeric speed command uses kbps: `network speed 128:128` must report 128000 bit/s in both directions. The driver also checks the reported 150–300ms latency bounds.

The stressed replay shares the hosted emulator job of the normal journey, so the emulator setup is paid once per journey and not once per checkpoint. The two packages that job needs, `gdb` for the emulator crash tracer and `libpulse0` for the emulator audio library, come from the runner cache after the first job saves them, so a slow package mirror cannot add minutes to every journey job.

A broken or missing checkpoint fails the nightly journey.

Hosted controls use PR 408's doubled-reply repair at `75c43b90864ef8479ba1dda52a1f04d4d0e3d968`. A second run plants draft loss during activity recreation in a complete checkout of that revision. The normal journey must pass before rotation rejects that candidate.

The retained `rotation-draft.xml` comes from run 44202379025; the editor kept its text but lost focus and placeholder text. `rotation-lost.xml` comes from planted run 44200077830. The read-only `editor-value` oracle accepts the former and rejects the latter without tapping or refilling.
