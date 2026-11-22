#include "boundary.h"
#include "trust_store.h"
#include <gesher/builtin_library.h>
#include <jni.h>
#include <windows.h>
#undef X509_NAME
#undef X509_EXTENSIONS
#undef X509_CERT_PAIR
#undef PKCS7_ISSUER_AND_SERIAL
#undef PKCS7_SIGNER_INFO
#undef OCSP_REQUEST
#undef OCSP_RESPONSE
#include <openssl/ec.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/sha.h>
#include <openssl/x509.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <ctime>
#include <cstdlib>
#include <future>
#include <map>
#include <set>
#include <string>
#include <vector>

namespace fs = std::filesystem;
using trust_test::counts;
using trust_test::fault;
using trust_test::Fault;
using trust_test::Read;
using trust_test::Write;
using trust_test::SetRoot;
using Digest = std::array<uint8_t, SHA256_DIGEST_LENGTH>;
namespace {
size_t checks;
#define CHECK(value) do { ++checks; if (!(value)) { std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #value); std::abort(); } } while (false)
jint (*on_load)(JavaVM*, void*) = nullptr;
size_t conscrypt_loads;
struct Certificate { std::string der, pem; unsigned long hash; Digest digest; };

void Environment(const char* name, const char* value) {
#ifdef _WIN32
  CHECK(_putenv_s(name, value) == 0);
#else
  CHECK(setenv(name, value, 1) == 0);
#endif
}

Certificate MakeCertificate(int serial, const std::string& subject, int version = 2,
                            bool self_signed = true, size_t padding = 0) {
  static bssl::UniquePtr<EVP_PKEY> key([] {
    bssl::UniquePtr<EC_KEY> ec(EC_KEY_new_by_curve_name(NID_X9_62_prime256v1));
    CHECK(ec && EC_KEY_generate_key(ec.get()));
    EVP_PKEY* pkey = EVP_PKEY_new();
    CHECK(pkey && EVP_PKEY_assign_EC_KEY(pkey, ec.release()));
    return pkey;
  }());
  bssl::UniquePtr<X509> cert(X509_new());
  CHECK(cert && X509_set_version(cert.get(), version));
  CHECK(ASN1_INTEGER_set(X509_get_serialNumber(cert.get()), serial));
  CHECK(X509_gmtime_adj(X509_getm_notBefore(cert.get()), -60));
  CHECK(X509_gmtime_adj(X509_getm_notAfter(cert.get()), 86400));
  CHECK(X509_set_pubkey(cert.get(), key.get()));
  X509_NAME* name = X509_get_subject_name(cert.get());
  CHECK(X509_NAME_add_entry_by_txt(name, "C", MBSTRING_ASC,
          reinterpret_cast<const uint8_t*>("US"), -1, -1, 0));
  CHECK(X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_UTF8,
          reinterpret_cast<const uint8_t*>(subject.c_str()), -1, -1, 0));
  // Exercise multivalued RDNs, non-ASCII strings and differing input encodings.
  CHECK(X509_NAME_add_entry_by_txt(name, "OU", MBSTRING_ASC,
          reinterpret_cast<const uint8_t*>("Trust Test"), -1, -1, -1));
  CHECK(X509_set_issuer_name(cert.get(), name));
  if (!self_signed) {
    CHECK(X509_NAME_add_entry_by_txt(X509_get_issuer_name(cert.get()), "OU", MBSTRING_ASC,
            reinterpret_cast<const uint8_t*>("Other issuer"), -1, -1, 0));
  }
  if (padding) {
    bssl::UniquePtr<ASN1_OBJECT> oid(OBJ_txt2obj("1.2.3.4.5.6.7", 1));
    bssl::UniquePtr<ASN1_OCTET_STRING> value(ASN1_OCTET_STRING_new());
    std::string bytes(padding, 'x');
    CHECK(ASN1_OCTET_STRING_set(value.get(), reinterpret_cast<const uint8_t*>(bytes.data()), bytes.size()));
    bssl::UniquePtr<X509_EXTENSION> extension(X509_EXTENSION_create_by_OBJ(nullptr, oid.get(), 0, value.get()));
    CHECK(extension && X509_add_ext(cert.get(), extension.get(), -1));
  }
  CHECK(X509_sign(cert.get(), key.get(), EVP_sha256()) > 0);
  Certificate result;
  uint8_t* bytes = nullptr;
  int size = i2d_X509(cert.get(), &bytes);
  CHECK(size > 0);
  result.der.assign(reinterpret_cast<char*>(bytes), size);
  OPENSSL_free(bytes);
  result.hash = X509_NAME_hash_old(name);
  SHA256(reinterpret_cast<const uint8_t*>(result.der.data()), result.der.size(), result.digest.data());
  bssl::UniquePtr<BIO> bio(BIO_new(BIO_s_mem()));
  CHECK(PEM_write_bio_X509(bio.get(), cert.get()));
  const uint8_t* contents;
  size_t length;
  CHECK(BIO_mem_contents(bio.get(), &contents, &length));
  result.pem.assign(reinterpret_cast<const char*>(contents), length);
  return result;
}

