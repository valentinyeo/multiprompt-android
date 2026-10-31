#!/usr/bin/env python3
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

PACKAGE = "dev.multiprompt.companion.m3"
INSTALLERS = {"com.android.packageinstaller", "com.google.android.packageinstaller"}
REMOTE_VIDEO = "/data/local/tmp/app-name-update.mp4"
REMOTE_PID = "/data/local/tmp/app-name-screenrecord.pid"
REMOTE_XML = "/data/local/tmp/app-name-window.xml"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def command(args, timeout=30, check=True):
    return subprocess.run(
        args,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=check,
    )


def adb(*args, timeout=30, check=True):
    return command(["adb", *map(str, args)], timeout=timeout, check=check)


def text(result):
    return result.stdout.decode("utf-8", errors="replace")


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


@dataclass(frozen=True)
class ProofPlan:
    mode: str
    package: str
    baseline: dict
    target: dict

    @classmethod
    def read(cls, path):
        data = json.loads(path.read_text(encoding="utf-8"))
        require(isinstance(data, dict), "Plan must be an object")
        require(data.get("mode") in ("control", "release"), "Invalid proof mode")
        require(data.get("package") == PACKAGE, "Unexpected package")
        for name in ("baseline", "target"):
            item = data.get(name)
            require(isinstance(item, dict), f"Missing {name} metadata")
            require(
                type(item.get("versionCode")) is int and item["versionCode"] > 0,
                f"Invalid {name} version code",
            )
            for field in ("versionName", "label", "apk"):
                require(
                    isinstance(item.get(field), str) and bool(item[field].strip()),
                    f"Invalid {name} {field}",
                )
            require(Path(item["apk"]).is_absolute(), f"{name} APK path is not absolute")
            require(
                isinstance(item.get("commit"), str)
                and re.fullmatch(r"[0-9a-f]{40}", item["commit"]) is not None,
                f"Invalid {name} commit",
            )
            for field in ("sha256", "certificateSha256"):
                require(
                    isinstance(item.get(field), str)
                    and re.fullmatch(r"[0-9a-f]{64}", item[field]) is not None,
                    f"Invalid {name} {field}",
                )
        baseline = data["baseline"]
        target = data["target"]
        require(target["versionCode"] > baseline["versionCode"], "Target is not newer")
        require(
            target["certificateSha256"] == baseline["certificateSha256"],
            "Plan signer mismatch",
        )
        if data["mode"] == "release":
            require(target["label"] == "Multi Bot", "Release target label is not Multi Bot")
        return cls(data["mode"], data["package"], baseline, target)


