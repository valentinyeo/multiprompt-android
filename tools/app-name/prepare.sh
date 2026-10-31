#!/usr/bin/env bash
set -euo pipefail

: "${EVIDENCE:?}"
: "${FIXTURE:?}"
: "${RUNNER_TEMP:?}"
: "${ANDROID_HOME:?}"
: "${GH_TOKEN:?}"
[[ "${GITHUB_ACTIONS:-}" == true ]]
[[ "${GITHUB_REPOSITORY:-}" == valentinyeo/multiprompt-android ]]
[[ "${PROOF_MODE:-}" == control || "${PROOF_MODE:-}" == release ]]
[[ -z "${EXPECTED_COMMIT:-}" || "${EXPECTED_COMMIT}" =~ ^[0-9a-f]{40}$ ]]
[[ -z "${BASELINE_RUN:-}" || "${BASELINE_RUN}" =~ ^[0-9]+$ ]]

mkdir -p "$EVIDENCE" "$FIXTURE"
EVIDENCE=$(cd "$EVIDENCE" && pwd -P)
export EVIDENCE
repo=valentinyeo/multiprompt-android
manifest_url=https://github.com/valentinyeo/multiprompt-android/releases/download/android-m3-latest/multiprompt-m3-update.json
apk_url=https://github.com/valentinyeo/multiprompt-android/releases/download/android-m3-latest/multiprompt-m3.apk
tools="$ANDROID_HOME/build-tools/35.0.0"
target_probe="$EVIDENCE/target-manifest-probe.json"

validate_manifest() {
    python3 - "$1" "$apk_url" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    data = json.load(stream)
assert isinstance(data, dict)
assert type(data.get("schemaVersion")) is int and data["schemaVersion"] == 1
assert isinstance(data.get("commit"), str) and re.fullmatch(r"[0-9a-f]{40}", data["commit"])
assert data.get("apkUrl") == sys.argv[2]
assert isinstance(data.get("sha256"), str) and re.fullmatch(r"[0-9a-f]{64}", data["sha256"])
assert type(data.get("sizeBytes")) is int and data["sizeBytes"] > 0
assert type(data.get("versionCode")) is int and data["versionCode"] > 0
assert isinstance(data.get("versionName"), str) and data["versionName"].strip()
print(data["commit"])
PY
}

requested=${EXPECTED_COMMIT:-}
deadline=$((SECONDS + 900))
while :; do
    if curl -fsSL --connect-timeout 15 --max-time 60 "$manifest_url" -o "$target_probe.tmp" \
        && commit=$(validate_manifest "$target_probe.tmp"); then
        mv "$target_probe.tmp" "$target_probe"
        if [[ -z "$requested" || "$commit" == "$requested" ]]; then
            break
        fi
    fi
    rm -f "$target_probe.tmp"
    if [[ -z "$requested" || $SECONDS -ge $deadline ]]; then
        printf 'Published target manifest unavailable or does not match the expected commit\n' >&2
        exit 1
    fi
    remaining=$((deadline - SECONDS))
    if (( remaining < 300 )); then
        sleep "$remaining"
        printf 'Timed out waiting for expected published target\n' >&2
        exit 1
    fi
    sleep 300
done

export EXPECTED_COMMIT="$commit"
export CANDIDATE=false
export RELEASE_CHANNEL=android-m3-latest
export VERIFY_FEATURE=settings-navigation.md
export APP_REF=
bash tools/release-choices/prepare.sh

test "$(cat "$EVIDENCE/package-name.txt")" = dev.multiprompt.companion.m3
test "$(validate_manifest "$EVIDENCE/manifest.json")" = "$EXPECTED_COMMIT"
cp "$EVIDENCE/manifest.json" "$EVIDENCE/target-manifest.json"
cp "$EVIDENCE/apk-package.txt" "$EVIDENCE/target-badging.txt"
cp "$EVIDENCE/apk-certificate.txt" "$EVIDENCE/target-certificate.txt"
sha256sum "$EVIDENCE/app.apk" > "$EVIDENCE/target-apk-sha256.txt"

