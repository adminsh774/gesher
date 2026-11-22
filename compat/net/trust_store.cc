// Gesher: the system's trusted CA certificates from Windows' certificate store
// (trust_store.h; docs/network.md, ADR 0037).
//
// Android trusts the CA certificates in /system/etc/security/cacerts (or the
// Conscrypt APEX's copy): PEM files named by the OpenSSL "old" hash of their
// subject, <hash>.<n>, which Conscrypt's TrustedCertificateStore and the
// framework's network security config look up by issuer. Gesher writes that
// directory, under $ANDROID_ROOT, from Android's own set and from what Windows
// trusts, so a root an administrator, a company or a content filter installed
// in Windows is trusted by Android apps too.
//
// Win32 side: Windows' headers and BoringSSL's, no file descriptors.

#include "trust_store.h"

#include <windows.h>
#include <wincrypt.h>
// <wincrypt.h> names some of its constants like OpenSSL's types.
#undef X509_NAME
#undef X509_EXTENSIONS
#undef X509_CERT_PAIR
#undef PKCS7_ISSUER_AND_SERIAL
#undef PKCS7_SIGNER_INFO
#undef OCSP_REQUEST
#undef OCSP_RESPONSE

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wctype.h>

#include <array>
#include <algorithm>
#include <limits>
#include <map>
#include <set>
#include <string>
#include <vector>

#include <openssl/bio.h>
#include <openssl/bytestring.h>
#include <openssl/md5.h>
#include <openssl/pem.h>
#include <openssl/sha.h>
#include <openssl/x509.h>

namespace gesher::net {
namespace {

using Der = std::vector<uint8_t>;
using Digest = std::array<uint8_t, SHA256_DIGEST_LENGTH>;

// Limits on optional cache admission, not on the trusted set. Oversized/malformed
// cache entries fall back to conversion. Sources retain the existing BoringSSL
// BIO/d2i signed-int input boundary; no certificate-count/aggregate trust quota.
constexpr size_t kMaxCacheEntries = 16384;
constexpr size_t kMaxCachePemBytes = 2 * 1024 * 1024;
constexpr size_t kMaxCacheBytes = 32 * 1024 * 1024;
constexpr size_t kMaxCertificateBytes = std::numeric_limits<int>::max();
constexpr size_t kMaxManifestBytes = 2 * 1024 * 1024;

bool Fail(const char* operation, DWORD error = GetLastError()) {
  fprintf(stderr, "gesher: system CA certificates: %s (Windows error %lu)\n", operation, error);
  return false;
}

bool NotFound(DWORD error) {
  return error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND ||
         error == static_cast<DWORD>(CRYPT_E_NOT_FOUND);
}

class PublicationLock {
 public:
  ~PublicationLock() {
    if (owned_) ReleaseMutex(handle_);
    if (handle_ != nullptr) CloseHandle(handle_);
  }
  bool Acquire() {
    handle_ = CreateMutexW(nullptr, FALSE, L"Local\\gesher-system-cacerts");
    if (handle_ == nullptr) return Fail("create publication mutex");
    DWORD wait = WaitForSingleObject(handle_, 30000);
    owned_ = wait == WAIT_OBJECT_0 || wait == WAIT_ABANDONED;
    if (!owned_) return Fail("acquire publication mutex", wait == WAIT_FAILED ? GetLastError() : wait);
    return true;
  }
 private:
  HANDLE handle_ = nullptr;
  bool owned_ = false;
};

struct Sources {
  std::vector<Der> bundled, windows, disallowed;
  bool Append(std::vector<Der>* out, const uint8_t* data, size_t size) {
    if (size == 0 || size > kMaxCertificateBytes) {
      return Fail("certificate exceeds BoringSSL input size", ERROR_FILE_TOO_LARGE);
    }
    out->emplace_back(data, data + size);
    return true;
  }
};

bool Trace() {
  const char* v = getenv("GESHER_TRACE_CACERTS");
  return v != nullptr && *v != '\0' && strcmp(v, "0") != 0;
}

std::wstring Widen(const std::string& s) {
  if (s.empty()) return std::wstring();
  int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
  std::wstring w(static_cast<size_t>(n), L'\0');
  MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), w.data(), n);
  return w;
}

