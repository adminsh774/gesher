# Versioned libdl source transition

PR103 integrates its symbol-version head
`bcb3d1278d84d29a71d36bb40a01f16e2045b33f` with main
`751d82310f7c4c470c098ccebfd5d2d04699a0cd`. One production file in the original
71-file OFD qualification changes: `compat/bionic/shim.cc`. The other 70 files,
the original `ofd-stream-source.json`, its independently pinned digest,
`ofd-stream-source.patch`, supplier receipts and `native_runtime_approval`
remain unchanged.

Before this transition was constructed, a separate unmodified checkout of
that main commit passed `test-selected-policy.py --selftest`, including its
15 existing mutation controls. The transition supplements that policy; the
original descriptor, socket, stdio, supplier, receipt and workflow assertions
continue to execute.

## Production delta and preserved contracts

The shim now sends all three `dlvsym` arguments to version-aware ELF lookup.
It distinguishes missing symbols, successfully resolved addresses (including
null IFUNC results), and selected TLS objects whose current-thread storage is
unavailable. Builtin fallback applies only to absence. An unavailable TLS
address sets an owned thread-local error; a successful null IFUNC preserves
its result and any pending error. Installation supplies the existing virtual
library recognizer so a symbol callback alone cannot invent a version
provider. Main's environment owner, memory, OS, signal and stdio adapters are
unchanged.

The directly coupled changed header is
`compat/elf/include/gesher/elf_loader.h`: it declares the library-provider
callback, version-aware lookup and explicit lookup status. The existing
`extra_bionic.h`, `bionic_shim.h` and `shim_internal.h` headers are compared
directly with the same main commit. PR103 adds no PR107 classloader namespace,
provider-ownership registry or caller-context API.

The complete loader and ARM changes still require independent scope and
correctness review. A source transition establishes the exact admitted bytes;
it does not establish Android compatibility, Windows execution or approval.

## Exact boundary

`preceding_symbol_versions_sources` verifies a separately pinned manifest,
fixed main and feature identities, exact source/header lists and the original
main policy's digest. It checks each current and original full-file digest,
the exact two patch paths, and the original shim digest in the unchanged OFD
receipt. The patch contains only changed lines, with zero context.

Both files must reverse exactly to the named main bytes and then apply forward
exactly to their current bytes. Only the reconstructed shim bytes enter the
original 71-file digest loop. A new endpoint hash alone cannot admit an extra
include, unrelated change, expanded path list or replacement baseline. The
original POSIX reverse patch and function-level preservation checks run on
their unchanged files. No file is excluded from checking and no function is
replaced by a stub.

The new negative controls mutate only in-memory inputs. They cover both
changed files, endpoint repins, undeclared includes, all three unchanged
headers, expanded path/header/endpoint sets, added approval metadata, patch
path and before-hunk changes despite repinning, changed baseline/feature and
policy identities, unrelated protected files and the original OFD receipt.

```sh
python3 tools/net-tcp-priority/test-selected-policy.py --selftest
python3 tools/net-tcp-priority/test-symbol-versions-policy.py
python3 tools/net-tcp-priority/test-vector-policy.py
python3 tools/net-tcp-priority/test-selected-supplier.py
python3 tools/host-pthread-authority/minimum_receipt.py selftest
```

Behavior validation is separate: full-production ELF symbol versions,
null-IFUNC dispatch, selected unavailable TLS, atomic rollback, packed
relocations, current-main lookup order/allocation bounds, ARM TLS and dispatch
fixtures, and full Windows target compilation/linking. The existing Windows
tracks 40 and 47, including actual versioned DSOs and guest `dlvsym`, remain
required runtime gates. Host boundary adapters and cross compilation do not
establish Windows or ART runtime execution.
