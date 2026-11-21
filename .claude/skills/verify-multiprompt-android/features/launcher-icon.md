# Launcher icon

## Sub-features

Approved robot artwork replaces the old launcher icon. Adaptive icons keep the dark background. Android 13 also receives a monochrome layer.

## How to get to it

Launch Multi Bot from the app drawer, then press Home. The hosted AOSP launcher (`com.android.launcher3`) does not put the app in the hotseat, so the driver drags the drawer cell onto the home screen. The icon crop comes from the home-screen cell.

## Driving it

Dispatch `verify.yml` with `feature=launcher-icon.md`. Add `release_icon=true` for the published signed APK. The published download verifies the manifest checksum.

The driver checks the installed version first. It captures the home screen. The oracle requires `workspace` and rejects `apps_view`, so the drawer dump above only locates the cell to drag out; a drawer screenshot cannot satisfy the icon check. The icon crop must contain the bright head. It rejects the old cyan glyph. The APK must also contain its monochrome resource.

For the negative control, set `app_ref` to a full pre-icon commit SHA. The same journey must report `LAUNCHER_ICON_NOT_ROBOT`.

## Evidence

Retain `launcher-home-icon.png`, its UI XML, `launcher-icon-crop.png`, `launcher-icon-result.json`, `package.txt`, and `adaptive-icon-tree.txt`.
