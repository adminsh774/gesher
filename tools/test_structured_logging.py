#!/usr/bin/env python3
"""Run complete production log cache/assembly against declared host or real Windows boundaries.

Windows also compiles actual system_properties.cc, tests mapped generations and
area switches across processes, and observes real file operations. --source-ref
compiles the exact baseline sources/headers with this same fixture. Its raw
timings are observations, not a noisy speed-ratio gate. Host mode cannot measure
Windows file I/O, process memory or the mapped-area implementation.
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
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--source-ref")
    parser.add_argument("--measure-only", action="store_true")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--compile-only", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.compile_only and args.output is None:
        parser.error("--compile-only requires --output")
    compiler = shlex.split(args.cxx)
    windows = os.name == "nt" or "mingw" in compiler[0] or bool(args.output and args.output.suffix.lower() == ".exe")

    def read(path: str) -> str:
        if args.source_ref:
            return subprocess.check_output(["git", "show", f"{args.source_ref}:{path}"], cwd=ROOT, text=True)
        return (ROOT / path).read_text(encoding="utf-8")

    with tempfile.TemporaryDirectory(prefix="gesher-structured-logging-") as temporary:
        work = Path(temporary)
        (work / "include/gesher").mkdir(parents=True)
        for name in ("logging", "system_properties"):
            for source, destination in ((f"compat/posix/src/{name}.cc", work / f"{name}.cc"),
                                        (f"compat/posix/include/gesher/{name}.h", work / f"include/gesher/{name}.h")):
                content = read(source)
                destination.write_text(content, encoding="utf-8")
                print(f"SOURCE {source} sha256={hashlib.sha256(content.encode()).hexdigest()}", flush=True)
        revised = "struct Revision" in (work / "include/gesher/system_properties.h").read_text()
        flags = ["-std=c++20", "-O2", "-g", "-Wall", "-Wextra", "-Werror", "-fno-exceptions", "-pthread",
                 "-I", str(work / "include"), f"-DLOGGING_HAS_REVISION={int(revised)}"]
        if windows:
            flags += ["-D_WIN32_WINNT=0x0A00", "-static"]
        else:
            flags += ["-I", str(ROOT / "tests/logging/host")]
        if args.sanitize:
            flags += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all", "-fno-omit-frame-pointer"]
        objects = []
        sources = [work / "logging.cc", ROOT / "tests/logging/contracts.cc"]
        sources.append(work / "system_properties.cc" if windows else ROOT / "tests/logging/host_boundary.cc")
        for i, source in enumerate(sources):
            obj = work / f"source{i}.o"
            extra = ["-include", str(ROOT / "tests/logging/windows_calls.h")] if windows and source.parent == work else []
            subprocess.run(compiler + flags + extra + ["-c", str(source), "-o", str(obj)], check=True, timeout=120)
            objects.append(str(obj))
        output = args.output.resolve() if args.output else work / ("test.exe" if windows else "test")
        output.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(compiler + flags + objects + (["-lpsapi"] if windows else []) + ["-o", str(output)], check=True, timeout=120)
        if args.compile_only:
            print(f"Compiled real Windows structured logger contracts: {output}", flush=True)
            return 0
        environment = os.environ.copy()
        if os.name == "nt" and Path(compiler[0]).is_absolute():
            environment["PATH"] = str(Path(compiler[0]).parent) + os.pathsep + environment.get("PATH", "")
        return subprocess.run([str(output)] + (["--measure-only"] if args.measure_only else []),
                              env=environment, timeout=180).returncode


if __name__ == "__main__":
    raise SystemExit(main())
