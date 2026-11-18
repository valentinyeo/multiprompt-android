# Bot look choices

Goal: exercise 32 previously uncovered controls from SOFT-2653 without coordinate taps.
Run `verify.yml` with `feature=bot-look.md` on a fresh GitHub-hosted emulator.

After importing the fixture key and explicitly trusting its fingerprint, open verify-session,
then Session actions → Bot look. Use a short 1080×1280 display to require real scrolling.
Capture the top with Face 1 visible and Auto hidden. Tap each Face 1–20 by description;
after every tap assert Bot look, the selected choice, no competing selected face, and its
visible bounds, retaining a numbered XML/screenshot pair and `bot-look-face-N.json`.
Tap Colour 1–8 by description with the same selected-state checks, then tap Auto.
Assert Auto selected, all explicit colours deselected, and Auto revealed while Face 1
is offscreen in `bot-look-bottom.json`. The action resolver scrolls the actual container
to reach clipped choices; merely finding offscreen rows does not pass.

Press Android Back and assert the reader returns without Bot look. Restore the normal
display, force-stop/relaunch, reopen the session and Bot look, and assert Face 20 and
Auto remain selected **without tapping either choice again**. Retain
`bot-look-retained-face.json`, `bot-look-retained-auto.json` and
`BOT_LOOK_CHOICES_SCROLL_DISMISS_OK` in `bot-look-proof.txt`.

Appearance is local in this signed-out fixture, so these choices have no host write.
The screenshots, accessibility selection state and cold-restart readback prove the result.
Picture import/removal and account appearance sync remain uncovered. Bot look labels
and selection state are also available to screen readers; no test tags are added.
