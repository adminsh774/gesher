# Audio output demand and idle lifecycle

Issue [#178](https://github.com/gesher-runtime/gesher/issues/178), MEDIA-01.
This change supplies the implementation and isolated lifecycle checks. Hardware
restart latency, audible output, idle CPU and full-workload qualification remain
separate acceptance work; no measured CPU or latency gain is claimed here.

## Output discovery

`GetOutputInfo()` discovers the configured output synchronously without starting
the mixer or WASAPI feeder. An unstarted WASAPI client supplies its actual chosen
format, buffer size and stream latency. Its temporary handles/client are then
released. Preparing a low-latency client does not allocate its software PCM ring.
The existing fallback WASAPI path also remains unstarted during discovery.
Discovery publishes rate and latency together in a single atomic snapshot used
by Track latency and timestamp calculations, including before first playback and
after an idle route change. Rendering keeps its own rate/channel state.

A process-local `IMMNotificationClient` invalidates the cache on endpoint/default
route/property changes and wakes an existing mixer. The registration keeps the
COM MTA alive without an application-owned polling thread. Callbacks only advance
an atomic generation and signal an event; they do not block or manage COM object
lifetimes. A caller in an STA uses a short-lived MTA setup helper so registration
is not attached to its message pump. If notification registration fails, later
queries re-probe. A device notification racing a probe leaves that result stale
for the next query. Unchanged metadata retains its public generation, including
when uncached: Android's separate port/patch queries must be able to agree.

Metadata invalidation is separate from rendering invalidation. The renderer
resolves the actual default render endpoint identity/state outside the callback;
unchanged endpoints retain their current client and queued PCM. Unrelated input
notifications and benign output property changes therefore do not tear down
playback. A transient enumeration error is not evidence that a working output
has disappeared. The legacy sink also filters removal/state/default callbacks
against its selected endpoint ID. Real default switches, selected-output loss
and reported client failures still enter recovery.
Failed route lookup leaves the notification unresolved: the next active pass
may retry once promptly, and repeated failures then retry at most every 100 ms.
A later success is cached even if no new notification arrived. This recovery
uses the existing active loop; idle rendering adds no polling or retry timer.

Route observation and opening a client are separate operations. Each WASAPI
sink records the actual persistent ID of its opened MMDevice before initializing
the client. A route-generation change triggers identity reconciliation: learning
an initially unresolved route, or catching up with a switch during opening,
retains the exact sink and its queued PCM when that ID already matches. A
genuinely different identity selects a new output. Failed or empty identity
queries follow the existing hardware-open failure path before rendering; friendly
names and guessed identities are never used. Both WASAPI implementations use the
same identity-query helper. Resolved no-output is an empty route, distinct from a
failed lookup, and real output arrival still invalidates the null sink.

For explicit `GESHER_AUDIO_CAPTURE`, discovery reports the configured software
format and an unopened capture description. It never creates, truncates or
validates the destination by opening it. Actual opening, errors and fallback
selection happen when playback needs a sink. This is configuration metadata,
not a claim that the destination is writable or a physical endpoint exists.

## Rendering lifetime

Creating or priming a stopped Track may create the process's waiting mixer
thread, but opens no rendering sink and arms no periodic timer. Start, the first
write into an empty playing queue, pause/stop/flush, control changes and release
signal an auto-reset event. This signal takes no Engine or Track mutex, including
when called by a Track destructor or MMDevice callback. It therefore does not
invert Engine-to-Track locking, and a signal arriving between the demand check
and the infinite wait remains pending.
The mixer uses nonthrowing Win32 thread creation, matching production's disabled
C++ exceptions. A failed creation rejects that Track without publishing it, and
a later Track can retry. The successful process-lifetime worker releases its
detached thread handle immediately.

The mixer arms its high-resolution 10 ms timer and takes its existing active
priority only when some group is playing or draining. The existing one-second
group tail remains available for queued endpoint audio. Once no group needs
output, the sink enters idle, the timer is cancelled, priority returns to normal,
and the thread waits indefinitely for an event. Device retry runs only while
output is demanded. Control wakes re-evaluate state without adding extra mixes;
a ready timer takes precedence over frequent write notifications.

The low-latency sink creates its feeder on first playback. Stop/Reset serialize
with the feeder's GetBuffer/ReleaseBuffer pair, and writers do not acquire the
client lock while retaining the PCM ring lock. A blocking initial write starts
the consumer before waiting for room. The feeder waits on shutdown/control while
stopped, ignoring stale buffer events, and releases its MMCSS task. It resumes
MMCSS only during active rendering. Endpoint failures propagate to the Engine's
existing output recovery path. The fallback WASAPI sink stops/resets all running
groups at idle.
The feeder retains an owned native thread handle. Close serializes with startup,
signals shutdown, then joins without holding the client lock before releasing
the client and handle. A failed worker creation wakes blocked writers and reports
the sink failure without starting the client or taking MMCSS.

The null/capture clock does not feed newly queued audio through elapsed idle time.
On resume it advances its accounting across the gap. Capture writes the gap as
silence once playback resumes, keeping the timeline between active intervals;
it does not extend the file periodically while idle. Thus trailing indefinite
idle is not materialized until another active interval. Active scheduling stalls
still use the existing clock catch-up behavior. Long capture gaps still require
writing that many silent bytes at resume; this diagnostic file path is not a
hardware restart-latency measurement.

## Validation boundaries

`python3 tools/test_audio_idle_contracts.py --sanitize` executes complete actual
Engine/Track methods, actual WASAPI preparation/control/feed-loop methods, and
actual clock/null/capture sink methods. Synthetic endpoints exist only in these
fixtures. The tests cover metadata-only startup and cache races; unavailable
notifications and stable port generations; capture-file preservation; a forced
start-at-idle-wait race; stop/drain, pause/resume, multiple groups, last release,
endpoint failure/arrival; event/timer allocation failures; lazy feeder creation,
MMCSS retirement, stale buffer events, resumed PCM; concurrent render/stop and
blocked-writer failure; both sink stop paths; and silence/position across idle gaps.
They also execute the actual notification/route classification, before-play and
idle-route latency, and concurrent query/timestamp snapshot regressions.
Integrated actual Engine/Track/route tests also cover initial lookup failure
with unchanged or changed output; repeated failures; notifications racing route
lookup or opening; no-output and open-failure recovery; and metadata-only events
retaining the exact active sink. The shared identity-query helper is checked for
real IDs, null/empty results, preserved errors and allocation cleanup.
Worker-creation failures, Start/Close publication races and shutdown during an
outstanding render buffer also execute the actual lifecycle methods. Idle
observations acknowledge a retained control signal and the following infinite
wait before asserting zero further ticks or wakeups; timer cancellation alone
is earlier than that boundary. A deterministic release/destructor case covers
that distinction. Non-MSVC fixture builds also disable C++ exceptions.

On Linux the fixture substitutes only Win32 wait/timer transport and endpoint
boundaries. CI also runs it on Windows 2022 and 2025 with real Win32 events,
timers and scheduling calls; its COM endpoint remains isolated. The production
Windows sources must additionally compile in the normal native build. Existing
Track/SoundPool state, mixer allocation and unity-rate equivalence checks remain
required regressions. These checks do not establish physical-device playback,
real route removal, audible clipping/underrun or sustained process-tree CPU/RAM.

Implementation references:

- [IMMNotificationClient restrictions](https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/nn-mmdeviceapi-immnotificationclient)
- [Notification registration and callback ownership](https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/nf-mmdeviceapi-immdeviceenumerator-registerendpointnotificationcallback)
- [CoIncrementMTAUsage lifetime](https://learn.microsoft.com/en-us/windows/win32/api/combaseapi/nf-combaseapi-coincrementmtausage)
