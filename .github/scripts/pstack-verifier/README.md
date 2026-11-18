# Verifier reliability

Goal: the Android verifier rejects known defects and accepts clean changes on two model families.

The workflow `pstack-verifier-eval.yml` runs on dispatch, weekly, and pull requests changing this directory or its workflow/tests. DeepSeek uses the production `deepseek-flash` request function. Claude Code uses `--model haiku` with the existing subscription OAuth secret. No new secrets or Android builds are needed.

## Cases and acceptance

Each case contains an organic pull request, a small source diff, registered outcomes, and parent/head artifact files. These are **synthetic evidence fixtures**, not captures from new Android builds. The patches are based on source at `e37d6303f9e7ad1e898909d3f5febee91ad57f0d`. They exercise whether the verifier reads contradictions rather than trusting a green journey status. They do not establish emulator reliability or general detection accuracy.

| Label | Change | Expected |
| --- | --- | --- |
| cedar | Append assistant replies a second time; XML and row state show duplicates | FAIL |
| maple | Construct clipboard data without installing it; pasted composers stay empty | FAIL |
| birch | Drop the final character before submitting; independent host receipts lose it | FAIL |
| willow | Replace the unique-row assertion with `True`; green summary contradicts duplicate XML | FAIL |
| aspen | Extract clipboard data creation without changing its label, value or installation; both copied values remain exact | PASS |
| elm | Rewrite the ready/disconnected branch without changing behavior; all reader actions remain exact | PASS |

Every case must satisfy all three criteria on **both** families:

1. Its parsed model verdict exactly matches the hidden answer, including raw FAIL for defects. A deterministic gate rescuing a model's false PASS does not count.
2. The production coverage checks and verdict rules produce the same verdict. Clean cases must cite retained action/result files and independent side effects where required.
3. The answer is valid JSON in the production format. Missing cases, errors, malformed responses and `PASS+NOTES` fail.

## Blinding and reuse

`cases/*.json` contains no answer labels. `expected.json` is loaded only after all model responses. The judge receives no label, answer table, model comparison, case file path or task brief. Each Claude call starts in a fresh temporary directory with only the parent/head evidence. Tools, MCP servers, slash commands, settings sources and session persistence are disabled. Its environment excludes GitHub and DeepSeek credentials.

Both families receive the identical production `JUDGE_INSTRUCTIONS` and `judgeContext` output. The existing registry remains unchanged, including ordinary references to signed release candidates. Parsing, artifact quoting, changed-outcome planning, coverage checks and final verdict rules are shared with the live verifier. There is no alternate prompt and no automatic retry to improve a failing score.

The retained artifacts include each request, raw response, parsed/final verdict, coverage errors, expected result and match flag. Compare matching `*.request.json` files between families to confirm identical inputs. Read every response before accepting a run; mismatches are failures to investigate, not permission to relabel cases.

## Run

On GitHub, dispatch `pstack-verifier-eval.yml` at the branch under review. The two job summaries show per-case results. The `verifier-reliability-deepseek` and `verifier-reliability-claude` artifacts retain proof for 14 days.

Local source-only checks:

```sh
node --test tools/pstack-verifier-eval.test.js tools/pstack-verifier.test.js tools/feature-coverage.test.js
node tools/strip-comments.mjs --check
```

The regular hosted source checks and `pstack-verifier` picker also run the harness tests. No app behavior changes in this PR, so no Android journey coverage is claimed.
