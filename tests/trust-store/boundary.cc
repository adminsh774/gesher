#include "boundary.h"
#include <windows.h>
#include <wincrypt.h>
#undef X509_NAME
#undef X509_EXTENSIONS
#undef X509_CERT_PAIR
#undef PKCS7_ISSUER_AND_SERIAL
#undef PKCS7_SIGNER_INFO
#undef OCSP_REQUEST
#undef OCSP_RESPONSE
#include <openssl/pem.h>
#include <openssl/x509.h>
#include <algorithm>
#include <chrono>
#include <codecvt>
#include <cstring>
#include <fstream>
#include <locale>
#include <memory>
#include <thread>

namespace fs = std::filesystem;
namespace trust_test {
Counts counts;
Fault fault = Fault::kNone;
fs::path root;
std::string Read(const fs::path& path) {
  std::ifstream stream(path, std::ios::binary);
  return {std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>()};
}
void Write(const fs::path& path, const std::string& data) {
  fs::create_directories(path.parent_path());
  std::ofstream stream(path, std::ios::binary);
  stream.write(data.data(), data.size());
  if (!stream) std::abort();
}
void SetRoot(const fs::path& path) {
  root = fs::absolute(path);
  std::string output = (root / "system").string();
#ifdef _WIN32
  _putenv_s("ANDROID_ROOT", output.c_str());
  _putenv_s("GESHER_WINDOWS_CERTS", "1");
#else
  setenv("ANDROID_ROOT", output.c_str(), 1);
  setenv("GESHER_WINDOWS_CERTS", "1", 1);
#endif
}
}
using trust_test::counts;
using trust_test::fault;
using trust_test::Fault;
namespace {
bool Consume(Fault value) {
  if (fault != value) return false;
  fault = Fault::kNone;
  SetLastError(ERROR_ACCESS_DENIED);
  return true;
}
std::wstring Wide(const fs::path& path) {
#ifdef _WIN32
  return path.wstring();
#else
  return std::wstring_convert<std::codecvt_utf8<wchar_t>>{}.from_bytes(path.string());
#endif
}
std::string StoreName(DWORD flags, const void* name) {
  std::wstring wide(static_cast<const wchar_t*>(name));
  return ((flags & CERT_SYSTEM_STORE_LOCAL_MACHINE) ? "machine-" : "user-") +
         std::string(wide.begin(), wide.end());
}
#ifndef _WIN32
struct HostCertificate {
  CERT_CONTEXT context;
  CERT_INFO info;
  std::string der, subject, issuer, usage;
  HostCertificate(std::string bytes, std::string purpose) : der(std::move(bytes)), usage(std::move(purpose)) {
    const uint8_t* p = reinterpret_cast<const uint8_t*>(der.data());
    X509* cert = d2i_X509(nullptr, &p, der.size());
    if (cert == nullptr) std::abort();
    auto name = [](X509_NAME* value) {
      uint8_t* data = nullptr;
      int size = i2d_X509_NAME(value, &data);
      std::string result(reinterpret_cast<char*>(data), size);
      OPENSSL_free(data);
      return result;
    };
    subject = name(X509_get_subject_name(cert));
    issuer = name(X509_get_issuer_name(cert));
    X509_free(cert);
    info = {{static_cast<DWORD>(subject.size()), reinterpret_cast<BYTE*>(subject.data())},
            {static_cast<DWORD>(issuer.size()), reinterpret_cast<BYTE*>(issuer.data())}};
    context = {X509_ASN_ENCODING, reinterpret_cast<BYTE*>(der.data()), static_cast<DWORD>(der.size()), &info};
  }
};
struct HostStore { std::vector<std::unique_ptr<HostCertificate>> certs; };
#endif
}

