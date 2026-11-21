#!/usr/bin/env python3
"""Observation only: retain every original helper line and prove reversible insertion."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
pins = {
    "tests/windows/clipboard/ForeignForeground.ps1": "357439b14a3cbbb2cadbb222fce3d33e9810eea5",
    "tests/windows/tracks/50-services.ps1": "24940cd78943c31d195ddb998fe5a538bb04aa15",
}
for path, expected in pins.items():
    actual = subprocess.check_output(["git", "hash-object", path], cwd=root, text=True).strip()
    if actual != expected:
        raise SystemExit(f"Historical measurement source changed: {path}: {actual}")
source = (root / next(iter(pins))).read_text(encoding="utf-8")
observed = source
insertions = []
def add_after(anchor, lines, comment="#"):
    global observed
    if observed.count(anchor) != 1:
        raise AssertionError(f"Nonunique trace anchor: {anchor!r}")
    addition = "\n" + comment + " DIAGNOSTIC-TRACE-BEGIN\n" + lines + "\n" + comment + " DIAGNOSTIC-TRACE-END"
    observed = observed.replace(anchor, anchor + addition, 1)
    insertions.append(addition)
add_after("$ErrorActionPreference = 'Stop'", r"""
function Write-Phase([string] $Phase) {
  [IO.File]::AppendAllText($Receipt + '.phases.log',
    ([DateTime]::UtcNow.ToString('o') + ' pid=' + $PID + ' phase=' + $Phase + [Environment]::NewLine))
}
Write-Phase 'powershell-enter'
Write-Phase 'compile-before'
""".strip())
add_after("public static class ClipboardForegroundWindow {", r"""
  static void Trace(string receipt, string phase) {
    File.AppendAllText(receipt + ".phases.log", DateTime.UtcNow.ToString("o")
      + " pid=" + GetCurrentProcessId() + " phase=" + phase + Environment.NewLine);
  }
""".rstrip(), "//")
add_after("  public static void Run(string receipt, string stop) {",
          '    Trace(receipt, "run-enter");', "//")
add_after("    if (RegisterClassW(ref cls) == 0) throw new InvalidOperationException(\"RegisterClassW failed\");",
          '    Trace(receipt, "class-registered");', "//")
add_after("      // Initially hidden: creation must not disturb the application's earlier input.",
          '      Trace(receipt, "window-create-before");', "//")
add_after("      if (window == IntPtr.Zero) throw new InvalidOperationException(\"CreateWindowExW failed\");",
          '      Trace(receipt, "window-created");', "//")
add_after("      if (!GetProcessTimes(GetCurrentProcess(), out created, out exited, out kernel, out user)) throw new InvalidOperationException(\"GetProcessTimes failed\");",
          '      Trace(receipt, "generation-read");', "//")
add_after("      File.WriteAllText(receipt + \".new\", json); File.Move(receipt + \".new\", receipt);",
          '      Trace(receipt, "receipt-published");', "//")
add_after("    } finally {",
          '      Trace(receipt, "retire-enter stop=" + File.Exists(stop));', "//")
add_after("      if (window != IntPtr.Zero && IsWindow(window) && !DestroyWindow(window)) throw new InvalidOperationException(\"DestroyWindow failed\");",
          '      Trace(receipt, "window-destroyed");', "//")
add_after("      if (!UnregisterClassW(name, instance)) throw new InvalidOperationException(\"UnregisterClassW failed\");",
          '      Trace(receipt, "class-unregistered");', "//")
add_after("'@", "Write-Phase 'compile-after'")
add_after("[ClipboardForegroundWindow]::Run($Receipt, $Stop)", "Write-Phase 'powershell-return'")
stripped = observed
for addition in reversed(insertions):
    if stripped.count(addition) != 1:
        raise AssertionError("Trace inverse is ambiguous")
    stripped = stripped.replace(addition, "", 1)
assert stripped == source
original = out / "ForeignForeground-original.ps1"
instrumented = out / "ForeignForeground-observed.ps1"
original.write_text(source, encoding="utf-8", newline="\n")
instrumented.write_text(observed, encoding="utf-8", newline="\n")
receipt = {
    "historical_source": "9eec2089061fb5eb0586cd59da16d56ed7e1dfd2",
    "git_pins": pins,
    "original_sha256": hashlib.sha256(source.encode()).hexdigest(),
    "observed_sha256": hashlib.sha256(observed.encode()).hexdigest(),
    "trace_inverse_byte_identical": stripped.encode() == source.encode(),
    "insertions": len(insertions),
    "checkout": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
    "tree": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=root, text=True).strip(),
    "scope": "Native helper phases only; no Android read-admission or original ART teardown claim.",
}
(out / "source-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps(receipt))
