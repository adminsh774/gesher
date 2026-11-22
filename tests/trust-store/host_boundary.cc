// POSIX file/flock adapters for the complete production synchronizer. These
// adapters are test-only; they do not measure Windows kernel/provider latency.
#include <windows.h>
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <codecvt>
#include <cstring>
#include <filesystem>
#include <locale>
#include <string>
#include <thread>
#include <vector>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

namespace fs = std::filesystem;
namespace {
thread_local DWORD last_error;
DWORD Error() {
  switch (errno) {
    case ENOENT: return ERROR_FILE_NOT_FOUND;
    case ENOTDIR: return ERROR_PATH_NOT_FOUND;
    case EACCES: case EPERM: return ERROR_ACCESS_DENIED;
    case EEXIST: return ERROR_ALREADY_EXISTS;
    default: return ERROR_GEN_FAILURE;
  }
}
std::string Narrow(LPCWSTR text) {
  std::string value = std::wstring_convert<std::codecvt_utf8<wchar_t>>{}.to_bytes(text);
  std::replace(value.begin(), value.end(), '\\', '/');
  return value;
}
struct Handle {
  enum Kind { kFile, kMutex, kFind } kind;
  int fd = -1;
  std::vector<fs::directory_entry> entries;
  size_t position = 0;
  explicit Handle(Kind value) : kind(value) {}
};
bool Fill(Handle* handle, WIN32_FIND_DATAW* out) {
  if (handle->position == handle->entries.size()) {
    SetLastError(ERROR_NO_MORE_FILES);
    return false;
  }
  const auto& entry = handle->entries[handle->position++];
  auto name = std::wstring_convert<std::codecvt_utf8<wchar_t>>{}.from_bytes(entry.path().filename().string());
  if (name.size() >= MAX_PATH) std::abort();
  std::wmemcpy(out->cFileName, name.c_str(), name.size() + 1);
  out->dwFileAttributes = entry.is_directory() ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL;
  return true;
}
}
DWORD GetLastError() { return last_error; }
void SetLastError(DWORD value) { last_error = value; }
int MultiByteToWideChar(unsigned, DWORD, const char* input, int size, wchar_t* output, int capacity) {
  std::wstring value = std::wstring_convert<std::codecvt_utf8<wchar_t>>{}.from_bytes(input, input + size);
  if (output && capacity >= static_cast<int>(value.size())) std::copy(value.begin(), value.end(), output);
  return static_cast<int>(value.size());
}
HANDLE CreateMutexW(LPSECURITY_ATTRIBUTES, BOOL, LPCWSTR) {
  auto* handle = new Handle(Handle::kMutex);
  // One name shared by all fixture processes, matching production serialization.
  handle->fd = open((fs::temp_directory_path() / ("gesher-trust-test-" + std::to_string(getuid()) + ".lock")).c_str(),
                    O_CREAT | O_RDWR, 0600);
  if (handle->fd < 0) { delete handle; SetLastError(Error()); return nullptr; }
  return handle;
}
DWORD WaitForSingleObject(HANDLE raw, DWORD milliseconds) {
  auto* handle = static_cast<Handle*>(raw);
  auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(milliseconds);
  while (flock(handle->fd, LOCK_EX | LOCK_NB) != 0) {
    if (errno != EWOULDBLOCK) { SetLastError(Error()); return WAIT_FAILED; }
    if (std::chrono::steady_clock::now() >= end) return WAIT_TIMEOUT;
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
  }
  return WAIT_OBJECT_0;
}
BOOL ReleaseMutex(HANDLE raw) { return flock(static_cast<Handle*>(raw)->fd, LOCK_UN) == 0; }
BOOL CloseHandle(HANDLE raw) {
  auto* handle = static_cast<Handle*>(raw);
  bool ok = handle->fd < 0 || close(handle->fd) == 0;
  delete handle;
  if (!ok) SetLastError(Error());
  return ok;
}
HANDLE CreateFileW(LPCWSTR path, DWORD access, DWORD, LPSECURITY_ATTRIBUTES, DWORD disposition, DWORD, HANDLE) {
  int flags = access & GENERIC_WRITE ? O_WRONLY : O_RDONLY;
  if (disposition == CREATE_ALWAYS) flags |= O_CREAT | O_TRUNC;
  auto* handle = new Handle(Handle::kFile);
  handle->fd = open(Narrow(path).c_str(), flags, 0600);
  if (handle->fd < 0) { delete handle; SetLastError(Error()); return INVALID_HANDLE_VALUE; }
  return handle;
}
BOOL ReadFile(HANDLE raw, LPVOID bytes, DWORD size, LPDWORD count, LPOVERLAPPED) {
  ssize_t n = read(static_cast<Handle*>(raw)->fd, bytes, size);
  if (n < 0) { SetLastError(Error()); *count = 0; return false; }
  *count = static_cast<DWORD>(n);
  return true;
}
BOOL WriteFile(HANDLE raw, LPCVOID bytes, DWORD size, LPDWORD count, LPOVERLAPPED) {
  ssize_t n = write(static_cast<Handle*>(raw)->fd, bytes, size);
  if (n < 0) { SetLastError(Error()); *count = 0; return false; }
  *count = static_cast<DWORD>(n);
  return true;
}
BOOL MoveFileExW(LPCWSTR from, LPCWSTR to, DWORD) {
  if (rename(Narrow(from).c_str(), Narrow(to).c_str()) == 0) return true;
  SetLastError(Error()); return false;
}
BOOL DeleteFileW(LPCWSTR path) {
  if (unlink(Narrow(path).c_str()) == 0) return true;
  SetLastError(Error()); return false;
}
BOOL CreateDirectoryW(LPCWSTR path, LPSECURITY_ATTRIBUTES) {
  if (mkdir(Narrow(path).c_str(), 0700) == 0) return true;
  SetLastError(errno == ENOENT ? ERROR_PATH_NOT_FOUND : Error()); return false;
}
DWORD GetCurrentProcessId() { return static_cast<DWORD>(getpid()); }
DWORD GetCurrentThreadId() { return static_cast<DWORD>(std::hash<std::thread::id>{}(std::this_thread::get_id())); }
HANDLE FindFirstFileW(LPCWSTR pattern, WIN32_FIND_DATAW* output) {
  fs::path directory = fs::path(Narrow(pattern)).parent_path();
  std::error_code error;
  auto* handle = new Handle(Handle::kFind);
  for (const auto& entry : fs::directory_iterator(directory, error)) handle->entries.push_back(entry);
  if (error) { delete handle; SetLastError(error == std::errc::no_such_file_or_directory ? ERROR_PATH_NOT_FOUND : ERROR_ACCESS_DENIED); return INVALID_HANDLE_VALUE; }
  if (!Fill(handle, output)) { delete handle; SetLastError(ERROR_FILE_NOT_FOUND); return INVALID_HANDLE_VALUE; }
  return handle;
}
BOOL FindNextFileW(HANDLE raw, WIN32_FIND_DATAW* output) { return Fill(static_cast<Handle*>(raw), output); }
BOOL FindClose(HANDLE raw) { delete static_cast<Handle*>(raw); return true; }
