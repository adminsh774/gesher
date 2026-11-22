// Gesher: the system's trusted CA certificates from Windows' certificate store
// (compat/net/trust_store.cc; docs/network.md, ADR 0037).
#pragma once

namespace gesher::net {

// Writes the system CA certificates into $ANDROID_ROOT/etc/security/cacerts,
// the directory Conscrypt's TrustedCertificateStore and the framework's
// network security config read: Android's own CA set (the cacerts directory
// next to the executable, Conscrypt's apex/ca-certificates) and the
// certificates Windows trusts as roots for TLS servers (the ROOT stores of the
// user and the machine, group policy and enterprise ones included, and
// self-signed certificates in the CA stores), less those in Windows'
// Disallowed stores. Each is a PEM file named <subject hash>.<n>, as Android
// names them. Every call freshly reads all sources and restrictions. Unchanged
// certificates reuse their PEM only after verifying the actual DER and subject
// hash; the manifest is an untrusted index, not a source-generation receipt.
// Returns false on an unreadable source, serialization or publication failure;
// JNI_OnLoad must then fail rather than initialize TLS with stale trust. Output
// files are individually atomic, with stale certificates removed first and the
// manifest last. Failure can leave a partial directory, repaired by the next
// call; there is no directory transaction or live revocation of existing TLS.
bool SyncSystemCaCerts();

}  // namespace gesher::net
