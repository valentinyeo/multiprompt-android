# Reachable journey actions

Phone journeys must tap what a person can reach. `drive.sh` owns taps through `tap_node`, `tap_text` and `tap_desc`. `journey_action.py` plans each action from a fresh accessibility tree. Observation selectors stay separate from action reachability.

## Run the checks

```sh
python3 -B tools/journey-actions/check.py
python3 -B tools/journey-actions/action.test.py
```

Both commands run in `tools/check.sh source` on GitHub. Local execution tests tools only, never Android. Hosted phone journeys still need their original result and receipt assertions.

The action boundary is the highest useful architectural enforcement. It centralizes geometry, enabled ancestors and scroll-axis ownership. App architecture and static types cannot know a future accessibility tree, translated label, fixture agent or overlay. Runtime tree checks and behavior tests enforce those facts. The raw-tap lint prevents new drivers from bypassing that boundary.

## Historical proof

The tests reconstruct the geometry and two-axis failure conditions described by the correction commits. These are minimized replay trees, not downloaded original emulator XML.

- PR #129, `f6594348`, corrected a pin label peeking below fixed tabs while its clickable center was obscured. The replay runs `f6594348^`'s real selector, which returns `100 200` inside Frequent. The shared planner rejects that overlap.
- PR #383, `8dada246`, corrected terminal sheet navigation and horizontal key-row interference. The replay runs `8dada246^`'s real selector on a tree containing both scroll axes. It fails with `Ambiguous scroll-back`; the planner returns a vertical swipe within the sheet.
- The raw-tap ratchet rejects the real `tap_node` implementation from both correction commits because it bypasses reachability. The behavioral suite also drives the current Bash helper through scroll, redump and one tap.

## Existing cleanup inventory

`baseline.json` records eight existing raw-tap occurrences. New occurrences fail even when another old occurrence disappears. Once this baseline exists on main, it may only shrink.

- One system ANR **Wait** tap in the shared driver.
- Four account-journey taps.
- One app-name journey tap.
- Two session-gesture journey taps.

The garden pass names six recipe families for cleanup, plus the #375 single-choice progression case. These historical corrections are not newly verified phone journeys.

| Family | Historical correction |
|---|---|
| Session reader | #85 `b4109970`, renamed composer |
| Native skills | #92 `090bb443`, fixture agent lacks expected menu rows |
| Favorites/pins | #129 `f6594348`, obscured clickable pin |
| Chat reactions | #216 `e9c91a3d`, answer below the fold |
| Settings navigation | #218 `45ac770e`, clipped workspace colors; #219 `a321ad69`, stale menu labels |
| Terminal | #383 `8dada246`, sheet below the fold and two scroll axes |
| Single-choice progression | #375 `8b84e2be`, do not submit the next question after auto-send |

The shared action boundary applies to existing `tap_node` callers, but does not prove the label names the intended business action. Keep the #375 receipt assertion and all other independent result assertions. Run the affected hosted recipe when an interaction or label changes. Do not regenerate the baseline to accept a new bypass.
