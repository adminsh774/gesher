// Isolated Linux-host Win32 boundary for failure/concurrency tests. This header
// is never included by production or by the real-Windows contract executable.
#pragma once
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <thread>
#include <vector>

#define CALLBACK
#define __declspec(x)
using DWORD = uint32_t;
using BOOL = int;
using BOOLEAN = unsigned char;
using HANDLE = void*;
using HMODULE = void*;
using FARPROC = void (*)();
constexpr BOOL FALSE = 0;
constexpr DWORD ERROR_SUCCESS = 0, ERROR_INVALID_PARAMETER = 87, ERROR_INVALID_HANDLE = 6,
    ERROR_NOT_ENOUGH_MEMORY = 8, ERROR_ALREADY_EXISTS = 183, ERROR_NOT_FOUND = 1168,
    ERROR_ARITHMETIC_OVERFLOW = 534, ERROR_IO_INCOMPLETE = 996, ERROR_PROC_NOT_FOUND = 127,
    ERROR_ACCESS_DENIED = 5, ERROR_IO_PENDING = 997, ERROR_INVALID_DATA = 13;
constexpr DWORD WAIT_OBJECT_0 = 0, WAIT_TIMEOUT = 258, WAIT_FAILED = 0xffffffffu,
    INFINITE = 0xffffffffu, STILL_ACTIVE = 259, WT_EXECUTEONLYONCE = 8,
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000, SYNCHRONIZE = 0x100000, HEAP_ZERO_MEMORY = 8;
#define INVALID_HANDLE_VALUE reinterpret_cast<HANDLE>(intptr_t{-1})
constexpr DWORD MEM_COMMIT = 0x1000, PAGE_GUARD = 0x100, PAGE_READWRITE = 4,
    PAGE_WRITECOPY = 8, PAGE_EXECUTE_READWRITE = 64, PAGE_EXECUTE_WRITECOPY = 128;
struct FILETIME { DWORD dwLowDateTime = 0, dwHighDateTime = 0; };
struct MEMORY_BASIC_INFORMATION { void* BaseAddress; size_t RegionSize; DWORD State, Protect; };
struct PROCESS_MEMORY_COUNTERS { DWORD cb = 0; size_t PeakWorkingSetSize = 0; DWORD PageFaultCount = 0; };
using SRWLOCK = std::shared_mutex;
#define SRWLOCK_INIT {}
inline void AcquireSRWLockExclusive(SRWLOCK* lock) { lock->lock(); }
inline void ReleaseSRWLockExclusive(SRWLOCK* lock) { lock->unlock(); }
inline void AcquireSRWLockShared(SRWLOCK* lock) { lock->lock_shared(); }
inline void ReleaseSRWLockShared(SRWLOCK* lock) { lock->unlock_shared(); }

