import json
import sys

RELEASE_PATH = ".github/workflows/release.yml"
IGNORED_CONCLUSIONS = ("cancelled", "skipped")
PENDING_STATUSES = ("queued", "in_progress", "requested", "waiting", "pending")


def newest(runs):
    return max(runs, key=lambda run: (run["run_number"], str(run.get("created_at") or "")))


def lookup(payload, expected_commit):
    runs = payload.get("workflow_runs")
    assert isinstance(runs, list) and len(runs) <= 10
    matches = []
    for run in runs:
        assert isinstance(run, dict)
        if (
            run.get("path") == RELEASE_PATH
            and run.get("head_branch") == "main"
            and run.get("head_sha") == expected_commit
        ):
            assert type(run.get("id")) is int and run["id"] > 0
            assert isinstance(run.get("status"), str)
            assert type(run.get("run_number")) is int and run["run_number"] > 0
            matches.append(run)
    candidates = [
        run
        for run in matches
        if not (run["status"] == "completed" and run.get("conclusion") in IGNORED_CONCLUSIONS)
    ]
    if not candidates:
        return "waiting"
    successes = [
        run for run in candidates
        if run["status"] == "completed" and run.get("conclusion") == "success"
    ]
    if successes:
        return "success %d" % newest(successes)["id"]
    pending = [run for run in candidates if run["status"] != "completed"]
    for run in pending:
        assert run["status"] in PENDING_STATUSES, "Unexpected target run status"
        assert run.get("conclusion") is None
    if pending:
        return "waiting"
    return "failed"


def main(argv):
    with open(argv[1], encoding="utf-8") as stream:
        payload = json.load(stream)
    print(lookup(payload, argv[2]))


if __name__ == "__main__":
    main(sys.argv)
