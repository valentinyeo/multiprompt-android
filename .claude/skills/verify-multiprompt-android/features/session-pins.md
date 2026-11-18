# Android1233: pinned sessions scroll with the list

Run the public GitHub Actions `Verify real Android UI` workflow after publication:

- `feature=session-favorites.md`
- `pins_scroll=true`
- `released_chat=true`
- `channel=android-m3-latest`
- `candidate=false`
- `expected_commit=<actual merge commit>`
- Leave `app_ref` and `update_baseline_run` empty; disable other feature modes.

For pre-merge candidate review use `candidate=true`; that run is not published-APK
proof. Review the retained MP4, PNG and XML captures, source revision, APK hash,
certificate and release manifest from the existing evidence tooling. Review the
header screenshots visually for opacity.

The host inbox is the shared seed: `pin-00` through `pin-11`, the `row-*` sessions,
`cl-long-chat`, `verify-session`, and this journey's `verify-keep`. The driver does
not create those pin and row sessions. It reveals `Robot verify-keep`, then finds
`Robot pin-00` and `Robot pin-11` by those exact descriptions, including horizontal
scroll. A scrolled list must show an exact `Robot row-*` description and no pin
description. Opening `pin-11` still requires `Message pin-11`.

The scope is an isolated offline account, real SSH and tmux, and the actual signed
Android frontend. It does not prove production sign-in, a physical phone, native AI,
or a Desktop screenshot.
