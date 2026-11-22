// Ordinary public-API fixture shared by Linux reference and Android ELF runs.
// Children are real posix_spawn processes; no fork emulation or shell is used.
#define _GNU_SOURCE
#include <errno.h>
#include <inttypes.h>
#include <spawn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/times.h>
#include <sys/wait.h>
#include <unistd.h>

extern char** environ;
static int failures;
static void check(int ok, const char* what) {
  if (!ok) { ++failures; printf("child-usage: FAIL %s (errno=%d)\n", what, errno); }
}
static int64_t micros(struct timeval t) { return (int64_t)t.tv_sec * 1000000 + t.tv_usec; }
static int64_t cpu(struct rusage r) { return micros(r.ru_utime) + micros(r.ru_stime); }
static struct rusage children(void) {
  struct rusage r = {0};
  check(getrusage(RUSAGE_CHILDREN, &r) == 0, "RUSAGE_CHILDREN succeeds");
  return r;
}
static void normalized(struct rusage r) {
  check(r.ru_utime.tv_sec >= 0 && r.ru_utime.tv_usec >= 0 && r.ru_utime.tv_usec < 1000000 &&
        r.ru_stime.tv_sec >= 0 && r.ru_stime.tv_usec >= 0 && r.ru_stime.tv_usec < 1000000,
        "timevals are nonnegative and normalized");
}
static void times_agree(struct rusage r) {
  struct tms t = {0};
  long hz = sysconf(_SC_CLK_TCK);
  clock_t result = times(&t);
  check(hz > 0 && result != (clock_t)-1, "times and clock tick query succeed");
  if (hz <= 0 || result == (clock_t)-1) return;
  int64_t user_ticks = micros(r.ru_utime) * hz / 1000000;
  int64_t kernel_ticks = micros(r.ru_stime) * hz / 1000000;
  check(llabs((long long)t.tms_cutime - user_ticks) <= 1 &&
        llabs((long long)t.tms_cstime - kernel_ticks) <= 1,
        "times child fields agree with cumulative rusage at clock-tick precision");
}
static int child(void) {
  struct rusage before, now;
  if (getrusage(RUSAGE_SELF, &before) != 0) return 90;
  uint64_t value = 1;
  volatile uint64_t observed = 0;
  do {
    for (unsigned i = 0; i < 100000; ++i) value = value * UINT64_C(6364136223846793005) + 1;
    observed = value;
    if (getrusage(RUSAGE_SELF, &now) != 0) return 91;
  } while (cpu(now) - cpu(before) < 50000);  // actual process CPU, not elapsed time
  if (!observed) return 92;
  printf("ready %" PRId64 "\n", cpu(now));
  fflush(stdout);
  char byte;
  return read(STDIN_FILENO, &byte, 1) == 1 ? 37 : 93;
}
struct child_process { pid_t pid; int input, output; int64_t ready_cpu; };
static struct child_process spawn(const char* executable) {
  struct child_process p = {-1, -1, -1, 0};
  int input[2], output[2];
  if (pipe(input) || pipe(output)) { check(0, "create child pipes"); return p; }
  posix_spawn_file_actions_t actions;
  check(posix_spawn_file_actions_init(&actions) == 0, "initialize spawn actions");
  check(posix_spawn_file_actions_adddup2(&actions, input[0], STDIN_FILENO) == 0 &&
        posix_spawn_file_actions_adddup2(&actions, output[1], STDOUT_FILENO) == 0 &&
        posix_spawn_file_actions_addclose(&actions, input[1]) == 0 &&
        posix_spawn_file_actions_addclose(&actions, output[0]) == 0,
        "redirect real child pipes");
  char* argv[] = {(char*)executable, (char*)"--child", NULL};
  int error = posix_spawn(&p.pid, executable, &actions, NULL, argv, environ);
  posix_spawn_file_actions_destroy(&actions);
  close(input[0]); close(output[1]);
  p.input = input[1]; p.output = output[0];
  check(error == 0, "posix_spawn creates a real direct child");
  if (error) { close(p.input); close(p.output); p.pid = -1; return p; }
  char line[128]; size_t at = 0;
  while (at + 1 < sizeof(line) && read(p.output, &line[at], 1) == 1) {
    if (line[at++] == '\n') break;
  }
  line[at] = 0;
  check(sscanf(line, "ready %" SCNd64, &p.ready_cpu) == 1 && p.ready_cpu >= 50000,
        "child reports actual CPU and remains alive until released");
  return p;
}
static void release(struct child_process p) {
  check(write(p.input, "x", 1) == 1, "release child after prereap observations");
  close(p.input); close(p.output);
}
int main(int argc, char** argv) {
  if (argc == 2 && !strcmp(argv[1], "--child")) return child();
  if (argc != 2) { fprintf(stderr, "usage: child-usage /absolute/path/to/self\n"); return 2; }
  struct rusage before = children();
  struct child_process p = spawn(argv[1]);
  if (p.pid < 0) return 1;
  struct rusage running = children();
  check(cpu(running) == cpu(before), "running child's CPU is not counted");
  struct rusage usage; memset(&usage, 0x5a, sizeof(usage));
  struct rusage sentinel = usage;
  int status = 123;
  check(wait4(p.pid, &status, WNOHANG, &usage) == 0 && status == 123 &&
        !memcmp(&usage, &sentinel, sizeof(usage)), "WNOHANG preserves both outputs while child is alive");
  release(p);
  check(wait4(p.pid, &status, 0, &usage) == p.pid && WIFEXITED(status) && WEXITSTATUS(status) == 37,
        "wait4 reaps actual child with its exit status");
  normalized(usage);
  check(cpu(usage) >= p.ready_cpu && cpu(usage) > 0, "wait4 returns actual positive child CPU");
  struct rusage after = children();
  check(llabs((long long)(micros(after.ru_utime) - micros(before.ru_utime) - micros(usage.ru_utime))) <= 1 &&
        llabs((long long)(micros(after.ru_stime) - micros(before.ru_stime) - micros(usage.ru_stime))) <= 1,
        "cumulative increase equals the single wait4 sample without per-child rounding loss");
  times_agree(after);
  errno = 0;
  check(waitpid(p.pid, &status, 0) == -1 && errno == ECHILD, "already-reaped child is ECHILD");
  check(cpu(children()) == cpu(after), "repeated wait and queries do not charge CPU again");

  p = spawn(argv[1]);
  if (p.pid < 0) return 1;
  before = children();
  release(p);
  check(waitpid(p.pid, &status, 0) == p.pid && WIFEXITED(status) && WEXITSTATUS(status) == 37,
        "status-only waitpid reaps real child");
  after = children();
  check(cpu(after) - cpu(before) >= p.ready_cpu, "waitpid contributes CPU to process cumulative usage");
  normalized(after); times_agree(after);
  printf("child-usage: SUMMARY failures=%d\n", failures);
  return failures ? 1 : 0;
}
