#include <gesher/child_usage.h>

#include <limits.h>

namespace gesher::child_usage {
namespace {

struct Child {
  Child* next;
  HANDLE process;
  Token token;
  DWORD pid;
  uint64_t created;
};

// No CRT destructor or DLL allocator owns the ledger. Records are released at
// successful collection/rollback; kernel teardown owns any genuinely unreaped
// children left when the containing process exits.
SRWLOCK mutex = SRWLOCK_INIT;
Child* children = nullptr;
Token next_token = 0;
Cpu total{};
DWORD unavailable = ERROR_SUCCESS;

uint64_t Time(FILETIME value) {
  return (static_cast<uint64_t>(value.dwHighDateTime) << 32) | value.dwLowDateTime;
}

DWORD RegisterOwned(HANDLE process, Token* token) {
  if (!token || !process || process == INVALID_HANDLE_VALUE) return ERROR_INVALID_PARAMETER;
  HANDLE retained = nullptr;
  if (!DuplicateHandle(GetCurrentProcess(), process, GetCurrentProcess(), &retained,
                       PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, FALSE, 0)) {
    DWORD error = GetLastError();
    return error ? error : ERROR_INVALID_HANDLE;
  }
  FILETIME created{}, exited{}, kernel{}, user{};
  DWORD pid = GetProcessId(retained);
  DWORD error = ERROR_SUCCESS;
  if (!pid || pid == GetCurrentProcessId() ||
      !GetProcessTimes(retained, &created, &exited, &kernel, &user)) {
    error = GetLastError();
    if (!error || pid == GetCurrentProcessId()) error = ERROR_INVALID_PARAMETER;
    CloseHandle(retained);
    return error;
  }
  auto* child = static_cast<Child*>(HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(Child)));
  if (!child) { CloseHandle(retained); return ERROR_NOT_ENOUGH_MEMORY; }
  child->process = retained;
  child->pid = pid;
  child->created = Time(created);
  AcquireSRWLockExclusive(&mutex);
  for (Child* existing = children; existing; existing = existing->next) {
    if (existing->pid == pid && existing->created == child->created) {
      error = ERROR_ALREADY_EXISTS;
      break;
    }
  }
  if (!error && next_token == UINT64_MAX) error = ERROR_ARITHMETIC_OVERFLOW;
  if (!error) {
    child->token = ++next_token;
    child->next = children;
    children = child;
    *token = child->token;
  }
  ReleaseSRWLockExclusive(&mutex);
  if (error) {
    CloseHandle(retained);
    HeapFree(GetProcessHeap(), 0, child);
  }
  return error;
}

DWORD Consume(Token* token, Cpu* cpu, bool collect, bool abandon_reaped = false) {
  if (!token || !*token || (collect && !cpu)) return ERROR_INVALID_PARAMETER;
  AcquireSRWLockExclusive(&mutex);
  if (abandon_reaped && !unavailable) unavailable = ERROR_INVALID_DATA;
  Child** link = &children;
  while (*link && (*link)->token != *token) link = &(*link)->next;
  if (!*link) { ReleaseSRWLockExclusive(&mutex); return ERROR_NOT_FOUND; }
  Child* child = *link;
  DWORD error = ERROR_SUCCESS;
  bool completed = !collect;
  Cpu sample{};
  if (collect) {
    DWORD state = WaitForSingleObject(child->process, 0);
    if (state != WAIT_OBJECT_0) {
      error = state == WAIT_TIMEOUT ? ERROR_IO_INCOMPLETE : GetLastError();
      if (!error) error = ERROR_INVALID_HANDLE;
    } else {
      completed = true;
      FILETIME created{}, exited{}, kernel{}, user{};
      if (!GetProcessTimes(child->process, &created, &exited, &kernel, &user)) {
        error = GetLastError();
        if (!error) error = ERROR_INVALID_HANDLE;
      } else if (Time(created) != child->created || GetProcessId(child->process) != child->pid)
        error = ERROR_INVALID_HANDLE;
      else {
        sample = {Time(user), Time(kernel)};
        if (sample.user_100ns > UINT64_MAX - total.user_100ns ||
            sample.kernel_100ns > UINT64_MAX - total.kernel_100ns)
          error = ERROR_ARITHMETIC_OVERFLOW;
      }
    }
  }
  if (completed) {
    if (collect && !error) {
      total.user_100ns += sample.user_100ns;
      total.kernel_100ns += sample.kernel_100ns;
      *cpu = sample;
    }
    if (error && !unavailable) unavailable = error;
    *link = child->next;
    *token = 0;
  }
  ReleaseSRWLockExclusive(&mutex);
  if (completed) {
    CloseHandle(child->process);
    HeapFree(GetProcessHeap(), 0, child);
  }
  // Once a completed child is reaped, sampling failure must not replace its
  // real exit status or leak its handles. Cumulative queries become unavailable
  // for the lifetime of this process rather than returning partial/zero totals.
  // A non-signaled process still has not been reaped and retains its record.
  return error;
}

DWORD Dispatch(Operation operation, HANDLE process, Token* token, Cpu* cpu) {
  switch (operation) {
    case Operation::kRegister: return RegisterOwned(process, token);
    case Operation::kCollect: return Consume(token, cpu, true);
    case Operation::kForget: return Consume(token, nullptr, false);
    case Operation::kAbandonReaped: return Consume(token, nullptr, false, true);
    case Operation::kSnapshot:
      if (!cpu) return ERROR_INVALID_PARAMETER;
      AcquireSRWLockShared(&mutex);
      DWORD error = unavailable;
      if (!error) *cpu = total;
      ReleaseSRWLockShared(&mutex);
      return error;
  }
  return ERROR_INVALID_PARAMETER;
}

}  // namespace
}  // namespace gesher::child_usage

// Force this private anchor into every executable, even if an import library
// could otherwise satisfy a reference to the public DLL export.
extern "C" void gesher_child_usage_owner_anchor_v1() {}
extern "C" __declspec(dllexport) DWORD gesher_child_usage_owner_v1(
    gesher::child_usage::Operation operation, HANDLE process,
    gesher::child_usage::Token* token, gesher::child_usage::Cpu* cpu) {
  return gesher::child_usage::Dispatch(operation, process, token, cpu);
}
