// Declared Linux boundary for the unedited production trust synchronizer.
// Real Windows builds use the SDK, kernel files/mutexes and CryptoAPI instead.
#pragma once
#include <cstddef>
#include <cstdint>
using DWORD = uint32_t;
using BYTE = unsigned char;
using BOOL = int;
using HANDLE = void*;
using LPCWSTR = const wchar_t*;
using LPCSTR = const char*;
using LPCVOID = const void*;
using LPVOID = void*;
using LPDWORD = DWORD*;
using LPSECURITY_ATTRIBUTES = void*;
using LPOVERLAPPED = void*;
using HCRYPTPROV_LEGACY = uintptr_t;
#define WINAPI
#define INVALID_HANDLE_VALUE reinterpret_cast<HANDLE>(static_cast<intptr_t>(-1))
constexpr BOOL TRUE = 1, FALSE = 0;
constexpr DWORD CP_UTF8 = 65001, MAX_PATH = 260;
constexpr DWORD GENERIC_READ = 1, GENERIC_WRITE = 2, FILE_SHARE_READ = 1, FILE_SHARE_WRITE = 2,
                FILE_SHARE_DELETE = 4, OPEN_EXISTING = 3, CREATE_ALWAYS = 2,
                FILE_ATTRIBUTE_NORMAL = 0x80, FILE_ATTRIBUTE_DIRECTORY = 0x10,
                MOVEFILE_REPLACE_EXISTING = 1;
constexpr DWORD ERROR_SUCCESS = 0, ERROR_FILE_NOT_FOUND = 2, ERROR_PATH_NOT_FOUND = 3,
                ERROR_ACCESS_DENIED = 5, ERROR_INVALID_HANDLE = 6, ERROR_INVALID_DATA = 13,
                ERROR_NO_MORE_FILES = 18, ERROR_WRITE_FAULT = 29, ERROR_GEN_FAILURE = 31,
                ERROR_NOT_SUPPORTED = 50, ERROR_ALREADY_EXISTS = 183,
                ERROR_FILE_TOO_LARGE = 223, WAIT_OBJECT_0 = 0, WAIT_ABANDONED = 0x80,
                WAIT_TIMEOUT = 258, WAIT_FAILED = 0xffffffff;
struct WIN32_FIND_DATAW { DWORD dwFileAttributes; wchar_t cFileName[MAX_PATH]; };
DWORD GetLastError();
void SetLastError(DWORD);
int MultiByteToWideChar(unsigned, DWORD, const char*, int, wchar_t*, int);
HANDLE CreateMutexW(LPSECURITY_ATTRIBUTES, BOOL, LPCWSTR);
DWORD WaitForSingleObject(HANDLE, DWORD);
BOOL ReleaseMutex(HANDLE);
BOOL CloseHandle(HANDLE);
HANDLE CreateFileW(LPCWSTR, DWORD, DWORD, LPSECURITY_ATTRIBUTES, DWORD, DWORD, HANDLE);
BOOL ReadFile(HANDLE, LPVOID, DWORD, LPDWORD, LPOVERLAPPED);
BOOL WriteFile(HANDLE, LPCVOID, DWORD, LPDWORD, LPOVERLAPPED);
BOOL MoveFileExW(LPCWSTR, LPCWSTR, DWORD);
BOOL DeleteFileW(LPCWSTR);
BOOL CreateDirectoryW(LPCWSTR, LPSECURITY_ATTRIBUTES);
DWORD GetModuleFileNameW(HANDLE, wchar_t*, DWORD);
DWORD GetCurrentProcessId();
DWORD GetCurrentThreadId();
HANDLE FindFirstFileW(LPCWSTR, WIN32_FIND_DATAW*);
BOOL FindNextFileW(HANDLE, WIN32_FIND_DATAW*);
BOOL FindClose(HANDLE);