// A Linux path of Gesher's path model (ADR 0010: drive-less paths are on the
// current drive) as a Win32 path.
std::wstring WinPath(const std::string& path) {
  std::wstring w = Widen(path);
  for (wchar_t& c : w) {
    if (c == L'/') c = L'\\';
  }
  return w;
}

Digest Sha256(const Der& der) {
  Digest d;
  SHA256(der.data(), der.size(), d.data());
  return d;
}

// Whether Windows lets `cert` vouch for TLS servers: an administrator can
// restrict a root's purposes (its enhanced key usage property).
bool ServerAuthAllowed(PCCERT_CONTEXT cert, bool* allowed) {
  *allowed = true;
  DWORD size = 0;
  SetLastError(ERROR_SUCCESS);
  if (!CertGetEnhancedKeyUsage(cert, CERT_FIND_PROP_ONLY_ENHKEY_USAGE_FLAG, nullptr, &size)) {
    // No property means whatever the certificate itself allows. An unreadable
    // property must not silently lift an administrator's purpose restriction.
    return GetLastError() == static_cast<DWORD>(CRYPT_E_NOT_FOUND) ||
           Fail("read certificate purpose property");
  }
  if (size < sizeof(CERT_ENHKEY_USAGE) || size > kMaxCertificateBytes) {
    return Fail("certificate purpose property size", ERROR_INVALID_DATA);
  }
  std::vector<BYTE> buffer(size);
  auto* usage = reinterpret_cast<PCERT_ENHKEY_USAGE>(buffer.data());
  SetLastError(ERROR_SUCCESS);
  if (!CertGetEnhancedKeyUsage(cert, CERT_FIND_PROP_ONLY_ENHKEY_USAGE_FLAG, usage, &size)) {
    return GetLastError() == static_cast<DWORD>(CRYPT_E_NOT_FOUND) ||
           Fail("read certificate purpose property");
  }
  if (usage->cUsageIdentifier == 0) {
    // Good for every purpose, or (no error) for none.
    *allowed = GetLastError() == static_cast<DWORD>(CRYPT_E_NOT_FOUND);
    return true;
  }
  *allowed = false;
  for (DWORD i = 0; i < usage->cUsageIdentifier; i++) {
    if (strcmp(usage->rgpszUsageIdentifier[i], szOID_PKIX_KP_SERVER_AUTH) == 0) *allowed = true;
  }
  return true;
}

enum class Take { kAll, kSelfSigned, kForDisallowed };

// The certificates of system store `name` at `location` (CERT_SYSTEM_STORE_*).
bool ReadStore(DWORD location, const wchar_t* name, Take take, Sources* sources,
               std::vector<Der>* out) {
  HCERTSTORE store = CertOpenStore(CERT_STORE_PROV_SYSTEM_W, 0, 0,
                                   location | CERT_STORE_OPEN_EXISTING_FLAG |
                                       CERT_STORE_READONLY_FLAG,
                                   name);
  if (store == nullptr) {
    DWORD error = GetLastError();
    // AuthRoot/Disallowed and user stores need not exist on a fresh machine.
    // An unsupported/unreadable provider is not an absent, empty store.
    if (NotFound(error)) return true;
    fprintf(stderr, "gesher: system CA certificates: open %s %ls store (Windows error %lu)\n",
            location == CERT_SYSTEM_STORE_CURRENT_USER ? "user" : "machine", name, error);
    return false;
  }
  bool ok = true;
  PCCERT_CONTEXT cert = nullptr;
  while ((cert = CertEnumCertificatesInStore(store, cert)) != nullptr) {
    if ((cert->dwCertEncodingType & X509_ASN_ENCODING) == 0) continue;
    if (take == Take::kSelfSigned &&
        !CertCompareCertificateName(X509_ASN_ENCODING, &cert->pCertInfo->Subject,
                                    &cert->pCertInfo->Issuer)) {
      continue;
    }
    bool allowed = true;
    if (take != Take::kForDisallowed && !ServerAuthAllowed(cert, &allowed)) {
      ok = false;
      break;
    }
    if (!allowed) continue;
    if (!sources->Append(out, cert->pbCertEncoded, cert->cbCertEncoded)) {
      ok = false;
      break;
    }
  }
  if (cert != nullptr) CertFreeCertificateContext(cert);
  // External physical-store providers may use ERROR_NO_MORE_FILES instead.
  if (ok && GetLastError() != static_cast<DWORD>(CRYPT_E_NOT_FOUND) &&
      GetLastError() != ERROR_NO_MORE_FILES) {
    ok = Fail("enumerate certificate store");
  }
  if (!CertCloseStore(store, 0)) ok = Fail("close certificate store");
  return ok;
}

