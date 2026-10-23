# Session list settings

Inbox's Settings opens a full-screen **Session list settings** page. Split switches edit the synced account layout, not local debug preferences.

## Sub-features

- `settings-open`: **Session list actions → Settings**, full-screen title and **Back**.
- `settings-frequent`: **Show Frequent split**, then Back to check **Frequent** visible/absent.
- `settings-other`: **Frequent as first tab**, **Show Favorites split**, **Show All split**, theme/font and local ordering choices (not automated).
- `settings-sync`: compare the saved setting on another signed-in device (not automated).

## How to get to it (user POV)

Complete account **Sign in** (email OTP) and pull the account layout. From Inbox open **Session list actions → Settings**; **Session list settings** is the page title, not another menu item. Use the switch contentDescription **Show Frequent split**, then **Back** to Inbox.

## Driving it

Not dispatchable in `verify.yml`. A human signed in with email OTP opens **Session list actions → Settings**, checks the **Show Frequent split** switch's `checked` state flips, goes **Back**, and sees **Frequent** appear in Inbox; then switches it off and sees **Frequent** gone.

## Gotchas

**Blocked in fresh CI:** `MainViewModel.setSessionListSetting` calls `editAccountLayout`, which requires signed-in state and a pulled account layout. Debug **Use without account (debug)** cannot save split switches, even though they look enabled. CI cannot receive OTP; no tokens, layout setters or preference seeding are permitted. A human can perform the same on/off route after real sign-in; this workflow does not automate that prerequisite. Source: `ui/AppMenu.kt`, `ui/MultipromptApp.kt` settings screen, `MainViewModel.setSessionListSetting/editAccountLayout` and `SessionListSettingsTest`.