fs::path Output() { return trust_test::root / "system/etc/security/cacerts"; }
void Put(const char* store, const char* key, const Certificate& certificate, const char* usage = "") {
  Write(trust_test::root / "stores" / store / (std::string(key) + ".der"), certificate.der);
  Write(trust_test::root / "stores" / store / (std::string(key) + ".eku"), usage);
}
void Remove(const char* store, const char* key) {
  fs::remove(trust_test::root / "stores" / store / (std::string(key) + ".der"));
  fs::remove(trust_test::root / "stores" / store / (std::string(key) + ".eku"));
}
bool Sync() {
#if TRUST_SYNC_RETURNS_BOOL
  return gesher::net::SyncSystemCaCerts();
#else
  gesher::net::SyncSystemCaCerts();
  return true;
#endif
}
std::map<std::string, std::string> Expected(const std::vector<Certificate>& values) {
  std::map<Digest, const Certificate*> ordered;
  for (const auto& certificate : values) ordered[certificate.digest] = &certificate;
  std::map<unsigned long, int> next;
  std::map<std::string, std::string> output;
  for (const auto& [digest, certificate] : ordered) {
    char name[32];
    std::snprintf(name, sizeof(name), "%08lx.%d", certificate->hash, next[certificate->hash]++);
    output[name] = certificate->pem;
  }
  return output;
}
void Verify(const std::vector<Certificate>& expected) {
  std::map<std::string, std::string> actual;
  for (const auto& entry : fs::directory_iterator(Output())) {
    std::string name = entry.path().filename().string();
    if (name.size() >= 10 && name[8] == '.') actual.emplace(name, Read(entry.path()));
    CHECK(name.find(".tmp-") == std::string::npos);
  }
  if (actual != Expected(expected)) {
    for (const auto& [name, pem] : actual) std::fprintf(stderr, "actual %s %zu\n", name.c_str(), pem.size());
    for (const auto& [name, pem] : Expected(expected)) std::fprintf(stderr, "expected %s %zu\n", name.c_str(), pem.size());
  }
  CHECK(actual == Expected(expected));
}
std::string Hex(const Digest& digest) {
  std::string result;
  for (uint8_t byte : digest) {
    char text[3]; std::snprintf(text, sizeof(text), "%02x", byte); result += text;
  }
  return result;
}
void Reset(const fs::path& root) {
  fs::remove_all(root);
  fs::create_directories(root);
  SetRoot(root);
  counts = {};
  fault = Fault::kNone;
}

