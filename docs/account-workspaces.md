# Desktop account workspaces on Android

Android uses the same schema-1 service as desktop: https://account.multiprompt.dev.
The older OIDC/encrypted-sync scaffolding is not this account. There is no service
credential in the APK, and schema-1 layout metadata is plaintext in D1 over HTTPS,
not an end-to-end encrypted vault. SSH keys, host trust, users and settings remain
local.

## Sign in

Workspaces → Sign in opens a system Custom Tab for Cloudflare Access email OTP.
The Worker verifies Access independently and returns only a two-minute one-use
code and random state. The exact release callback is
`dev.multiprompt.companion://auth/callback`; debug uses
`dev.multiprompt.companion.debug://auth/callback`, so installing debug does not
steal the release callback. Android checks state, endpoint, duplicate parameters
and pending-login expiry before exchanging with PKCE S256. Both cold-start and
warm callbacks go through the application singleton. Pending state/verifier/code
and the rotating device tokens are encrypted by the existing Android Keystore
AES-GCM SecretStore; OS backup is disabled. A custom scheme is not a verified app
link: interception cannot exchange the code without the verifier, but can deny
completion. The desktop loopback flow is unchanged.

Access tokens last ten minutes and are reused until their last minute. Network,
timeout, malformed-response, rate-limit and service failures retain credentials.
Only an explicit `invalid_grant` requires login again. A pending code exchange
can be retried with Refresh after a network error. Sign out clears local login
and best-effort revokes this device; another signed-in device can revoke it too.

## Workspaces and All Sessions

Workspaces show the account's exact names, array order and tabs, even when an SSH
host cannot be reached. Rename tab/workspace, workspace reorder, tab move/add and
Close tab change the account layout. Close tab never kills tmux; the session stays
in All Sessions. Reader's explicit End/Archive session actions retain their
existing SSH behavior. Inbox remains available for local attention/archive,
reminders and settings, but its workspace membership comes only from the account.

All Sessions lists the existing SSH inventory on **every saved profile**, without
Inbox/Waiting/archive filtering, including sessions outside the account layout.
Each row shows membership or “Not in a workspace”, with an Add/Move action.

Inbox shows gold stars synced with the laptop; signed-in account tabs offer Star/Unstar.
The ★ Favorites split stays just after All and shows only starred account sessions.
Android negotiates favorite metadata and preserves absent values without inventing stars.

Frequent is the first split. It includes tabs with at least three visits in the last
six hours, the local session currently open in the foreground reader/terminal, and
the tab holding the newest visit anywhere in the layout if that visit is within
six hours. Future values count. Sessions are ordered by newest visit (now for the
local front session), with the newest row at the bottom, next to the split bar,
regardless of the Inbox newest-at-bottom setting. Selection uses stable split IDs,
not stored indices, so moving Frequent does not change the selected split.

Android negotiates `X-Layout-Features: favorite,used,visits`. Optional per-tab
`visits` is a unique ascending array of at most eight non-negative safe-integer
Unix-epoch millisecond values; absent means unknown. A foreground reader/terminal
front period records one visit after ten seconds, using the period's start time.
Switching sessions, closing the session or backgrounding the app cancels the dwell;
returning starts a new period. Shorter periods record and upload no visit.
Local/server visits merge by union, deduplication, ascending sort and newest-eight
cap, including across conflicts and PUT acknowledgements. Omission never erases
known visits. A new visit persists and triggers sync immediately, independently
of the used-stamp throttle.

`used` remains an optional safe-integer epoch time in milliseconds, merged by
maximum for older apps. Opening a reader or terminal, sending a message, and
terminal input still stamp it locally. Stamp-only uploads require a value more
than ten minutes newer than the last sent or received value for that tab; other
layout edits and conflict retries still use the normal revisioned PUT path.
Terminal input tracking uses the library's modifier-reset hook after processed
hardware or IME input, not its keyboard-output callback, which also carries
automatic replies to remote terminal queries. Output, polling, history browsing
and restoring an archived session never stamp usage.

Host mapping is device-local: 0 = Hetzner, 1 = Contabo UK, 2 = Inne CRO. Recognized,
unambiguous profiles are selected automatically; Host mapping lets the user choose
explicitly when labels are ambiguous. Host 0 must use the `valentin` outer tmux
profile. An inne tab is the existing valentin-owned tmux session whose pane SSHes
to inne: opening it does not spawn another inner SSH wrapper. Existing direct
`inne` and `agent` profiles, their keys/trust and their sessions still open from
All Sessions. Their separate user-owned tmux servers cannot be represented as
additional host-0 identities by the desktop's current schema; use the outer
valentin session for account tabs rather than pretending those servers are equal.
Missing host credentials are shown explicitly, never synthesized or auto-trusted.

Layout refreshes on resume and every 20 seconds while foregrounded, including in
the reader/terminal. Edits synchronously persist an account-scoped `{base,edited}`
diff before updating UI. GET rebases actual local fields over remote changes;
PUT sends `base_revision`. A 409 re-pulls and re-applies the diff, preserving remote
additions and not reviving deleted tabs with a stale rename. Edits during PUT are
rebased against its acknowledgement. Repeated conflicts/outages keep pending work
for the next poll or restart. Unchanged cache is never uploaded. Account logout
hides workspaces without deleting the cache or pending edits.

Legacy directory/folder workspace preferences and path-use counts are kept in
`WorkspaceStore`, but directory discovery no longer defines workspace membership
or populates the workspace screen. No automatic folder import occurs, even for an
empty account; the preserved data remains available for a future explicit import.
The new-session directory picker still picks the remote working directory, not a
workspace identity. Starting in an account workspace adds the new mapped tmux tab;
otherwise use All Sessions to add it.

## Verification

`./gradlew test assembleDebug lintDebug` with JDK 17 / SDK 36 / Gradle 9.5.1.
Account tests cover schema/ordering, edits, pulls, conflicts/deletions, in-flight
edits, restart persistence, host identity and auth PKCE/state/expiry/error handling.
Service tests execute the actual Worker with signed Access assertions and D1;
production verification checks non-mutating layout conflict, callback-bound code
exchange, refresh/revoke and current D1/API readback without logging credentials.
Actual phone OTP/Custom Tab dispatch and live SSH attachment need owner/device QA;
passing JVM tests and CI are not a claim that those interactions were exercised.
