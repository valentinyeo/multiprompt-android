import base64
import hashlib
import io
from pathlib import Path
import subprocess
import sys
import tarfile

REVISION = "bebcb708b7ae9338230c2c5b04fa769484f46ccd"
BUNDLE_SHA256 = "999ef191b364af22994311f4d8b168e22c67a7103585a0c22e0e9c1cc7a9c316"
ASSETS = {"reader": "chat_history_reader.py", "claude": "chat_connectors/claude.py", "codex": "chat_connectors/codex.py"}
ROOT = Path(__file__).resolve().parents[1]


def prepare(destination, revision=REVISION):
    available = subprocess.run(["git", "cat-file", "-e", revision + "^{commit}"], cwd=ROOT, capture_output=True)
    if available.returncode:
        subprocess.run(["git", "fetch", "--no-tags", "--depth=1", "origin", revision], cwd=ROOT, check=True)
    data = subprocess.check_output(["git", "archive", revision, "app/src/main/assets"], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        assets = {name: archive.extractfile("app/src/main/assets/" + path).read() for name, path in ASSETS.items()}
    prefix = "CONNECTOR_SOURCES = {'claude':'%s','codex':'%s'}\n" % tuple(base64.b64encode(assets[name]).decode() for name in ("claude", "codex"))
    bundle = prefix.encode() + assets["reader"]
    if len(bundle) != 224961 or hashlib.sha256(bundle).hexdigest() != BUNDLE_SHA256:
        raise ValueError("SHARED_FIXTURE_RUNTIME_MISMATCH: prepare the complete generation-22 bundle at " + REVISION + "; do not copy current app assets or relabel their generation.")
    destination.mkdir(parents=True, exist_ok=True)
    for name, content in assets.items():
        (destination / ("shared-" + name + ".py")).write_bytes(content)
    print("SHARED_FIXTURE_GENERATION_22_READY", REVISION, BUNDLE_SHA256)


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))