void Contracts(const fs::path& root, const std::string& executable) {
  Reset(root);
  // No optional stores/bundle directory on a fresh fixture is an empty set.
  CHECK(Sync()); Verify({}); CHECK(counts.stores == 8);
  const Certificate android = MakeCertificate(1, "Android fixture", 0);
  const Certificate user = MakeCertificate(2, "User root");
  const Certificate machine = MakeCertificate(3, "Machine root");
  const Certificate client_only = MakeCertificate(4, "Restricted root");
  const Certificate no_purpose = MakeCertificate(5, "Disabled root");
  const Certificate server = MakeCertificate(6, "Server root");
  const Certificate intermediate = MakeCertificate(7, "Intermediate", 2, false);
  const Certificate collision = MakeCertificate(8, "User root");
  const Certificate unicode = MakeCertificate(9, "Unicode \xc3\x96 root");
  Write(root / "cacerts/android.0", android.pem);
  Put("user-ROOT", "user", user);
  Put("machine-AuthRoot", "duplicate", android);
  Put("machine-CA", "machine", machine);
  Put("machine-CA", "intermediate", intermediate);
  Put("user-ROOT", "client", client_only, "client");
  Put("user-ROOT", "none", no_purpose, "none");
  Put("user-ROOT", "server", server, "server");
  Put("machine-Disallowed", "android", android);
  counts = {};
  CHECK(Sync()); Verify({user, machine, server});
  CHECK(counts.decode == 3 && counts.encode == 3);
  std::string manifest = Read(Output() / ".gesher-cacerts");
  counts = {};
  CHECK(Sync()); Verify({user, machine, server});
  CHECK(counts.decode == 0 && counts.encode == 0 && counts.read_pem == 0 && counts.writes == 0);
  CHECK(Read(Output() / ".gesher-cacerts") == manifest);

  Remove("machine-Disallowed", "android");
  counts = {};
  CHECK(Sync()); Verify({android, user, machine, server});
  CHECK(counts.decode == 1 && counts.encode == 1);
  Remove("user-ROOT", "user");
  counts = {};
  CHECK(Sync()); Verify({android, machine, server});
  CHECK(counts.decode == 0 && counts.encode == 0);
  Put("user-Disallowed", "server", server);
  CHECK(Sync()); Verify({android, machine});
  // Re-read policy, both locations, GESHER_WINDOWS_CERTS and ANDROID_ROOT.
  Environment("GESHER_WINDOWS_CERTS", "0");
  counts = {};
  CHECK(Sync()); Verify({android}); CHECK(counts.stores == 2);
  Put("machine-Disallowed", "android", android);
  CHECK(Sync()); Verify({});
  Environment("GESHER_WINDOWS_CERTS", "1");
  Remove("machine-Disallowed", "android");
  Remove("user-Disallowed", "server");
  Put("machine-ROOT", "unicode", unicode);
  Put("user-ROOT", "user", user);
  Put("user-ROOT", "collision", collision);
  std::vector<Certificate> admitted{android, user, collision, machine, server, unicode};
  CHECK(Sync()); Verify(admitted);
  counts = {};
  CHECK(Sync()); Verify(admitted);
  CHECK(counts.decode == 0 && counts.encode == 0);
  fault = Fault::kExternalEnd;
  CHECK(Sync()); Verify(admitted); CHECK(fault == Fault::kNone);

  // Correct certificate bytes under the wrong derived hash must be repaired.
  auto first = Expected(admitted).begin()->first;
  const std::string original = Read(Output() / first);
  manifest = Read(Output() / ".gesher-cacerts");
  std::string wrong = manifest;
  auto offset = wrong.find(first);
  CHECK(offset != std::string::npos);
  wrong.replace(offset, first.size(), "00000000.999");
  fs::rename(Output() / first, Output() / "00000000.999");
  Write(Output() / ".gesher-cacerts", wrong);
  counts = {};
  CHECK(Sync()); Verify(admitted);
  CHECK(counts.decode == 1 && counts.encode == 1);

  // A forged index cannot grant trust to an unrelated, well-formed certificate.
  const Certificate forged = MakeCertificate(90, "Not an admitted root");
  Write(Output() / first, forged.pem);
  CHECK(Sync()); Verify(admitted);
  Write(Output() / first, original + forged.pem);
  CHECK(Sync()); Verify(admitted);
  Write(Output() / first, "invalid PEM\n");
  CHECK(Sync()); Verify(admitted);
  Write(Output() / ".gesher-cacerts", "../../outside " + Hex(forged.digest) + "\n");
  CHECK(Sync()); Verify(admitted);
  Write(Output() / "deadbeef.17", forged.pem);
  Write(Output() / ".gesher-cacerts", Read(Output() / ".gesher-cacerts") +
        "deadbeef.17 " + Hex(forged.digest) + "\n");
  CHECK(Sync()); Verify(admitted);

  // Optional cache limits never truncate authoritative input or trust.
  const Certificate large = MakeCertificate(91, "Large supported root", 2, true, 1600000);
  CHECK(large.pem.size() > 2 * 1024 * 1024);
  Put("user-ROOT", "large", large);
  admitted.push_back(large);
  CHECK(Sync()); Verify(admitted);
  counts = {};
  CHECK(Sync()); Verify(admitted);
  CHECK(counts.decode == 1 && counts.encode == 1);  // cache bypass only
  Remove("user-ROOT", "large"); admitted.pop_back();
  CHECK(Sync()); Verify(admitted);
  Write(Output() / ".gesher-cacerts", std::string(2 * 1024 * 1024 + 1, 'x'));
  CHECK(Sync()); Verify(admitted);

  for (Fault source_failure : {Fault::kStoreDenied, Fault::kDisallowedDenied, Fault::kStoreUnsupported,
                              Fault::kEnumeration, Fault::kPartialEnumeration, Fault::kUsage, Fault::kWait}) {
    manifest = Read(Output() / ".gesher-cacerts");
    fault = source_failure;
    CHECK(!Sync());
    CHECK(fault == Fault::kNone);
    CHECK(Read(Output() / ".gesher-cacerts") == manifest);
    Verify(admitted);
    CHECK(Sync());
  }
  // Removing a source must not report success when its old output cannot go.
  Remove("user-ROOT", "collision");
  fault = Fault::kDelete;
  CHECK(!Sync()); CHECK(fault == Fault::kNone);
  admitted.erase(std::remove_if(admitted.begin(), admitted.end(), [&](const Certificate& c) {
    return c.der == collision.der;
  }), admitted.end());
  CHECK(Sync()); Verify(admitted);
  for (Fault publication_failure : {Fault::kWrite, Fault::kShortWrite,
                                   Fault::kRename, Fault::kManifestRename}) {
    Put("machine-ROOT", "change", collision);
    manifest = Read(Output() / ".gesher-cacerts");
    fault = publication_failure;
    CHECK(!Sync()); CHECK(fault == Fault::kNone);
    CHECK(Read(Output() / ".gesher-cacerts") == manifest);
    // Every visible file is complete even after a partial publication.
    for (const auto& entry : fs::directory_iterator(Output())) {
      auto name = entry.path().filename().string();
      CHECK(name.find(".tmp-") == std::string::npos);
      if (name.size() >= 10 && name[8] == '.') {
        std::string data = Read(entry.path());
        bssl::UniquePtr<BIO> bio(BIO_new_mem_buf(data.data(), data.size()));
        bssl::UniquePtr<X509> cert(PEM_read_bio_X509(bio.get(), nullptr, nullptr, nullptr));
        CHECK(cert != nullptr);
      }
    }
    CHECK(Sync());
    auto next = admitted; next.push_back(collision); Verify(next);
    Remove("machine-ROOT", "change"); CHECK(Sync()); Verify(admitted);
  }

  CHECK(on_load != nullptr);
  size_t loads = conscrypt_loads;
  fault = Fault::kWait;
  CHECK(on_load(nullptr, nullptr) == JNI_ERR && conscrypt_loads == loads);
  CHECK(on_load(nullptr, nullptr) == 0x00010006 && conscrypt_loads == loads + 1);

  // A revoked root can occupy the exact hash/index needed by its replacement.
  // Even when publication of the replacement fails, the revoked bytes must go.
  SetRoot(root / "replacement");
  Put("user-ROOT", "old", user);
  CHECK(Sync()); Verify({user});
  Remove("user-ROOT", "old");
  Put("user-ROOT", "new", collision);
  fault = Fault::kRename;
  CHECK(!Sync()); CHECK(fault == Fault::kNone); Verify({});
  CHECK(Sync()); Verify({collision});
  SetRoot(root);

  // Independent processes use actual named Win32 mutexes/files in the Windows
  // gate. Force the first holder to linger so concurrent launches overlap.
  Environment("TRUST_TEST_LOCK_PAUSE", "30");
  fs::remove_all(Output());
  fs::remove_all(root / "receipts");
  std::vector<std::future<int>> children;
  for (int i = 0; i < 4; ++i) {
    std::string command = "\"" + executable + "\" --child \"" + root.string() + "\"";
#ifdef _WIN32
    command = "\"" + command + "\"";
#endif
    children.push_back(std::async(std::launch::async, [command] { return std::system(command.c_str()); }));
  }
  for (auto& child : children) CHECK(child.get() == 0);
  Environment("TRUST_TEST_LOCK_PAUSE", "0");
  size_t processes = 0, decodes = 0, encodes = 0, waiters = 0;
  for (const auto& entry : fs::directory_iterator(root / "receipts")) {
    unsigned long long decode = 0, encode = 0, waits = 0;
    CHECK(std::sscanf(Read(entry.path()).c_str(), "%llu %llu %llu", &decode, &encode, &waits) == 3);
    ++processes; decodes += decode; encodes += encode; waiters += waits;
  }
  CHECK(processes == 4 && waiters == 4);
  CHECK(decodes == admitted.size() && encodes == admitted.size());
  Verify(admitted);
  counts = {};
  CHECK(Sync()); CHECK(counts.decode == 0 && counts.encode == 0 && counts.writes == 0);
  SetRoot(root / "second-session");
  Put("user-ROOT", "isolated", forged);
  CHECK(Sync()); Verify({forged});
  SetRoot(root);
  CHECK(Sync()); Verify(admitted);
  std::printf("trust-store contracts: PASS (%zu assertions)\n", checks);
}

