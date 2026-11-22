# Reaped direct-child CPU accounting

This is one prerequisite of issue [#56](https://github.com/gesher-runtime/gesher/issues/56),
N38. It records actual user and kernel CPU for real, direct children owned by
the native Bionic process adapter or the libcore Java process adapter. It does
not complete the resource-usage/resource-limit workstream.

## Contract and implemented paths

[Android's wait implementation](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/bionic/wait.cpp)
routes `waitpid` through `wait4`. A successful `wait4` with a non-null resource
pointer reports the waited child's usage. `getrusage(RUSAGE_CHILDREN)` and
`times` report cumulative CPU for children that have terminated **and have
been waited for**. The Linux reference contracts describe the
[wait output](https://man7.org/linux/man-pages/man2/wait4.2.html),
[resource totals](https://man7.org/linux/man-pages/man2/getrusage.2.html), and
[clock-tick conversion](https://man7.org/linux/man-pages/man2/times.2.html).

Previously guest `wait4` discarded its resource output, guest and POSIX
`getrusage(RUSAGE_CHILDREN)` returned zeros, and guest `times` set both child
fields to zero. The native and Java adapters already retained real Win32
process handles but closed them without collecting the CPU sample.

| Path | Reap and query behavior |
| --- | --- |
| Bionic `SpawnPrepared → Track → CollectOne` | Register a real child, retain its process identity, sample once at successful `wait`/`waitpid`/`wait4` collection. `wait4` also writes that child's CPU. |
| libcore `ForkAndExec → WaitForProcessExit` | Register the real child and publish its sample when the native Java reaper successfully waits for it. |
| Guest `getrusage(RUSAGE_CHILDREN)` and `times` | Read the shared cumulative native-and-Java total. Queries never consume or add samples. |
| gesher-posix `getrusage(RUSAGE_CHILDREN)` | Read that same total through the executable-owned provider. |

Java's background native reaper can finish before the application calls public
`Process.waitFor()`. Accounting follows that actual native reap; repeated
public waits return Java's cached status and do not add usage again.

## Ownership, errors and precision

`gesher_child_usage_owner_v1` is a versioned C ABI in the containing executable.
Callers resolve it from the main module, including statically linked copies
inside DLLs. A private link anchor forces the executable's owner object to be
included; an import library or private DLL copy cannot silently create separate
Java and Bionic totals. A missing owner reports an error, with no local fallback.

Each registration duplicates a process handle with query and synchronization
rights and stores the PID, creation timestamp and a monotonically allocated
generation token. The retained handle preserves object identity across PID
reuse. A process can be registered only once while its identity is outstanding.
Consumption requires the **process wait signal**; exit code `259` is not used
as a liveness test. A terminated process is allowed to have that exit code.

[GetProcessTimes](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getprocesstimes)
provides actual user and kernel CPU summed across the child's threads in
integer 100 ns units. The owner validates the same retained identity, samples
under its lock, checks both sums for overflow, publishes the sample and removes
the record atomically. Native and Java caller locks then retire their own
records. Every waiting Java thread holds a shared reference to the immutable
child generation and rechecks its identity, so another waiter cannot invalidate
its blocked wait or make it consume a replacement child. Sharing the original
owned handle also avoids a new handle-duplication failure at the Java wait
boundary; the last generation reference closes it.

Native asynchronous wait callbacks carry that generation plus a reference
counted context. The child record and one-shot callback each own one reference.
This allows a SIGCHLD handler to reap from within the callback without waiting
for itself or freeing its running context. The lifetime follows Microsoft's
[UnregisterWaitEx contract](https://learn.microsoft.com/en-us/windows/win32/sync/unregisterwaitex):
nonblocking unregister can leave an already queued callback running.

Running children, exited-but-unreaped children, `WNOHANG` without an exit,
failed waits and repeated queries add no CPU. Once a real child has completed,
a failed CPU measurement retires its registration and closes its retained
handle. Status-only native waits and the one-shot Java reaper still return the
authentic observed child exit status. They never require a Java retry or return
a fabricated `-1` exit status because accounting failed.

Missing one reaped child's sample makes the cumulative CPU total incomplete.
The provider records that error for the lifetime of the containing process;
subsequent guest and POSIX resource queries fail without modifying their output
instead of returning a partial or zero total. Actual samples of later children
can still be returned by their individual `wait4` calls. If the failed sample
was requested by `wait4`, that call returns its mapped accounting error after
consuming the completed child and writing its real status, leaving the usage
output untouched. The process cannot be reaped twice. Overflow follows the
same unavailable-total policy. A defensive abandonment operation also releases
ownership and invalidates incomplete totals if Java has already observed an
OS wait/status success but the provider cannot consume the record normally.

Once collection succeeds, a bad `wait4` output pointer returns `EFAULT` without
resurrecting the already consumed child or undoing cumulative accounting.
Guest pointer checks cover committed writable Win32 regions; they are not a
kernel copy-to-user mechanism that can make concurrent unmapping safe.

Totals retain 100 ns precision until the caller's ABI conversion. Guest
`rusage` uses normalized 64-bit seconds/microseconds; `times` uses the runtime's
100 Hz clock ticks. The POSIX host structure's narrower `long` seconds are
checked for overflow. Summing before conversion preserves fractions across
multiple children.

## Deliberately incomplete portions of N38

- Only CPU user/system fields are implemented here. Other child `rusage`
  fields retain their existing zero reporting; memory, I/O, faults, switches
  and resource-limit enforcement need separate implementations and evidence.
- Linux can include reaped descendants through intervening parents. This
  change has no cross-process descendant-usage propagation. An intermediate
  launcher's own measured CPU is counted, not the CPU of its descendants.
- `ExecReplace`, shell execution and tool-launcher waits still bypass these
  native/Java collectors. Their direct children and propagation through those
  wrappers remain separate work.
- The pending fork-removal/descriptor PRs own the inline synthetic fork path.
  An inline child has no independent process handle and cannot provide a real
  sample. `wait4` with a resource pointer reports `ENOSYS` for it without
  consuming it; ordinary status-only behavior remains as before. This change
  does not relabel parent execution as measured child CPU.
- Existing process selection, signal, job-control and option semantics outside
  this accounting boundary have not been expanded.

These limitations mean this change does **not** establish complete POSIX
`RUSAGE_CHILDREN` parity, full N38 completion, or completion of issue #56.

## Reproducible checks and evidence boundaries

`python tools/test_child_usage_contracts.py --sanitize` compiles the real owner
and verbatim production native/Java reapers and resource-query functions. On a
Linux host only the Win32 boundary is controlled. It checks once-only
consumption, CPU units and fractional accumulation, failed queries and output preservation,
overflow, PID reuse, stale callback generation, callback re-entry, rollback,
bad output pointers, synthetic-child rejection and handle ownership. Fresh
failure-control processes inject `GetProcessTimes` failure into the real
production collection code: a one-shot Java reaper must return the child's
actual exit code and leave no owned record or handle; native status-only waits
must also succeed; `wait4` must fail its CPU output without leaving another
reap; every cumulative query must report unavailable accounting. This tests the
native return value published by Java and its ownership effects, not an ART
failure-injection run. The same
script's `--source-root BASELINE` mode uses baseline adapter/reaper functions
with the candidate owner component: the old ignored wait4 output and zero
totals fail as behavior assertions, not as missing-file compilation errors.

On Windows the same command builds against real Windows APIs, starts actual
CPU-burning child processes and compares collection with `GetProcessTimes` on
an independent retained handle. It also builds a peer DLL containing another
static copy of the owner object and verifies that DLL registration and queries
reach the executable's shared ledger. This required gate is registered in
`ci.yml` on Windows 2022 and Windows 2025. The extracted reaper fixture tests
real Win32 waits and callbacks; it does not run ART or the guest loader.

`tests/exec/child_usage_contract.c` is a separate ordinary public API fixture:
real `posix_spawn`, a measured-CPU child handshake, `wait4`, `waitpid`,
`getrusage` and `times`. `exec.yml` executes it as a Linux reference and builds
the same source with the pinned NDK for x86-64 and ARM64. Windows track 43 runs
both ELF variants through the actual guest process path. Track 42 separately
runs `ChildUsageProcessTest` in ART: `ProcessBuilder` starts the real busy
child, libcore reaps it, and a JNI library reads the guest resource queries.
The Java/JNI fixture also runs as an ordinary Linux JVM reference. Each Windows
guest case has a 60-second deadline and writes its result and log under
`child-usage-evidence`, retained with the native shard's artifacts.

Local Linux host fixtures and cross-compilation do not establish Windows,
ART, ARM64 translation, or Android-device runtime correctness. Their separate
CI results must be inspected at the reviewed commit. No Android-device oracle
execution is claimed by registering or building these fixtures.
