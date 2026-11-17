#!/usr/bin/env python3
"""Compile the actual GLES readback layout against an independent pixel oracle.

No AOSP checkout or Windows machine is required. A separate Windows test
exercises the live ImageReader/SurfaceTexture/ANGLE pipeline and its fallback.
"""
import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--sanitize", action="store_true")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="gesher-egl-readback-") as tmp:
        exe = Path(tmp) / ("readback_test.exe" if os.name == "nt" else "readback_test")
        flags = ["-std=c++20", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
                 "-fno-exceptions", "-I", str(ROOT),
                 str(ROOT / "tests/native/egl_readback_layout_test.cc"),
                 "-o", str(exe)]
        if args.sanitize:
            flags += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all",
                      "-fno-omit-frame-pointer"]
        subprocess.run(shlex.split(args.cxx) + flags, check=True, timeout=120)
        env = os.environ.copy()
        if args.sanitize:
            env["UBSAN_OPTIONS"] = "halt_on_error=1:" + env.get("UBSAN_OPTIONS", "")
        return subprocess.run([str(exe)], cwd=ROOT, env=env, timeout=120,
                              check=False).returncode

if __name__ == "__main__":
    raise SystemExit(main())