void Measure(const fs::path& root) {
  Reset(root);
  std::vector<Certificate> certificates;
  for (int i = 0; i < 128; ++i) {
    certificates.push_back(MakeCertificate(1000 + i, "Measurement " + std::to_string(i)));
    if (i % 2) Put("user-ROOT", std::to_string(i).c_str(), certificates.back());
    else Write(root / "cacerts" / (std::to_string(i) + ".pem"), certificates.back().pem);
  }
  auto sample = [&](const char* name, int iteration) {
    counts = {};
    auto cpu_micros = []() -> uint64_t {
#ifdef _WIN32
      FILETIME created, exited, kernel, user;
      CHECK(GetProcessTimes(GetCurrentProcess(), &created, &exited, &kernel, &user));
      auto ticks = [](FILETIME t) { return (uint64_t{t.dwHighDateTime} << 32) | t.dwLowDateTime; };
      return (ticks(kernel) + ticks(user)) / 10;
#else
      timespec time;
      CHECK(clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &time) == 0);
      return static_cast<uint64_t>(time.tv_sec) * 1000000 + time.tv_nsec / 1000;
#endif
    };
    uint64_t cpu_start = cpu_micros();
    auto start = std::chrono::steady_clock::now();
    CHECK(Sync());
    auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count();
    uint64_t cpu = cpu_micros() - cpu_start;
    std::printf("MEASURE,%s,%d,%lld,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu\n", name, iteration,
        static_cast<long long>(elapsed), static_cast<unsigned long long>(cpu), static_cast<unsigned long long>(counts.decode),
        static_cast<unsigned long long>(counts.encode), static_cast<unsigned long long>(counts.read_pem),
        static_cast<unsigned long long>(counts.stores), static_cast<unsigned long long>(counts.enumerated),
        static_cast<unsigned long long>(counts.opens), static_cast<unsigned long long>(counts.reads),
        static_cast<unsigned long long>(counts.read_bytes), static_cast<unsigned long long>(counts.writes));
  };
  std::puts("MEASURE,case,iteration,microseconds,cpu_microseconds,x509_decode,pem_encode,pem_x509_read,store_opens,enumerated,file_opens,file_reads,read_bytes,file_writes");
  sample("cold", 0);
  for (int i = 0; i < 8; ++i) sample("warm", i);
  Put("machine-ROOT", "added", MakeCertificate(2000, "Added root"));
  sample("addition", 0);
  Remove("machine-ROOT", "added");
  sample("removal", 0);
}

