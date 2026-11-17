# Exact alarms and Alarms & reminders Settings

Gesher's exact-alarm access follows the pinned Android 16 `android-16.0.0_r4`
contracts. A manifest declaration, an AppOps decision, and a runtime permission
grant are different kinds of state. `SCHEDULE_EXACT_ALARM` is a signature,
privileged, app-op permission; it is not a dangerous permission that an app can
obtain with `requestPermissions`.

## Scheduling and permission state

| Caller | Exact PendingIntent alarm authority |
| --- | --- |
| Target SDK below 31 | Android compatibility allowance |
| Target SDK 31–32, declaring `SCHEDULE_EXACT_ALARM` | Default or allowed AppOps mode |
| Target SDK 33 or later | Granted `SCHEDULE_EXACT_ALARM` special access, or `USE_EXACT_ALARM` |
| Core UID | Android's core-UID exemption |
| Direct `OnAlarmListener` | No exact-alarm permission required; one-shot listener lifetime is required |

The shared `ExactAlarmAccess` policy drives scheduling, queries, and Settings.
The AppOps service owns the durable mode and verifies the installed generation
when Settings commits a change. Shared UID permissions and package ownership use
the normal package and permission services. Gesher does not treat the absence of
Windows Doze restrictions as a power-allowlist grant.

Pending listeners are death-linked from registration through dispatch. Repeating
listeners are rejected. Effective special-access revocation cancels exact alarms
and retires the affected UID's authenticated process generations while preserving
normal service/activity recovery and inexact alarms. The revocation path preserves
valid requests waiting for a process that had not attached yet. A later grant sends
the protected permission-state broadcast; revocation does not send a grant event.

## Native Settings integration

When no installed `com.android.settings` package owns the namespace and the native
backend is available, PackageManager exposes the implemented Android Settings list
and app-detail components. The real intent filters, priority ordering, selectors,
direct-boot classification, and activity-options results agree with dispatch.
An installed Settings package takes precedence over the native components.

The Windows screen shows the actual selected package's access and applies an
explicit user decision through the server AppOps service. Dismissal preserves
state. A decision captured before package replacement cannot mutate its replacement
or return an accepted grant. A committed app-specific grant returns `RESULT_OK`;
dismissal, denial, or an invalidated choice returns `RESULT_CANCELED`.

The launch origin and the activity receiving a result are separate from the task's
top activity. Opening Settings pauses/stops the activity it covers. Closing it
restores the current task top without bringing a stopped result recipient to the
front. Such a recipient keeps its result pending until its normal resume. A
`NEW_TASK` launch cancels the result dependency immediately, as ActivityStarter
does. `NO_USER_ACTION` suppresses the user-leaving hint while retaining lifecycle
transitions.

## Validation boundaries

`tools/test_exact_alarm_contracts.py` runs the production Java policy, AppOps,
Settings coordinator, resolver, and activity lifecycle against actual framework
classes. Only parsed package/process identities, host-window choices, native
allocation/identity facilities, and timer dispatch are isolated fixtures. The
scheduler suite separately checks pending/delivery listener lifetime regressions.

Windows track 94 runs the public alarm/AppOps contracts through generated Binder
transport. The existing native `permission_dialog_test` also exercises the actual
Settings controls in English and Hebrew. Linux host success does not establish
Windows native compilation, dialog execution, OS process termination, or timer
suspend/wake behavior; the latest commit's Windows CI remains a required gate.
The grant broadcast's flags are implemented; this change does not claim to add the
runtime's general background-FGS enforcement or its temporary allowlist policy.

## Android source evidence

- [AlarmManagerService](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/apex/jobscheduler/service/java/com/android/server/alarm/AlarmManagerService.java): access checks, direct listeners, grant broadcasts, and revocation.
- [AlarmManager](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/apex/jobscheduler/framework/java/android/app/AlarmManager.java): target-SDK compatibility changes and public API contracts.
- [Framework permissions](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/core/res/AndroidManifest.xml): permission protection levels.
- [Settings manifest](https://android.googlesource.com/platform/packages/apps/Settings/+/android-16.0.0_r4/AndroidManifest.xml) and [AlarmsAndRemindersDetails](https://android.googlesource.com/platform/packages/apps/Settings/+/android-16.0.0_r4/src/com/android/settings/applications/appinfo/AlarmsAndRemindersDetails.java): components, filters, UID mode changes, and activity results.
- [ActivityStarter](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/services/core/java/com/android/server/wm/ActivityStarter.java), [TaskFragment](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/services/core/java/com/android/server/wm/TaskFragment.java), and [ActivityRecord](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r4/services/core/java/com/android/server/wm/ActivityRecord.java): task selection, pausing the resumed activity, and deferred result delivery.
