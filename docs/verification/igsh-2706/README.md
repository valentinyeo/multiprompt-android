# Inbox folder bar comparison

Both images are unedited GitHub emulator captures after selecting **Folder 09** from nine seeded folders. The app source is unchanged after the after-image revision; subsequent edits repair the journey and its assertions.

| Image | APK source | Retained file |
| --- | --- | --- |
| Before | `dd344ad00da239f645060dfe2d85be819203761d` | `04-folder-bar-selected.png` in the parent artifact |
| After | `64ab840285fb8f3be917ed4608c1cb37d2a9d3f0` | `04-folder-bar-selected.png` in the head artifact |

Run: https://github.com/valentinyeo/multiprompt-android/actions/runs/44339995658

The old bar has an enclosing surface and outline with abrupt clipping. The new bar leaves the gaps transparent, fades the overflow edge and keeps the selected pill clear of that fade.

These images compare appearance, not successful completion of that run. The PR's final verifier run owns the restart, middle-folder and stress assertions at its exact head SHA. Restart proof must not use the shared `relaunch_app` helper because that helper selects All before returning.
