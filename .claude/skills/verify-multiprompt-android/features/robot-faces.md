# Robot inbox and chat

Run `verify.yml` with `feature=robot-faces.md`. For the signed candidate, set `released_robots=true` and `candidate=true`. For the published update, set `released_robots=true` and `expected_commit` to the released SHA.

The isolated emulator imports its ephemeral SSH key and explicitly trusts its fixture host. The driver asserts `Robot verify-session` after real tmux discovery, captures the inbox, opens the session, then asserts the same robot alongside the chat title. Evidence includes screenshots, UI trees, SSH authentication, recording, package identity, signing-certificate comparison, APK digest and exact published commit.

Pinned favourites, unread badges, avatar sizes, previews and preserved tap/long-press actions are covered by `RobotFacesTest` in `robot-faces.yml`, including rejection of the pre-change inbox.
