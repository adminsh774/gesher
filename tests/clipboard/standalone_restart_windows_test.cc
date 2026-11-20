// Actual Windows process/stdio/job boundary for standalone UID re-entry.
// The services components/net fixtures separately run Main, PackageManager and ART with
// a later installed package, whose canonical UID differs from the default 10000.
#include <windows.h>
#include <cstdio>
#include <cstdlib>
#include <cwchar>
#include <string>

#include "../../compat/framework/services_zygote.h"

namespace {
int checks = 0;
void Check(bool value, const char* what) {
  ++checks;
  if (!value) {
    std::fprintf(stderr, "FAIL %s (Win32 %lu)\n", what, GetLastError());
    std::fflush(stderr);
    ExitProcess(1);
  }
}
struct Handle {
  HANDLE value = nullptr;
  ~Handle() { if (value != nullptr && value != INVALID_HANDLE_VALUE) CloseHandle(value); }
};
struct File {
  std::wstring path;
  File() {
    wchar_t directory[MAX_PATH], name[MAX_PATH];
    Check(GetTempPathW(MAX_PATH, directory) != 0, "temporary directory");
    Check(GetTempFileNameW(directory, L"gsi", 0, name) != 0, "temporary file");
    path = name;
  }
  ~File() { DeleteFileW(path.c_str()); }
  HANDLE Open(DWORD access) {
    SECURITY_ATTRIBUTES inherit{sizeof(inherit), nullptr, TRUE};
    HANDLE result = CreateFileW(path.c_str(), access,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, &inherit, OPEN_EXISTING, 0, nullptr);
    Check(result != INVALID_HANDLE_VALUE, "open fixture standard handle");
    return result;
  }
  std::string Read() {
    Handle handle;
    handle.value = CreateFileW(path.c_str(), GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, 0, nullptr);
    Check(handle.value != INVALID_HANDLE_VALUE, "read fixture output");
    char buffer[16384];
    DWORD count = 0;
    Check(ReadFile(handle.value, buffer, sizeof(buffer), &count, nullptr) != FALSE, "read output bytes");
    return std::string(buffer, count);
  }
};

void Worker(uint32_t expected, const wchar_t* payload) {
  Check(std::wcscmp(payload, L"argument with spaces \u05d0") == 0, "same Unicode arguments");
  const char* restarted = std::getenv("GESHER_STANDALONE_RESTARTED");
  if (restarted != nullptr && std::strcmp(restarted, "1") == 0) {
    const char* uid = std::getenv("GESHER_UID"), *binder = std::getenv("GESHER_BINDER_UID");
    Check(uid != nullptr && std::strcmp(uid, "10037") == 0, "UID before child code");
    Check(binder != nullptr && std::strcmp(binder, uid) == 0, "matching Binder environment");
    char input[64]{};
    Check(std::fgets(input, sizeof(input), stdin) != nullptr
        && std::strcmp(input, "original input\n") == 0, "original stdin bytes");
    unsigned long pid = GetCurrentProcessId();
    auto birth = static_cast<unsigned long long>(gesher::zygote::StartTime(static_cast<int>(pid)));
    Check(birth != 0, "actual child generation");
    std::printf("child pid=%lu generation=%llu input=original input\n", pid, birth);
    std::fprintf(stderr, "child stderr remains separate\n");
    std::fflush(stdout); std::fflush(stderr);
    if (expected == 99) Sleep(INFINITE);  // Parent-death test owns this process and its job.
    ExitProcess(expected);
  }
  DWORD before = 0, after = 0;
  Check(GetProcessHandleCount(GetCurrentProcess(), &before) != FALSE, "initial handle count");
  std::printf("supervisor before restart\n"); std::fflush(stdout);
  uint32_t result = 0;
  Check(gesher::zygote::RestartWithUid(10037, &result) == 0, "native restart");
  Check(GetProcessHandleCount(GetCurrentProcess(), &after) != FALSE && before == after,
      "restart releases process, thread, job, stdio and attribute resources");
  std::printf("supervisor observed exact exit %u and released handles\n", result);
  std::fflush(stdout);
  ExitProcess(result);
}

void Run(uint32_t expected) {
  File input, output, error;
  Handle in, out, err, job, process, thread;
  in.value = input.Open(GENERIC_READ | GENERIC_WRITE);
  out.value = output.Open(GENERIC_WRITE);
  err.value = error.Open(GENERIC_WRITE);
  constexpr char text[] = "original input\n";
  DWORD written = 0;
  Check(WriteFile(in.value, text, sizeof(text) - 1, &written, nullptr) != FALSE,
      "write fixture stdin");
  LARGE_INTEGER zero{};
  Check(SetFilePointerEx(in.value, zero, nullptr, FILE_BEGIN) != FALSE, "rewind fixture stdin");
  job.value = CreateJobObjectW(nullptr, nullptr);
  Check(job.value != nullptr, "fixture lifetime job");
  JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
  limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
  Check(SetInformationJobObject(job.value, JobObjectExtendedLimitInformation, &limits,
      sizeof(limits)) != FALSE, "fixture owns all descendants on failure");
  auto executable = gesher::zygote::ExePath();
  std::wstring command = L"\"" + std::wstring(executable.begin(), executable.end())
      + L"\" --worker " + std::to_wstring(expected) + L" \"argument with spaces \u05d0\"";
  STARTUPINFOW startup{};
  startup.cb = sizeof(startup); startup.dwFlags = STARTF_USESTDHANDLES;
  startup.hStdInput = in.value; startup.hStdOutput = out.value; startup.hStdError = err.value;
  PROCESS_INFORMATION child{};
  Check(CreateProcessW(reinterpret_cast<const wchar_t*>(executable.c_str()), command.data(),
      nullptr, nullptr, TRUE, CREATE_SUSPENDED, nullptr, nullptr, &startup, &child) != FALSE,
      "start real supervisor process");
  process.value = child.hProcess; thread.value = child.hThread;
  Check(AssignProcessToJobObject(job.value, process.value) != FALSE, "fixture job enrollment");
  Check(ResumeThread(thread.value) != static_cast<DWORD>(-1), "run supervisor");
  if (expected == 99) {
    unsigned long pid = 0;
    unsigned long long birth = 0;
    for (int i = 0; i < 200; ++i) {
      std::string report = output.Read();
      auto at = report.find("child pid=");
      if (at != std::string::npos
          && std::sscanf(report.c_str() + at, "child pid=%lu generation=%llu", &pid, &birth) == 2) break;
      Sleep(25);
    }
    Check(pid != 0 && birth != 0, "parked child reported actual identity");
    Handle descendant;
    descendant.value = OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    Check(descendant.value != nullptr, "retain parked child handle");
    FILETIME created, exited, kernel, user;
    Check(GetProcessTimes(descendant.value, &created, &exited, &kernel, &user) != FALSE,
        "read retained generation");
    Check(((static_cast<unsigned long long>(created.dwHighDateTime) << 32)
        | created.dwLowDateTime) == birth, "retained child is the reported generation");
    Check(TerminateProcess(process.value, 77) != FALSE, "end supervisor through owned handle");
    Check(WaitForSingleObject(process.value, 5000) == WAIT_OBJECT_0, "supervisor ended");
    Check(WaitForSingleObject(descendant.value, 5000) == WAIT_OBJECT_0,
        "supervisor death retires the relaunched child");
  } else {
    Check(WaitForSingleObject(process.value, 15000) == WAIT_OBJECT_0, "restart completed");
    DWORD code = 0;
    Check(GetExitCodeProcess(process.value, &code) != FALSE && code == expected,
        "exact native exit status (including high bit and 259)");
    std::string stdout_text = output.Read(), stderr_text = error.Read();
    Check(stdout_text.find("input=original input") != std::string::npos,
        "restarted child inherited input and output");
    Check(stdout_text.find("released handles") != std::string::npos,
        "supervisor observed complete resource release");
    Check(stderr_text.find("child stderr remains separate") != std::string::npos
        && stdout_text.find("child stderr") == std::string::npos, "stderr destination preserved");
  }
}
}  // namespace

int wmain(int argc, wchar_t** argv) {
  if (argc == 4 && std::wcscmp(argv[1], L"--worker") == 0) {
    Worker(static_cast<uint32_t>(std::wcstoull(argv[2], nullptr, 10)), argv[3]);
  }
  Check(argc == 1, "fixture arguments");
  Check(SetEnvironmentVariableW(L"GESHER_STANDALONE_RESTARTED", nullptr) != FALSE,
      "clear fixture restart marker");
  uint32_t unchanged = 123;
  Check(gesher::zygote::RestartWithUid(999, &unchanged) == ERROR_INVALID_PARAMETER
      && unchanged == 123, "invalid application UID does not launch or fabricate status");
  for (uint32_t code : {0u, 17u, 259u, 0x80000000u, 99u}) Run(code);
  std::printf("Standalone native restart all passed: %d checks\n", checks);
  return 0;
}
