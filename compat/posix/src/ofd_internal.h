// gesher-base: Open File Description (OFD) semantics and status flags tracking.
#pragma once

#include <fcntl.h>
#include <atomic>
#include <memory>
#include <mutex>

namespace gesher {

struct OpenFileDescription {
  std::atomic<int> status_flags{0};
  std::mutex mutex;
};

// Flags mask for OFD storage: access mode, O_PATH, and status flags.
inline constexpr int kFileStatusFlagsMask =
    O_APPEND | O_NONBLOCK | O_SYNC | O_DSYNC | O_DIRECT | O_NOATIME | O_ASYNC | O_LARGEFILE;
inline constexpr int kOpenDescriptionFlagsMask =
    O_ACCMODE | O_PATH | kFileStatusFlagsMask;
inline constexpr int kModifiableStatusFlags =
    O_APPEND | O_NONBLOCK | O_DIRECT | O_NOATIME | O_ASYNC;

// Registers an OFD for fd with the given initial flags.
void RegisterOfd(int fd, int flags);
void RegisterOfd(int fd, int flags, std::shared_ptr<OpenFileDescription> existing);

// Duplicates the OFD from old_fd to new_fd (shared ownership).
void DupOfd(int old_fd, int new_fd);

// Drops the OFD mapping for fd (decrements shared ownership).
void ForgetOfd(int fd);

// Looks up the OFD for fd.
bool LookupOfd(int fd, std::shared_ptr<OpenFileDescription>* ofd);

// Gets status flags for fd (creating lazily from handle access if not yet registered).
int GetFileStatusFlags(int fd);

// Sets status flags for fd via F_SETFL (modifying only kModifiableStatusFlags).
int SetFileStatusFlags(int fd, int arg);

// Checks if fd has O_APPEND set on its OFD.
bool IsAppendMode(int fd);

// Checks if fd has O_NONBLOCK set on its OFD.
bool IsNonBlockingMode(int fd);

}  // namespace gesher