deadline=$((SECONDS + 900))
target_run=
while :; do
    gh api "repos/$repo/actions/workflows/release.yml/runs?branch=main&per_page=10" \
        > "$EVIDENCE/target-run-poll.json"
    lookup=$(
        python3 - "$EVIDENCE/target-run-poll.json" "$EXPECTED_COMMIT" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    payload = json.load(stream)
runs = payload.get("workflow_runs")
assert isinstance(runs, list) and len(runs) <= 10
matches = []
for run in runs:
    assert isinstance(run, dict)
    if (
        run.get("path") == ".github/workflows/release.yml"
        and run.get("head_branch") == "main"
        and run.get("head_sha") == sys.argv[2]
    ):
        assert type(run.get("id")) is int and run["id"] > 0
        assert isinstance(run.get("status"), str)
        matches.append(run)
assert len(matches) <= 1, "Ambiguous target release runs"
if not matches:
    print("waiting")
else:
    run = matches[0]
    if run["status"] == "completed":
        if run.get("conclusion") != "success":
            print("failed")
        else:
            print("success", run["id"])
    else:
        assert run["status"] in (
            "queued", "in_progress", "requested", "waiting", "pending"
        ), "Unexpected target run status"
        assert run.get("conclusion") is None
        print("waiting")
PY
    )
    case "$lookup" in
        "success "*)
            target_run=${lookup#success }
            [[ "$target_run" =~ ^[0-9]+$ ]]
            break
            ;;
        failed)
            printf 'Target release run completed unsuccessfully\n' >&2
            exit 1
            ;;
        waiting) ;;
        *)
            printf 'Unexpected target run lookup result\n' >&2
            exit 1
            ;;
    esac
    remaining=$((deadline - SECONDS))
    if (( remaining < 300 )); then
        if (( remaining > 0 )); then sleep "$remaining"; fi
        printf 'Timed out waiting for successful target release run\n' >&2
        exit 1
    fi
    sleep 300
done

gh api "repos/$repo/actions/workflows/release.yml/runs?branch=main&status=success&per_page=10" \
    > "$EVIDENCE/recent-release-runs.json"

validate_run() {
    python3 - "$1" "$2" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    run = json.load(stream)
assert isinstance(run, dict)
assert type(run.get("id")) is int and str(run["id"]) == sys.argv[2]
assert run.get("path") == ".github/workflows/release.yml"
assert run.get("head_branch") == "main"
assert run.get("status") == "completed"
assert run.get("conclusion") == "success"
assert isinstance(run.get("head_sha"), str) and re.fullmatch(r"[0-9a-f]{40}", run["head_sha"])
print(run["head_sha"])
PY
}

gh api "repos/$repo/actions/runs/$target_run" > "$EVIDENCE/target-run.json"
test "$(validate_run "$EVIDENCE/target-run.json" "$target_run")" = "$EXPECTED_COMMIT"
printf '%s\n' "$target_run" > "$EVIDENCE/target-run-id.txt"

"$tools/aapt" dump badging "$EVIDENCE/app.apk" > "$EVIDENCE/target-badging.txt"
"$tools/apksigner" verify --print-certs "$EVIDENCE/app.apk" > "$EVIDENCE/target-certificate.txt"

