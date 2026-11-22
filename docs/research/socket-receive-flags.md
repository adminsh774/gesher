# Socket descriptor flags on SCM_RIGHTS receive

## Contract and confirmed defect

The receiver chooses the new descriptor's `FD_CLOEXEC` bit through
`MSG_CMSG_CLOEXEC`, independently of the sending descriptor. Android's kernel
[`scm_detach_fds` implementation](https://android.googlesource.com/kernel/common/+/42645c79ab13fcb272b515c06f1c00c782ceceb1/net/core/scm.c)
passes either `O_CLOEXEC` or zero to descriptor allocation according to that
receive flag. The shared public Linux reference in this change exercises the
same expectation using actual `sendmsg`, `recvmsg` and `fcntl` operations.

On corrected common baseline
`f8800806ffd3a52c3e28256c2d6dfd5129ef8e2e`, ordinary files and native fences already
honor this choice. The socket arm of `ReadDelivered` calls two-argument
`AdoptSocket`; `RegisterSocket` reaches a permanently noninheritable `ReserveFd`.
A socket received without `MSG_CMSG_CLOEXEC` therefore still reports
`FD_CLOEXEC`. The received endpoint otherwise survives and remains usable.

The original-source host control reproduces this defect in four combinations
of sender flag and nonblocking state. Its two additional failures concern the
newly required error path when setting the false choice fails; these are not
two additional independently discovered defects.

## Bounded production repair

Only five production files change. `ReadDelivered` passes its receiver choice
to `AdoptSocket`, which forwards it through `RegisterNew` and `RegisterSocket`
to `ReserveFd`. The internal default remains true at all existing entry
points. Existing direct and Binder adoption calls therefore retain their
previous close-on-exec result. Normal socket creation also keeps its previous
default; this change does not broaden socket inheritance support.

Reservation creates the private NUL handle as before and holds the existing
descriptor transaction while creating its CRT descriptor. For a false choice,
it explicitly sets `HANDLE_FLAG_INHERIT` before installing the unshareable OFD
marker or publishing any socket registry owner. The corresponding CRT creation
flag also follows that choice. The true/default path adds no flag mutation.
[`SetHandleInformation`](https://learn.microsoft.com/en-us/windows/win32/api/handleapi/nf-handleapi-sethandleinformation)
changes only the requested handle property; a failed operation reports its
native error through `GetLastError`.

When flag setup fails, reservation saves the mapped native error and closes the
private CRT slot. The existing registration failure path consumes a newly
owned native socket, while an alias registration failure preserves the shared
native socket and its surviving descriptor references. No OFD or socket owner
has been published before this new failure point. This is a bounded guarantee
for the new failure path, not a new claim about every pre-existing CRT or
Winsock close failure or allocation failure.

The socket registry's insertion/removal functions, socket metadata encoding,
provider configuration, native I/O, duplication, ordinary descriptor owners,
completed-fence validation and all wire tags remain unchanged. In particular,
the native-fence protocol remains kind 5, shared handle zero and metadata 4.
No FIFO code or protocol change is part of this correction.

## Capability and launch boundaries

Socket metadata continues to use its existing typed socket transfer route.
Its CRT placeholder stays `DescriptorKind::kUnshareable`; clearing the receive
flag does not turn NUL into an exportable ordinary file. Generic raw-file
export still fails with `EOPNOTSUPP`. Generic startup inheritance still fails
with `ERROR_NOT_SUPPORTED` before creating a child when a socket is selected.
The real Windows fixture checks these restrictions with the actual captured
socket, confirms failed export/launch leaves its typed owner intact, and then
uses the same endpoint again.

## Tests and limits

`tools/test_socket_receive.py` compiles byte-exact production bodies for
reservation, registration, socket creation/adoption, transfer metadata,
delivery, and registry insertion/removal/lookup. It uses the complete actual
`socket_internal.h` and the actual internal default declarations. Its declared
Win32/CRT/Winsock/OFD boundaries are backed by real Linux socketpair endpoints
and native descriptor flags. It does not execute Windows or the broker.

The 256-check matrix covers both receiver choices, both sending flag values,
blocking/nonblocking metadata, framing and credentials, typed export, endpoint
I/O, the two-argument default, malformed socket metadata and disposal. Failure
controls cover Winsock startup, nonblocking setup, NUL creation, CRT adoption,
inheritance setup, OFD setup and datagram configuration. Alias failures verify
the surviving endpoint and exact registry reference count. Four in-memory
mutations exercise ignored receiver choice, changed default, ignored flag
failure and lost native-socket cleanup; each compiles and fails behaviorally.

The same tool's `--linux-reference` mode compiles the shared public contract
from `socket_receive_flags_test.inc`. Its 24 combinations cover three carrier
types, two transferred endpoint types, both sender flags and both receiver
flags. Each batch also contains a real ordinary pipe. The 313 checks include
sender closure before receipt, both endpoint directions, preserved socket
status/credentials and ordinary pipe data. Linux reference results are not
Windows evidence.

For actual Windows execution, `unix_rights_test.so` includes that entire public
matrix and runs through the existing Android shim and separately owned broker
in the native-libraries track. The existing EGL default/WARP fixture additionally
transfers a real ANGLE completion, an ordinary pipe and a real socket in each
of its eight sender/receiver/transport combinations. All three receiver flags
are checked. Its existing native completion, pipe EOF, retirement, failure and
launch controls remain; socket direct-adoption and unsupported generic
export/startup checks are added. These are genuine target gates, not host
substitutes. The SCM clients in these added cases share one process; the broker
runs separately. No new two-client-process SCM test is claimed.

Author-side validation includes a fresh full production Windows cross-build
linking `egl_fence_test.exe`, `binderd.exe` and `socket_probe.exe`, plus compiling
the complete public C rights fixture against the actual Windows compatibility
headers. This proves compilation/linking only. Genuine Windows2022/2025,
Android-shim and default/WARP execution remain required.

Local default LeakSanitizer runs fail because this environment is traced.
The unchanged baseline control exhibits the same limitation. Raw failures are
retained separately from explicitly labeled ASan/UBSan runs with leak detection
disabled. The committed CI command uses the default sanitizer gate and no
environment override.

## Exact source qualification and baseline provenance

The published baseline above has tree
`a083bde2afa491a2ebbc16cfe068b4119d63023b` and ordered parents
`20252fde18db387b4d99d99f7050d6336f58bf6e` and
`f6307ad47ba16d838770f100675e33f2eb84ed7a`. Its entire tree and parent list match
the reviewed local node `86fbcee21bf0750ef16f8569f77c8ea7dee20ca2`; only commit
metadata differs. The new qualifier uses the durable published identity.

`socket-receive-source.json` pins the ordered five-path set, both complete
source endpoints, the exact patch, baseline policy identity, and existing
fence/OFD qualification identities. The guard reverses all five actual current
files to exact published-baseline bytes and reapplies the patch to recover the
exact current bytes. It then performs the unchanged fence transition back to
main972 and the original 71-file OFD qualification. The historical manifest,
patch, reviewed checkpoint/hunks, supplier inputs and approval files are not
repinned; `native_runtime_approval` remains false.

The original 33 source mutations, four fence authority-pin controls and two
fence coherent-repin controls remain. Fourteen additional source mutations,
four socket authority-pin controls and six coherent source/manifest/path/patch
repin controls reject altered endpoints, headers, wrong baselines and omitted
dependencies. Even a coherently narrowed socket path set cannot remove the
changed internal header from the following original OFD check.

This is an author implementation and test record. Independent source and
qualification review, target CI, publication and any later FIFO composition
remain separate decisions.
