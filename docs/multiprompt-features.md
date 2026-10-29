# Multiprompt desktop and Android

> can you keep Android and desktop in sync somehow

One list for both apps. Layout differences allowed.

Source audit, not a claim of device testing.
Partial means shipped, with a reported gap.

Reviewed: 2026-10-29

| Feature | Desktop | Android |
|---|---|---|
| Claude chat | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/chat_history.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/reader/ChatHistory.kt) |
| Codex chat | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/chat_history.zig) | [partial](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/reader/ChatHistoryProtocol.kt) · [gap](https://app.hypertask.ai/detail/project-2060/1220) |
| Queued messages and delivery | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/main.zig) | [partial](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/reader/QueuedMessages.kt) · [gap](https://app.hypertask.ai/detail/project-2060/1223) |
| Colourful robot faces | [partial](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/bot_avatar.zig) · [gap](https://app.hypertask.ai/detail/project-2060/1221) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/ui/BotAvatar.kt) |
| Shared workspaces | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/account_layout.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/account/AccountLayout.kt) |
| Starred sessions | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/main.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/account/FavoriteSplit.kt) |
| Unread sessions and inbox | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/main.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/data/SessionReadStore.kt) |
| Archive and restore sessions | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/archive.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/data/DissolvedSessionStore.kt) |
| Voice dictation | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/dictation_audio.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/dictation/DeepgramDictation.kt) |
| Spoken replies and voice calls | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/voice.zig) | [missing](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/dictation/DeepgramDictation.kt) · [gap](https://app.hypertask.ai/detail/project-2060/1025) |
| Skills in the composer | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/skills.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/skills/NativeSkills.kt) |
| Terminal fallback | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/main.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/terminal/TerminalConnection.kt) |
| Account sign-in | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/account_client.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/account/AccountAuth.kt) |
| In-app updates | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/main.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/update/UpdateManager.kt) |
| Multi Bot app name | [planned](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/main.zig) · [gap](https://app.hypertask.ai/detail/project-2060/1193) | [planned](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt) · [gap](https://app.hypertask.ai/detail/project-2060/1193) |
| Web pages beside chat | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/browser_pane.zig) | [missing](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt) · [gap](https://app.hypertask.ai/detail/project-2060/1224) |
| Saved Hax conversations | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/chat_history_reader.py) | [missing](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/reader/ChatHistoryProtocol.kt) · [gap](https://app.hypertask.ai/detail/project-2060/1046) |
| Agent questions and permissions | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/chat_permission.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/reader/PendingQuestion.kt) |
| Images in chat | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/chat_image.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt) |
| Session names | [shipped](https://github.com/valentinyeo/multiprompt/blob/93ff8a0631761eefaf772b58ccb7a6318c7edc02/src/main.zig) | [shipped](https://github.com/valentinyeo/multiprompt-android/blob/9e643f9c1aef04f0170a58d2f4eca3c03c6455ec/app/src/main/java/dev/multiprompt/companion/model/TmuxSession.kt) |

## Automatic counterparts

Daily checks pair new single-app requests.
Product fixes included: shared behavior stays matched.
Automatic crash reports stay on their own app.
The counterpart checks the same user outcome.
Desktop-only controls need an explicit phone equivalent.
Both-app requests already cover both apps.
Existing gap tickets are reused, never copied.
Unlinked inventory gaps get their own requests.
Generated counterparts never create more counterparts.
Archived counterparts still count as already paired.

New-request coverage starts 2026-10-29T22:55:59Z.
Historical gaps remain linked in this list.

Agent operation: `python3 tools/parity/multiprompt-parity.py --apply`.
Without `--apply`, only planned pairs are printed.
The daily user timer is `multiprompt-parity.timer`.
Board writes use `bin/ceo-ht` as Hax Runner 14.
Update this inventory when gap tickets ship.
