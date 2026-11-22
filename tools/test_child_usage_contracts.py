#!/usr/bin/env python3
"""Production direct-child CPU ledger, Bionic adapters and Java reaper contracts.

Linux uses an isolated Win32 boundary for deterministic faults, precision and
generation/concurrency controls. Windows builds the same production pieces and
an independently linked peer DLL, then measures real CPU-burning processes.
--source-root substitutes the adapters/reapers for a baseline negative control;
the new owner component still comes from this checkout so the old zero/ignored
output behavior is exercised, rather than failing because its file was absent.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def between(source, begin, end):
    if source.count(begin) != 1 or source.count(end) != 1:
        raise ValueError("ambiguous production boundary: " + begin)
    start = source.index(begin)
    return source[start:source.index(end, start)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "cl" if os.name == "nt" else "c++"))
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--build-only", type=Path, help="keep cross-compiled Windows artifacts; do not execute")
    args = parser.parse_args()
    sources = {name: (args.source_root / name).read_text() for name in (
        "compat/bionic/process_bionic.cc", "compat/bionic/extra_sys_bionic.cc",
        "compat/libcore/process.cc", "compat/elf/exec/exec_spawn.cc", "compat/posix/src/process.cc")}
    native, resource, java, spawn, posix = sources.values()
    has_usage = "gesher::child_usage::Collect" in java
    windows = os.name == "nt" or args.build_only is not None
    # Old resource structs were private to extra_sys; use precisely those for
    # the baseline and the shared production header for the candidate.
    resource_body = between(resource, "int64_t FileTime100ns(", "using UtimensatFn =")
    shared_resource = '#include "resource_usage.h"' if "struct LinuxRusage" not in resource_body else ""
    pieces = {
        "@RESOURCE_HEADER@": shared_resource,
        "@RESOURCE_TYPES@": "" if shared_resource else between(resource_body, "struct LinuxTimeval", "LinuxTimeval Tv"),
        "@NATIVE_CHILDREN@": between(native, "// --- the shim's children", "// --- the environment and working directory"),
        "@NATIVE_WAIT@": between(native, "constexpr int kWNOHANG =", "// --- system / popen"),
        "@RESOURCE_QUERIES@": resource_body,
        "@JAVA_CHILDREN@": between(java, "std::mutex g_mutex;", "// The program being started"),
        "@JAVA_WAIT@": between(java, "jint JNICALL WaitForProcessExit(", "// Process.destroy():"),
        "@EXIT_STATUS@": between(spawn, "int ExitStatus(DWORD code)", "}  // namespace gesher::exec"),
        "@POSIX_QUERY@": between(posix, "int getrusage(int who", '}  // extern "C"'),
        "@POSIX_TYPES@": between((args.source_root / "compat/posix/include/sys/resource.h").read_text(), "struct rusage {", "#define PRIO_PROCESS"),
        "@JAVA_INSERT@": ("gesher::child_usage::Token token = 0; Require(gesher::child_usage::Register(h, &token) == 0, \"register Java child\"); g_children[pid] = std::make_shared<ChildProcess>(h, token);"
                          if has_usage else "g_children[pid] = h;"),
        "@HAS_USAGE@": "1" if has_usage else "0",
        "@HOST_ONLY@": "0" if windows else "1",
        "@HOST_ONLY_REPEAT@": "0" if windows else "1",
        "@GENERATION_CONTROL@": '''
  worker = Spawn(0); p = static_cast<boundary::Process*>(worker.process); pid = static_cast<int>(p->pid);
  guest::Track(pid, Retain(worker.process));
  auto* stale = new guest::ChildWatch{pid, guest::g_children.at(pid).usage_token - 1};
  stale->references = 1;
  guest::OnChildExit(stale, 0);
  Require(!guest::g_children.at(pid).exited, "stale callback cannot mark the replacement PID exited");
  Finish(worker); Require(guest::Waitpid(pid, nullptr, 0) == pid, "replacement generation remains normally reapable");
  CloseHandle(worker.process);

  worker = Spawn(0); pid = static_cast<int>(GetProcessId(worker.process));
  HANDLE borrowed = Retain(worker.process); boundary::fail_watch = true;
  Require(guest::Track(pid, borrowed) == ERROR_NOT_ENOUGH_MEMORY && guest::g_children.count(pid) == 0,
          "callback-registration failure removes native record");
  boundary::fail_watch = false;
  Require(cu::Register(worker.process, &token) == 0 && cu::Forget(token) == 0,
          "callback-registration failure rolls back provider identity and retained handle");
  CloseHandle(borrowed); Finish(worker); CloseHandle(worker.process);
''' if has_usage else "",
    }
    # Avoid declaring the baseline ABI twice: it needs to be visible to native
    # Wait4 and remains present verbatim in the resource-query section.
    if not shared_resource:
        pieces["@RESOURCE_QUERIES@"] = resource_body.replace(pieces["@RESOURCE_TYPES@"], "")
    source = (ROOT / "tests/exec/child_usage_contracts.cc.in").read_text()
    for marker, body in pieces.items():
        if source.count(marker) != 1:
            raise ValueError("ambiguous fixture marker: " + marker)
        source = source.replace(marker, body)
    for name, body in sources.items():
        print(name, hashlib.sha256(body.encode()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix="gesher-child-usage-") as temporary:
        folder = args.build_only.resolve() if args.build_only else Path(temporary)
        folder.mkdir(parents=True, exist_ok=True)
        include = folder / "include"
        (include / "gesher").mkdir(parents=True, exist_ok=True)
        for src, dst in ((ROOT / "compat/posix/include/gesher/child_usage.h", include / "gesher/child_usage.h"),
                         (ROOT / "compat/bionic/resource_usage.h", include / "resource_usage.h")):
            shutil.copyfile(src, dst)
        if not windows:
            shutil.copyfile(ROOT / "tests/exec/child_usage_win32_fixture.h", include / "windows.h")
        unit = folder / "child_usage_contracts.cc"
        unit.write_text(source)
        output = folder / ("child-usage.exe" if windows else "child-usage")
        provider = ROOT / "compat/posix/src/child_usage.cc"
        compiler = shlex.split(args.cxx)
        if os.name == "nt":
            common = compiler + ["/nologo", "/std:c++20", "/EHsc", "/W4", "/WX", "/D_WIN32_WINNT=0x0A00", "/DNOMINMAX", "/D_CRT_SECURE_NO_WARNINGS", "/I" + str(include)]
            command = common + [str(unit), str(provider), "/Fe:" + str(output), "psapi.lib"]
        else:
            common = compiler + ["-std=c++20", "-O1", "-g", "-Wall", "-Wextra", "-Werror", "-pthread", "-I", str(include)]
            command = common + [str(unit), str(provider), "-o", str(output)]
            command += ["-D_WIN32_WINNT=0x0A00", "-DNOMINMAX", "-lpsapi"] if windows else ["-rdynamic", "-ldl"]
            if args.sanitize:
                command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-fno-sanitize-recover=all"]
        subprocess.run(command, cwd=folder, check=True, timeout=120)
        if windows:
            peer = folder / "peer.cc"
            peer.write_text('#include <gesher/child_usage.h>\nextern "C" __declspec(dllexport) DWORD peer_snapshot(gesher::child_usage::Cpu* cpu) { return gesher::child_usage::Snapshot(cpu); }\nextern "C" __declspec(dllexport) DWORD peer_register(HANDLE h, gesher::child_usage::Token* token) { return gesher::child_usage::Register(h, token); }\n')
            if os.name == "nt":
                command = common + ["/LD", str(peer), str(provider), "/Fe:" + str(folder / "child-usage-peer.dll")]
            else:
                command = common + ["-shared", str(peer), str(provider), "-o", str(folder / "child-usage-peer.dll")]
            subprocess.run(command, cwd=folder, check=True, timeout=120)
        if args.build_only:
            print("Windows cross-build only; no execution:", folder, flush=True)
            return 0
        failed = False
        for case in (["normal"] if windows else ["normal", "native-sample-failure", "wait4-sample-failure", "java-sample-failure", "overflow"]):
            result = subprocess.run([str(output), case], cwd=folder, text=True, capture_output=True, timeout=50)
            print(case + ": " + result.stdout, end="", flush=True)
            if result.returncode:
                failed = True
                print(result.stderr[:16000], end="", flush=True)
        return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
