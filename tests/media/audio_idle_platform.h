// Isolated test boundary: real Win32 waits on Windows, event/timer transport on Linux.
#pragma once
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <cstdio>
using namespace std::chrono_literals;
namespace idle_test {
inline std::atomic<int> armed_timers{0}, critical_threads{0}, wait_returns{0};
inline std::mutex hook_mu;
inline std::function<void()> idle_hook;
inline void BeforeIdleWait() {
  std::function<void()> hook;
  { std::lock_guard<std::mutex> lock(hook_mu); hook = std::move(idle_hook); }
  if (hook) hook();
}
}
#ifdef _WIN32
#include <windows.h>
#include <objbase.h>
using ssize_t = std::ptrdiff_t;
using clockid_t = int;
#ifndef CLOCK_MONOTONIC
#define CLOCK_MONOTONIC 1
#endif
inline HANDLE TestCreateEventW(LPSECURITY_ATTRIBUTES attributes, BOOL manual, BOOL initial, LPCWSTR name) {
  if (std::getenv("GESHER_TEST_EVENT_FAILURE")) return nullptr;
  return ::CreateEventW(attributes, manual, initial, name);
}
inline HANDLE TestCreateWaitableTimerExW(LPSECURITY_ATTRIBUTES attributes, LPCWSTR name, DWORD flags, DWORD access) {
  if (std::getenv("GESHER_TEST_TIMER_FAILURE")) return nullptr;
  return ::CreateWaitableTimerExW(attributes, name, flags, access);
}
inline HANDLE TestCreateWaitableTimerW(LPSECURITY_ATTRIBUTES attributes, BOOL manual, LPCWSTR name) {
  if (std::getenv("GESHER_TEST_TIMER_FAILURE")) return nullptr;
  return ::CreateWaitableTimerW(attributes, manual, name);
}
inline DWORD TestWaitForSingleObject(HANDLE h, DWORD ms) {
  if (ms == INFINITE) idle_test::BeforeIdleWait();
  const DWORD result = ::WaitForSingleObject(h, ms);
  ++idle_test::wait_returns;
  return result;
}
inline DWORD TestWaitForMultipleObjects(DWORD count, const HANDLE* handles, BOOL all, DWORD ms) {
  const DWORD result = ::WaitForMultipleObjects(count, handles, all, ms);
  ++idle_test::wait_returns;
  return result;
}
inline BOOL TestSetWaitableTimer(HANDLE h, const LARGE_INTEGER* due, LONG period,
                                PTIMERAPCROUTINE fn, LPVOID arg, BOOL resume) {
  const BOOL ok = ::SetWaitableTimer(h, due, period, fn, arg, resume);
  if (ok) ++idle_test::armed_timers;
  return ok;
}
inline BOOL TestCancelWaitableTimer(HANDLE h) {
  const BOOL ok = ::CancelWaitableTimer(h);
  if (ok) --idle_test::armed_timers;
  return ok;
}
inline BOOL TestSetThreadPriority(HANDLE h, int value) {
  thread_local bool critical = false;
  const bool next = value == THREAD_PRIORITY_TIME_CRITICAL;
  if (next != critical) idle_test::critical_threads += next ? 1 : -1;
  critical = next;
  return ::SetThreadPriority(h, value);
}
#define WaitForSingleObject TestWaitForSingleObject
#define WaitForMultipleObjects TestWaitForMultipleObjects
#define CreateEventW TestCreateEventW
#define CreateWaitableTimerExW TestCreateWaitableTimerExW
#define CreateWaitableTimerW TestCreateWaitableTimerW
#define SetWaitableTimer TestSetWaitableTimer
#define CancelWaitableTimer TestCancelWaitableTimer
#define SetThreadPriority TestSetThreadPriority
#else
using DWORD = uint32_t;
using BOOL = int;
using LONGLONG = int64_t;
using HRESULT = long;
struct LARGE_INTEGER { LONGLONG QuadPart; };
constexpr DWORD INFINITE = 0xffffffff, WAIT_OBJECT_0 = 0, WAIT_TIMEOUT = 258;
constexpr int FALSE = 0, TRUE = 1, COINIT_MULTITHREADED = 0;
constexpr int THREAD_PRIORITY_NORMAL = 0, THREAD_PRIORITY_ABOVE_NORMAL = 1;
constexpr int THREAD_PRIORITY_HIGHEST = 2, THREAD_PRIORITY_TIME_CRITICAL = 15;
constexpr int CREATE_WAITABLE_TIMER_HIGH_RESOLUTION = 2, TIMER_ALL_ACCESS = 0;
inline bool SUCCEEDED(HRESULT result) { return result >= 0; }
inline HRESULT CoInitializeEx(void*, int) { return 0; }
inline void CoUninitialize() {}
struct TestHandle {
  bool manual = false, signaled = false, timer = false, armed = false;
  int period = 0;
  std::chrono::steady_clock::time_point due;
};
using HANDLE = TestHandle*;
namespace idle_test {
inline std::mutex waits_mu;
inline std::condition_variable waits_cv;
inline std::vector<std::unique_ptr<TestHandle>> handles;
}
inline HANDLE NewTestHandle() {
  std::lock_guard<std::mutex> lock(idle_test::waits_mu);
  idle_test::handles.push_back(std::make_unique<TestHandle>());
  return idle_test::handles.back().get();
}
inline HANDLE CreateEventW(void*, BOOL manual, BOOL initial, void*) {
  if (std::getenv("GESHER_TEST_EVENT_FAILURE")) return nullptr;
  HANDLE h = NewTestHandle(); h->manual = manual; h->signaled = initial; return h;
}
inline BOOL SetEvent(HANDLE h) {
  { std::lock_guard<std::mutex> lock(idle_test::waits_mu); h->signaled = true; }
  idle_test::waits_cv.notify_all(); return TRUE;
}
inline HANDLE CreateWaitableTimerExW(void*, void*, int, int) {
  if (std::getenv("GESHER_TEST_TIMER_FAILURE")) return nullptr;
  HANDLE h = NewTestHandle(); h->timer = true; return h;
}
inline HANDLE CreateWaitableTimerW(void*, BOOL, void*) {
  return CreateWaitableTimerExW(nullptr, nullptr, 0, 0);
}
inline BOOL SetWaitableTimer(HANDLE h, const LARGE_INTEGER* due, int period, void*, void*, BOOL) {
  { std::lock_guard<std::mutex> lock(idle_test::waits_mu);
    if (!h->armed) ++idle_test::armed_timers;
    h->armed = true; h->period = period;
    h->due = std::chrono::steady_clock::now() + std::chrono::nanoseconds(-due->QuadPart * 100); }
  idle_test::waits_cv.notify_all(); return TRUE;
}
inline BOOL CancelWaitableTimer(HANDLE h) {
  std::lock_guard<std::mutex> lock(idle_test::waits_mu);
  if (h->armed) --idle_test::armed_timers;
  h->armed = false; return TRUE;
}
inline DWORD WaitForMultipleObjects(DWORD n, const HANDLE* handles, BOOL, DWORD ms) {
  const auto deadline = ms == INFINITE ? std::chrono::steady_clock::time_point::max()
                                      : std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
  std::unique_lock<std::mutex> lock(idle_test::waits_mu);
  for (;;) {
    const auto now = std::chrono::steady_clock::now();
    auto wake = deadline;
    for (DWORD i = 0; i < n; ++i) {
      HANDLE h = handles[i];
      if (h->timer && h->armed) {
        if (now >= h->due) {
          h->due = now + std::chrono::milliseconds(h->period);
          ++idle_test::wait_returns; return WAIT_OBJECT_0 + i;
        }
        wake = std::min(wake, h->due);
      } else if (h->signaled) {
        if (!h->manual) h->signaled = false;
        ++idle_test::wait_returns; return WAIT_OBJECT_0 + i;
      }
    }
    if (now >= deadline) { ++idle_test::wait_returns; return WAIT_TIMEOUT; }
    if (wake == std::chrono::steady_clock::time_point::max()) idle_test::waits_cv.wait(lock);
    else idle_test::waits_cv.wait_until(lock, wake);
  }
}
inline DWORD WaitForSingleObject(HANDLE h, DWORD ms) {
  if (ms == INFINITE) idle_test::BeforeIdleWait();
  return WaitForMultipleObjects(1, &h, FALSE, ms);
}
inline uint64_t GetTickCount64() {
  return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::steady_clock::now().time_since_epoch()).count());
}
inline HANDLE GetCurrentThread() { return nullptr; }
inline BOOL SetThreadPriority(HANDLE, int priority) {
  thread_local bool critical = false;
  const bool next = priority == THREAD_PRIORITY_TIME_CRITICAL;
  if (next != critical) idle_test::critical_threads += next ? 1 : -1;
  critical = next; return TRUE;
}
inline const wchar_t* _wgetenv(const wchar_t*) {
  const char* value = std::getenv("GESHER_AUDIO_CAPTURE");
  if (value == nullptr) return nullptr;
  thread_local std::wstring wide; wide.assign(value, value + std::strlen(value));
  return wide.c_str();
}
#endif
namespace gesher {
inline int64_t MonotonicNanos() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
      std::chrono::steady_clock::now().time_since_epoch()).count();
}
inline int AndroidClockGettime(clockid_t, timespec* value) {
  const int64_t ns = MonotonicNanos(); value->tv_sec = ns / 1000000000;
  value->tv_nsec = static_cast<long>(ns % 1000000000); return 0;
}
}
#define MEDIA_LOGI(...) ((void)0)
#define MEDIA_LOGW(...) ((void)0)
#define MEDIA_LOGE(...) ((void)0)
