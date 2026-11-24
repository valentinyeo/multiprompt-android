# Repeated Android mistakes (audit, ~400 commits on main through Nov 2026)

Classes ranked by how often fixes, reverts, or verifier callouts recurred in commit subjects and merged repair PRs (`gh pr list --state merged`, targeted `gh search commits`, and ticket-linked bodies for #77, #136, #389, #391, #392, #408).

## 1. Independent chat row echoes outside `ChatHistory.projectMessages`

**Frequency:** Highest in the sample (message / duplicate / reply fix themes dominate the log; dedicated projection work in #77, #2532, #389, #408).

**Examples**

- #77 (`ce2d32d9`) — queued messages collided on lossy hash identity.
- #389 (`705856a2` / `IGSH-2532`) — parallel managed/display projections.
- #408 (`75c43b90` / `IGSH-2569`) — doubled replies after AI switch.

**Prevention:** `tools/message-projection/check.py` (ratchet), `MessageProjectionTest`, hosted journeys with receipt identity. New `ChatHistoryMessage(` constructors, parallel projections, and legacy matchers outside `LegacyMessageIdentity` fail CI with `MESSAGE_PROJECTION_DUPLICATE`.

**Phase 1 note:** The projection ratchet already blocked a second row constructor. It did not block a second text matcher, and #539 was exactly that: the pending copy and the saved copy were compared with a private string rule that missed the phone's skill and mention notes.

**Prevention (phase 2):** One `MessageIdentity` (`reader/MessageIdentity.kt`) owns every chat row key and every text match. Keys come from `key`/`shared`/`held`/`local`/`live`/`unknown`/`unread`; text comes from `MessageIdentity.same` over the `MessageText` type, and only `MessageIdentity` may call the `LegacyMessageIdentity` rule table. `tools/message-projection/check.py` fails with `MESSAGE_IDENTITY_BYPASSED` on a locally built key, a raw `.text` comparison, a `LegacyMessageIdentity` call from anywhere else, or a matcher that takes `String` again.

**Proof:** `check.py 00e1c674^` (the revision before PR 539's fix) exits non-zero with `MESSAGE_IDENTITY_BYPASSED` including `LegacyMessageIdentity.kt: untyped-matcher`; the #77, #312, #389 and #408 revisions are rejected the same way. `MessageIdentityTest` pins the #539 sent-versus-saved pair, the pending copy that must take its saved row, and the near-miss that must stay two rows.

**Status: done (phase 2).** One identity and one matcher, enforced by the check, the types and the test above.

## 2. Screen-owned reader / snapshot I/O (warm chats, duplicate owners)

**Frequency:** High (#1607, #2311, #328, #341, #391).

**Examples**

- #328 / #341 — hidden warm chats kept polling snapshots.
- #391 (`c268b21a` / `IGSH-2534`) — second history owner and missing `ChatPollGate` lease.

**Prevention:** `tools/reader-ownership/check.py`, `reader.lifecycle` journey, `SessionReaderConnection` + `ChatPollGate` architecture.

## 3. Legacy Material buttons, dialogs, and menus instead of `DesignKit`

**Frequency:** Moderate but expensive (#132, #134, #136, #392).

**Examples**

- #136 (`c58a41b6`) — `AlertDialog` on rename/remind/key flows.
- #392 (`38878ce0` / `IGSH-2536`) — competing button wrappers and dropdown menus.

**Prevention (phase 1):** `KitControlRequired` lint in `tools/kit-controls`, shrinking symbol baseline, historical #132/#136 fixtures.

**Prevention (phase 2, blocking):** `tools/kit-controls/check.py`, run by `tools/check.sh source`, fails with `KIT_MATERIAL_BYPASSED` when any `app/src/main` file outside `ui/DesignKit.kt` and `ui/Theme.kt` mentions a Material button, `AlertDialog`, `DropdownMenu`/`DropdownMenuItem`, `Switch`, `ModalBottomSheet`, any `FilledTonal*` control, `tonalElevation` or a purple colour (palette name or template literal). Fix by moving the screen onto `KitSheet`/`KitMenu`/`KitMenuRow`, `KitToggle`, `KitPrimaryButton`/`KitSecondaryButton`/`KitRoundButton` and the `Kit` colours.

**Proof:** `check.py c58a41b6` (the #136 revision before the fix) and `check.py 38878ce0^` (the #392 revision) exit non-zero with `KIT_MATERIAL_BYPASSED` naming the dialogs, menus and legacy buttons; `check.test.py` replays both. The current tree reports `KIT_MATERIAL_BYPASSED_OK: 0`, so there is no allowance list to grow.

## 4. Wrong tmux `-t` session selectors in remote SSH strings

**Frequency:** Lower count but security-load-bearing; repeated across session-list ops and resume/attach (#84, #125 / `844f774b`, stream attach refactors).

**Examples**

- `844f774b` (`IGSH-1230`) — `has-session -t` used `shellQuote(fresh)` (new-session name) instead of `sessionTarget()`; `target()` quoted `"$name:"` without the `=` exact prefix tmux needs.
- Pre-#84 send/attach paths conflated pane vs session targets.

**Prevention (phase 1):** `tools/tmux-target/check.py` — requires `sessionTarget()` / `target()` helpers, rejects inline `shellQuote("$…:")` and `has-session -t $session` when `session` is only a quoted session name. Error id: `TMUX_TARGET_UNQUOTED`.

## 5. Raw coordinate or blind journey taps

**Frequency:** Recurring in journey scripts before #2535 and verifier hardening.

**Examples**

- #2535 (`889f5b51` / `IGSH-2535`) — unreachable controls and `input tap`.
- Early `drive.sh` helpers before `journey_action.py`.

**Prevention:** `tools/journey-actions/check.py` baseline ratchet (`JOURNEY_ACTION_UNREACHABLE`), `mpa tap` reachability planner.