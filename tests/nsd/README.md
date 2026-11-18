# NSD reference and Windows peer fixtures

These fixtures use unique owned service types. They do not enumerate unrelated service
types or log other devices' names/addresses.

## Android discovery reference

`NsdAndroidReference.java` uses the official API36 NSD SDK at its callsites. Its bootstrap
obtains a system context through app_process, so its 19-check Android run is discovery,
value and executor evidence under shell identity, not ordinary APK permission evidence.
The source and payload hash are described in
[`nsd-windows-browse-2026-11-18.md`](../../docs/research/nsd-windows-browse-2026-11-18.md).

## Ordinary Android APK permission reference

`permissions/src/gesher/qa/nsdpermissions/ControlReceiver.java` and the three
`permissions/*/AndroidManifest.xml` files are the unchanged inputs measured by independent
QA. The source records outcomes; it does not hardcode which permission should succeed.
The manifests declare no permissions, INTERNET only, or INTERNET plus ACCESS_NETWORK_STATE.
All use min/target SDK36. Their repository license is Apache-2.0; source bytes are retained
to preserve the measured input hash.

Build the three owned debug APKs using explicit SDK/JDK paths:

```sh
python3 tools/build_nsd_permission_reference.py \
  --java-home /path/to/jdk \
  --android-jar /path/to/android-36/android.jar \
  --build-tools /path/to/build-tools/36.1 \
  --output /path/to/isolated/nsd-reference-build
```

The builder uses only the SDK jar, generates a local fixture signing key, verifies APK
signatures/package/target/permissions, and records input/tool/APK hashes in manifest.json.
It writes setup, execution and cleanup command arrays and **does not execute ADB**.
Setup pushes read-only APKs and checks device hashes. Execution covers acquisition and
both discovery overloads for each APK. Cleanup names only the three owned packages and
their uploaded files. Run arrays serially through the chosen isolated-device coordinator,
verifying setup hashes before installation.

Broadcasts return JSON frames prefixed `NSD_APK_PERMISSION_REFERENCE:`. Verify actual
SDK/target, manifest/self-permission state, ordinary app UID and matching worker Binder
UID before interpreting observations. Distinguish synchronous exceptions from callbacks
and timeouts. This is an Android reference: its explicit `candidateWindowsExecution:false`
output must never be counted as Windows permission qualification.

The pinned SDK/JDK/D8 can reproduce the frozen DEX. APK hashes may differ because this
builder generates a fresh test key. Record the exact APKs executed; rebuilt artifacts
must not replace an earlier execution receipt. Do not commit APKs, keys or build outputs.

## Required Windows production peer

`windows_peer.py` constructs mDNS packets independently using Python's standard library
and opens a real listening TCP socket. The Windows gate supplies an owned random type;
two further types derived from that nonce are reserved for goodbye cases. IPv4 and IPv6
use the selected real interface. TXT contains embedded NUL, and Unicode/dot/backslash
instance names occupy one DNS label.

The services-track gate drives the peer and the actual manager/native transport. Short
PTR2/SRV5 lifetimes test refresh and silent expiry. Fresh PTR120/SRV180 observations,
verified native positive lifetime and a three-second goodbye deadline distinguish
goodbye loss from natural expiry. A base-only goodbye during subtype discovery must
reach the native callback and cause Java loss before that deadline.

The Windows gate has not run for this candidate. Host fixtures, packet parsing, Android
references and cross-compilation cannot replace it.
