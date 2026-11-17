// Kernel pipe operations; no process-local peer counts or capacity estimates.
#pragma once
#include <windows.h>
namespace gesher {
int CreatePipeDescriptors(int descriptors[2], int flags);
int PipeStatusFlags(HANDLE file, int flags);
int SetPipeNonBlocking(HANDLE file, bool nonblocking);
int TransferPipe(HANDLE file, void* bytes, unsigned count, bool writing);
short PollPipeHandle(HANDLE file, int flags, short events);
}
