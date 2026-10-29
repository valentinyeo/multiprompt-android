#!/usr/bin/env bash
set -euo pipefail
: "${GITHUB_REPOSITORY:?}"
: "${PROOF_REF:?}"
: "${PROOF_COUNT:?}"
: "${PROOF_COMMIT:?}"
mkdir -p stability-evidence
printf '%s\n' "$PROOF_COMMIT" > stability-evidence/commit.txt
for ((index=1; index<=PROOF_COUNT; index++)); do
  response=$(gh api -X POST "repos/$GITHUB_REPOSITORY/actions/workflows/test.yml/dispatches" -f ref="$PROOF_REF" -F return_run_details=true)
  run=$(jq -er '.workflow_run_id | select(type == "number")' <<< "$response")
  url=$(jq -er '.html_url | select(startswith("https://github.com/"))' <<< "$response")
  printf '%s\t%s\n' "$index" "$url" | tee -a stability-evidence/runs.tsv
  timeout 1800 gh run watch "$run" --repo "$GITHUB_REPOSITORY" --interval 300 --exit-status
  gh run view "$run" --repo "$GITHUB_REPOSITORY" --json headSha,headBranch,conclusion > "stability-evidence/run-$index.json"
  jq -e --arg sha "$PROOF_COMMIT" --arg ref "$PROOF_REF" '.headSha == $sha and .headBranch == $ref and .conclusion == "success"' "stability-evidence/run-$index.json"
done
echo EMULATOR_SERIES_OK