void Corpus(const fs::path& root, const fs::path& corpus) {
  Reset(root);
  std::vector<Certificate> certificates;
  for (const auto& entry : fs::directory_iterator(corpus)) {
    if (!entry.is_regular_file()) continue;
    std::string input = Read(entry.path());
    bssl::UniquePtr<BIO> bio(BIO_new_mem_buf(input.data(), input.size()));
    bssl::UniquePtr<X509> cert(PEM_read_bio_X509(bio.get(), nullptr, nullptr, nullptr));
    if (!cert) continue;
    Certificate value;
    uint8_t* bytes = nullptr;
    int size = i2d_X509(cert.get(), &bytes);
    CHECK(size > 0);
    value.der.assign(reinterpret_cast<const char*>(bytes), size);
    OPENSSL_free(bytes);
    value.hash = X509_NAME_hash_old(X509_get_subject_name(cert.get()));
    SHA256(reinterpret_cast<const uint8_t*>(value.der.data()), value.der.size(), value.digest.data());
    bio.reset(BIO_new(BIO_s_mem()));
    CHECK(PEM_write_bio_X509(bio.get(), cert.get()));
    const uint8_t* data;
    size_t length;
    CHECK(BIO_mem_contents(bio.get(), &data, &length));
    value.pem.assign(reinterpret_cast<const char*>(data), length);
    Write(root / "cacerts" / entry.path().filename(), input);
    certificates.push_back(std::move(value));
  }
  CHECK(certificates.size() >= 100);
  CHECK(Sync()); Verify(certificates);
  counts = {};
  CHECK(Sync()); Verify(certificates);
  CHECK(counts.decode == 0 && counts.encode == 0 && counts.writes == 0);
  std::printf("trust-store Android corpus: PASS (%zu certificates, independently derived X509 hashes)\n", certificates.size());
}
}