class Proof:
    def __init__(self, evidence, android_home, plan):
        self.evidence = evidence
        self.plan = plan
        self.apksigner = str(Path(android_home) / "build-tools/35.0.0/apksigner")
        self.recorder = None
        self.recorder_log = None
        self.device_pid = None
        self.recording_ok = False
        self.phase = "initializing"
        self.download_clicked = False
        self.confirmation_observed = False
        self.events = evidence / "name-update-events.jsonl"

    def event(self, phase, **details):
        self.phase = phase
        entry = {"time": time.time(), "phase": phase, **details}
        with self.events.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(entry, sort_keys=True) + "\n")

    def installed(self):
        result = adb("shell", "dumpsys", "package", self.plan.package)
        output = text(result)
        codes = re.findall(r"\bversionCode=(\d+)", output)
        names = re.findall(r"^\s*versionName=(.*?)\s*$", output, re.MULTILINE)
        uids = re.findall(r"\buserId=(\d+)", output)
        require(len(set(codes)) == 1, "Cannot uniquely determine installed version code")
        require(len(set(names)) == 1, "Cannot uniquely determine installed version name")
        require(len(set(uids)) == 1, "Cannot uniquely determine installed UID")
        return {"versionCode": int(codes[0]), "versionName": names[0], "uid": int(uids[0])}

    def installed_apk(self, name, expected):
        paths = text(adb("shell", "pm", "path", self.plan.package)).splitlines()
        bases = [
            line.removeprefix("package:").strip()
            for line in paths
            if line.startswith("package:") and line.strip().endswith("/base.apk")
        ]
        require(len(bases) == 1, "Cannot uniquely determine installed base APK")
        local = self.evidence / f"{name}-installed-base.apk"
        adb("pull", bases[0], str(local), timeout=120)
        actual_hash = digest(local)
        require(actual_hash == expected["sha256"], f"{name} installed APK hash mismatch")
        certificate = command(
            [self.apksigner, "verify", "--print-certs", str(local)], timeout=120
        )
        certificate_text = text(certificate)
        (self.evidence / f"{name}-installed-certificate.txt").write_text(
            certificate_text, encoding="utf-8"
        )
        signers = re.findall(
            r"^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$",
            certificate_text,
            re.MULTILINE,
        )
        require(len(signers) == 1 and signers[0][0] == "1", "Unexpected APK signer set")
        actual_cert = signers[0][1].lower()
        require(
            actual_cert == expected["certificateSha256"],
            f"{name} installed APK certificate mismatch",
        )
        return actual_hash, actual_cert

    def start_recording(self):
        adb("shell", "rm", "-f", REMOTE_VIDEO, REMOTE_PID)
        self.recorder_log = (self.evidence / "screenrecord.log").open("wb")
        script = (
            "screenrecord --time-limit 0 /data/local/tmp/app-name-update.mp4 & "
            "p=$!; echo $p > /data/local/tmp/app-name-screenrecord.pid; wait $p"
        )
        self.recorder = subprocess.Popen(
            ["adb", "shell", script],
            stdout=self.recorder_log,
            stderr=subprocess.STDOUT,
        )
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            require(self.recorder.poll() is None, "Screen recorder exited during startup")
            result = adb("shell", "cat", REMOTE_PID, check=False)
            pid = text(result).strip()
            if result.returncode == 0 and re.fullmatch(r"[1-9][0-9]*", pid):
                self.device_pid = pid
                self.check_recording()
                self.event("recording-started", devicePid=int(pid))
                return
            time.sleep(0.25)
        raise RuntimeError("Screen recorder did not publish its PID")

    def check_recording(self):
        require(self.recorder is not None and self.device_pid, "Screen recorder not started")
        require(self.recorder.poll() is None, "Screen recorder exited early")
        result = adb("shell", "kill", "-0", self.device_pid, check=False)
        require(result.returncode == 0, "Device screen recorder is not running")

    def stop_recording(self):
        errors = []
        if self.recorder is not None:
            if self.device_pid:
                try:
                    self.check_recording()
                    result = adb("shell", "kill", "-INT", self.device_pid, check=False)
                    require(result.returncode == 0, "Could not stop device recorder")
                except Exception as error:
                    errors.append(str(error))
            else:
                errors.append("Recorder PID was not obtained")
            try:
                self.recorder.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.recorder.kill()
                self.recorder.wait(timeout=10)
                errors.append("Recorder did not stop within deadline")
            if self.recorder_log is not None:
                self.recorder_log.close()
            try:
                video = self.evidence / "name-update.mp4"
                result = adb("pull", REMOTE_VIDEO, str(video), timeout=120, check=False)
                require(result.returncode == 0, "Could not pull recording")
                require(video.is_file() and video.stat().st_size > 0, "Recording is empty")
            except Exception as error:
                errors.append(str(error))
        else:
            if self.recorder_log is not None:
                self.recorder_log.close()
            errors.append("Recorder was never started")
        self.recording_ok = not errors
        if errors:
            raise RuntimeError("; ".join(errors))

    def dump(self, check_recorder=True):
        if check_recorder:
            self.check_recording()
        result = adb("shell", "uiautomator", "dump", REMOTE_XML, timeout=30, check=False)
        require(result.returncode == 0, "UI hierarchy dump failed")
        xml = adb("exec-out", "cat", REMOTE_XML, timeout=20).stdout
        root = ET.fromstring(xml)
        require(root.tag == "hierarchy", "Unexpected UI hierarchy")
        return xml, root

    def capture(self, name, xml=None):
        if xml is None:
            xml, _ = self.dump()
        (self.evidence / f"{name}.xml").write_bytes(xml)
        image = adb("exec-out", "screencap", "-p", timeout=30).stdout
        require(image.startswith(b"\x89PNG\r\n\x1a\n"), "Invalid screenshot")
        (self.evidence / f"{name}.png").write_bytes(image)

    def failure_capture(self):
        errors = []
        try:
            xml, _ = self.dump(check_recorder=False)
            (self.evidence / "failure.xml").write_bytes(xml)
        except Exception as error:
            errors.append(f"Failure XML: {error}")
        try:
            image = adb("exec-out", "screencap", "-p", timeout=30).stdout
            require(image.startswith(b"\x89PNG\r\n\x1a\n"), "Invalid failure screenshot")
            (self.evidence / "failure.png").write_bytes(image)
        except Exception as error:
            errors.append(f"Failure screenshot: {error}")
        try:
            output = adb("shell", "dumpsys", "package", self.plan.package, timeout=30)
            (self.evidence / "failure-package.txt").write_bytes(output.stdout)
        except Exception as error:
            errors.append(f"Failure package snapshot: {error}")
        return errors

    def display_size(self):
        output = text(adb("shell", "wm", "size"))
        physical = re.findall(r"Physical size:\s*(\d+)x(\d+)", output)
        override = re.findall(r"Override size:\s*(\d+)x(\d+)", output)
        require(len(physical) == 1 and len(override) <= 1, "Unknown display size")
        width, height = map(int, override[0] if override else physical[0])
        require(width > 0 and height > 0, "Invalid display size")
        return width, height

    def foreground(self):
        output = text(adb("shell", "dumpsys", "window", "windows"))
        focused = re.findall(r"^\s*mCurrentFocus=([^\r\n]+)", output, re.MULTILINE)
        require(len(focused) == 1, "Cannot uniquely determine focused window")
        match = re.search(r"\s([A-Za-z0-9_.]+)/[^\s}]+", focused[0])
        require(match is not None, "Focused window has no package")
        return match.group(1)

    def visible_nodes(self, root, package, value):
        width, height = self.display_size()
        matches = []
        parents = {child: parent for parent in root.iter() for child in parent}
        for node in root.iter("node"):
            if node.get("package") != package or node.get("text") != value:
                continue
            current = node
            visible = True
            while current is not None:
                if current.get("visible-to-user") == "false":
                    visible = False
                    break
                current = parents.get(current)
            if not visible:
                continue
            bounds = re.fullmatch(
                r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", "")
            )
            if not bounds:
                continue
            x1, y1, x2, y2 = map(int, bounds.groups())
            if 0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height:
                matches.append((node, (x1, y1, x2, y2)))
        return matches

    def has(self, root, package, value):
        return bool(self.visible_nodes(root, package, value))

    def tap(self, package, value, identity=None):
        require(self.foreground() == package, "Unexpected foreground package before tap")
        _, root = self.dump()
        require(self.foreground() == package, "Foreground package changed during UI dump")
        if identity is not None:
            require(
                any(self.has(root, package, label) for label in identity),
                "Installer confirmation lacks expected app identity",
            )
        matches = self.visible_nodes(root, package, value)
        require(len(matches) == 1, f"Expected one visible {value!r} in {package}")
        node, (x1, y1, x2, y2) = matches[0]
        require(node.get("enabled") != "false", f"Disabled control: {value}")
        parents = {child: parent for parent in root.iter() for child in parent}
        ancestor = node
        while ancestor.get("clickable") != "true" and ancestor in parents:
            ancestor = parents[ancestor]
            require(ancestor.get("package") == package, "Clickable ancestor package mismatch")
            require(ancestor.get("enabled") != "false", "Clickable ancestor disabled")
            require(
                ancestor.get("visible-to-user") != "false",
                "Clickable ancestor is not visible",
            )
        require(ancestor.get("clickable") == "true", f"No clickable ancestor for {value}")
        require(self.foreground() == package, "Foreground package changed before tap")
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def open_update(self):
        result = text(
            adb(
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                f"{self.plan.package}/dev.multiprompt.companion.MainActivity",
                "--ez",
                "open_update",
                "true",
                timeout=45,
            )
        )
        require("Status: ok" in result, "Could not open update activity")

    def wait_page(self, expected, expected_name: str | None, available=False, timeout=120):
        deadline = time.monotonic() + timeout
        required = [
            "App updates",
            f"Installed: {expected['versionName']} ({expected['versionCode']})",
        ]
        if expected_name is not None:
            required.append(expected_name)
        if available:
            required += [
                f"v{self.plan.target['versionName']} available",
                "Download and update",
            ]
        while time.monotonic() < deadline:
            xml, root = self.dump()
            if all(self.has(root, self.plan.package, value) for value in required):
                return xml, root
            time.sleep(1)
        raise RuntimeError("Timed out waiting for update page: " + ", ".join(required))

    def wait_install(self, baseline_uid, timeout=300):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.check_recording()
            info = self.installed()
            require(info["uid"] == baseline_uid, "UID changed during update")
            if info["versionCode"] == self.plan.target["versionCode"]:
                require(self.download_clicked, "Target appeared before app download click")
                require(
                    info["versionName"] == self.plan.target["versionName"],
                    "Installed target version name mismatch",
                )
                return info
            require(
                info["versionCode"] == self.plan.baseline["versionCode"]
                and info["versionName"] == self.plan.baseline["versionName"],
                "Unexpected installed version during update",
            )
            xml, root = self.dump()
            for installer in INSTALLERS:
                buttons = self.visible_nodes(root, installer, "Update")
                if not buttons:
                    continue
                if self.confirmation_observed:
                    break
                require(
                    self.has(root, installer, self.plan.baseline["label"])
                    or self.has(root, installer, self.plan.target["label"]),
                    "Installer confirmation lacks expected app identity",
                )
                self.capture("before-installer-confirmation", xml)
                self.tap(
                    installer,
                    "Update",
                    identity=(self.plan.baseline["label"], self.plan.target["label"]),
                )
                self.confirmation_observed = True
                self.event("installer-confirmed", package=installer)
                break
            time.sleep(1)
        raise RuntimeError("Timed out waiting for app-initiated installation")

    def wait_app_info(self, timeout=45):
        result = text(
            adb(
                "shell",
                "am",
                "start",
                "-W",
                "-a",
                "android.settings.APPLICATION_DETAILS_SETTINGS",
                "-d",
                f"package:{self.plan.package}",
                timeout=45,
            )
        )
        require("Status: ok" in result, "Could not open Android app info")
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            xml, root = self.dump()
            if self.has(root, "com.android.settings", self.plan.target["label"]):
                self.capture("target-app-info", xml)
                return
            time.sleep(1)
        raise RuntimeError("Expected target label not visible in Android app info")

    def logcat(self):
        result = adb("logcat", "-d", "-v", "threadtime", timeout=60)
        output = text(result)
        (self.evidence / "name-update-logcat.txt").write_text(output, encoding="utf-8")
        lines = output.splitlines()
        for index, line in enumerate(lines):
            if "FATAL EXCEPTION" in line:
                context = "\n".join(lines[index : index + 20])
                require(self.plan.package not in context, "App fatal exception detected")
            require(
                not re.search(r"ANR in " + re.escape(self.plan.package) + r"(?:\s|$)", line),
                "App ANR detected",
            )

    def run(self):
        self.event("baseline-inspection")
        baseline = self.installed()
        require(
            baseline["versionCode"] == self.plan.baseline["versionCode"]
            and baseline["versionName"] == self.plan.baseline["versionName"],
            "Installed baseline version mismatch",
        )
        baseline_hash, baseline_cert = self.installed_apk("baseline", self.plan.baseline)
        self.start_recording()
        self.event("opening-baseline-update")
        self.open_update()
        xml, _ = self.wait_page(self.plan.baseline, expected_name=None)
        self.capture("before-update", xml)
        xml, _ = self.wait_page(self.plan.baseline, expected_name=None, available=True)
        self.capture("update-available", xml)
        require(
            self.installed()["versionCode"] == self.plan.baseline["versionCode"],
            "Target appeared before download click",
        )
        self.tap(self.plan.package, "Download and update")
        self.download_clicked = True
        self.event("app-download-clicked")
        installed = self.wait_install(baseline["uid"])
        self.event("target-installed", uid=installed["uid"])
        target_hash, target_cert = self.installed_apk("target", self.plan.target)
        self.open_update()
        xml, _ = self.wait_page(self.plan.target, expected_name=self.plan.target["label"])
        self.capture("after-update", xml)
        self.event("target-update-page-visible")
        self.wait_app_info()
        self.event("target-app-info-visible")
        self.logcat()
        return {
            "mode": self.plan.mode,
            "status": "passed",
            "package": self.plan.package,
            "baseline": {
                **self.plan.baseline,
                "installedSha256": baseline_hash,
                "installedCertificateSha256": baseline_cert,
                "uid": baseline["uid"],
            },
            "target": {
                **self.plan.target,
                "installedSha256": target_hash,
                "installedCertificateSha256": target_cert,
                "uid": installed["uid"],
            },
            "offeredNotificationObserved": False,
            "appDownloadClicked": self.download_clicked,
            "installedThroughApp": True,
            "installerConfirmationObserved": self.confirmation_observed,
            "uidUnchanged": baseline["uid"] == installed["uid"],
            "installedApkSha256": target_hash,
            "installedCertificateSha256": target_cert,
            "uiLabel": self.plan.target["label"],
            "recording": "name-update.mp4",
        }


