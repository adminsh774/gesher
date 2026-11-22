# Native loader composition with the corrected main249 baseline

This transition composes PR107 head
`5779008c800a3f5e9caedf1a1e4c0cdc584ed1eb` with the durable corrected baseline
`f8800806ffd3a52c3e28256c2d6dfd5129ef8e2e`, tree
`a083bde2afa491a2ebbc16cfe068b4119d63023b`. Its ordered parents are main
`20252fde18db387b4d99d99f7050d6336f58bf6e` and the native-fence receive-flag
correction `f6307ad47ba16d838770f100675e33f2eb84ed7a` (PR296).

The original namespace manifest and patch, `native-loader-source.json` and
`native-loader-source.patch`, retain their exact reviewed bytes. The policy
pins both as historical records. Their old stdio endpoint predates main249's
metadata cache and is not used to qualify the new composition. The separate
`native-loader-main296-source.json` and `.patch` name the actual current
baseline and the complete current endpoints. This is a new production and
qualification review boundary, not a refresh of the original OFD receipt.

## Production changes at the new boundary

The admitted paths remain exactly the same four implementations and five
directly coupled headers as the historical namespace transition:

| Source | PR107 behavior | Corrected baseline preserved |
| --- | --- | --- |
| `compat/bionic/stdio_bionic.cc` | Split existing liblog exports into `RegisterLog`; libc and liblog keep their real owners. | All stream implementation, including the positive thread-local cache, bucket generation, close/reopen invalidation and saturation behavior. |
| `compat/bionic/extra_bionic.cc` | Actual libc/libm/libdl/private-linker providers and contextual guest libdl dispatch. | Existing OS, property, stdio and math adapters. |
| `compat/bionic/shim.cc` | Owned provider lookup and namespace/version/caller context, with internal-only names excluded from public exports. | Main's exact memmem helper and substitutions, shared environment owner and all other adapters. |
| `compat/elf/exec/gesher_exec.cc` | Existing executable overrides registered to their actual libc provider. | Process initialization, descriptor transport and the shared environment slot. |

The five coupled headers and the unchanged shared symbol-table header retain
their explicit fixed path lists. The stdio delta against this baseline is only
the `RegisterLog` split. The new manifest's full-file endpoints also bind the
unchanged main cache bytes. Other composed production changes outside this
qualification boundary still require integration review: atomic trampoline
publication with caller/TLS context, late graphics/libandroid registrations,
real JNI/ART target ownership, and package-generation invalidation with native
dependency admission. A byte round trip does not establish those contracts.

## Normalization and authority

1. Pin both historical records, the new manifest, the published baseline,
   feature head, baseline policy digest, exact path sets and exact patch digest.
2. Compare all nine current files with their complete endpoint digests and all
   nine before files with the actual named baseline commit. Reject empty deltas.
3. Reverse the namespace patch exactly to those nine baseline files; apply it
   forward and require exact equality with all nine current files.
4. Apply the unchanged memmem normalizer to the restored baseline shim. The
   four restored protected sources must match the unchanged 71-entry OFD map.
5. Independently run the unchanged three-file fence receive transition. Its
   path set must be disjoint from the namespace source outputs. The existing
   OFD reverse patch, function-preservation checks and supplier gates then run.

The corrected baseline's OFD manifest, patch, 71 paths and digests, reviewed
checkpoint, supplier authority and approval state remain unchanged.
`native_runtime_approval` remains `false`. The separate known socket
receive-flag defect is not included or declared fixed. Generic native-fence
startup/export remains explicitly unsupported; this composition adds no such
capability. The existing PR296 Windows tests still use two SCM endpoints in
one client process and a separate broker.

## Targeted evidence

Run `test-selected-policy.py --selftest`, `test-native-loader-policy.py`,
`test-vector-policy.py`, `test-selected-supplier.py` and the minimum-supplier
receipt selftest. Namespace controls reject edits to every admitted file,
repinned endpoint/include/ownership changes, missing memmem or namespaces,
lost stdio cache or close/reopen invalidation, an unchanged coupled header,
expanded paths, tampered patch/baseline/feature/policy pins, rewritten historical
records and unrelated protected sources. The unchanged fence and OFD controls
run alongside them.

Behavior checks exercise actual namespace/provider registration, zero-allocation
lookup, main's metadata publication and stdio lifetime, current Java package
generation and native admission, and real affected Windows target links.
Host fixtures declare their Win32/JVM/address adapters; local PE linking does
not establish Windows execution, ART lifecycle, graphics capability or the
fresh candidate's default/WARP Windows runtime gates. Independent integration
review and fresh candidate CI remain required before publication acceptance.
