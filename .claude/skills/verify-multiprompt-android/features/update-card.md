# Phone update card notes

The signed APK shows up to three plain change lines from the offered release's own notes, then **See all**, which opens that version's complete notes. The card keeps its version heading, size and **Download and update** action. This journey never taps the install action.

Dispatch `verify.yml` with `feature=update-card.md`, `release_choices=true` and `candidate=true`. The candidate is built from the head SHA with a version code below the published one, so the published manifest offers a newer version. The candidate is signed, so the real manifest is read through the emulator proxy that allows only `github.com` and its release assets. Restore the app's account through the existing offline fixture.

1. Open `verify-session`, then tap the reader's version label whose description is `Version <version>, open release notes` and require **App updates**.
2. Read the published manifest for the installed channel and derive the offered release's plain change lines with `tools/update-card/notes.py`. The offered version and the release's own notes only are expected; history entries from other versions are never shown.
3. Wait for `v<offered version> available`. If it does not appear, tap **Check now** once. Capture `update-card-preview`. Require **See all**, no **Show less**, no fourth or later change line, then observe each of the first three offered change lines and record them in `update-card-preview-lines.txt`. Reveal **Download and update** without tapping it. No raw markdown marker, HTML tag, repeated header or ticket-only line may appear.
4. Tap **See all**. Require **Show less** and no **See all**, capture `update-card-notes`, then observe every offered change line and record them in `update-card-notes-lines.txt` beside the numbered PNG and XML pair.
5. Require the installed version code unchanged, so the journey proves the display and never touched the install action. `update-card-result.json` compares the observed lines with the offered release's own notes only.

A release with three changes or fewer shows every change and **See all** still opens the complete notes. The same rules for markdown, HTML, headers and ticket-only lines are covered on the JVM by `ReleaseNoteLinesTest` and `UpdateCardNotesTest`, which render the real card with a five-change fixture. This journey proves the real published notes on the phone; a manifest with more than three lines is not published today.

The artifact contains the numbered PNG and XML pairs, `offered-manifest.json`, `offered-notes.json`, `update-card-preview.json`, `update-card-notes.json`, `update-card-result.json`, the APK source SHA, the driver transcript and `logcat.txt`. Signature, download and install verification stay with `app-name.md`, which this journey does not replace.
