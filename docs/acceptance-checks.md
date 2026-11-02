# Acceptance checks on the phone

One line per behaviour Valentin asked for, the check that proves it, and the release it shipped in. If a line has no check, it is a gap; if it has a check, a regression fails the build.

## Talking to a chat

| Behaviour | Check | Shipped |
|---|---|---|
| A sent message shows once, from send to started | released chat-shared journey, `SHARED_STORE_RESET_OK` | 0.1.319 |
| Reset clears waiting copies and keeps the draft | same journey, `04-shared-after-reset.png` | 0.1.319 |
| A send made while a chat opens is held, not lost | `ChatHistoryView` readiness plus the hold in `sendDraft` | 0.1.332 |
| Two hax runners in one folder never share a reply | `TranscriptProtocolTest.haxRunnersSharingAFolderKeepTheirOwnReplies` | 0.1.353 |
| Pi rides the shared store like Claude and Codex | `MessageProtocolPiTest` | 0.1.354 |
| Each chat keeps its own unsent text | `SessionReadStore` composer draft, plus the inbox and chat checks | 0.1.321 |
| Each chat keeps its own pinned page | `PinnedPageStoreTest` | 0.1.360 |

## Reading replies aloud

| Behaviour | Check | Shipped |
|---|---|---|
| Newest reply first, then older ones | `ReadAloudTest.newestAssistantReplyComesFirstAndUserTextStaysSilent` | 0.1.318 |
| Blank, code-only and tool-only replies stay silent | `ReadAloudTest.toolOnlyRepliesStaySilent`, `emptyToolOnlyAndPendingRepliesStaySilent` | 0.1.357 |
| The reply being read lights up | `ReadAloud` returns the message key; the chat view uses its existing highlight | 0.1.349 |

## Bots, faces and colours

| Behaviour | Check | Shipped |
|---|---|---|
| Every bot draws the approved simple head | robot faces proof, twelve preset pairs | 0.1.340 |
| A bot's own pick beats the workspace colour | `BotAppearanceTest.aBotsOwnPickWinsOverTheWorkspaceColour` | 0.1.358 |
| Face, colour and picture survive a restart | `BotLookStoreTest` | 0.1.356 |
| Every face colour stands out on both themes | `BotColourVisibilityTest` | 0.1.355 |
| A newer colour number never breaks syncing | `AccountLayoutTest.newerColourNumbersAreReadWithoutBreakingThePhone` | 0.1.342 |
| A desktop host removal never breaks syncing | `AccountLayoutTest.desktopHostRemovalsAreReadAndSurviveAPhoneEdit` | 0.1.339 |

## Words and emoji

| Behaviour | Check | Shipped |
|---|---|---|
| Every phone label is in the shared word list | `UiWordsTest` | 0.1.336 |
| Bot names keep emoji and accents | `InboxRowTest.botNamesKeepEmojiAndAccents` | 0.1.341 |
| Emoji stay colourful in messages, names, reactions, typing and the reply preview | `ColourEmojiTest`, five captures | 0.1.361 |

## Lists and rooms

| Behaviour | Check | Shipped |
|---|---|---|
| Several bots move to another workspace in one go | selection locals plus the move sheet | 0.1.345 |
| Room row shows two stacked heads and its members | `RoomScreenTest.roomRowShowsTheRoomAndItsMembers` | 0.1.350 |
| Room screen shows members, chips and the waiting line | `RoomScreenTest.roomShowsNameMembersAndTheWaitingLine` | 0.1.350 |

## The VPS screen

| Behaviour | Check | Shipped |
|---|---|---|
| A chat opens the VPS screen and comes back in one tap | `ComputerPaneTest.theBackArrowReturnsToTheChatInOneTap` | next |
| Only the visible chat holds a viewer connection | `ComputerViewerTest.wakeRetriesOnceThenGoesLiveAndSleepClosesTheTunnel` | next |
| A viewer that cannot be reached offers one retry | `ComputerViewerTest.aViewerThatNeverAnswersEndsLostWithAReason` | next |
| The screen is read through the chat's own connection, never the internet | `SessionReaderConnection.openComputerTunnel` plus the loopback-only cleartext rule | next |
| Each server keeps its own viewer address | `ComputerStoreTest.eachServerKeepsItsOwnViewerAddress` | next |

## Releases

| Behaviour | Check | Shipped |
|---|---|---|
| The published APK is the one we built | `node tools/verify-published.mjs android-m3-latest` | 0.1.352 |
| The live manifest serves the merged commit | `node tools/release-status.mjs` | always |
