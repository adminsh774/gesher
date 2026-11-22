# Native sensor access policy contracts

`tools/test_sensor_access.py` compiles the actual `SensorAccessPolicy.java` and
`sensor_access.cc`. It checks ordered lifecycle transitions, foreground services,
per-UID isolation, retirement, and coherent native snapshots under concurrent
out-of-order publications. No synthetic sensor is enabled by these tests.

With `--java-home`, `--framework-classes`, `--core-classes`, `--app-classes`, and
`--apksig`, it also executes the real server identity check and activity/foreground
service hooks, plus the actual Java client generation/death/reconnect methods.
The established host JNI boundary supplies Android Binder identity and framework
initialization. APK/process admission is explicitly constructed using the existing
AppOps fixture. The host API archive has no framework resource image; the declared
normal high-sampling permission definition is supplied at that host boundary.
Outgoing Binder Parcel callbacks are deliberately delayed rather than replaced by
a fake transport. Actual ART/Binder transport and the native sensor queue belong
to the integrated Windows guest fixture; these tests alone do not establish them.

The controller suite also calls the real device-mode `realStartActivity` with a
declared activity and an admitted process. Its isolated client records the actual
transaction but never acknowledges resume. A previously inactive subscription
must become active after a successful visible launch; failed dispatch and retry,
paused/hidden/covered/retired targets, and cancellation during dispatch must not
gain that authority. Real stop and re-resume hooks check revocation, activation,
and failed-resume state restoration. Against the preceding production app jar,
the first successful-launch assertion fails because the policy stays inactive;
the regression does not depend on inspecting a new private flag.

`tools/test_sensor_access_jni.py --java-home <JDK> --jni-include
<libnativehelper/include_jni>` compiles the actual JNI initialization entrypoint
and atomic receiver against a real host JVM. Real native threads cover first-use
attachment, ownership-preserving cleanup, unavailable/throwing policy transport,
existing caller exceptions, reconnect setup, and the steady-state no-JNI path.
The runtime VM accessor and Java policy service are explicit test boundaries;
the Windows/guest JNI ABI remains the integrated runtime fixture's responsibility.

Production wiring is `Services` → `SensorAccessService` → authenticated
`ActivityTaskManagerService.authenticatedPackageCodeForPid`, with immutable policy
updates from actual activity and foreground-service state changes. Only ordered
callback delivery runs on the service executor, never while controller monitors
are held. `NativeSensorAccess` associates each callback with its connection and
converts server generations into a monotonically increasing process-local
revision. `sensor_access_jni.cc` publishes that revision into one atomic native
word. The native queue sampler therefore performs no Binder/JNI lookup per event.

The policy follows Android 16's
[sensor access and rate checks](https://android.googlesource.com/platform/frameworks/native/+/android-16.0.0_r4/services/sensorservice/SensorService.cpp)
and [NDK sensor API contract](https://developer.android.com/ndk/reference/group/sensor).
Foreground delivery, target-SDK/manifest rate restrictions, and unknown-owner
failure are separate states. An authenticated app can register a queue before
resume; delivery begins only when its UID becomes active. Unsupported microphone
privacy control is not reported as an implemented device capability by this work.
