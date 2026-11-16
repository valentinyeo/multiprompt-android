# Inbox draft label

A chat row shows **Draft** before its preview while the composer still holds unsent text. Sending that text removes the label. The label is the `Session draft` test tag on the row.

Dispatch `verify.yml` with `feature=session-drafts.md`. The driver uses the same debug host import, fingerprint compare, and **Trust and connect** path as the other session journeys. The fixture prints one assistant line, `Inbox preview stays.`, then the usual `VERIFY_READY_528` console.

## Journey

1. On Inbox, require `verify-session` and `Inbox preview stays.`, and require no **Draft** label. Capture `inbox-before-draft`.
2. Open `verify-session`. Type `unsent draft text` and do not send it. Capture `composer-unsent`. The host receipt file must not contain that line.
3. Go back to Inbox. Require the **Draft** label on the `verify-session` row, with its bounds ending before `Inbox preview stays.` starts on the same line. Capture `inbox-draft`.
4. Open the chat again. The composer still shows `unsent draft text`. Tap **Send prompt**. Require `VERIFY_RECEIVED:unsent draft text` in the fixture receipt file.
5. Go back to Inbox. Require `Inbox preview stays.` and no **Draft** label. Capture `inbox-draft-cleared`.

## Gotchas

The preview comes from the fixture's `⏺ Inbox preview stays.` line. A console with no assistant reply has an empty preview, so this journey cannot check "before the preview" on that console. Whitespace-only composer text is not a draft; this journey types a real sentence. The app before this change has no **Draft** label, so the parent run of this same script stops when it waits for **Draft**.

Account OTP, a signed release build, and a physical phone are outside this recipe. More than one chat with a draft at the same time is outside this recipe.