extern "C" void gesher_register_builtin_library(const char* name,
          const gesher_builtin_symbol* symbols, size_t count) {
  if (std::string(name) == "libjavacrypto.so") {
    for (size_t i = 0; i < count; ++i) {
      if (std::string(symbols[i].name) == "JNI_OnLoad") on_load = reinterpret_cast<jint (*)(JavaVM*, void*)>(symbols[i].address);
    }
  }
}
jint JNI_OnLoad_conscrypt(JavaVM*, void*) { ++conscrypt_loads; return 0x00010006; }

int main(int argc, char** argv) {
  if (argc == 3 && std::string(argv[1]) == "--child") {
    SetRoot(argv[2]);
    if (!Sync()) return 1;
    Write(trust_test::root / "receipts" / std::to_string(GetCurrentProcessId()),
          std::to_string(counts.decode) + " " + std::to_string(counts.encode) + " " + std::to_string(counts.waits));
    return 0;
  }
  const fs::path root = fs::temp_directory_path() / ("gesher-trust-contract-" + std::to_string(GetCurrentProcessId()) + "-" +
      std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
  const bool measure_only = argc == 2 && std::string(argv[1]) == "--measure-only";
  if (!measure_only) Contracts(root / "contracts", fs::absolute(argv[0]).string());
  if (argc == 3 && std::string(argv[1]) == "--corpus") Corpus(root / "corpus", fs::absolute(argv[2]));
  Measure(root / "measure");
  fs::remove_all(root);
  return 0;
}
