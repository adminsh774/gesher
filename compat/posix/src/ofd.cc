// gesher-base: Open File Description (OFD) table and status flags implementation.
#include "ofd_internal.h"

#include <errno.h>
#include <fcntl.h>
#include <windows.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>

#include "internal.h"
#include "record_lock_liveness.h"

namespace gesher {
namespace {

std::mutex g_ofd_mutex;
std::unordered_map<int, std::shared_ptr<OpenFileDescription>> g_ofd_table;

std::shared_ptr<OpenFileDescription> GetOrCreateOfdLocked(int fd, HANDLE h) {
  auto it = g_ofd_table.find(fd);
  if (it != g_ofd_table.end()) {
    return it->second;
  }
  if (h == INVALID_HANDLE_VALUE) {
    return nullptr;
  }
  int flags = O_RDWR;
  uint32_t access = 0;
  if (record_locks::HandleAccess(h, &access) == 0) {
    const bool r = (access & (GENERIC_READ | FILE_READ_DATA)) != 0;
    const bool w = (access & (GENERIC_WRITE | FILE_WRITE_DATA)) != 0;
    if (r && w) {
      flags = O_RDWR;
    } else if (w) {
      flags = O_WRONLY;
    } else if (r) {
      flags = O_RDONLY;
    }
  }
  auto ofd = std::make_shared<OpenFileDescription>();
  ofd->status_flags.store(flags);
  g_ofd_table[fd] = ofd;
  return ofd;
}

}  // namespace

void RegisterOfd(int fd, int flags, std::shared_ptr<OpenFileDescription> existing) {
  if (fd < 0) return;
  std::lock_guard<std::mutex> lock(g_ofd_mutex);
  if (existing) {
    g_ofd_table[fd] = std::move(existing);
  } else {
    auto ofd = std::make_shared<OpenFileDescription>();
    ofd->status_flags.store(flags & kOpenDescriptionFlagsMask);
    g_ofd_table[fd] = std::move(ofd);
  }
}

void RegisterOfd(int fd, int flags) {
  RegisterOfd(fd, flags, nullptr);
}

void DupOfd(int old_fd, int new_fd) {
  if (old_fd < 0 || new_fd < 0) return;
  std::lock_guard<std::mutex> lock(g_ofd_mutex);
  auto it = g_ofd_table.find(old_fd);
  if (it != g_ofd_table.end()) {
    g_ofd_table[new_fd] = it->second;
    return;
  }
  HANDLE h = HandleFromFd(old_fd);
  if (h != INVALID_HANDLE_VALUE) {
    auto ofd = GetOrCreateOfdLocked(old_fd, h);
    if (ofd) {
      g_ofd_table[new_fd] = ofd;
    }
  }
}

void ForgetOfd(int fd) {
  if (fd < 0) return;
  std::lock_guard<std::mutex> lock(g_ofd_mutex);
  g_ofd_table.erase(fd);
}

bool LookupOfd(int fd, std::shared_ptr<OpenFileDescription>* out) {
  if (fd < 0) return false;
  std::lock_guard<std::mutex> lock(g_ofd_mutex);
  auto it = g_ofd_table.find(fd);
  if (it == g_ofd_table.end()) return false;
  if (out) *out = it->second;
  return true;
}

int GetFileStatusFlags(int fd) {
  HANDLE h = HandleFromFd(fd);
  if (h == INVALID_HANDLE_VALUE) return errno = EBADF, -1;

  std::lock_guard<std::mutex> lock(g_ofd_mutex);
  auto it = g_ofd_table.find(fd);
  if (it != g_ofd_table.end()) {
    return it->second->status_flags.load();
  }
  auto ofd = GetOrCreateOfdLocked(fd, h);
  return ofd ? ofd->status_flags.load() : (errno = EBADF, -1);
}

int SetFileStatusFlags(int fd, int arg) {
  HANDLE h = HandleFromFd(fd);
  if (h == INVALID_HANDLE_VALUE) return errno = EBADF, -1;

  std::shared_ptr<OpenFileDescription> ofd;
  {
    std::lock_guard<std::mutex> lock(g_ofd_mutex);
    auto it = g_ofd_table.find(fd);
    if (it != g_ofd_table.end()) {
      ofd = it->second;
    } else {
      ofd = GetOrCreateOfdLocked(fd, h);
    }
  }
  if (!ofd) return errno = EBADF, -1;

  std::lock_guard<std::mutex> ofd_lock(ofd->mutex);
  int old_flags = ofd->status_flags.load();
  int new_flags = (old_flags & ~kModifiableStatusFlags) | (arg & kModifiableStatusFlags);
  ofd->status_flags.store(new_flags);
  return 0;
}

bool IsAppendMode(int fd) {
  std::shared_ptr<OpenFileDescription> ofd;
  if (LookupOfd(fd, &ofd)) {
    return (ofd->status_flags.load() & O_APPEND) != 0;
  }
  return false;
}

bool IsNonBlockingMode(int fd) {
  std::shared_ptr<OpenFileDescription> ofd;
  if (LookupOfd(fd, &ofd)) {
    return (ofd->status_flags.load() & O_NONBLOCK) != 0;
  }
  return false;
}

}  // namespace gesher
