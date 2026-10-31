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

The scope is an isolated offline account, real SSH and tmux, and the actual signed
Android frontend. It does not prove production sign-in, a physical phone, native AI,
or a Desktop screenshot.