HCERTSTORE WINAPI TrustTestOpenStore(LPCSTR, DWORD, HCRYPTPROV_LEGACY, DWORD flags, const void* name) {
  ++counts.stores;
  if (Consume(Fault::kStoreDenied)) return nullptr;
  if (std::wstring(static_cast<const wchar_t*>(name)) == L"Disallowed" && Consume(Fault::kDisallowedDenied)) return nullptr;
  if (Consume(Fault::kStoreUnsupported)) { SetLastError(ERROR_NOT_SUPPORTED); return nullptr; }
  auto directory = trust_test::root / "stores" / StoreName(flags, name);
  if (!fs::exists(directory)) { SetLastError(ERROR_FILE_NOT_FOUND); return nullptr; }
#ifdef _WIN32
  HCERTSTORE store = CertOpenStore(CERT_STORE_PROV_MEMORY, 0, 0, 0, nullptr);
  if (!store) std::abort();
#else
  auto* store = new HostStore;
#endif
  for (const auto& entry : fs::directory_iterator(directory)) {
    if (entry.path().extension() != ".der") continue;
    std::string der = trust_test::Read(entry.path());
    auto purpose_path = entry.path();
    purpose_path.replace_extension(".eku");
    std::string usage = trust_test::Read(purpose_path);
#ifdef _WIN32
    PCCERT_CONTEXT context = nullptr;
    if (!CertAddEncodedCertificateToStore(store, X509_ASN_ENCODING,
          reinterpret_cast<const BYTE*>(der.data()), static_cast<DWORD>(der.size()),
          CERT_STORE_ADD_ALWAYS, &context)) std::abort();
    if (!usage.empty()) {
      char* oid = const_cast<char*>(usage == "server" ? szOID_PKIX_KP_SERVER_AUTH : "1.3.6.1.5.5.7.3.2");
      CERT_ENHKEY_USAGE purposes{usage == "none" ? 0UL : 1UL, &oid};
      if (!CertSetEnhancedKeyUsage(context, &purposes)) std::abort();
    }
    CertFreeCertificateContext(context);
#else
    store->certs.push_back(std::make_unique<HostCertificate>(std::move(der), std::move(usage)));
#endif
  }
  return store;
}

#ifndef _WIN32
PCCERT_CONTEXT CertEnumCertificatesInStore(HCERTSTORE raw, PCCERT_CONTEXT previous) {
  auto* store = static_cast<HostStore*>(raw);
  size_t i = 0;
  if (previous) {
    while (i < store->certs.size() && &store->certs[i]->context != previous) ++i;
    ++i;
  }
  if (i >= store->certs.size()) { SetLastError(CRYPT_E_NOT_FOUND); return nullptr; }
  return &store->certs[i]->context;
}
BOOL CertGetEnhancedKeyUsage(PCCERT_CONTEXT context, DWORD, PCERT_ENHKEY_USAGE out, DWORD* size) {
  const auto* cert = reinterpret_cast<const HostCertificate*>(context);
  DWORD needed = sizeof(CERT_ENHKEY_USAGE) + sizeof(char*);
  if (out == nullptr) { *size = needed; return true; }
  if (*size < needed) std::abort();
  out->rgpszUsageIdentifier = reinterpret_cast<char**>(reinterpret_cast<BYTE*>(out) + sizeof(*out));
  out->cUsageIdentifier = cert->usage.empty() || cert->usage == "none" ? 0 : 1;
  out->rgpszUsageIdentifier[0] = const_cast<char*>(cert->usage == "server" ? szOID_PKIX_KP_SERVER_AUTH : "1.3.6.1.5.5.7.3.2");
  SetLastError(cert->usage.empty() ? CRYPT_E_NOT_FOUND : ERROR_SUCCESS);
  return true;
}
BOOL CertCompareCertificateName(DWORD, const CRYPT_DATA_BLOB* a, const CRYPT_DATA_BLOB* b) {
  return a->cbData == b->cbData && !std::memcmp(a->pbData, b->pbData, a->cbData);
}
BOOL CertFreeCertificateContext(PCCERT_CONTEXT) { return true; }
BOOL CertCloseStore(HCERTSTORE raw, DWORD) { delete static_cast<HostStore*>(raw); return true; }
#endif

