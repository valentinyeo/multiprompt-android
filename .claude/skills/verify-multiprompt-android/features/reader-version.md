# Chat version label

Open a chat and check the version label in the reader top bar. Its description is `Version <version>, open release notes`. Tap it. **App updates** opens.

Dispatch `verify.yml` with `feature=reader-version.md`. The fixture is the hosted Claude chat used by `ai-switch.md`, so the same phone can send during a streamed reply after it returns from App updates.

1. Open `verify-session`. Require **Message verify-session** and exactly one version label whose text is the installed version and whose description is `Version <version>, open release notes`. Capture `reader-version`.
2. Tap that label. Require **App updates**. Capture `reader-updates`.
3. Press Back, open the same chat again, then run the switch-and-stream path from `ai-switch.md`. A bug-fix replay rotates, backgrounds, switches chats and throttles the network on the first draft, then `break_it_stream_send` checks the follow-up was admitted before the streamed reply finished.

The parent build has no version label in this bar, so step 1 fails with `PRODUCT_ASSERTION_FAILED`. Inbox, settings and the live shell keep their own labels. This journey proves the chat bar.
