# Session switching and send destinations

Dispatch `feature=session-routing.md`. The fixture creates `verify-session` and `verify-other`, each with an independent console receipt file.

The real UI journey opens A from Inbox, sends `UI_ONLY_A`, returns through Back, opens B and sends `UI_ONLY_B`. Screenshots must show the matching `Messaging …` destination. The instrumentation then rapidly switches ReaderScreen instances and swipes between them, sends typed and simulated final dictation text to both real SSH connections, and switches away while dictation is finishing. It checks no late dictation reaches B.

The speech-service result is supplied by the test, not a paid Deepgram call or microphone recording. This proves the app's dictation routing and completion lifecycle, not speech recognition. The SSH/tmux boundary remains real: the driver independently compares both server receipt files, including order and absence of cross-session messages. The test also captures `routing-b.png` on the emulator.

Require `OK (1 test)` and `EMULATOR_ROUTING_OK`, inspect the PNG/XML pairs and both receipt files. Unit checks separately start real processes sharing a workspace and prove history never falls back to a neighbouring transcript. Recorded red control: https://github.com/valentinyeo/multiprompt-android/actions/runs/40240658032.
