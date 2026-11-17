# PR73 repair candidate: native evidence still required

This candidate preserves PR73's open-description and shared-stdio work and
integrates main `e80cd1854163bd18e4ea5392c25f423184048804`. The only added
merge-queue workflow repair is the approved `.github/workflows` sparse-checkout
input. It is an intermediate repair, not an approval or evidence that Windows
or Android execution succeeded.

## Repairs established locally

The wide stream implementation now owns its symbols without MinGW macro or
C++ inline collisions. The public wchar header also prevents MinGW's inline
`fwide` from bypassing Gesher's stream orientation. Both production architectures
compile with the pinned LLVM-MinGW toolchain at `-O2 -Wall -Wextra -Werror`.
The fortified stdio adapter checks its summary write result. Track45 expects
the eight substantive exec2 checks that remain after removal of fake fork
completion; it uses the same executable body as the JNI route.

FILE close and reopen now retire socket/event/binder/epoll ownership in the
same descriptor transaction that observes CRT closure/replacement. Native
socket close and linger happen after that transaction, including failed
reopens. A new public regression corpus uses actual sockets, duplicate aliases,
event counters, EOF and slot reuse. Portable lifecycle envelopes remain
explicitly limited to their declared FILE/CRT boundaries.

OFD allocation failures are caught at the owning implementation's boundary.
Snapshot control blocks are allocated before native duplication; enumeration
failure releases accumulated snapshots. The original flags/description survive
failed registration. The source-specific exception setting is confined to
the OFD implementation, as it already was for stdio allocation.

The newly cancellable native pipe calls also require explicit stream cleanup.
The pinned winpthreads cleanup chain now releases stream locks and state pins,
flush-all snapshots and formatting context even when the runtime does not
unwind C++ objects. Public regression cases cancel blocked reads, formatted
writes, flush-all and close, then acquire and reuse the same stream. These are
host winpthreads/POSIX lifecycle tests, not an added Android pthread_cancel
capability; bionic does not expose that operation.

## Pipe implementation and concrete remaining gates

`pipe2` now creates local Windows message-type pipes with byte-stream reads.
Published read/write handles have actual directional data access. The kernel
wait mode supplies O_NONBLOCK across duplicated/inherited FILE_OBJECT handles.
Small writes submit one message; insufficient nonblocking message capacity
returns EAGAIN without partial admission. Larger nonblocking writes consult
the actual kernel write quota. Readiness observes the real kernel queue and
peer state; no local peer counter, condition variable, retained opposite end,
or assumed capacity controls application behavior.

Synchronous handles preserve ordinary UCRT-child compatibility. Each active
transfer uses one joinable worker with a 64 KiB stack reservation; the caller
waits at cancellation points. Cancellation repeatedly issues
`CancelSynchronousIo` until that exact worker exits, covering cancellation
before syscall entry. Neither the I/O buffer nor its retained endpoint can be
released first. There is no detached queue of transfers or retained idle worker.
This is a real per-call thread cost, including ready nonblocking operations;
it needs Windows throughput/latency measurement before considering a pool or
another backend. Portable tests establish bounded retirement, not acceptable
Windows throughput.

The following remain mandatory and unresolved until actual Windows execution:

1. `pipe_kernel_test.exe` must pass full-pipe scalar/vector admission, competing
   readers, F_GETFL/F_SETFL while another read is blocked, cancellation handle
   retirement, close/reuse of a retained snapshot, and native kernel readiness.
   Synchronous Windows FILE_OBJECT serialization is a particular risk for
   status queries and mode changes while a worker is blocked.
2. The existing `/proc/self/fd` pipe path still duplicates a descriptor. It does
   not implement Linux's independent description, requested access direction,
   or ordinary CLOEXEC default. This is a known source-level blocker, not just
   missing test evidence. The new test checks all two-source/three-access cases
   against the same byte queue. It also prints isolated native ReOpenFile
   observations to determine whether a real independent endpoint is available.
   No unsupported error has been substituted for the Linux operation.
3. `ofd_spawn_test.exe` must prove actual UCRT `_read`/`_write` on the inherited
   synchronous endpoints, real last-peer EOF, and an aware child's F_SETFL
   change through a high descriptor alias reaching its parent.
4. The public stdio socket/event regression must run on Windows, including
   failed reopen, numeric fd reuse and all four blocked-stdio cancellation
   cases. Host envelopes do not prove the virtual registries, the native
   winpthreads cleanup path or MinGW/UCRT symbol ownership at link/runtime.
5. Native inherited byte-type pipes and the runtime's pre-existing asynchronous
   signal/SIGPIPE limitations are not certified by the message-pipe fixture.
   They must not be inferred to have full Linux behavior from these results.

The native test retains all failures as gates. In particular, the procfs
case is expected to expose the known missing implementation; no skipped
assertion or accepted failure is introduced.

## Source qualification and supplier preservation

`tools/net-tcp-priority/qualified/ofd-stream-source.patch` records the exact
fd/system transition from the previously qualified main source. The policy
test reverses that pinned patch, then executes every original baseline/hash
assertion. It additionally compares the socket ownership functions, socket
vector dispatch and complete native polling core byte-for-byte and pins all
newly coupled OFD/stdio/pipe implementation inputs. Five mutation controls
demonstrate rejection of ownership, dispatch, pipe behavior and patch changes.

The supplier source/patch identities, DLL import roles, controller execution
tail, fixture assertions and deadlines, and two-supplier workflow comparisons
remain enforced. Source qualification is not a runtime pass or merge approval;
the qualification records `native_runtime_approval: false`.

## Primary behavior references

- [AOSP Android16 stdio](https://github.com/aosp-mirror/platform_bionic/blob/android16-release/libc/stdio/stdio.cpp),
  initial review blob `a5f2f81f44614fa5c7742741dcdd1ffdfe37ac73`.
- [Linux pipe behavior](https://github.com/torvalds/linux/blob/master/fs/pipe.c),
  initial review blob `4c1122597a3c379169fa39955936b5233d47b955`.
  This is a behavior reference only; no Linux implementation code is copied.
- [Microsoft named-pipe modes](https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipe-type-read-and-wait-modes).
- [Microsoft SetNamedPipeHandleState](https://learn.microsoft.com/en-us/windows/win32/api/namedpipeapi/nf-namedpipeapi-setnamedpipehandlestate).
- [Microsoft ReOpenFile](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-reopenfile).
- [Microsoft CancelSynchronousIo](https://learn.microsoft.com/en-us/windows/win32/api/ioapiset/nf-ioapiset-cancelsynchronousio).
- [Winpthreads cleanup records](https://github.com/mingw-w64/mingw-w64/blob/master/mingw-w64-libraries/winpthreads/include/pthread.h)
  (the actual implementation is compiled against the pinned toolchain header).
- [Bionic functionality status](https://android.googlesource.com/platform/bionic/+/2109f12c3b04d4023030ad12e17197c4a1c843ac/docs/status.md)
  documents the absence of pthread cancellation.

The Linux differential corpus observes its actual kernel behavior independently;
the host pipe envelope uses real Linux pipes and native threads but explicitly
models Windows message-write return conventions. Neither is evidence that NPFS,
UCRT, or Android/ART passed.
