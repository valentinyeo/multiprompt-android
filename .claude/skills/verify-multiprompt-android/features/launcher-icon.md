# Launcher icon

## Sub-features

Approved robot artwork replaces the old launcher icon. Adaptive icons keep the dark background. Android 13 also receives a monochrome layer.

## How to get to it

Launch multiprompt, then press Home. Pixel Launcher predicts the recently launched app. The robot appears in the home-screen hotseat.

## Driving it

Dispatch `verify.yml` with `feature=launcher-icon.md`. Add `release_icon=true` for the published signed APK. The published download verifies the manifest checksum.

The driver checks the installed version first. It captures the real home screen. The oracle requires `workspace` and rejects `apps_view`. A drawer screenshot cannot satisfy this check. The icon crop must contain the bright head. It rejects the old cyan glyph. The APK must also contain its monochrome resource.

For the negative control, set `app_ref` to a full pre-icon commit SHA. The same journey must report `LAUNCHER_ICON_NOT_ROBOT`.

## Evidence

Retain `launcher-home-icon.png`, its UI XML, `launcher-icon-crop.png`, `launcher-icon-result.json`, `package.txt`, and `adaptive-icon-tree.txt`.
