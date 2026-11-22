# Camera admission checks current permission and process ownership

Refs [#56, C07](https://github.com/gesher-runtime/gesher/issues/56). The broad
camera-permission item remains open.

## Defect and behavior

At main `638e19b4a6c109ec8881ba90a94d64b1817fc095`,
`CameraService.hasCameraPermission` only searched a package's manifest request
list. Both `connectDevice` and `LegacyCamera.setup` used it, so a declared but
denied or revoked runtime permission still admitted a new camera owner. Camera2
also trusted the supplied package name without confirming its process UID.

The shared opening check now queries the live package UID and CAMERA grant.
It uses the actual process identity because this camera service is cached inside
the application process, not a remote cameraserver. Incidental incoming Binder
identity is cleared for local service queries and restored in `finally`. A
package cannot lend its grant across UIDs; a legitimate installed shared-UID
sibling remains eligible. Camera2 additionally validates supplied root UID/PID,
including the `ICameraService.USE_CALLING_UID/PID` sentinels. Package-service or
permission-service failure denies opening. No successful answer is cached.
Here, revocation means the live grant becomes denied. Legacy compatibility
revocation can retain the permission grant while AppOps denies data delivery;
that distinct soft-denial policy remains outside this admission-only change.

Camera2 preserves its existing `ERROR_PERMISSION_DENIED` result, translated by
the framework to `SecurityException`. Legacy setup preserves `-EACCES`. Rejection
happens before publishing a new session or evicting an existing camera owner.

The bundled capture APK targets API 36 and previously only declared CAMERA.
It now requests ordinary runtime permission before starting its worker, opening
a device, or scheduling its automatic test capture. Refusal/cancellation returns
`RESULT_CANCELED`. Duplicate or late results cannot restart a completed activity.
Take and destruction also handle a worker that never started. No production
package receives an automatic grant.

## Contract sources

- [CameraManager.openCamera](https://developer.android.com/reference/android/hardware/camera2/CameraManager):
  CAMERA is required and denial is a `SecurityException`.
- [Android 16 camera service](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android16-release/services/camera/libcameraservice/CameraService.cpp):
  actual camera permission and attribution checks; ongoing AppOps and streaming
  ownership are additional contracts, not established by this change.
- The repository's selected Android 16/API 36 framework classes define both
  ICameraService identity sentinels as `-1`; its CameraManager open path supplies
  context attribution. These ABI inputs were inspected rather than guessed.
- [Runtime permission request workflow](https://developer.android.com/training/permissions/requesting).

## Validation and limits

`CameraPermissionContractTest` invokes the **complete production** Camera2
`connectDevice` and legacy `setup/release` methods. Its package/permission answers
and device enumeration are deliberately controlled boundaries. It exercises
declared-but-denied, grant, revoke before a subsequent open, undeclared denial,
shared-UID attribution, wrong UID/PID, absent package/source, service failures,
package UID reassignment, identity restoration, and denial while an existing
camera owner is retained. It does not capture frames or simulate hardware output.

The Linux host runner uses the real compiled application/framework Java classes
and explicit JNI initialization/identity boundaries. It passed 270 checks. The
same fixture rebuilt against the previous main CameraService/LegacyCamera fails
at `declared CAMERA is initially denied: Camera2 admitted the caller`; this is a
semantic negative control, not a missing-symbol or compilation failure.

`CapturePermissionFlowHost` executes the activity's actual permission, start,
capture, completion and destroy methods with declared UI/permission/camera
boundaries. Its 56 checks cover normal grant, existing grant, refusal, empty
result, queued stale grant, duplicate result, unrelated callback, Cancel,
destruction, pending Take, timer admission and started-resource cleanup.

The full application Java/D8 build, services DEX build, and bundled camera APK
build use the selected
framework/core compile dependencies. Historical cached compile inputs do not
establish a fresh complete runtime distribution. Current-source CI rebuilds
the framework and runs these host gates. The Windows devices track runs the
admission fixture on ART and retains its existing synthetic-camera end-to-end
paths. Its explicit operator grants cover both the devices fixture and bundled
capture APK, including readback from the running permission service. These are
isolated test decisions; they do not alter production consent policy.
The surfaces and recording tracks also use explicit CAMERA grants for their
camera-calling APKs, verified in both standalone and installed profiles. Their
single-process runners use each fixture's actual ordinary application UID.

Actual Windows CI results belong to the published commit/run. No Linux host
result, prepared gate or APK build is described as physical-camera or matching
Android-device execution. This does not complete C07's active capture revocation,
continuous AppOps/data-delivery ownership, trusted proxy chains, renounced
permissions, foreground/privacy transitions, or real-device/Android comparison.

## Reproduce

After building current application classes with `app/build.sh`:

```sh
python3 tools/test_camera_permission.py --java-home "$JAVA_HOME" \
  --framework-classes out/framework/classes --core-classes out/coremod \
  --app-classes out/app/gesher-app-classes.jar
python3 tools/test_camera_permission.py --java-home "$JAVA_HOME" \
  --framework-classes out/framework/classes --core-classes out/coremod \
  --app-classes out/app/gesher-app-classes.jar \
  --source-ref 638e19b4a6c109ec8881ba90a94d64b1817fc095  # expected assertion failure
python3 tools/test_capture_permission_flow.py --java-home "$JAVA_HOME"
```