PCCERT_CONTEXT WINAPI TrustTestEnum(HCERTSTORE store, PCCERT_CONTEXT previous) {
  if (Consume(Fault::kEnumeration) || (previous != nullptr && Consume(Fault::kPartialEnumeration))) {
    if (previous) CertFreeCertificateContext(previous);
    return nullptr;
  }
  PCCERT_CONTEXT cert = CertEnumCertificatesInStore(store, previous);
  if (!cert && Consume(Fault::kExternalEnd)) SetLastError(ERROR_NO_MORE_FILES);
  if (cert) ++counts.enumerated;
  return cert;
}
BOOL WINAPI TrustTestUsage(PCCERT_CONTEXT cert, DWORD flags, PCERT_ENHKEY_USAGE usage, DWORD* size) {
  if (Consume(Fault::kUsage)) return false;
  return CertGetEnhancedKeyUsage(cert, flags, usage, size);
}
DWORD WINAPI TrustTestWait(HANDLE handle, DWORD wait) {
  ++counts.waits;
  if (Consume(Fault::kWait)) return WAIT_TIMEOUT;
  DWORD result = WaitForSingleObject(handle, wait);
  if (const char* pause = getenv("TRUST_TEST_LOCK_PAUSE")) {
    std::this_thread::sleep_for(std::chrono::milliseconds(std::atoi(pause)));
  }
  return result;
}
BOOL WINAPI TrustTestWrite(HANDLE handle, LPCVOID data, DWORD size, LPDWORD written, LPOVERLAPPED at) {
  ++counts.writes;
  if (Consume(Fault::kWrite)) { *written = 0; return false; }
  if (Consume(Fault::kShortWrite)) {
    BOOL result = WriteFile(handle, data, size ? size - 1 : 0, written, at);
    SetLastError(ERROR_WRITE_FAULT);
    return result;
  }
  return WriteFile(handle, data, size, written, at);
}
BOOL WINAPI TrustTestRename(LPCWSTR from, LPCWSTR to, DWORD flags) {
  if (Consume(Fault::kRename)) return false;
  if (std::wstring(to).ends_with(L".gesher-cacerts") && Consume(Fault::kManifestRename)) return false;
  return MoveFileExW(from, to, flags);
}
BOOL WINAPI TrustTestDelete(LPCWSTR path) {
  if (Consume(Fault::kDelete)) return false;
  return DeleteFileW(path);
}
DWORD WINAPI TrustTestModule(HANDLE, wchar_t* output, DWORD size) {
  auto path = Wide(trust_test::root / "app.exe");
  std::replace(path.begin(), path.end(), L'/', L'\\');
  if (path.size() >= size) return size;
  std::wmemcpy(output, path.c_str(), path.size() + 1);
  return static_cast<DWORD>(path.size());
}
HANDLE WINAPI TrustTestFile(LPCWSTR path, DWORD access, DWORD sharing, LPSECURITY_ATTRIBUTES attrs,
                             DWORD disposition, DWORD flags, HANDLE template_file) {
  ++counts.opens;
  return CreateFileW(path, access, sharing, attrs, disposition, flags, template_file);
}
BOOL WINAPI TrustTestRead(HANDLE handle, LPVOID buffer, DWORD size, LPDWORD read, LPOVERLAPPED at) {
  ++counts.reads;
  BOOL result = ReadFile(handle, buffer, size, read, at);
  if (result) counts.read_bytes += *read;
  return result;
}
X509* TrustTestDecode(X509** out, const uint8_t** input, long size) {
  ++counts.decode;
  return d2i_X509(out, input, size);
}
int TrustTestEncode(BIO* bio, X509* cert) { ++counts.encode; return PEM_write_bio_X509(bio, cert); }
X509* TrustTestReadPem(BIO* bio, X509** out, pem_password_cb* callback, void* user) {
  ++counts.read_pem;
  return PEM_read_bio_X509(bio, out, callback, user);
}