def main():
    evidence = Path(os.environ["EVIDENCE"])
    evidence.mkdir(parents=True, exist_ok=True)
    proof = None
    result = None
    errors = []
    try:
        require(os.environ.get("GITHUB_ACTIONS") == "true", "GitHub Actions required")
        serial = text(adb("get-serialno")).strip()
        require(re.fullmatch(r"emulator-[0-9]+", serial) is not None, "Emulator required")
        plan = ProofPlan.read(evidence / "name-update-plan.json")
        proof = Proof(evidence, os.environ["ANDROID_HOME"], plan)
        adb("logcat", "-c")
        result = proof.run()
    except Exception as error:
        errors.append(f"{type(error).__name__}: {error}")
    finally:
        if proof is not None:
            if errors:
                errors.extend(proof.failure_capture())
            try:
                proof.logcat()
            except Exception as error:
                errors.append(f"Logcat: {error}")
            try:
                proof.stop_recording()
            except Exception as error:
                errors.append(f"Recording: {error}")
        if errors:
            failure = {
                "status": "failed",
                "phase": proof.phase if proof else "initializing",
                "errors": errors,
                "appDownloadClicked": proof.download_clicked if proof else False,
                "installerConfirmationObserved": (
                    proof.confirmation_observed if proof else False
                ),
                "recordingComplete": proof.recording_ok if proof else False,
            }
            (evidence / "name-update-failure.json").write_text(
                json.dumps(failure, indent=2, sort_keys=True) + "\n", encoding="utf-8"
            )
            print(json.dumps(failure, sort_keys=True), file=sys.stderr)
    if errors:
        return 1
    require(result is not None and proof is not None and proof.recording_ok, "Missing proof")
    result["recordingComplete"] = True
    (evidence / "name-update-result.json").write_text(
        json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(
        "PUBLISHED_UPDATE_CONTROL_OK"
        if proof.plan.mode == "control"
        else "PUBLISHED_NAME_UPDATE_OK"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
