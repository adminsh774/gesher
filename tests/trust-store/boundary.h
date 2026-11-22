#pragma once
#include <atomic>
#include <filesystem>
#include <string>
#include <vector>
#include <cstdint>
namespace trust_test {
enum class Fault { kNone, kStoreDenied, kDisallowedDenied, kStoreUnsupported, kEnumeration,
                   kPartialEnumeration, kExternalEnd, kUsage, kWait,
                   kWrite, kShortWrite, kRename, kManifestRename, kDelete };
struct Counts { uint64_t decode = 0, encode = 0, read_pem = 0, stores = 0, enumerated = 0,
                       opens = 0, reads = 0, read_bytes = 0, writes = 0, waits = 0; };
extern Counts counts;
extern Fault fault;
extern std::filesystem::path root;
std::string Read(const std::filesystem::path&);
void Write(const std::filesystem::path&, const std::string&);
void SetRoot(const std::filesystem::path&);
}