inspect_apk() {
    python3 - "$1" "$2" "$3" "$4" "$5" "$6" <<'PY'
import hashlib
import json
import re
import sys
from pathlib import Path

manifest_path, apk_path, badging_path, certificate_path, expected_commit, output_path = sys.argv[1:]
with open(manifest_path, encoding="utf-8") as stream:
    manifest = json.load(stream)
apk = Path(apk_path).resolve(strict=True)
assert apk.is_file()
assert apk.stat().st_size == manifest["sizeBytes"]
digest = hashlib.sha256()
with apk.open("rb") as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
assert digest.hexdigest() == manifest["sha256"]
assert manifest["commit"] == expected_commit
badging = Path(badging_path).read_text(encoding="utf-8")
packages = re.findall(
    r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'",
    badging,
    re.MULTILINE,
)
assert len(packages) == 1
package, version_code, version_name = packages[0]
assert package == "dev.multiprompt.companion.m3"
assert version_code.isdecimal() and int(version_code) == manifest["versionCode"]
assert version_name == manifest["versionName"]
labels = re.findall(r"^application-label:'([^']*)'\s*$", badging, re.MULTILINE)
assert len(labels) == 1 and labels[0].strip()
certificate = Path(certificate_path).read_text(encoding="utf-8")
signers = re.findall(
    r"^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$",
    certificate,
    re.MULTILINE,
)
assert len(signers) == 1 and signers[0][0] == "1"
result = {
    "versionCode": manifest["versionCode"],
    "versionName": manifest["versionName"],
    "commit": manifest["commit"],
    "sha256": manifest["sha256"],
    "apk": str(apk),
    "label": labels[0],
    "certificateSha256": signers[0][1].lower(),
}
Path(output_path).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
PY
}

inspect_apk \
    "$EVIDENCE/target-manifest.json" \
    "$EVIDENCE/app.apk" \
    "$EVIDENCE/target-badging.txt" \
    "$EVIDENCE/target-certificate.txt" \
    "$EXPECTED_COMMIT" \
    "$EVIDENCE/target-metadata.json"

if [[ -n "${BASELINE_RUN:-}" ]]; then
    printf '%s\n' "$BASELINE_RUN" > "$EVIDENCE/baseline-candidates.txt"
else
    python3 - "$EVIDENCE/recent-release-runs.json" "$EXPECTED_COMMIT" "$target_run" \
        > "$EVIDENCE/baseline-candidates.txt" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    runs = json.load(stream)["workflow_runs"]
target_index = next(
    i for i, run in enumerate(runs) if str(run.get("id")) == sys.argv[3]
)
candidates = []
for run in runs[target_index + 1:]:
    if (
        run.get("path") == ".github/workflows/release.yml"
        and run.get("head_branch") == "main"
        and run.get("status") == "completed"
        and run.get("conclusion") == "success"
        and run.get("head_sha") != sys.argv[2]
    ):
        identifier = run.get("id")
        assert type(identifier) is int and identifier > 0
        candidates.append(identifier)
assert candidates, "No older successful main release candidate"
for identifier in candidates:
    print(identifier)
PY
fi

selected=false
while IFS= read -r baseline_run; do
    [[ "$baseline_run" =~ ^[0-9]+$ ]]
    candidate="$EVIDENCE/baseline-candidate-$baseline_run"
    mkdir -p "$candidate"
    gh api "repos/$repo/actions/runs/$baseline_run" > "$candidate/run.json"
    baseline_commit=$(validate_run "$candidate/run.json" "$baseline_run")
    [[ "$baseline_commit" != "$EXPECTED_COMMIT" ]]

    gh api "repos/$repo/actions/runs/$baseline_run/artifacts?per_page=100" \
        > "$candidate/artifacts.json"
    artifact=$(
        python3 - "$candidate/artifacts.json" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    payload = json.load(stream)
artifacts = payload.get("artifacts")
assert isinstance(artifacts, list)
assert type(payload.get("total_count")) is int
assert payload["total_count"] == len(artifacts), "Incomplete artifact listing"
matches = []
for item in artifacts:
    assert isinstance(item, dict)
    name = item.get("name")
    if isinstance(name, str) and re.fullmatch(r"multiprompt-companion-[0-9A-Za-z][0-9A-Za-z._-]*", name):
        matches.append(item)
assert len(matches) == 1, "Expected exactly one normal release artifact"
assert matches[0].get("expired") is False, "Release artifact expired"
assert type(matches[0].get("id")) is int and matches[0]["id"] > 0
print(matches[0]["name"])
PY
    )

    mkdir -p "$candidate/release"
    gh run download "$baseline_run" --repo "$repo" --name "$artifact" --dir "$candidate/release"
    baseline_manifest="$candidate/release/android-m3/multiprompt-m3-update.json"
    baseline_apk="$candidate/release/android-m3/multiprompt-m3.apk"
    test -f "$baseline_manifest"
    test -f "$baseline_apk"
    test "$(find "$candidate/release" -type f -name multiprompt-m3-update.json | wc -l)" -eq 1
    test "$(find "$candidate/release" -type f -name multiprompt-m3.apk | wc -l)" -eq 1
    test "$(validate_manifest "$baseline_manifest")" = "$baseline_commit"
    "$tools/aapt" dump badging "$baseline_apk" > "$candidate/badging.txt"
    "$tools/apksigner" verify --print-certs "$baseline_apk" > "$candidate/certificate.txt"
    inspect_apk "$baseline_manifest" "$baseline_apk" "$candidate/badging.txt" \
        "$candidate/certificate.txt" "$baseline_commit" "$candidate/metadata.json"

    python3 - "$candidate/metadata.json" "$EVIDENCE/target-metadata.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    baseline = json.load(stream)
