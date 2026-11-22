#pragma once

#include <gesher/child_usage.h>
#include <stdint.h>
#include <cstring>

namespace gesher::bionic {

struct LinuxTimeval { int64_t tv_sec, tv_usec; };
struct LinuxRusage {
  LinuxTimeval ru_utime, ru_stime;
  int64_t ru_maxrss, ru_ixrss, ru_idrss, ru_isrss, ru_minflt, ru_majflt, ru_nswap, ru_inblock,
      ru_oublock, ru_msgsnd, ru_msgrcv, ru_nsignals, ru_nvcsw, ru_nivcsw;
};
static_assert(sizeof(LinuxRusage) == 144, "64-bit Android rusage ABI");

inline LinuxTimeval CpuTimeval(uint64_t ticks) {
  return {static_cast<int64_t>(ticks / 10000000), static_cast<int64_t>((ticks % 10000000) / 10)};
}
inline void ChildRusage(const child_usage::Cpu& cpu, LinuxRusage* usage) {
  std::memset(usage, 0, sizeof(*usage));
  usage->ru_utime = CpuTimeval(cpu.user_100ns);
  usage->ru_stime = CpuTimeval(cpu.kernel_100ns);
}

}  // namespace gesher::bionic
