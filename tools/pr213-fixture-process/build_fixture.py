#!/usr/bin/env python3
"""Build only reviewed fixture source; keep supplied historical runtime binaries unchanged."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
work = out / "build"
work.mkdir()
artifact = importlib.util.spec_from_file_location("archive", ROOT / "tools/surface/download-verified-artifact.py")
archive = importlib.util.module_from_spec(artifact)
artifact.loader.exec_module(archive)
def extract(source, destination):
    digest = hashlib.sha256(source.read_bytes())
    archive.extract_verified(source, "sha256:" + digest.hexdigest(), destination, digest)
def download(url, expected, destination):
    with urllib.request.urlopen(url, timeout=90) as response, destination.open("wb") as stream:
        shutil.copyfileobj(response, stream)
    actual = hashlib.sha256(destination.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError("Pinned build dependency digest mismatch: " + destination.name)
    return dict(url=url, sha256=actual, bytes=destination.stat().st_size)
deps = {}
deps["r8"] = download("https://dl.google.com/android/maven2/com/android/tools/r8/9.5.26/r8-9.5.26.jar",
    "3502f087388ce95bbf5254555927f09385dc2636873964d0bd53f3eaf4fb49ad", work / "r8.jar")
deps["apksig"] = download("https://dl.google.com/android/maven2/com/android/tools/build/apksig/8.13.0/apksig-8.13.0.jar",
    "c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89", work / "apksig.jar")
deps["build-tools"] = download("https://dl.google.com/android/repository/build-tools_r36.1_linux.zip",
    "a7b5889e4a79fcf3b0976bef40d401f4240fb1eed891d9d91169da1111e11d78", work / "build-tools.zip")
extract(work / "build-tools.zip", work / "build-tools")
tools = work / "build-tools/android-16"
for binary in ("aapt2", "zipalign"):
    (tools / binary).chmod(0o755)
historical = ROOT / "historical-inputs"
# javac and the existing generators accept this verified archive directly. Java
# class names are case-sensitive; no Windows filesystem extraction is involved.
framework = historical / "classes/framework-classes.zip"
extract(historical / "classes/coremod.zip", work / "coremod")
env = dict(os.environ, R8_JAR=str(work / "r8.jar"), APKSIG_JAR=str(work / "apksig.jar"),
           BUILD_TOOLS_DIR=str(tools))
framework_args = ["--framework-classes", str(framework), "--core-classes", str(work / "coremod")]
commands = [
    ["bash", "app/build.sh", str(work / "app"), *framework_args],
    ["bash", "tests/dex-services/build.sh", str(work / "services"), *framework_args,
        "--app-classes", str(work / "app/gesher-app-classes.jar")],
    ["bash", "tests/apps/components/build.sh", str(work / "components"), *framework_args,
        "--framework-res", str(historical / "framework/framework-res.apk")],
]
records = []
for index, command in enumerate(commands):
    log_path = out / f"build-{index}.log"
    with log_path.open("w") as log:
        process = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    records.append(dict(command=command, exit=process.returncode, log=log_path.name,
        log_sha256=hashlib.sha256(log_path.read_bytes()).hexdigest()))
    print(log_path.read_text(), flush=True)
    if process.returncode:
        raise RuntimeError("Fixture build failed")
payload = out / "payload"
(payload / "signed").mkdir(parents=True)
(payload / "original").mkdir()
for source, destination in (
    (work / "components/components.apk", payload / "signed/components.apk"),
    (work / "services/services-tests.dex", payload / "signed/services-tests.dex"),
    (historical / "framework/components.apk", payload / "original/components.apk"),
    (historical / "framework/services-tests.dex", payload / "original/services-tests.dex"),
):
    shutil.copyfile(source, destination)
if hashlib.sha256((payload / "original/components.apk").read_bytes()).hexdigest() != "f80d7dadc1a9e6e2c160b6cfb0345f6f5ea706ac95beb55987944b73f0782d06":
    raise RuntimeError("Actual original consumed unsigned APK changed")
if hashlib.sha256((payload / "original/services-tests.dex").read_bytes()).hexdigest() != "2f47b39da1334e1ea0313b45f06121c7bbe0b708539eb71c2bc32a205674e50f":
    raise RuntimeError("Actual original consumed services DEX changed")
git = lambda *args: subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()
source_pins = {path: git("hash-object", path) for path in (
    "tests/apps/components/build.sh", "tests/apps/lifecycle/build.sh",
    "tests/dex-services/gesher/services/FixtureSettingsSetup.java",
    "app/java/com/gesher/server/ApkSigningVerifier.java",
    "app/java/com/gesher/server/AppOpsModeStore.java",
    "app/java/com/gesher/server/AppOpsService.java")}
receipt = dict(scope="Fresh approved fixture APK/services DEX only; historical9eec installer is not rebuilt",
    source=git("rev-parse", "HEAD"), tree=git("rev-parse", "HEAD^{tree}"),
    run=os.environ["GITHUB_RUN_ID"], attempt=os.environ["GITHUB_RUN_ATTEMPT"],
    java=subprocess.check_output(["javac", "-version"], text=True).strip(),
    commands=records, dependencies=deps, source_pins=source_pins,
    outputs={str(p.relative_to(payload)):dict(sha256=hashlib.sha256(p.read_bytes()).hexdigest(),bytes=p.stat().st_size)
             for p in payload.rglob("*") if p.is_file()})
(payload / "build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
shutil.copyfile(historical / "historical-input-receipt.json", payload / "historical-input-receipt.json")
print(json.dumps(receipt), flush=True)
