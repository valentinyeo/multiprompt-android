# Launcher icon

## Sub-features

The app's launcher icon is the approved white robot head on a dark tile: an adaptive icon with a gradient head foreground and a `#1E1E1E` background, plus a one-colour monochrome layer that Android 13 and later use for themed icons.

## How to get to it (user POV)

Go to the Android home screen and open the app drawer. The multiprompt icon sits in the alphabetical list. Nothing in the app changes; the icon comes from `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`.

## Driving it with control.ps1

Dispatch `verify.yml` with `feature=launcher-icon.md`. `drive.sh` installs the debug APK, passes the doctor, presses Home, opens the drawer with a real swipe and scrolls until the launcher shows a cell labelled multiprompt. `launcher-icon.py check` crops the top of that cell from the real screenshot and requires a large bright head area, a dark tile and visor area, and no pixel of the old cyan glyph. The built APK's adaptive icon is dumped with `aapt2` and must contain a `monochrome` element.

Red control: dispatch the same journey with `app_ref` set to a commit from before this change. The run must fail with `LAUNCHER_ICON_NOT_ROBOT`, not a setup error.

Evidence: `01-sign-in`, `launcher-home` and `launcher-drawer` screenshots with their UI XML, `launcher-icon-crop.png`, `launcher-icon-result.json`, `adaptive-icon-tree.txt`.

## Gotchas

The default emulator launcher does not place new apps on the home screen, so the drawer is the proof surface. The launcher draws its own mask on the adaptive icon, so the check measures colour areas, not exact pixels. Inspect `launcher-icon-crop.png` yourself. The themed monochrome rendering is checked in the APK, not on a themed launcher, because the emulator launcher does not apply themed icons.
