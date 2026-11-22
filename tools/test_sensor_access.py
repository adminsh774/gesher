#!/usr/bin/env python3
"""Exercise production sensor policy transitions and native atomic snapshots.

Host-only: this does not establish ART/Binder transport or physical Windows sensors.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    for name in ("framework-classes", "core-classes", "app-classes", "apksig"):
        parser.add_argument("--" + name, type=Path)
    args = parser.parse_args()
    javac = str(args.java_home / "bin/javac") if args.java_home else shutil.which("javac")
    java = str(args.java_home / "bin/java") if args.java_home else shutil.which("java")
    cxx = shutil.which("c++")
    if not javac or not java or not cxx:
        parser.error("JDK 17+ and a C++20 compiler are required")
    with tempfile.TemporaryDirectory(prefix="gesher-sensor-access-") as temporary:
        work = Path(temporary)
        subprocess.run([javac, "-d", str(work),
                        str(ROOT / "app/java/com/gesher/server/SensorAccessPolicy.java"),
                        str(ROOT / "tests/sensors/SensorAccessPolicyHost.java")], check=True)
        subprocess.run([java, "-cp", str(work), "com.gesher.server.SensorAccessPolicyHost"], check=True)
        native = work / "sensor-access"
        subprocess.run([cxx, "-std=c++20", "-Wall", "-Wextra", "-Werror", "-pthread",
                        "-I" + str(ROOT / "compat/devices"),
                        str(ROOT / "compat/devices/sensor_access.cc"),
                        str(ROOT / "tests/sensors/sensor_access_host.cc"), "-o", str(native)], check=True)
        subprocess.run([str(native)], check=True, timeout=30)
        runtime = (args.framework_classes, args.core_classes, args.app_classes, args.apksig)
        if any(runtime):
            if not all(runtime) or not args.java_home:
                parser.error("Lifecycle/client tests need --java-home and all four classpath arguments")
            classpath = os.pathsep.join(str(path.resolve()) for path in
                    (args.app_classes, args.framework_classes, args.core_classes, args.apksig))
            library = work / "libsensor_access_host.so"
            subprocess.run([shutil.which("cc"), "-std=c11", "-D_POSIX_C_SOURCE=200809L", "-shared", "-fPIC",
                            "-I" + str(args.java_home / "include"), "-I" + str(args.java_home / "include/linux"),
                            str(ROOT / "tests/sensors/framework_host.c"), "-o", str(library)], check=True)
            subprocess.run([javac, "-encoding", "UTF-8", "-cp", classpath, "-d", str(work),
                            str(ROOT / "tests/dex-services/gesher/services/FixtureServiceContext.java"),
                            str(ROOT / "tests/dex-services/gesher/services/AppOpsContractTest.java"),
                            str(ROOT / "tests/sensors/SensorAccessLifecycleHost.java"),
                            str(ROOT / "tests/sensors/SensorAccessClientHost.java")], check=True)
            cleaner = work / "cleaner/sun/misc"
            cleaner.mkdir(parents=True)
            for source in (args.core_classes / "sun/misc").glob("Cleaner*.class"):
                shutil.copy2(source, cleaner / source.name)
            env = os.environ.copy()
            env["ANDROID_DATA"] = str(work / "data")
            env["GESHER_MICROG_SPOOF"] = "1"
            env.pop("GESHER_BINDER_UID", None)
            for entry in ("com.gesher.server.SensorAccessLifecycleHost", "com.gesher.sensors.SensorAccessClientHost"):
                subprocess.run([java, "-XX:-UsePerfData", "--patch-module", "jdk.unsupported=" + str(work / "cleaner"),
                                "-cp", str(work) + os.pathsep + classpath, entry, str(library)],
                               env=env, check=True, timeout=75)


if __name__ == "__main__":
    main()
