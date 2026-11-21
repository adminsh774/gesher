#!/usr/bin/env python3
"""Extract exact Track50 functions/first manual-device section with reversible ownership observations."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
path = "tests/windows/tracks/50-services.ps1"
blob = subprocess.check_output(["git", "hash-object", path], cwd=ROOT, text=True).strip()
if blob != "24940cd78943c31d195ddb998fe5a538bb04aa15":
    raise RuntimeError("Track50 historical source changed")
source = (ROOT / path).read_text(encoding="utf-8-sig")
functions = source[source.index("function RunTimed("):source.index("# The driver's ABI")]
ordered = source[source.index("# Passes when every line"):source.index("# system_server-lite:")]
prefix = source[source.index("# system_server-lite:"):source.index("# The rest of the app model")]
observed = prefix
additions = []
def observe(anchor, line):
    global observed
    if observed.count(anchor) != 1:
        raise AssertionError("Ambiguous ownership observation anchor")
    addition = "\n# DIAGNOSTIC-OBSERVE-BEGIN\n" + line + "\n# DIAGNOSTIC-OBSERVE-END"
    observed = observed.replace(anchor, anchor + addition, 1)
    additions.append(addition)
observe('  -RedirectStandardOutput "$logs/apps-binderd.out" -RedirectStandardError "$logs/apps-binderd.log"',
        "Observe-Owned $binderd 'binderd'")
observe('$servicemanager = Start-ReadyServiceManager $binderd $device "$logs/apps-servicemanager" "$logs/apps-binderd.log"',
        "Observe-Owned $servicemanager 'servicemanager'")
observe('  -RedirectStandardOutput "$logs/system_server.out" -RedirectStandardError "$logs/system_server.err"',
        "Observe-Owned $server 'system_server'")
observe("Write-Host $settingsSetup",
        "Write-Phase 'settings-complete'\nif ($Case -eq 'Injected') { throw 'PR213_AFTER_REAL_SETTINGS_FAILURE' }")
inverse = observed
for addition in reversed(additions):
    inverse = inverse.replace(addition, "", 1)
assert inverse == prefix
(out / "functions.ps1").write_text(functions + ordered, encoding="utf-8", newline="\n")
(out / "prefix.ps1").write_text(observed, encoding="utf-8", newline="\n")
receipt = dict(track_blob=blob, track_sha256=hashlib.sha256((ROOT/path).read_bytes()).hexdigest(),
    functions_sha256=hashlib.sha256((functions+ordered).encode()).hexdigest(),
    original_prefix_sha256=hashlib.sha256(prefix.encode()).hexdigest(),
    observed_prefix_sha256=hashlib.sha256(observed.encode()).hexdigest(),
    reversible_insertions=len(additions), inverse_exact=True,
    scope="Exact Track50 manual-device section through real Settings setup and lifecycle CAMERA finally; original assertions and time bounds retained. Later Track50 groups are not run by this diagnostic.")
(out / "track-extraction.json").write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps(receipt), flush=True)
