# Long drafts in the chat box

A long unsent draft stays usable in the chat text box. It wraps inside the field instead of
running off the side, the caret stays on screen when the user edits in the middle, the field
scrolls to the top and the bottom of the draft, and the exact text is still there after leaving
and reopening the chat.

MUBO-569 proves the behaviour and measures layout cost per key. The measurement is
`typing-timing.yml` with `mode=long-drafts`, and its table lands in the pull request, not in this
recipe. Run it with
`gh workflow run typing-timing.yml --ref <branch> -f mode=long-drafts`.

Dispatch `verify.yml` with `feature=long-drafts.md`. The fixture console is the skill console that
serves `/review-native`, so the draft holds a real skill token.

## Journey

`tools/long-drafts/drafts.py` writes the three drafts. Each one is exactly 100, 1,000 or 5,000
characters and carries `/review-native` at offset 12. Nothing is ever sent, so the host receives no
message from this journey.

For every length the driver:

1. Focuses the composer, clears it with Ctrl+A and Delete, then reads the field back as empty.
2. Fills the draft with `tools/long-drafts/ui.py fill`. The fill types up to 500 characters per
   `input text` burst, so every character still arrives as a real key event, waits until the field
   stops changing, reads it back, and compares. When a burst loses characters anywhere in the draft,
   the fill deletes back to the first character that no longer matches and retypes from there, capped
   at one burst, so a repair never retypes the whole draft. A burst is retyped only when a round ends
   where it started, so a healthy phone types every character exactly once, and two rounds that make
   no progress halve the burst. The fill stops with `LONG_DRAFTS_FILL_FAILED` after 40 rounds or six
   times the draft in typed characters instead of eating the job's time limit. The last four characters
   are typed one key event at a time. `--length 100` also requires the painted skill pill outline
   inside the composer at the end of the fill, and the two longer drafts require no pill outline
   because a scrolled draft draws none.
3. Requires the exact draft back in the field, the same field width as the shorter drafts, and the
   field inside the window between the attach and send controls. That is the no-horizontal-overflow
   check: the field never widens, only the height grows, then stops at the six-line cap.
4. Walks the caret to the first line and one line further, requiring the composer to draw the same
   text view both times, then walks it to the last line the same way. The two ends must draw
   different text views, which is the composer scrolling the draft under a caret that stays on
   screen. A draft that fits draws the same view at both ends.
5. Walks the caret down with `KEYCODE_DPAD_DOWN`, types `MIDEDIT` as real key events and requires
   the draft back with one `MIDEDIT` inserted. Arrow keys move one visual line at a time, so the
   driver reads the insertion offset back and corrects the caret up to twice until the edit sits
   between 10% and 90% of the draft, undoing the marker each time with Delete, and requires the
   composer to draw a view away from the end. That is the caret-kept-visible check: the composer
   scrolled to the caret before the edit landed. The edit may land inside the `/review-native`
   token and turn it back into plain text, so this capture asserts neither a pill nor no pill.
6. After the 5,000 character draft, leaves the chat for Inbox, reopens it and requires the edited
   draft byte for byte. The soft keyboard is still up after the middle edit and takes the first Back
   for itself, so the driver presses Back up to three times, twelve seconds apart, until the Inbox
   shows.

Every step keeps a numbered PNG and UI XML pair per length and phase. `long-drafts-result.json`
records the field widths, heights, the middle-edit offset and the scroll comparisons.
`long-drafts-proof.txt` is the pass marker.

## Gotchas

The field stops growing at six lines, so a 1,000 character and a 5,000 character draft have the same
height. That is the wrap proof, not a missing screenshot: the whole draft is in the field, the field
width never changes, and only the visible window moves.

A burst of key events drops characters anywhere in the draft, not only at its end, so the fill reads
the field back after every burst and rewinds it to the last correct character before retyping. The
burst is 500 characters rather than a handful, because the emulator drops about as many characters
per burst either way: the read-back and retry cost, not the typing, is what made an earlier 50
character chunk version of this journey need about 14 seconds per 50 characters and run past the
35 minute job limit on the 5,000 character draft. Retyping a burst after every round, rather than
only after a round that went nowhere, doubled the typing on a healthy phone and cost the fill half
its time.

The arrow-walk travel distance is deliberately generous: a line holds about 20 characters, so
overshooting only parks the caret at the start or the end of the draft, which is exactly what the
two ends of the walk assert.

The field's height and width are recorded from the filled draft before any edit, so the wrap and
line-cap checks compare whole drafts and not a draft carrying the middle edit.

A draft that scrolls draws no skill pill, which is the existing composer rule guarded by
`ComposerLongDraftTest`. The journey therefore checks the pill outline on the 100 character draft
before the middle edit, and the skill token text on all three.

Sending a long draft, dictation into a long draft and account or signed-release builds are outside
this recipe. Layout cost per key is measured on the emulator by the typing harness and is not a
physical-phone number.