namespace boundary {
struct Process {
  DWORD pid, code;
  uint64_t birth, user, kernel;
  std::atomic<bool> live{true}, fail_sample{false};
  std::atomic<unsigned> handles{1};
};
struct Watch { Process* process; void (*callback)(void*, BOOLEAN); void* context; bool fired = false; };
inline std::vector<std::unique_ptr<Process>> processes;
inline std::vector<std::unique_ptr<Watch>> watches;
inline thread_local DWORD error = 0;
inline bool fail_duplicate = false, fail_watch = false;
inline DWORD next_pid = 100;
inline uint64_t next_birth = 1000;
inline uint64_t next_user = 123456789, next_kernel = 23456789;
inline Process* current = nullptr;
inline Process thread{2, 0, 2000, 3456789, 987654};
inline std::atomic<unsigned> process_time_calls{0}, thread_time_calls{0};
inline FILETIME Time(uint64_t v) { return {static_cast<DWORD>(v), static_cast<DWORD>(v >> 32)}; }
inline HANDLE Spawn(DWORD code) {
  auto p = std::make_unique<Process>();
  p->pid = ++next_pid; p->code = code; p->birth = ++next_birth;
  p->user = next_user; p->kernel = next_kernel;
  auto* result = p.get();
  processes.push_back(std::move(p));
  return result;
}
inline void Finish(HANDLE h) {
  auto* p = static_cast<Process*>(h);
  p->live = false;
  for (auto& watch : watches) {
    if (watch->process == p && !watch->fired) {
      watch->fired = true;
      watch->callback(watch->context, 0);
    }
  }
}
}
inline DWORD GetLastError() { return boundary::error; }
inline DWORD GetCurrentProcessId() { return 1; }
inline HANDLE GetCurrentProcess() { return boundary::current; }
inline HANDLE GetCurrentThread() { return &boundary::thread; }
inline DWORD GetProcessId(HANDLE h) {
  if (!h || h == INVALID_HANDLE_VALUE) { boundary::error = ERROR_INVALID_HANDLE; return 0; }
  return static_cast<boundary::Process*>(h)->pid;
}
inline BOOL DuplicateHandle(HANDLE, HANDLE h, HANDLE, HANDLE* result, DWORD, BOOL, DWORD) {
  if (boundary::fail_duplicate || !h || h == INVALID_HANDLE_VALUE) {
    boundary::error = ERROR_INVALID_HANDLE; return FALSE;
  }
  ++static_cast<boundary::Process*>(h)->handles;
  *result = h;
  return 1;
}
inline BOOL CloseHandle(HANDLE h) {
  if (!h || h == INVALID_HANDLE_VALUE || !static_cast<boundary::Process*>(h)->handles.fetch_sub(1)) std::abort();
  return 1;
}
inline BOOL FixtureTimes(HANDLE h, FILETIME* created, FILETIME* exited, FILETIME* kernel, FILETIME* user) {
  auto* p = static_cast<boundary::Process*>(h);
  if (!p || !p->handles || p->fail_sample) { boundary::error = ERROR_ACCESS_DENIED; return FALSE; }
  *created = boundary::Time(p->birth); *exited = boundary::Time(p->live ? 0 : p->birth + 1);
  *kernel = boundary::Time(p->kernel); *user = boundary::Time(p->user);
  return 1;
}
inline BOOL GetProcessTimes(HANDLE h, FILETIME* a, FILETIME* b, FILETIME* c, FILETIME* d) {
  ++boundary::process_time_calls;
  return FixtureTimes(h, a, b, c, d);
}
inline BOOL GetThreadTimes(HANDLE h, FILETIME* a, FILETIME* b, FILETIME* c, FILETIME* d) {
  ++boundary::thread_time_calls;
  return FixtureTimes(h, a, b, c, d);
}
inline BOOL GetProcessMemoryInfo(HANDLE, PROCESS_MEMORY_COUNTERS*, DWORD) { return FALSE; }
inline DWORD WaitForSingleObject(HANDLE h, DWORD timeout) {
  auto* p = static_cast<boundary::Process*>(h);
  if (!p || !p->handles) { boundary::error = ERROR_INVALID_HANDLE; return WAIT_FAILED; }
  while (p->live && timeout == INFINITE) std::this_thread::yield();
  return p->live ? WAIT_TIMEOUT : WAIT_OBJECT_0;
}
inline BOOL GetExitCodeProcess(HANDLE h, DWORD* code) {
  auto* p = static_cast<boundary::Process*>(h);
  *code = p->live ? STILL_ACTIVE : p->code;
  return 1;
}
inline BOOL RegisterWaitForSingleObject(HANDLE* handle, HANDLE process, void (*callback)(void*, BOOLEAN),
                                       void* context, DWORD, DWORD) {
  if (boundary::fail_watch) { boundary::error = ERROR_NOT_ENOUGH_MEMORY; return FALSE; }
  auto watch = std::make_unique<boundary::Watch>();
  watch->process = static_cast<boundary::Process*>(process); watch->callback = callback; watch->context = context;
  *handle = watch.get(); boundary::watches.push_back(std::move(watch));
  return 1;
}
inline BOOL UnregisterWaitEx(HANDLE, HANDLE) { return 1; }
inline HANDLE GetProcessHeap() { return nullptr; }
inline void* HeapAlloc(HANDLE, DWORD, size_t bytes) { return std::calloc(1, bytes); }
inline BOOL HeapFree(HANDLE, DWORD, void* block) { std::free(block); return 1; }
inline HMODULE GetModuleHandleW(const wchar_t*) { return nullptr; }
inline FARPROC GetProcAddress(HMODULE, const char* name) { return reinterpret_cast<FARPROC>(dlsym(RTLD_DEFAULT, name)); }
inline size_t VirtualQuery(const void* p, MEMORY_BASIC_INFORMATION* region, size_t size) {
  if (reinterpret_cast<uintptr_t>(p) < 4096) return 0;
  *region = {const_cast<void*>(p), size_t{1} << 20, MEM_COMMIT, PAGE_READWRITE}; return size;
}
inline uint64_t GetTickCount64() { return 1000; }
inline void Sleep(DWORD ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }
inline int _fileno(FILE*) { return -1; }
inline intptr_t _get_osfhandle(int) { return -1; }
inline BOOL WriteFile(HANDLE, const void*, DWORD, DWORD*, void*) { return FALSE; }
