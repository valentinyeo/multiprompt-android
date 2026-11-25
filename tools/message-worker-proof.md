# MUBO-529 idle-worker proof

Idle message checks should not start server helpers. Sending and retrying must still start one immediately.

## Live comparison

Run on the shared VPS with its real store and real idle chat, not a fixture. Both sides used the same Python, connectors and database. The only product difference was the reader asset. A read-only query found no queued, sent, failed, accepted or started rows before and after the run.

```
timeout 300 python3 -B tools/message-worker-benchmark.py --provider claude --sid c93225a3-9cc3-47f6-a93b-992a12acc1ce --tmux ht-claude-runner-20 --out /home/valentin/.cache/mpa-529/benchmark.json
```

| Reader | Calls | Median ms | p95 ms | Worker launches | Errors |
|---|---:|---:|---:|---:|---:|
| origin/main, 3412dfdbe8567e46f1651826b91819461f0f77f4 | 50 | 63.92 | 78.18 | 41 | 0 |
| MUBO-529 | 50 | 28.99 | 38.84 | 0 | 0 |

[Raw samples, asset hashes and exact counts](message-worker-proof.json).

The harness starts a separate reader process for each asset, imports that exact asset without publishing it as the shared runtime, then drives its real `serve()` through stdin/stdout. It wraps `subprocess.Popen` only to count actual worker launches; it does not replace workers. Startup is excluded. The requests run in five alternating blocks of ten per side, without warmup. p95 uses nearest rank. Each response must have the matching request ID, protocol, snapshot ID and items list.

This is a host-side request/reply measurement, not an Android frame-time measurement. The VPS load average before the run was 2.89/2.79/3.54 on 18 cores, below the 6.20/6.83/7.40 of an earlier run whose baseline median was 89.97 ms. Other chats remained active. The removed cost is loading the worker source through the child stdin pipe on the reply path; tmux discovery and the snapshot transaction still contribute to reply time. The baseline sometimes reused a still-running worker, hence 41 launches rather than 50. No claim is made about larger chats or peak server load.

## Worker lock handover

The first half of the fix gates every spawn on a read-only check for queued, sent, failed, accepted or started rows. The second half ports desktop MUBO-528 (`20d55092e98b562e59532ca068b9e6cb2c714069`, branch `mubo-528`, no pull request yet): the starter keeps its locked descriptor open across the spawn and passes it to the child, so a live worker holds the chat lock from the fork instead of after it compiles. A poll during that window now takes the busy lock and wakes the worker through the existing wake pipe instead of starting a second process. No cooldown.

## CI regression proof

`tools/message-worker.test.py`, registered in `tools/check.sh source`, drives the request handler with real isolated SQLite stores and counts `subprocess.Popen` calls. Its historical control runs the idle-poll and submit tests against the asset at 3412dfdbe8567e46f1651826b91819461f0f77f4. It requires exactly the idle assertion to fail with 12 launches for 12 polls, while submit passes.

The head tests cover empty polls, submit, retry, all five request operations and every work state, and pin the phone to the desktop gate where a sent row without a deadline is finished work that starts no helper, cancelled rows with another chat's work, missing stores/folders, missing tables, read-only connection mode, the handed lock descriptor and its use by the child, and a real flock/FIFO wake without another spawn. Tests and phone verification run on GitHub only.

## Desktop alignment

Android is pinned to desktop `ee643caf5b5c32567790124c6209942b2a95d86b` with no local patch, so the phone carries the desktop worker gate and its lock handover (passing the locked descriptor to the child through `MULTIPROMPT_MESSAGE_WORKER_LOCK_FD`). The phone check stays read-only and creates no store or folder.

Fix Root Causes shaped the change to check real pending rows and hold the real lock rather than rate-limit spawns. Test Behavior, Not Implementation shaped the tests to count process launches and read the wake pipe through the request interface.

No code comments were added. The manual no-comments pass found none to remove. deslop is unavailable in this environment.
