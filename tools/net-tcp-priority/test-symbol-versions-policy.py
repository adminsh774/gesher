#!/usr/bin/env python3
"""Reject unqualified changes to PR103's exact source transition.

All mutations are in-memory inputs. Production files, original OFD/supplier
receipts and approval state are never modified. An updated endpoint digest
alone must not admit extra source, header or include bytes.
"""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "selected_policy", Path(__file__).with_name("test-selected-policy.py"))
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
FOLDER = "tools/net-tcp-priority/qualified/"
MANIFEST = FOLDER + "symbol-versions-source.json"
PATCH = FOLDER + "symbol-versions-source.patch"
read_bytes = Path.read_bytes
manifest = json.loads((ROOT / MANIFEST).read_bytes())
transition_patch = (ROOT / PATCH).read_bytes()
checks = 0


def sha(data):
    return hashlib.sha256(data).hexdigest()


def rejected(label, changes, expected, repinned_manifest=None):
    """Check the intended guard, not merely an arbitrary test failure."""
    global checks
    overrides = {ROOT / name: data for name, data in changes.items()}
    for path, data in overrides.items():
        assert data != read_bytes(path), "negative control made no change: " + str(path)
    pin = policy.SYMBOL_VERSIONS_TRANSITION_SHA256
    if repinned_manifest is not None:
        raw = (json.dumps(repinned_manifest, indent=2, sort_keys=True) + "\n").encode()
        overrides[ROOT / MANIFEST] = raw
        pin = sha(raw)

    def mutated(path):
        return overrides[path] if path in overrides else read_bytes(path)

    with patch.object(Path, "read_bytes", mutated), \
            patch.object(policy, "SYMBOL_VERSIONS_TRANSITION_SHA256", pin), \
            contextlib.redirect_stdout(io.StringIO()):
        try:
            policy.check()
        except AssertionError as error:
            assert expected in str(error), (label, expected, error)
        except subprocess.CalledProcessError as error:
            assert expected == "exact patch application", (label, error)
            assert error.cmd == ["git", "apply", "--reverse", "--unidiff-zero", "--check", "-"], error
            assert error.returncode == 1, error
        else:
            raise AssertionError("source policy accepted " + label)
    checks += 1
    print("PASS symbol-versions source policy rejects " + label)


with contextlib.redirect_stdout(io.StringIO()):
    policy.check()

for name in manifest["paths"]:
    rejected("unqualified bytes in " + name,
             {name: (ROOT / name).read_bytes() + b"\n// unqualified mutation\n"}, name)

# Pinning arbitrary new full-file hashes cannot replace exact reconstruction
# of the original qualified main bytes, including all unchanged regions.
for name in manifest["paths"]:
    mutated = (ROOT / name).read_bytes() + b'\n#include "unqualified_symbol_versions.h"\n'
    changed = copy.deepcopy(manifest)
    changed["after_sha256"][name] = sha(mutated)
    rejected("an undeclared include with a repinned endpoint: " + name,
             {name: mutated}, "symbol-versions reverse endpoint", changed)

for name in policy.SYMBOL_VERSIONS_UNCHANGED_PATHS:
    mutated = (ROOT / name).read_bytes() + b"\n// changed coupled API\n"
    changed = copy.deepcopy(manifest)
    changed["unchanged_sha256"][name] = sha(mutated)
    rejected("repinning an unchanged coupled header: " + name,
             {name: mutated}, name, changed)

extra_path = "compat/posix/src/ofd.cc"
changed = copy.deepcopy(manifest)
changed["paths"].append(extra_path)
rejected("an undeclared transition file", {}, "symbol-versions transition paths changed", changed)
changed = copy.deepcopy(manifest)
changed["protected_paths"].append(extra_path)
rejected("an expanded protected-file list", {}, "symbol-versions protected paths changed", changed)
changed = copy.deepcopy(manifest)
changed["coupled_paths"].append("compat/bionic/abi_types.h")
rejected("an undeclared coupled header", {}, "symbol-versions coupled paths changed", changed)
changed = copy.deepcopy(manifest)
changed["before_sha256"][extra_path] = "0" * 64
rejected("an undeclared endpoint", {}, "symbol-versions endpoint paths changed", changed)
changed = copy.deepcopy(manifest)
changed["native_runtime_approval"] = True
rejected("an added approval field", {}, "symbol-versions transition schema changed", changed)

extra_patch = transition_patch + b"diff --git a/unqualified.cc b/unqualified.cc\n"
changed = copy.deepcopy(manifest)
changed["patch_sha256"] = sha(extra_patch)
rejected("an undeclared patch path with repinned patch and manifest",
         {PATCH: extra_patch}, "symbol-versions patch paths changed", changed)

# A valid removed-line change can reverse successfully but must not alter the
# original main endpoint, even with fresh patch and manifest digests.
line = next(line for line in transition_patch.splitlines(keepends=True)
            if line.startswith(b"-") and not line.startswith(b"---") and line.strip() != b"-")
wrong_before = transition_patch.replace(line, b"-/* unqualified baseline */ " + line[1:], 1)
changed = copy.deepcopy(manifest)
changed["patch_sha256"] = sha(wrong_before)
rejected("a modified before-hunk with repinned patch and manifest",
         {PATCH: wrong_before}, "symbol-versions reverse endpoint", changed)

changed = copy.deepcopy(manifest)
changed["baseline"] = "0" * 40
rejected("a repinned baseline identity", {}, "symbol-versions baseline changed", changed)
changed = copy.deepcopy(manifest)
changed["feature_head"] = "0" * 40
rejected("a repinned feature identity", {}, "symbol-versions feature checkpoint changed", changed)
changed = copy.deepcopy(manifest)
name = manifest["protected_paths"][0]
changed["before_sha256"][name] = "0" * 64
rejected("a repinned baseline source digest", {}, name, changed)
changed = copy.deepcopy(manifest)
changed["baseline_policy_sha256"] = "0" * 64
rejected("a repinned baseline policy", {}, "symbol-versions baseline policy changed", changed)

rejected("changed transition metadata without its pin",
         {MANIFEST: (ROOT / MANIFEST).read_bytes() + b"\n"}, "symbol-versions source transition changed")
rejected("changed transition patch without its pin",
         {PATCH: transition_patch + b"\n"}, "symbol-versions transition patch changed")

# The other 70 original OFD pins remain outside this transition. Their
# original source/header assertions and receipt identity still reject edits.
for name in ("compat/posix/src/ofd.cc", "compat/posix/src/stdio_output.cc",
             "compat/posix/src/fd.cc", "compat/posix/include/gesher/stdio.h"):
    rejected("unrelated protected bytes in " + name,
             {name: (ROOT / name).read_bytes() + b"\n// unqualified mutation\n"}, name)
name = FOLDER + "ofd-stream-source.json"
rejected("a modified original OFD receipt", {name: (ROOT / name).read_bytes() + b"\n"},
         "OFD source qualification changed")
print("SYMBOL_VERSIONS_SOURCE_POLICY_PASS exact_main_round_trip=2 "
      "original_protected_digests=71 negative_controls=" + str(checks))
