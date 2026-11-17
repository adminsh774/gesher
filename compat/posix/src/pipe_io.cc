// Message-type kernel pipes with byte-stream reads preserve PIPE_BUF admission.
// Windows owns capacity, peer lifetime and waiter completion across processes.
#include "pipe_io.h"
#include "internal.h"
#include "record_lock_descriptors.h"

#include <bcrypt.h>
#include <fcntl.h>
#include <io.h>
#include <poll.h>
#include <pthread.h>
#include <winternl.h>
#include <algorithm>
#include <cstdio>

namespace gesher {
namespace {
struct PipeInformation { ULONG read_mode, completion_mode; };
int QueryPipe(HANDLE file, void* value, ULONG size, FILE_INFORMATION_CLASS kind) {
  using Query = LONG(WINAPI*)(HANDLE, PIO_STATUS_BLOCK, void*, ULONG, FILE_INFORMATION_CLASS);
  using Error = ULONG(WINAPI*)(LONG);
  HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
  auto query = ntdll ? reinterpret_cast<Query>(reinterpret_cast<void*>(GetProcAddress(ntdll, "NtQueryInformationFile"))) : nullptr;
  auto convert = ntdll ? reinterpret_cast<Error>(reinterpret_cast<void*>(GetProcAddress(ntdll, "RtlNtStatusToDosError"))) : nullptr;
  if (!query || !convert) return errno = ENOSYS, -1;
  IO_STATUS_BLOCK status{};
  LONG result = query(file, &status, value, size, kind);
  return result < 0 ? (errno = ErrnoFromWin32(convert(result)), -1) : 0;
}

struct PipeIo {
  HANDLE file = nullptr, thread = nullptr;
  void* bytes = nullptr;
  DWORD count = 0, transferred = 0, error = ERROR_SUCCESS;
  bool writing = false;
};
DWORD WINAPI RunPipeIo(void* raw) {
  auto* operation = static_cast<PipeIo*>(raw);
  const BOOL done = operation->writing
      ? WriteFile(operation->file, operation->bytes, operation->count, &operation->transferred, nullptr)
      : ReadFile(operation->file, operation->bytes, operation->count, &operation->transferred, nullptr);
  operation->error = done ? ERROR_SUCCESS : GetLastError();
  return 0;
}
void CancelPipeIo(void* raw) {
  auto* operation = static_cast<PipeIo*>(raw);
  if (!operation->thread) return;
  // Cancellation can race the worker's entry into ReadFile/WriteFile. Retry
  // until the actual worker has exited; a single ERROR_NOT_FOUND is not proof
  // of retirement. Its caller-owned buffer cannot be released before then.
  while (WaitForSingleObject(operation->thread, 0) == WAIT_TIMEOUT) {
    CancelSynchronousIo(operation->thread);
    WaitForSingleObject(operation->thread, 1);
  }
  CloseHandle(operation->thread);
  operation->thread = nullptr;
}
}

int PipeStatusFlags(HANDLE file, int flags) {
  PipeInformation info{};
  if (QueryPipe(file, &info, sizeof(info), FilePipeInformation) < 0) return -1;
  return (flags & ~O_NONBLOCK) | (info.completion_mode != 0 ? O_NONBLOCK : 0);
}

int SetPipeNonBlocking(HANDLE file, bool nonblocking) {
  // Wait mode belongs to the kernel FILE_OBJECT, so dup and explicitly
  // inherited handles observe the same change without copying metadata.
  DWORD mode = PIPE_READMODE_BYTE | (nonblocking ? PIPE_NOWAIT : PIPE_WAIT);
  return SetNamedPipeHandleState(file, &mode, nullptr, nullptr) ? 0 : FailWithLastError();
}

int CreatePipeDescriptors(int descriptors[2], int flags) {
  if (!descriptors) return errno = EFAULT, -1;
  unsigned char nonce[16];
  if (!BCRYPT_SUCCESS(BCryptGenRandom(nullptr, nonce, sizeof(nonce), BCRYPT_USE_SYSTEM_PREFERRED_RNG)))
    return errno = EIO, -1;
  wchar_t name[100] = L"\\\\.\\pipe\\gesher-";
  constexpr wchar_t hex[] = L"0123456789abcdef";
  const size_t prefix = 16;
  for (size_t i = 0; i != sizeof(nonce); ++i) {
    name[prefix + 2 * i] = hex[nonce[i] >> 4];
    name[prefix + 2 * i + 1] = hex[nonce[i] & 15];
  }
  name[prefix + 2 * sizeof(nonce)] = 0;
  HANDLE server = CreateNamedPipeW(name, PIPE_ACCESS_DUPLEX | FILE_FLAG_FIRST_PIPE_INSTANCE,
      PIPE_TYPE_MESSAGE | PIPE_READMODE_BYTE | PIPE_REJECT_REMOTE_CLIENTS, 1, 65536, 65536, 0, nullptr);
  if (server == INVALID_HANDLE_VALUE) return FailWithLastError();
  HANDLE client = CreateFileW(name, GENERIC_READ | GENERIC_WRITE | FILE_WRITE_ATTRIBUTES,
      0, nullptr, OPEN_EXISTING, 0, nullptr);
  int error = client == INVALID_HANDLE_VALUE ? ErrnoFromWin32(GetLastError()) : 0;
  if (!error && !ConnectNamedPipe(server, nullptr) && GetLastError() != ERROR_PIPE_CONNECTED)
    error = ErrnoFromWin32(GetLastError());
  if (!error && (SetPipeNonBlocking(server, (flags & O_NONBLOCK) != 0) < 0 ||
                 SetPipeNonBlocking(client, (flags & O_NONBLOCK) != 0) < 0)) error = errno;
  // Restrict the published handles to their real directional data access.
  // Attribute access permits a later F_SETFL without granting opposite I/O.
  HANDLE handles[2] = {nullptr, nullptr};
  const DWORD access[2] = {FILE_READ_DATA, FILE_WRITE_DATA};
  const HANDLE endpoints[2] = {server, client};
  for (int i = 0; !error && i != 2; ++i) {
    if (!DuplicateHandle(GetCurrentProcess(), endpoints[i], GetCurrentProcess(), &handles[i],
          access[i] | FILE_READ_ATTRIBUTES | FILE_WRITE_ATTRIBUTES | SYNCHRONIZE,
          (flags & O_CLOEXEC) == 0, 0)) error = ErrnoFromWin32(GetLastError());
  }
  CloseHandle(server);
  if (client != INVALID_HANDLE_VALUE) CloseHandle(client);
  record_locks::DescriptorGuard guard;
  int slots[2] = {-1, -1};
  for (int i = 0; !error && i != 2; ++i) {
    slots[i] = _open_osfhandle(reinterpret_cast<intptr_t>(handles[i]), _O_BINARY |
        (i == 0 ? _O_RDONLY : _O_WRONLY) | ((flags & O_CLOEXEC) != 0 ? _O_NOINHERIT : 0));
    if (slots[i] < 0) { error = gesher_errno_from_crt(errno); break; }
    handles[i] = nullptr;
    if (RegisterOfd(slots[i], (i == 0 ? O_RDONLY : O_WRONLY) | (flags & O_NONBLOCK)) < 0) error = errno;
  }
  if (error) {
    for (int i = 0; i != 2; ++i) {
      if (handles[i]) CloseHandle(handles[i]);
      if (slots[i] >= 0) { _close(slots[i]); ForgetOfd(slots[i]); }
    }
    return errno = error, -1;
  }
  descriptors[0] = slots[0];
  descriptors[1] = slots[1];
  return 0;
}

int TransferPipe(HANDLE file, void* bytes, unsigned count, bool writing) {
  if (!count) return 0;
  if (!bytes) return errno = EFAULT, -1;
  int flags = PipeStatusFlags(file, 0);
  if (flags < 0) return -1;
  if (writing && (flags & O_NONBLOCK) != 0 && count > 4096) {
    FILE_PIPE_LOCAL_INFORMATION info{};
    if (QueryPipe(file, &info, sizeof(info), FilePipeLocalInformation) < 0) return -1;
    if (info.NamedPipeState != 3) return errno = EPIPE, -1;
    if (!info.WriteQuotaAvailable) return errno = EAGAIN, -1;
    count = std::min<unsigned>(count, info.WriteQuotaAvailable);
  }
  // Synchronous handles are required by arbitrary native UCRT children. Keep
  // their blocking kernel call on one joinable worker while the caller waits
  // at cancellation points. At most one worker exists per active transfer;
  // completion/cancellation always joins it before releasing any I/O storage.
  PipeIo operation;
  operation.file = file;
  operation.bytes = bytes;
  operation.count = count;
  operation.writing = writing;
  operation.thread = CreateThread(nullptr, 64 * 1024, RunPipeIo, &operation,
                                   STACK_SIZE_PARAM_IS_A_RESERVATION, nullptr);
  if (!operation.thread) return FailWithLastError();
  DWORD wait = WAIT_TIMEOUT;
  pthread_cleanup_push(CancelPipeIo, &operation);
  do {
    pthread_testcancel();
    wait = WaitForSingleObject(operation.thread, 10);
  } while (wait == WAIT_TIMEOUT);
  pthread_cleanup_pop(0);
  if (wait != WAIT_OBJECT_0) {
    const DWORD error = GetLastError();
    CancelPipeIo(&operation);
    return errno = ErrnoFromWin32(error), -1;
  }
  CloseHandle(operation.thread);
  if (operation.error == ERROR_SUCCESS || operation.error == ERROR_MORE_DATA) {
    if (writing && operation.transferred == 0) return errno = EAGAIN, -1;
    return static_cast<int>(operation.transferred);
  }
  if (operation.error == ERROR_BROKEN_PIPE || operation.error == ERROR_PIPE_NOT_CONNECTED)
    return writing ? (errno = EPIPE, -1) : 0;
  if (operation.error == ERROR_NO_DATA) return errno = writing ? EPIPE : EAGAIN, -1;
  if (operation.error == ERROR_OPERATION_ABORTED) return errno = EINTR, -1;
  if (operation.error == ERROR_NOACCESS || operation.error == ERROR_INVALID_USER_BUFFER)
    return errno = EFAULT, -1;
  return errno = ErrnoFromWin32(operation.error), -1;
}

short PollPipeHandle(HANDLE file, int flags, short events) {
  FILE_PIPE_LOCAL_INFORMATION info{};
  if (QueryPipe(file, &info, sizeof(info), FilePipeLocalInformation) < 0) return POLLERR;
  short result = 0;
  const bool disconnected = info.NamedPipeState != 3;
  if ((flags & O_ACCMODE) != O_WRONLY) {
    if (info.ReadDataAvailable) result |= events & (POLLIN | POLLRDNORM);
    if (disconnected) result |= POLLHUP;
  }
  if ((flags & O_ACCMODE) != O_RDONLY) {
    if (disconnected) result |= POLLERR;
    else if (info.WriteQuotaAvailable >= 4096) result |= events & (POLLOUT | POLLWRNORM);
  }
  return result;
}
}