bool ReadWholeFile(const std::wstring& path, std::string* out,
                    size_t limit = kMaxCertificateBytes) {
  HANDLE h = CreateFileW(path.c_str(), GENERIC_READ,
                         FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                         OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
  if (h == INVALID_HANDLE_VALUE) return false;
  out->clear();
  char buffer[16384];
  DWORD n = 0;
  bool ok = true;
  while ((ok = ::ReadFile(h, buffer, sizeof(buffer), &n, nullptr)) && n > 0) {
    if (n > limit - out->size()) {
      SetLastError(ERROR_FILE_TOO_LARGE);
      ok = false;
      break;
    }
    out->append(buffer, n);
  }
  DWORD error = GetLastError();
  CloseHandle(h);
  if (!ok) SetLastError(error);
  return ok;
}

// Writes `path` whole: a temporary file next to it, then renamed over it.
bool WriteFileAtomically(const std::wstring& dir, const std::wstring& name,
                         const std::string& data) {
  std::wstring path = dir + L"\\" + name;
  wchar_t suffix[64];
  swprintf(suffix, 64, L".tmp-%lu-%lu", GetCurrentProcessId(), GetCurrentThreadId());
  std::wstring tmp = dir + L"\\." + name + suffix;
  HANDLE h = CreateFileW(tmp.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                         FILE_ATTRIBUTE_NORMAL, nullptr);
  if (h == INVALID_HANDLE_VALUE) return false;
  DWORD written = 0;
  bool ok = ::WriteFile(h, data.data(), static_cast<DWORD>(data.size()), &written, nullptr) != 0;
  if (ok && written != data.size()) {
    SetLastError(ERROR_WRITE_FAULT);
    ok = false;
  }
  DWORD error = ok ? ERROR_SUCCESS : GetLastError();
  if (!CloseHandle(h) && ok) {
    ok = false;
    error = GetLastError();
  }
  if (ok) {
    ok = MoveFileExW(tmp.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING) != 0;
    if (!ok) error = GetLastError();
  }
  if (!ok) DeleteFileW(tmp.c_str());
  if (!ok) SetLastError(error);
  return ok;
}

bool MakeDirectories(const std::wstring& dir) {
  if (CreateDirectoryW(dir.c_str(), nullptr) || GetLastError() == ERROR_ALREADY_EXISTS) {
    return true;
  }
  if (GetLastError() != ERROR_PATH_NOT_FOUND) return false;
  size_t slash = dir.find_last_of(L'\\');
  if (slash == std::wstring::npos || slash == 0) return false;
  if (!MakeDirectories(dir.substr(0, slash))) return false;
  return CreateDirectoryW(dir.c_str(), nullptr) || GetLastError() == ERROR_ALREADY_EXISTS;
}

bool IsCertFileName(const std::wstring& name);

// Decode the actual certificate bytes without constructing an X509 object or
// re-encoding it. Cache files are our one-certificate PEM format: no headers,
// leading text, additional PEM objects or trailing non-whitespace data.
bool DecodePem(const std::string& pem, Der* out, bool cache) {
  if (cache && pem.rfind("-----BEGIN CERTIFICATE-----\n", 0) != 0) return false;
  BIO* bio = BIO_new_mem_buf(pem.data(), static_cast<int>(pem.size()));
  if (bio == nullptr) return false;
  char *name = nullptr, *header = nullptr;
  uint8_t* bytes = nullptr;
  long size = 0;
  bool ok;
  if (cache) {
    ok = PEM_read_bio(bio, &name, &header, &bytes, &size) != 0 &&
         strcmp(name, PEM_STRING_X509) == 0 && header[0] == '\0';
  } else {
    // Match PEM_read_bio_X509's supported certificate labels and skipping of
    // unrelated PEM objects. System CA files never require a password prompt.
    auto no_password = [](char*, int, int, void*) { return 0; };
    ok = PEM_bytes_read_bio(&bytes, &size, nullptr, PEM_STRING_X509, bio, no_password, nullptr) != 0;
  }
  ok = ok && size > 0 && static_cast<size_t>(size) <= kMaxCertificateBytes;
  if (ok && cache) {
    const uint8_t* rest = nullptr;
    size_t remaining = 0;
    ok = BIO_mem_contents(bio, &rest, &remaining) != 0;
    for (size_t i = 0; ok && i < remaining; ++i) {
      ok = rest[i] == '\n' || rest[i] == '\r' || rest[i] == ' ' || rest[i] == '\t';
    }
  }
  if (ok) out->assign(bytes, bytes + size);
  OPENSSL_free(name);
  OPENSSL_free(header);
  OPENSSL_free(bytes);
  BIO_free(bio);
  return ok;
}

// Android's own CA certificates, shipped next to the executable (cacerts\).
bool ReadBundled(Sources* sources) {
  std::vector<wchar_t> exe(32768);
  DWORD n = GetModuleFileNameW(nullptr, exe.data(), static_cast<DWORD>(exe.size()));
  if (n == 0 || n >= exe.size()) return Fail("locate bundled certificates");
  std::wstring dir(exe.data(), n);
  dir = dir.substr(0, dir.find_last_of(L'\\')) + L"\\cacerts";
  WIN32_FIND_DATAW entry;
  HANDLE find = FindFirstFileW((dir + L"\\*").c_str(), &entry);
  if (find == INVALID_HANDLE_VALUE) return NotFound(GetLastError()) || Fail("open bundled certificates");
  bool ok = true;
  do {
    if (entry.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
    std::string pem;
    if (!ReadWholeFile(dir + L"\\" + entry.cFileName, &pem)) {
      ok = Fail("read bundled certificate");
      break;
    }
    Der der;
    if (!DecodePem(pem, &der, false)) {
      if (!IsCertFileName(entry.cFileName)) continue;  // e.g. NOTICE
      ok = Fail("decode bundled certificate", ERROR_INVALID_DATA);
      break;
    }
    if (!sources->Append(&sources->bundled, der.data(), der.size())) {
      ok = false;
      break;
    }
  } while (FindNextFileW(find, &entry));
  if (ok && GetLastError() != ERROR_NO_MORE_FILES) ok = Fail("enumerate bundled certificates");
  FindClose(find);
  return ok;
}

struct Anchor {
  unsigned long hash;
  std::string pem;
};

// BoringSSL's X509_NAME_hash_old hashes the original encoded subject, including
// its SEQUENCE header (crypto/x509/{x509_cmp,x_name}.cc). Its CBS reader extracts
// that exact span without an X509 allocation. This is only an encoding cache
// key, never certificate/chain validation. Unusual encodings fall back to X509.
bool SubjectHash(const Der& der, unsigned long* hash) {
  CBS input, certificate, tbs, field, subject;
  CBS_init(&input, der.data(), der.size());
  int version_present = 0;
  if (!CBS_get_asn1(&input, &certificate, CBS_ASN1_SEQUENCE) || CBS_len(&input) != 0 ||
      !CBS_get_asn1(&certificate, &tbs, CBS_ASN1_SEQUENCE) ||
      !CBS_get_optional_asn1(&tbs, &field, &version_present,
                             CBS_ASN1_CONTEXT_SPECIFIC | CBS_ASN1_CONSTRUCTED) ||
      !CBS_get_asn1(&tbs, &field, CBS_ASN1_INTEGER) ||  // serial
      !CBS_get_asn1(&tbs, &field, CBS_ASN1_SEQUENCE) ||  // signature algorithm
      !CBS_get_asn1(&tbs, &field, CBS_ASN1_SEQUENCE) ||  // issuer
      !CBS_get_asn1(&tbs, &field, CBS_ASN1_SEQUENCE) ||  // validity
      !CBS_get_asn1_element(&tbs, &subject, CBS_ASN1_SEQUENCE)) {
    return false;
  }
  uint8_t md[MD5_DIGEST_LENGTH];
  MD5(CBS_data(&subject), CBS_len(&subject), md);
  *hash = uint32_t{md[0]} | (uint32_t{md[1]} << 8) | (uint32_t{md[2]} << 16) |
          (uint32_t{md[3]} << 24);
  return true;
}

bool Convert(const Der& der, Anchor* anchor) {
  const uint8_t* p = der.data();
  X509* x = d2i_X509(nullptr, &p, static_cast<long>(der.size()));
  if (x == nullptr) return false;
  anchor->hash = X509_NAME_hash_old(X509_get_subject_name(x));
  BIO* bio = BIO_new(BIO_s_mem());
  bool ok = p == der.data() + der.size() && bio != nullptr && PEM_write_bio_X509(bio, x);
  if (ok) {
    const uint8_t* data = nullptr;
    size_t len = 0;
    ok = BIO_mem_contents(bio, &data, &len) != 0;
    if (ok) anchor->pem.assign(reinterpret_cast<const char*>(data), len);
  }
  BIO_free(bio);
  X509_free(x);
  return ok && !anchor->pem.empty();
}

bool IsCertFileName(const std::wstring& name) {
  // <8 hex digits>.<digits>, as Android names them.
  if (name.size() < 10 || name[8] != L'.') return false;
  for (size_t i = 0; i < 8; i++) {
    if (!((name[i] >= L'0' && name[i] <= L'9') ||
          (name[i] >= L'a' && name[i] <= L'f') ||
          (name[i] >= L'A' && name[i] <= L'F'))) return false;
  }
  for (size_t i = 9; i < name.size(); i++) {
    if (name[i] < L'0' || name[i] > L'9') return false;
  }
  return true;
}

int Hex(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  return -1;
}

using CacheIndex = std::map<Digest, std::wstring>;

struct CachedFile {
  std::string pem;
  Der der;
  Digest digest{};
  bool decoded = false;
};

struct Cache {
  CacheIndex index;
  std::map<std::wstring, CachedFile> files;
  size_t bytes = 0;

  const CachedFile* Load(const std::wstring& dir, const std::wstring& name) {
    auto found = files.find(name);
    if (found != files.end()) return &found->second;
    if (files.size() >= kMaxCacheEntries || bytes == kMaxCacheBytes) return nullptr;
    CachedFile file;
    if (!ReadWholeFile(dir + L"\\" + name, &file.pem,
                       std::min(kMaxCachePemBytes, kMaxCacheBytes - bytes))) return nullptr;
    file.decoded = DecodePem(file.pem, &file.der, true);
    if (file.decoded) file.digest = Sha256(file.der);
    if (file.pem.size() + file.der.size() > kMaxCacheBytes - bytes) return nullptr;
    bytes += file.pem.size() + file.der.size();
    return &files.emplace(name, std::move(file)).first->second;
  }
};

// The manifest is an untrusted lookup hint. It cannot admit an anchor, select
// its subject hash, suppress output validation, or decide whether stores changed.
CacheIndex ReadCacheIndex(const std::wstring& dir, std::string* manifest) {
  CacheIndex index;
  if (!ReadWholeFile(dir + L"\\.gesher-cacerts", manifest, kMaxManifestBytes)) return index;
  size_t pos = 0;
  while (pos < manifest->size()) {
    size_t end = manifest->find('\n', pos);
    size_t space = manifest->find(' ', pos);
    if (end == std::string::npos || space == std::string::npos || space > end ||
        space - pos > 24 || end - space != 65 || index.size() >= kMaxCacheEntries) return {};
    std::wstring name = Widen(manifest->substr(pos, space - pos));
    if (!IsCertFileName(name)) return {};
    Digest digest;
    for (size_t i = 0; i < digest.size(); ++i) {
      int high = Hex((*manifest)[space + 1 + 2 * i]);
      int low = Hex((*manifest)[space + 2 + 2 * i]);
      if (high < 0 || low < 0) return {};
      digest[i] = static_cast<uint8_t>((high << 4) | low);
    }
    if (!index.emplace(digest, std::move(name)).second) return {};
    pos = end + 1;
  }
  return index;
}

bool Reuse(const std::wstring& dir, Cache* cache, const Digest& digest,
           const Der& der, Anchor* anchor) {
  auto entry = cache->index.find(digest);
  if (entry == cache->index.end() || !SubjectHash(der, &anchor->hash)) return false;
  wchar_t prefix[10];
  swprintf(prefix, 10, L"%08lx.", anchor->hash);
  if (entry->second.compare(0, 9, prefix) != 0) return false;
  const CachedFile* cached = cache->Load(dir, entry->second);
  if (cached == nullptr || !cached->decoded || cached->digest != digest) return false;
  // SHA-256 identifies the freshly admitted source. The cached DER is checked
  // byte-for-byte as well; neither a forged digest line nor a second PEM object
  // can supply certificate bytes which did not come from that source.
  if (cached->der != der) return false;
  anchor->pem = cached->pem;
  return true;
}

bool Sync() {
  const char* android_root = getenv("ANDROID_ROOT");
  if (android_root == nullptr || *android_root == '\0') return true;
  std::wstring dir = WinPath(std::string(android_root) + "/etc/security/cacerts");

  // Lock before scanning, so a waiter sees its predecessor's complete cache.
  // No process-lifetime store/data handle or notification lifetime is invented.
  PublicationLock lock;
  if (!lock.Acquire()) return false;

  Sources sources;
  if (!ReadBundled(&sources)) return false;
  const char* use_windows = getenv("GESHER_WINDOWS_CERTS");
  bool from_windows = use_windows == nullptr || strcmp(use_windows, "0") != 0;
  for (DWORD location : {CERT_SYSTEM_STORE_CURRENT_USER, CERT_SYSTEM_STORE_LOCAL_MACHINE}) {
    if (from_windows) {
      if (!ReadStore(location, L"ROOT", Take::kAll, &sources, &sources.windows) ||
          !ReadStore(location, L"AuthRoot", Take::kAll, &sources, &sources.windows)) return false;
      // Roots installed among the intermediate CAs.
      if (!ReadStore(location, L"CA", Take::kSelfSigned, &sources, &sources.windows)) return false;
    }
    if (!ReadStore(location, L"Disallowed", Take::kForDisallowed, &sources, &sources.disallowed)) return false;
  }
  std::set<Digest> distrusted;
  for (const Der& der : sources.disallowed) distrusted.insert(Sha256(der));

  std::string current;
  Cache cache;
  cache.index = ReadCacheIndex(dir, &current);

  // By digest: each certificate once, in a stable order.
  std::map<Digest, Anchor> anchors;
  size_t from_android = 0, from_store = 0, skipped = 0, reused = 0, converted = 0;
  auto add = [&](const Der& der, size_t* count) {
    Digest digest = Sha256(der);
    if (anchors.count(digest) != 0) return true;
    if (distrusted.count(digest) != 0) {
      skipped++;
      return true;
    }
    Anchor anchor;
    if (Reuse(dir, &cache, digest, der, &anchor)) {
      ++reused;
    } else {
      if (!Convert(der, &anchor)) return Fail("convert source certificate", ERROR_INVALID_DATA);
      ++converted;
    }
    anchors.emplace(digest, std::move(anchor));
    (*count)++;
    return true;
  };
  for (const Der& der : sources.bundled) if (!add(der, &from_android)) return false;
  for (const Der& der : sources.windows) if (!add(der, &from_store)) return false;

  // Names: <hash>.<n>, n counting certificates whose subjects hash alike.
  std::map<std::wstring, const Anchor*> files;
  std::map<unsigned long, int> next;
  std::string manifest;
  for (const auto& [digest, anchor] : anchors) {
    wchar_t name[32];
    swprintf(name, 32, L"%08lx.%d", anchor.hash, next[anchor.hash]++);
    files[name] = &anchor;
    char line[128];
    snprintf(line, sizeof(line), "%08lx.%d ", anchor.hash, next[anchor.hash] - 1);
    manifest += line;
    for (uint8_t b : digest) {
      snprintf(line, sizeof(line), "%02x", b);
      manifest += line;
    }
    manifest += '\n';
  }

  bool changed = current != manifest;
  size_t written = 0, removed = 0;
  if (!MakeDirectories(dir)) return Fail("create output certificate directory");

  // Remove stale/unadmitted certificates before publishing additions, including
  // a revoked certificate occupying a filename needed by a new colliding root.
  // A failed delete/write must prevent this JNI load from trusting stale output.
  // Already initialized TLS objects are outside this per-load synchronization.
  std::map<std::wstring, std::string> existing_files;
  WIN32_FIND_DATAW entry;
  HANDLE find = FindFirstFileW((dir + L"\\*").c_str(), &entry);
  if (find == INVALID_HANDLE_VALUE && !NotFound(GetLastError())) return Fail("open output certificate directory");
  bool ok = true;
  if (find != INVALID_HANDLE_VALUE) {
    do {
      std::wstring name = entry.cFileName;
      if (!IsCertFileName(name)) continue;
      std::string existing;
      Der der;
      bool admitted;
      auto cached = cache.files.find(name);
      if (cached != cache.files.end()) {
        admitted = cached->second.decoded && anchors.count(cached->second.digest) != 0;
        existing = std::move(cached->second.pem);
      } else {
        admitted = ReadWholeFile(dir + L"\\" + name, &existing) &&
                   DecodePem(existing, &der, true) && anchors.count(Sha256(der)) != 0;
      }
      if (files.count(name) == 0 || !admitted) {
        if (!DeleteFileW((dir + L"\\" + name).c_str())) {
          ok = Fail("remove stale output certificate");
          break;
        }
        ++removed;
      } else {
        existing_files.emplace(std::move(name), std::move(existing));
      }
    } while (FindNextFileW(find, &entry));
    if (ok && GetLastError() != ERROR_NO_MORE_FILES) ok = Fail("enumerate output certificates");
    FindClose(find);
  }
  if (!ok) return false;
  for (const auto& [name, anchor] : files) {
    auto existing = existing_files.find(name);
    if (existing != existing_files.end() && existing->second == anchor->pem) continue;
    if (!WriteFileAtomically(dir, name, anchor->pem)) return Fail("publish output certificate");
    ++written;
  }
  if ((changed || written || removed) && !WriteFileAtomically(dir, L".gesher-cacerts", manifest)) {
    return Fail("publish output manifest");
  }
  if (Trace()) {
    fprintf(stderr,
            "gesher: system CA certificates: %zu (%zu Android's, %zu more from Windows' stores, "
            "%zu distrusted by Windows left out); %s (%zu converted, %zu reused, "
            "%zu written, %zu removed)\n",
            anchors.size(), from_android, from_store, skipped,
            changed || written || removed ? "updated" : "unchanged", converted, reused, written, removed);
  }
  return true;
}

}  // namespace

bool SyncSystemCaCerts() { return Sync(); }

}  // namespace gesher::net
