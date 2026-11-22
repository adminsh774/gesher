// Process-owned CPU accounting for genuinely spawned, successfully reaped children.
// Values are kept in Win32's integer 100 ns unit; convert only at the public ABI.
#pragma once

#include <stdint.h>
#include <windows.h>

namespace gesher::child_usage {

using Token = uint64_t;
struct Cpu {
  uint64_t user_100ns = 0;
  uint64_t kernel_100ns = 0;
};
static_assert(sizeof(Cpu) == 16);

enum class Operation : uint32_t { kRegister = 1, kCollect, kForget, kSnapshot, kAbandonReaped };
using Owner = DWORD (*)(Operation, HANDLE, Token*, Cpu*);

inline DWORD Call(Operation operation, HANDLE process, Token* token, Cpu* cpu) {
  // The executable owns one ledger. A private DLL copy must never silently
  // split Java, guest-Bionic and POSIX resource queries into different totals.
  static auto owner = reinterpret_cast<Owner>(
      GetProcAddress(GetModuleHandleW(nullptr), "gesher_child_usage_owner_v1"));
  return owner ? owner(operation, process, token, cpu) : ERROR_PROC_NOT_FOUND;
}
inline DWORD Register(HANDLE process, Token* token) {
  return Call(Operation::kRegister, process, token, nullptr);
}
inline DWORD Collect(Token token, Cpu* cpu, bool* consumed = nullptr) {
  const bool registered = token != 0;
  DWORD error = Call(Operation::kCollect, nullptr, &token, cpu);
  if (consumed) *consumed = registered && token == 0;
  return error;
}
inline DWORD Forget(Token token) {
  return Call(Operation::kForget, nullptr, &token, nullptr);
}
inline DWORD Snapshot(Cpu* cpu) {
  return Call(Operation::kSnapshot, nullptr, nullptr, cpu);
}
// A caller that has already observed a successful process wait must retire its
// resources even if the provider cannot finish. Never expose partial totals.
inline DWORD AbandonReaped(Token token) {
  return Call(Operation::kAbandonReaped, nullptr, &token, nullptr);
}

// Both the guest and gesher-base use Linux errno numbers, independently of
// which CRT a linked module uses. Provider calls return Win32 errors instead
// of setting another module's thread-local errno.
inline int LinuxError(DWORD error) {
  switch (error) {
    case ERROR_NOT_ENOUGH_MEMORY: return 12;  // ENOMEM
    case ERROR_PROC_NOT_FOUND: return 38;    // ENOSYS
    case ERROR_ARITHMETIC_OVERFLOW: return 75;  // EOVERFLOW
    case ERROR_IO_INCOMPLETE: return 11;     // EAGAIN
    case ERROR_INVALID_HANDLE:
    case ERROR_NOT_FOUND: return 10;         // ECHILD
    case ERROR_INVALID_PARAMETER: return 22; // EINVAL
    case ERROR_ACCESS_DENIED: return 13;     // EACCES
    case ERROR_ALREADY_EXISTS: return 17;    // EEXIST
    default: return 5;                      // EIO; never publish an invented zero
  }
}

inline bool Writable(const void* address, size_t length) {
  uintptr_t at = reinterpret_cast<uintptr_t>(address);
  if (!at || length > UINTPTR_MAX - at) return false;
  const uintptr_t end = at + length;
  while (at < end) {
    MEMORY_BASIC_INFORMATION region{};
    if (VirtualQuery(reinterpret_cast<const void*>(at), &region, sizeof(region)) != sizeof(region) ||
        region.State != MEM_COMMIT || (region.Protect & PAGE_GUARD)) return false;
    DWORD protection = region.Protect & 0xff;
    if (protection != PAGE_READWRITE && protection != PAGE_WRITECOPY &&
        protection != PAGE_EXECUTE_READWRITE && protection != PAGE_EXECUTE_WRITECOPY) return false;
    uintptr_t begin = reinterpret_cast<uintptr_t>(region.BaseAddress);
    if (region.RegionSize > UINTPTR_MAX - begin || begin + region.RegionSize <= at) return false;
    at = begin + region.RegionSize;
  }
  return true;
}

}  // namespace gesher::child_usage
