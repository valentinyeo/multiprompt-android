# Record working dots on the signed release

## Launch

Dispatch the dedicated workflow on a pushed branch:

```bash
gh workflow run working-dots.yml -R valentinyeo/multiprompt-android --ref <branch>
gh run watch <run-id> -R valentinyeo/multiprompt-android --interval 300 --exit-status
```

## Check the recording

Download `released-working-dots-<run-id>` and inspect:

- `manifest.json`, `apk-certificate.txt`, and `app-apk-sha256.txt`: published signed APK identity, not a rebuilt candidate.
- `working-dots.mp4`: 135 seconds of quiet local tools, screen lock and unlock, and an SSH reconnect.
- `quiet-tools.png`, `after-unlock.png`, `after-reconnect.png`, and `after-turn.png`: visible dots during work and no dots after work.
- `visibility.txt`: visible accessibility nodes sampled every 100 ms.
- `server.jsonl`: actual local tool executions and server start/end times.
- `journey.jsonl` and `reset-sockets.txt`: screen events, real socket reset, fresh SSH authentication, and clock alignment.
- `result.json`: independently checked duration, samples, tool receipts, and start/end visibility delays of at most one second.

The checker rejects missing-dot intervals, sampling gaps, late transitions, and a reconnect without new SSH authentication. Screen-off samples do not require visible dots. After unlock, the existing working state must return.

## Boundary

The app, SSH, tmux, tool commands, screen events, and reconnect are real. The fixture writes the server hook-state contract deterministically and leaves chat text unchanged. No model provider runs.

Same-signer instrumentation seeds an isolated offline account, not a production login. Host import and fingerprint trust still use the real UI. The observer starts after UIAutomator navigation, because Android permits only one automation owner. No personal device is used.
