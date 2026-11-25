# MUBO-529 idle-worker proof

Idle message checks should not start server helpers. Sending and retrying must still start one immediately.

## Live comparison

Run on the shared VPS with its real store and real idle chat, not a fixture. Both sides used the same Python, connectors and database. The only product difference was the reader asset. A read-only query found no queued, sent, failed, accepted or started rows before and after the run.

```
timeout 300 python3 -B tools/message-worker-benchmark.py --provider claude --sid c93225a3-9cc3-47f6-a93b-992a12acc1ce --tmux ht-claude-runner-20 --out /home/valentin/.cache/mpa-529/benchmark.json
```

| Reader | Calls | Median ms | p95 ms | Worker launches | Errors |
|---|---:|---:|---:|---:|---:|
| origin/main, 3412dfdbe8567e46f1651826b91819461f0f77f4 | 50 | 89.97 | 164.06 | 39 | 0 |
| MUBO-529 | 50 | 42.80 | 94.02 | 0 | 0 |

[Raw samples, asset hashes and exact counts](message-worker-proof.json).

The harness starts a separate reader process for each asset, imports that exact asset without publishing it as the shared runtime, then drives its real `serve()` through stdin/stdout. It wraps `subprocess.Popen` only to count actual worker launches; it does not replace workers. Startup is excluded. The requests run in five alternating blocks of ten per side, without warmup. p95 uses nearest rank. Each response must have the matching request ID, protocol, snapshot ID and items list.

This is a host-side request/reply measurement, not an Android frame-time measurement. The VPS load average before the run was 6.20/6.83/7.40 on 18 cores. Other chats remained active. The removed cost is loading the worker source through the child stdin pipe on the reply path; tmux discovery and the snapshot transaction still contribute to reply time. The baseline sometimes reused a still-running worker, hence 39 launches rather than 50. No claim is made about larger chats or peak server load.

## CI regression proof

`tools/message-worker.test.py`, registered in `tools/check.sh source`, drives the request handler with real isolated SQLite stores and counts `subprocess.Popen` calls. Its historical control runs the idle-poll and submit tests against the asset at 3412dfdbe8567e46f1651826b91819461f0f77f4. It requires exactly the idle assertion to fail with 12 launches for 12 polls, while submit passes.

The head tests cover empty polls, submit, retry, all five request operations and all five work states, cancelled rows with another chat's work, missing stores/folders, missing tables, read-only connection mode, and a real flock/FIFO wake without another spawn. Tests and phone verification run on GitHub only.

## Desktop alignment

At implementation time, MUBO-528 had no open or merged desktop PR, and desktop main a0c9676cd322dd6a3a78d7199ad638d129f1a246 still had unconditional snapshot spawns. Android remains pinned to desktop commit 6572511ca37a48b58bc6cc88d0fa4898d2bf4d71, with the explicit `+mubo-529` revision suffix and `tools/chat-reader-worker.patch` applied by the sync script. Replace that patch with the desktop MUBO-528 revision when available. The existing wake-pipe logic is unchanged; there is no cooldown.

Fix Root Causes shaped the change to check real pending rows rather than rate-limit spawns. Test Behavior, Not Implementation shaped the tests to count process launches and read the wake pipe through the request interface.

No code comments were added. The manual no-comments pass found none to remove. deslop is unavailable in this environment.
