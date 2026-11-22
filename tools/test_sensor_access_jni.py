#!/usr/bin/env python3
"""Run production sensor JNI initialization against a real host JVM and native threads.

Requires Android libnativehelper's include_jni directory (the production JNI header).
The VM accessor and Java policy service are explicit host boundaries; the native
initialization, attachment ownership, exception handling and atomic receiver are real.
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--jni-include", type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="gesher-sensor-jni-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        headers = work / "include/android_runtime"
        headers.mkdir(parents=True)
        shutil.copy2(ROOT / "tests/sensors/jni/AndroidRuntime.h", headers)
        subprocess.run([str(args.java_home / "bin/javac"), "-d", str(classes),
                        str(ROOT / "tests/sensors/jni/NativeSensorAccess.java")], check=True)
        executable = work / "sensor-jni"
        jvm = args.java_home / "lib/server"
        subprocess.run([shutil.which("c++"), "-std=c++20", "-Wall", "-Wextra", "-Werror", "-pthread",
                        "-I" + str(work / "include"), "-I" + str(args.jni_include),
                        "-I" + str(ROOT / "tests/sensors/jni"), "-I" + str(ROOT / "compat/devices"),
                        str(ROOT / "compat/devices/sensor_access.cc"),
                        str(ROOT / "compat/devices/sensor_access_jni.cc"),
                        str(ROOT / "tests/sensors/sensor_access_jni_host.cc"),
                        "-L" + str(jvm), "-Wl,-rpath," + str(jvm), "-ljvm", "-o", str(executable)], check=True)
        subprocess.run([str(executable), str(classes)], check=True, timeout=30)
        missing = work / "empty"
        missing.mkdir()
        subprocess.run([str(executable), str(missing), "missing"], check=True, timeout=30)


if __name__ == "__main__":
    main()
