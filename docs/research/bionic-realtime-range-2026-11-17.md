# Bionic public realtime signal range

PR120's actual Windows2022/2025 ABI runs aborted on the missing
`__libc_current_sigrtmin` after three ordinary setjmp checks. The public
`SIGRTMIN` macro calls this export; substituting a fixture constant would hide
the missing runtime API. The repair implements and registers both public range
functions in the shared Bionic provider, preserving errno and mutable state.

## Android contract

Pinned Android16r4 [signal.h](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/include/signal.h)
defines `SIGRTMIN`/`SIGRTMAX` as calls to these functions. The
[minimum](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/bionic/__libc_current_sigrtmin.cpp)
adds the reserved count to the kernel minimum; the
[maximum](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/bionic/__libc_current_sigrtmax.cpp)
returns the kernel maximum. The
[reserved header](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/platform/bionic/reserved_signals.h)
reserves ten signals. The
[x86 header](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/kernel/uapi/asm-x86/asm/signal.h),
[generic header used by ARM64](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/kernel/uapi/asm-generic/signal.h)
and [signal types](https://android.googlesource.com/platform/bionic/+/refs/tags/android-16.0.0_r4/libc/include/bits/signal_types.h)
give kernel bounds32–64, hence public bounds **42–64** on both supported LP64
guest ABIs. Host CRT/glibc ranges are not substituted.

## Dispatch and validation

The existing x64 ELF resolver binds the ordinary System V functions directly.
The ARM64 loader binds the same provider through its guest-to-host trampoline.
No guest override is needed. Mask handling, reserved-signal filtering, queues
and delivery are unchanged; this does not duplicate PR79's delivery work.

The same eight-check fixture is included in the existing x64/ARM64 `libc_test`
guests. It exercises actual macro imports and `dlsym` resolution, exact bounds,
errno preservation, repeat queries and endpoint signal sets. Windows tracks40
and47 require its dedicated marker alongside whole-library/loader success.
All existing realtime setjmp/mask cases and their required marker stay intact.

The identical standalone fixture passed all eight checks on actual API36
x86-64 Android, exit0, with its device SHA256 matching
`16564ced4684f067eb4c99c0a13693c63625b4722221b020d1dda8ac665575e1`.
This is Android reference evidence only. The full production signal translation
unit cross-compiles for Windows x64/ARM64, and both whole guest ELFs compile.
Actual candidate Windows execution through both loader routes remains required.
The narrow export repair does not complete N20 delivery or N15 callback jumps.
