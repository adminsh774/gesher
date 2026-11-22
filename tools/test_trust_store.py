#!/usr/bin/env python3
"""Exercise complete trust_store.cc and javacrypto.cc with pinned BoringSSL.

Linux has declared POSIX/CryptoAPI adapters. Windows uses real kernel files and
named mutexes, and real CryptoAPI memory stores containing isolated test inputs.
No test certificate is installed in a user's or machine's system trust store.
--source-ref builds the exact earlier production files with this same fixture;
use --measure-only for the old void-returning, pre-failure-contract baseline.
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


def run(command: list[str], **kwargs) -> None:
    subprocess.run(command, check=True, timeout=600, **kwargs)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "c++"))
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--boringssl-source", type=Path, default=ROOT / "third_party/aosp/external/boringssl")
    parser.add_argument("--crypto-library", type=Path)
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--source-ref")
    parser.add_argument("--measure-only", action="store_true")
    parser.add_argument("--corpus", type=Path, help="also verify at least 100 real Android bundled roots")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--compile-only", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.compile_only and not args.output:
        parser.error("--compile-only requires --output")
    compiler = shlex.split(args.cxx)
    windows = os.name == "nt" or "mingw" in compiler[0] or bool(args.output and args.output.suffix == ".exe")
    if windows and not args.crypto_library:
        parser.error("Windows compilation requires --crypto-library from the actual Windows build")
    with tempfile.TemporaryDirectory(prefix="gesher-trust-store-") as temporary:
        work = Path(temporary)
        source = args.boringssl_source.resolve()
        if not (source / "src/include/openssl/bytestring.h").exists():
            if not args.fetch:
                parser.error("pinned BoringSSL is absent; pass --boringssl-source or --fetch")
            source = work / "boringssl"
            tag = next(line.split()[1] for line in (ROOT / "aosp.lock").read_text().splitlines() if line.startswith("tag "))
            run(["git", "-c", "core.autocrlf=false", "clone", "--depth", "1", "--branch", tag,
                 "https://android.googlesource.com/platform/external/boringssl", str(source)])
        library = args.crypto_library.resolve() if args.crypto_library else work / "crypto/libcrypto.a"
        if not args.crypto_library:
            run([args.cmake, "-S", str(source / "src"), "-B", str(work / "crypto"),
                 "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_TESTING=OFF", "-DOPENSSL_NO_ASM=1"])
            run([args.cmake, "--build", str(work / "crypto"), "--target", "crypto", "--parallel", "4"])
        print("BORINGSSL source=" + subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip(), flush=True)
        for name in ("trust_store.cc", "trust_store.h", "javacrypto.cc"):
            path = "compat/net/" + name
            content = (subprocess.check_output(["git", "show", f"{args.source_ref}:{path}"], cwd=ROOT)
                       if args.source_ref else (ROOT / path).read_bytes())
            (work / name).write_bytes(content)
            print(f"SOURCE {path} sha256={hashlib.sha256(content).hexdigest()}", flush=True)
        revised = "bool SyncSystemCaCerts" in (work / "trust_store.h").read_text()
        flags = ["-std=c++20", "-O2", "-g", "-Wall", "-Wextra", "-Werror", "-pthread",
                 "-Wno-deprecated-declarations", "-I", str(work),
                 "-I", str(ROOT / "tests/trust-store/include"), "-I", str(source / "src/include"),
                 f"-DTRUST_SYNC_RETURNS_BOOL={int(revised)}"]
        if windows:
            flags += ["-D_WIN32_WINNT=0x0A00", "-static"]
        else:
            # Win32 DWORD is 32 bits while Linux unsigned long is 64 bits. The
            # unchanged Windows baseline's %lu diagnostics are not host ABI.
            flags += ["-I", str(ROOT / "tests/trust-store/host"), "-Wno-format"]
        if args.sanitize:
            flags += ["-fsanitize=address,undefined", "-fno-sanitize-recover=all", "-fno-omit-frame-pointer"]
        units = [work / "trust_store.cc", work / "javacrypto.cc", ROOT / "tests/trust-store/contracts.cc",
                 ROOT / "tests/trust-store/boundary.cc"]
        if not windows:
            units.append(ROOT / "tests/trust-store/host_boundary.cc")
        objects = []
        for i, unit in enumerate(units):
            obj = work / f"source{i}.o"
            observe = ["-include", str(ROOT / "tests/trust-store/observe.h")] if unit.name == "trust_store.cc" else []
            run(compiler + flags + observe + ["-c", str(unit), "-o", str(obj)])
            objects.append(str(obj))
        output = args.output.resolve() if args.output else work / ("test.exe" if windows else "test")
        output.parent.mkdir(parents=True, exist_ok=True)
        run(compiler + flags + objects + [str(library)] + (["-lcrypt32", "-lws2_32"] if windows else []) + ["-o", str(output)])
        if args.compile_only:
            print(f"Compiled trust-store contracts: {output}", flush=True)
            return 0
        arguments = ["--measure-only"] if args.measure_only else (["--corpus", str(args.corpus.resolve())] if args.corpus else [])
        return subprocess.run([str(output)] + arguments, timeout=240).returncode


if __name__ == "__main__":
    raise SystemExit(main())
