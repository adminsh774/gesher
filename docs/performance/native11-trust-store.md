# NATIVE-11: verified certificate encoding reuse

This is a partial implementation and measurement experiment for
[Issue #178, NATIVE-11](https://github.com/gesher-runtime/gesher/issues/178).
It removes repeated full-X509/PEM conversions for unchanged admitted
certificates. It **does not complete NATIVE-11**: source enumeration still
happens on every load, there is no lifetime owner for change notifications,
and Windows startup/first-TLS performance acceptance remains open. The initial
PR is intended to remain **draft** pending paired Windows measurements and
independent correctness review.

The original source baseline is
`a6be01164ff09de1a87382aad864a6253264b77b`. Its `trust_store.cc` SHA-256 is
`fb686a33c2285e876b270e2194d07b3e3cc1e75dd9181a357781f3d5eabd7e74`.
Both comparison executables compile the exact production `trust_store.cc`,
`trust_store.h` and `javacrypto.cc`; the source runner prints each content hash.
There is no handwritten slow baseline.

## Contract and implementation

Android's
[TrustedCertificateStore](https://android.googlesource.com/platform/external/conscrypt/+/refs/tags/android-16.0.0_r4/platform/src/main/java/org/conscrypt/TrustedCertificateStore.java)
uses the old OpenSSL subject-name hash and consecutive collision indices.
The framework's system certificate reader and native by-directory readers
consume the same `<hash>.<n>` files. This change preserves those filenames,
the Android/Windows root union, property-only server-auth EKU filtering,
self-issued CA-store selection, Disallowed subtraction and
`GESHER_WINDOWS_CERTS=0` behavior. It does not change chain verification,
certificate signatures, hostname checks, SNI, application network security
configuration or the Android user CA directory.

Synchronization now performs these operations:

1. Acquire the existing `Local\\gesher-system-cacerts` mutex before scanning.
   A 30-second timeout or failed acquisition is an error; an abandoned mutex
   is acquired and the directory is reconstructed from fresh inputs. Handles
   are scoped to this call. This serializes cooperating writers in the same
   Windows logon session, including their source scans.
2. Freshly read bundled certificates and the applicable user/machine logical
   Windows stores. Read EKU restrictions and both Disallowed stores again.
   No cached SID, environment value, registry timestamp, TTL or manifest flag
   substitutes for an authoritative source read.
3. Treat the old manifest only as a bounded, untrusted lookup index. Before
   reusing a file, decode exactly one PEM certificate and compare its DER
   byte-for-byte with the currently admitted source. An unrelated certificate,
   trailing certificate, invalid PEM or forged digest line cannot supply trust.
4. Recompute the subject hash from the source DER using BoringSSL's bounded
   CBS ASN.1 reader. BoringSSL's
   [old hash](https://android.googlesource.com/platform/external/boringssl/+/refs/tags/android-16.0.0_r4/src/crypto/x509/x509_cmp.cc)
   hashes the original encoded subject; its
   [name decoder](https://android.googlesource.com/platform/external/boringssl/+/refs/tags/android-16.0.0_r4/src/crypto/x509/x_name.cc)
   retains those bytes. MD5 here is Android's lookup-name algorithm, not the
   trust fingerprint. SHA-256 and byte equality identify certificate content.
   An unsupported fast-path encoding or wrong cached hash falls back to full
   X509 conversion. Collision indices are always regenerated in digest order;
   a manifest cannot choose a hole or wrong output index.
5. Convert only cache misses. A per-call bounded memo retains the PEM and its
   decoded DER so verification and publication do not reread/redecode the same
   cached file. There is no process-lifetime file handle, certificate store
   handle, thread, background task or queue.
6. Remove stale/unadmitted outputs before adding replacements, even when a
   revoked certificate occupies the hash/index needed by a new root. Write
   complete temporary files, close them, rename each over its destination,
   and replace the manifest last. The manifest is compared only after current
   inputs and output contents have been checked.

This is **per-file atomic publication**, not an all-or-nothing directory swap
or a new disk-durability promise. No per-certificate flush was added. A crash
or failed update can leave a partial set; the next call revalidates and repairs
it. `JNI_OnLoad` returns `JNI_ERR` on a source/publication failure before
calling Conscrypt's registration function. This prevents that new library
load from relying on stale output. It does not revoke trust already retained
by existing TLS objects, and it does not isolate other readers during the
multi-file update. Native TLS clients that have not loaded libjavacrypto retain
the existing behavior described in [network.md](../network.md).

### Failure and optional-store handling

| Condition | Behavior |
| --- | --- |
| Optional system store absent (`ERROR_FILE_NOT_FOUND`, `ERROR_PATH_NOT_FOUND`, `CRYPT_E_NOT_FOUND`) | Empty source; no fabricated roots and no JNI failure merely for absence. |
| Normal enumeration end (`CRYPT_E_NOT_FOUND`, or an external provider's `ERROR_NO_MORE_FILES`) | Successful complete enumeration. |
| Access denied, unsupported source provider, partial enumeration or unreadable EKU property | Report the operation/error and fail this JNI load. These are not empty stores. |
| Missing, invalid, oversized or forged optional cache | Rebuild from fresh sources. |
| Short/failed write, rename, stale-file deletion or manifest publication failure | Fail this JNI load; do not report a successful refresh. A later call repairs the output. |
| `ANDROID_ROOT` absent/empty | Preserve the existing no-op behavior. |

The external-store end code is part of Microsoft's
[CertEnumCertificatesInStore contract](https://learn.microsoft.com/en-us/windows/win32/api/wincrypt/nf-wincrypt-certenumcertificatesinstore).
The read-only/open-existing flags preserve the
[CertOpenStore contract](https://learn.microsoft.com/en-us/windows/win32/api/wincrypt/nf-wincrypt-certopenstore).

### Resource limits

| Limit | Purpose and overflow behavior |
| --- | --- |
| 16,384 manifest/cache entries | Optional index/metadata budget. Exceeding it abandons cache admission and uses normal conversion. It is not a certificate-count limit on trust. |
| 2 MiB manifest input | Optional cache parsing budget. A larger manifest is ignored and regenerated from authoritative inputs. |
| 2 MiB per cached PEM | Optional reuse budget. A valid larger source certificate is converted and published normally. The regression fixture covers a supported certificate exceeding this limit. |
| 32 MiB aggregate cached PEM plus decoded-DER payload | Optional per-call memo budget, plus metadata bounded by the entry cap. Overflow is a conversion miss, not omitted trust. |
| Signed-int maximum for source/PEM buffers | The BoringSSL memory-BIO input boundary; not a new small enterprise-root quota. There is no aggregate-source or authoritative-certificate-count quota. |

## Why there is no persistent Windows generation shortcut yet

Windows system stores are
[collections of physical stores](https://learn.microsoft.com/en-us/windows/win32/seccrypto/system-store-locations),
including policy, enterprise, AuthRoot and additional registered providers.
A registry timestamp for one location cannot prove that this complete input
set and its restrictions are unchanged.

[CertControlStore notifications](https://learn.microsoft.com/en-us/windows/win32/api/wincrypt/nf-wincrypt-certcontrolstore)
belong to a retained open store/event and compare that store's cached state
with persisted state. A fresh process registering an event cannot validate
an earlier process's manifest. Providers may return `ERROR_NOT_SUPPORTED` for
optional control operations. This implementation therefore does not invent a
generation number from a fresh event or treat unsupported notifications as
evidence of unchanged trust.

A subsequent design could retain every supported store handle and event in
a process-lifetime owner, observe changes before and after a snapshot, resync
and rearm events, and watch bundled-directory changes. It would need explicit
identity/environment changes, unsupported-provider fallback, cancellation,
handle budgets and restart/lost-notification behavior. Sharing that state
across app launches additionally requires an authenticated owner/lifetime
protocol; it cannot be replaced by a TTL. Existing Conscrypt and native TLS
objects also have their own trust-cache lifetimes. None of those ownership
contracts is supplied by this change.

## Measurements and acceptance limits

The workload contains 128 generated EC certificates, half bundled and half
in isolated fixture Windows stores. Each executable runs a cold refresh,
eight warm refreshes, one addition and one removal. Three pairs alternate the
original/candidate order. Measurements cover the complete synchronization
call, including the declared test source boundary; setup/key generation is
outside the sample. They are not an Android application startup or a first
TLS handshake measurement.

Linux uses the exact `android-16.0.0_r4` BoringSSL source
`1e3b727981831886a0910cd9e82d5bbee4cc7029`, built in Release with
`OPENSSL_NO_ASM=1`, and GCC 14.2. Its filesystem and CryptoAPI adapters are
explicitly test-only. The source-store adapter's I/O/parsing work is common
to both variants. Instrumented file counters describe production Win32
boundary calls, not all I/O performed by the fixture.

The initial paired results, before retaining the already decoded cached DER,
are preserved in [native11-initial-host-samples.csv](native11-initial-host-samples.csv):

| Warm metric (24 samples per variant) | Original source | Initial candidate |
| --- | ---: | ---: |
| Median process CPU | 4,060 µs | 3,692.5 µs |
| Median elapsed time | 5,455 µs | 7,746.5 µs |
| Explicit `d2i_X509` calls per refresh | 128 | 0 |
| Bundled `PEM_read_bio_X509` calls | 64 | 0 |
| `PEM_write_bio_X509` calls | 128 | 0 |
| Production file opens per refresh | 65 | 193 |
| Production writes per refresh | 0 | 0 |

That is approximately 9% less observed process CPU **and 42% more observed
elapsed time**, with nearly three times as many file opens. The unfavorable
wall-time observation is not discarded or explained away. Output integrity
checks add reads that the original manifest-equality shortcut did not perform.
Conversion counters alone do not establish a useful Windows startup result.

The final retained-DER candidate's samples are recorded separately in
[native11-host-samples.csv](native11-host-samples.csv), with the production
source hash on every row. Its `trust_store.cc` SHA-256 is
`d105f8c633ce07afd1fd469a87656c0fedaac4f82c816d1fdffd3c55a349dcb8`.

| Final series | Original median elapsed | Candidate median elapsed | Original median CPU | Candidate median CPU |
| --- | ---: | ---: | ---: | ---: |
| Cold, 3 samples each | 8,233 µs | 5,452 µs | 8,224 µs | 5,330 µs |
| Warm, 24 samples each | 3,927 µs | 3,079.5 µs | 3,928 µs | 3,079.5 µs |
| One addition, 3 samples each | 4,387 µs | 3,202 µs | 4,388 µs | 3,203 µs |
| One removal, 3 samples each | 4,501 µs | 3,556 µs | 4,495 µs | 3,557 µs |

The final host run observes roughly 22% less warm CPU/elapsed time after the
duplicate PEM decode was removed. The source adapter, scheduler and filesystem
are still those of this shared Linux executor. The original-source elapsed
distribution also changes between series; these observations cannot assign
the whole wall-time difference to the small code change. File opens remain
65 versus 193. Both series remain available for review. Actual Windows paired
measurements remain required; if the candidate loses there, the design needs
further work before readiness/performance acceptance.

## Verification and reproduction

`tools/test_trust_store.py` builds complete production translation units.
`tests/trust-store/observe.h` instruments only the synchronizer unit. Windows
uses actual kernel files/named mutexes and actual CryptoAPI memory stores;
test source selection is explicitly redirected to isolated inputs, and no
fixture certificate is installed into ROOT, AuthRoot, CA or Disallowed on
the machine. The JNI test calls the actual production registration wrapper
with an opaque VM pointer and a Conscrypt delegate stub; it verifies failure
propagation, not ART/JNI execution.

Focused contracts cover cold/warm/change, user/machine sources, additions,
removals, distrust, EKU restriction, `GESHER_WINDOWS_CERTS`, `ANDROID_ROOT`
switches, v1/v3/Unicode/multivalued subjects, hash collisions, wrong hash
filenames, forged indices, corrupt/multiple PEM objects, oversized cache
bypass, optional missing stores, external-provider enumeration end,
access denial, partial enumeration, failed publication and same-hash revoked
root replacement. Four independent processes report conversion receipts;
their combined conversion count must equal one cold pass, not four.

The real Android corpus test checks all 143 bundled certificates from
Conscrypt `771401e90ac8728487b599e5b87e0628ed528ea0` at
`android-16.0.0_r4`. Its expected names are computed independently through
full `X509_NAME_hash_old`, and expected output PEMs through full X509 APIs.

Example host checks, with the pinned source and library already built:

```sh
python3 tools/test_trust_store.py \
  --boringssl-source /path/to/boringssl \
  --crypto-library /path/to/host/libcrypto.a \
  --corpus /path/to/conscrypt/apex/ca-certificates/files

python3 tools/test_trust_store.py \
  --boringssl-source /path/to/boringssl \
  --crypto-library /path/to/host/libcrypto.a --sanitize

python3 tools/test_trust_store.py \
  --boringssl-source /path/to/boringssl \
  --crypto-library /path/to/host/libcrypto.a \
  --source-ref a6be01164ff09de1a87382aad864a6253264b77b --measure-only
```

`--fetch` can obtain/build the BoringSSL version in `aosp.lock` for a host run.
Local ASan/UBSan execution used `ASAN_OPTIONS=detect_leaks=0` because leak
sanitizer is unavailable under the executor's tracing environment. CI does
not set that override. The supplied local BoringSSL archive itself was built
without sanitizer instrumentation; the changed synchronizer and fixture
were instrumented.

Windows x64 compile/link checks use LLVM-MinGW 20261006 / Clang 23.1.3 and
the same pinned BoringSSL source. The local environment cannot execute the
Windows binaries. CI adds these gates without removing any existing gate:

- `artifact-preflight`: pinned BoringSSL host sanitizer contracts.
- `build`: actual CMake `trust_store_contract_test` for correctness, plus
  separate candidate and exact original-source measurement executables built
  with the same Python runner, compiler, flags and production crypto archive.
  The paired experiment does not compare differently configured CMake/runner
  binaries; it controls compiler configuration as well as fixture/library input.
- `test-native (windows-2022)` and `test-native (windows-2025)`: real Windows
  contracts, all shipped Android roots, and three alternating original/source
  pairs. Logs record elapsed time, process CPU, conversion and I/O counts;
  there is no flaky timing threshold.
- Existing Track 56 TLS/Windows ROOT addition-removal tests, native certificate
  directory checks and negative TLS tests remain required and unchanged.

Workflow policy checks are
`tools/net-tcp-priority/test-selected-policy.py` and
`.github/scripts/test_automerge_workflows.py`. No merge-authorization label,
automerge rule or required-status policy is changed by this experiment.
