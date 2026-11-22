#!/usr/bin/env python3
"""Exercise the production NDK sensor bridge against controlled platform edges.

The linked sensors_ndk.cc is unchanged. The host fixture substitutes Windows
sensor readings and Android Looper/policy transport, while keeping real eventfd
readiness, exported System V calls, queue lifetime and ASensorEvent packing.
It does not claim Windows/ART, AArch64 translation or physical-sensor coverage.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--source-ref", help="Compile the earlier libandroid sensor bodies as an inventory negative control")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    args = parser.parse_args()
    sources = [ROOT / "compat/bionic/sensors_ndk.cc", ROOT / "tests/devices/ndk-sensors/host_test.cc"]
    inputs = sources + [ROOT / "compat/bionic/sensors_ndk.h", ROOT / "compat/devices/sensor_access.h",
                        ROOT / "compat/devices/sensors.h", ROOT / "tests/devices/ndk-sensors/host/utils/Looper.h"]
    digest = hashlib.sha256(b"".join(path.read_bytes() for path in inputs)).hexdigest()
    print("NDK sensor bridge fixture input SHA256", digest, flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-ndk-sensors-") as temporary:
        executable = Path(temporary) / "sensor-contracts"
        if args.source_ref:
            previous = subprocess.check_output(["git", "show", args.source_ref + ":compat/bionic/libandroid.cc"],
                                               cwd=ROOT, text=True)
            body_start = previous.index("// --- ASensorManager: no sensors")
            bodies = previous[body_start:previous.index("// --- ATrace:", body_start)]
            entries_start = previous.index("      // Sensors.")
            entries = previous[entries_start:previous.index("      // Tracing.", entries_start)]
            old_source = Path(temporary) / "previous-sensor-provider.cc"
            old_source.write_text('#include "abi_types.h"\n#include "sensors_ndk.h"\n'
                '#include <gesher/bionic_shim.h>\n'
                'namespace gesher::bionic::sensors_ndk { namespace {\n' + bodies + '\n}\n'
                'void Register() { struct Entry {const char* name;void* fn;};\n'
                '#define E(name, fn) {name, reinterpret_cast<void*>(&fn)}\n'
                'const Entry entries[] = {\n' + entries + '\n};\n'
                '#undef E\nfor (const auto& entry : entries) AddBionicSymbol(entry.name, entry.fn);}\n}\n')
            sources[0] = old_source
            print("Negative-control production source", args.source_ref,
                  hashlib.sha256(previous.encode()).hexdigest(), flush=True)
        command = shlex.split(args.cxx) + ["-std=c++20", "-O1", "-g", "-Wall", "-Wextra", "-Werror", "-pthread",
            "-I" + str(ROOT / "tests/devices/ndk-sensors/host"), "-I" + str(ROOT / "compat/bionic/include"),
            "-I" + str(ROOT / "compat/bionic"), "-I" + str(ROOT / "compat/devices")]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all", "-fno-omit-frame-pointer"]
        subprocess.run(command + [str(path) for path in sources] + ["-o", str(executable)], check=True, timeout=120)
        if args.source_ref:
            return subprocess.run([str(executable), "--inventory-only"], timeout=15).returncode
        subprocess.run([str(executable)], check=True, timeout=45)
        subprocess.run([str(executable), "--sensorless"], check=True, timeout=15)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