with open(sys.argv[2], encoding="utf-8") as stream:
    target = json.load(stream)
assert baseline["certificateSha256"] == target["certificateSha256"], "Signer mismatch"
PY

    if python3 - "$candidate/metadata.json" "$EVIDENCE/target-metadata.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    baseline = json.load(stream)
with open(sys.argv[2], encoding="utf-8") as stream:
    target = json.load(stream)
sys.exit(0 if baseline["versionCode"] < target["versionCode"] else 1)
PY
    then
        selected=true
        break
    fi
    if [[ -n "${BASELINE_RUN:-}" ]]; then
        printf 'Explicit baseline is not older than the target\n' >&2
        exit 1
    fi
done < "$EVIDENCE/baseline-candidates.txt"

[[ "$selected" == true ]]
mkdir -p "$EVIDENCE/baseline-release"
cp -a "$candidate/release/." "$EVIDENCE/baseline-release/"
cp "$candidate/run.json" "$EVIDENCE/baseline-run.json"
cp "$candidate/artifacts.json" "$EVIDENCE/baseline-artifacts.json"
cp "$candidate/badging.txt" "$EVIDENCE/baseline-badging.txt"
cp "$candidate/certificate.txt" "$EVIDENCE/baseline-certificate.txt"
printf '%s\n' "$baseline_run" > "$EVIDENCE/baseline-run-id.txt"
printf '%s\n' "$artifact" > "$EVIDENCE/baseline-artifact-name.txt"
sha256sum "$EVIDENCE/baseline-release/android-m3/multiprompt-m3.apk" \
    > "$EVIDENCE/baseline-apk-sha256.txt"

inspect_apk \
    "$EVIDENCE/baseline-release/android-m3/multiprompt-m3-update.json" \
    "$EVIDENCE/baseline-release/android-m3/multiprompt-m3.apk" \
    "$EVIDENCE/baseline-badging.txt" \
    "$EVIDENCE/baseline-certificate.txt" \
    "$baseline_commit" \
    "$EVIDENCE/baseline-metadata.json"

python3 - "$EVIDENCE" "$PROOF_MODE" <<'PY'
import json
import sys
from pathlib import Path

evidence = Path(sys.argv[1])
mode = sys.argv[2]
baseline = json.loads((evidence / "baseline-metadata.json").read_text(encoding="utf-8"))
target = json.loads((evidence / "target-metadata.json").read_text(encoding="utf-8"))
assert target["versionCode"] > baseline["versionCode"]
assert target["certificateSha256"] == baseline["certificateSha256"]
assert target["commit"] != baseline["commit"]
if mode == "release":
    assert target["label"] == "Multi Bot", "Published target is not branded Multi Bot"
plan = {
    "mode": mode,
    "package": "dev.multiprompt.companion.m3",
    "baseline": baseline,
    "target": target,
}
(evidence / "name-update-plan.json").write_text(
    json.dumps(plan, indent=2, sort_keys=True) + "\n", encoding="utf-8"
)
PY

git rev-parse HEAD > "$EVIDENCE/tools-source-sha.txt"
printf 'PUBLISHED_UPDATE_PLAN_READY\n'
