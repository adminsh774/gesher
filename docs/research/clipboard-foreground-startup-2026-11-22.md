# Bound native foreground fixture startup before clipboard-denial checks

## Observed failure

The main-branch Windows 2025 job at commit
`8b316aebf09ab46d5407e955bf86ed748a1e739f` failed before the background
clipboard-denial probe could run:

- [CI run 44975994107, job 137069403973](https://github.com/gesher-runtime/gesher/actions/runs/44975994107/job/137069403973)
- `owned foreground window did not start` after the existing 15-second readiness bound.
- `owned foreground helper did not retire` after its existing 10-second stop bound.

The fixture launched hidden Windows PowerShell, compiled C# with `Add-Type`,
created a native HWND, and then published a receipt. It captured neither child
output nor intermediate phases. The failed run does not establish which of
those startup phases stalled. The identical helper completed four real
foreground/clipboard checks in the Windows 2022 job. This is evidence of a
fixture startup failure, not proof of a Gesher window-restoration regression.

## Change and preserved contract

`clipboard_foreground.exe` is an isolated native test actor, built from the
current checkout before any timed application check. CMake includes it in the
existing Windows CI artifact; the installer does not include this executable.
The short Windows host gate also compiles the same source with MSVC.

The actor creates a hidden HWND and atomically publishes its actual PID,
process creation time, and HWND. The existing windows3 test still independently
verifies each identity, explicitly makes that foreign window visible and
foreground, puts a nonempty value on the real Windows clipboard, asks the
background Android app to read it, requires denial, and rechecks ownership and
foreground afterward. The restored app must still regain native focus and
receive clipboard notifications. No runtime permission or foreground rule is
changed.

The 15-second startup and 10-second retirement bounds are unchanged. The parent
retains its process object, writes the owned stop file, and, on timeout, fails
the check and terminates that owned process tree. The native actor additionally
holds a verified parent-process handle, so parent termination releases its
HWND. Unexpected window destruction, invalid receipt paths and the three-minute
maximum lifetime are failures, not successful retirement.

The native phases, stdout and stderr are retained in each invocation's folder
and uploaded with CI evidence. Shell startup, script loading and dynamic C#
compilation are absent from the timed child path; there is no retry, fallback
actor or reduced clipboard-denial assertion.

## Validation

`tests/windows/clipboard/ForeignForegroundChecks.ps1` drives the real native
actor on Windows 2022 and 2025. It checks repeated hidden-window creation,
actual PID/creation-time/HWND identity, paths containing spaces and non-ASCII
characters, normal cleanup, failed publication, unexpected destruction and
parent-death cleanup. A live-sentinel negative control verifies that a stale
creation identity is rejected without terminating the process or its window.
Process handles recovered from receipts confer cleanup ownership only after
creation-time and native HWND verification; rejected handles are disposed only.
The existing full windows3 application gate remains.

Local LLVM-MinGW compilation and PowerShell parsing validate buildability and
syntax only. Actual HWND, process-lifecycle, foreground and application behavior
must be established by the Windows gates; local cross-compilation is not a
Windows execution result.
