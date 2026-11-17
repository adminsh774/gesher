/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.app.AppOpsManager;
import android.app.SyncNotedAppOp;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.gesher.server.AppOpsService;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Contract verification of AppOpsService state machine, mode enforcement, active client tracking,
 * client death cleanup, and callback dispatch.
 */
public final class AppOpsContractTest {
    public static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }

    static final class TestToken implements InvocationHandler {
        final IBinder binder;
        final List<IBinder.DeathRecipient> recipients = new ArrayList<>();
        boolean dead;

        TestToken() {
            binder = (IBinder) Proxy.newProxyInstance(
                    IBinder.class.getClassLoader(),
                    new Class<?>[]{IBinder.class},
                    this);
        }

        @Override
        public synchronized Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "TestToken@" + System.identityHashCode(proxy);
                case "linkToDeath":
                    if (dead) throw new RemoteException("Token dead");
                    recipients.add((IBinder.DeathRecipient) args[0]);
                    return null;
                case "unlinkToDeath":
                    return recipients.remove(args[0]);
                case "isBinderAlive":
                case "pingBinder":
                    return !dead;
                case "queryLocalInterface":
                    return null;
                default:
                    return null;
            }
        }

        void die() {
            List<IBinder.DeathRecipient> pending;
            synchronized (this) {
                dead = true;
                pending = new ArrayList<>(recipients);
            }
            for (IBinder.DeathRecipient r : pending) {
                r.binderDied();
            }
        }
    }

    static final class TestCallback extends IAppOpsCallback.Default {
        final AtomicInteger calls = new AtomicInteger();
        int lastOp = -1;
        int lastUid = -1;
        String lastPackageName;

        @Override
        public void opChanged(int op, int uid, String packageName, String persistentDeviceId) {
            calls.incrementAndGet();
            lastOp = op;
            lastUid = uid;
            lastPackageName = packageName;
        }
    }

    public static void testDefaultModes(AppOpsService service) {
        check(service.checkOperationRaw(AppOpsManager.OP_VIBRATE, 10001, "com.test.app", null)
                == AppOpsManager.MODE_DEFAULT, "Raw unconfigured mode must be MODE_DEFAULT");
        check(service.checkOperation(AppOpsManager.OP_VIBRATE, 10001, "com.test.app")
                == AppOpsManager.MODE_ALLOWED, "Vibrate default mode should be MODE_ALLOWED");
        check(service.checkOperation(AppOpsManager.OP_NONE, 10001, "com.test.app")
                == AppOpsManager.MODE_ALLOWED, "OP_NONE default mode should be MODE_ALLOWED");

        // Runtime permission default modes matching AOSP
        int cameraDefault = AppOpsService.getDefaultMode(AppOpsManager.OP_CAMERA);
        check(cameraDefault == AppOpsManager.opToDefaultMode(AppOpsManager.OP_CAMERA),
                "Camera default mode should match AOSP default");
        int audioDefault = AppOpsService.getDefaultMode(AppOpsManager.OP_RECORD_AUDIO);
        check(audioDefault == AppOpsManager.opToDefaultMode(AppOpsManager.OP_RECORD_AUDIO),
                "Audio default mode should match AOSP default");
    }

    public static void testModeIgnored(AppOpsService service) {
        final int op = AppOpsManager.OP_VIBRATE;
        final int uid = 10001;
        final String pkg = "com.test.ignored";

        service.setMode(op, uid, pkg, AppOpsManager.MODE_IGNORED);
        check(service.checkOperationRaw(op, uid, pkg, null) == AppOpsManager.MODE_IGNORED,
                "Configured raw mode must be MODE_IGNORED");
        check(service.checkOperation(op, uid, pkg) == AppOpsManager.MODE_IGNORED,
                "Effective mode must be MODE_IGNORED");

        SyncNotedAppOp noted = service.noteOperation(op, uid, pkg, null, false, null, false);
        check(noted != null && noted.getOpMode() == AppOpsManager.MODE_IGNORED,
                "noteOperation must return MODE_IGNORED");

        check(service.checkOperation(op, uid, "com.other.app") == AppOpsManager.MODE_ALLOWED,
                "Other package must remain allowed");

        service.setMode(op, uid, pkg, AppOpsManager.MODE_DEFAULT);
        check(service.checkOperationRaw(op, uid, pkg, null) == AppOpsManager.MODE_DEFAULT,
                "Resetting mode to MODE_DEFAULT must restore raw default");
        check(service.checkOperation(op, uid, pkg) == AppOpsManager.MODE_ALLOWED,
                "Resetting mode to MODE_DEFAULT must restore effective allowed");

        // UID-level mode
        final int uid2 = 10002;
        service.setUidMode(op, uid2, AppOpsManager.MODE_IGNORED);
        check(service.checkOperation(op, uid2, "any.pkg1") == AppOpsManager.MODE_IGNORED,
                "UID-level MODE_IGNORED must apply to pkg1");
        check(service.checkOperation(op, uid2, "any.pkg2") == AppOpsManager.MODE_IGNORED,
                "UID-level MODE_IGNORED must apply to pkg2");

        // Package override over UID mode
        service.setMode(op, uid2, "any.pkg1", AppOpsManager.MODE_ALLOWED);
        check(service.checkOperation(op, uid2, "any.pkg1") == AppOpsManager.MODE_ALLOWED,
                "Package-level override must precede UID-level mode");
        check(service.checkOperation(op, uid2, "any.pkg2") == AppOpsManager.MODE_IGNORED,
                "Other package must still adhere to UID-level MODE_IGNORED");

        service.setMode(op, uid2, "any.pkg1", AppOpsManager.MODE_DEFAULT);
        service.setUidMode(op, uid2, AppOpsManager.MODE_DEFAULT);
    }

    public static void testModeErrored(AppOpsService service) {
        final int op = AppOpsManager.OP_RECORD_AUDIO;
        final int uid = 10003;
        final String pkg = "com.test.errored";

        service.setMode(op, uid, pkg, AppOpsManager.MODE_ERRORED);
        check(service.checkOperationRaw(op, uid, pkg, null) == AppOpsManager.MODE_ERRORED,
                "Configured raw mode must be MODE_ERRORED");
        check(service.checkOperation(op, uid, pkg) == AppOpsManager.MODE_ERRORED,
                "Effective mode must be MODE_ERRORED");

        SyncNotedAppOp noted = service.noteOperation(op, uid, pkg, null, false, null, false);
        check(noted != null && noted.getOpMode() == AppOpsManager.MODE_ERRORED,
                "noteOperation must return MODE_ERRORED");

        SyncNotedAppOp started = service.startOperation(new TestToken().binder, op, uid, pkg, null,
                true, false, null, false, 0, 0);
        check(started != null && started.getOpMode() == AppOpsManager.MODE_ERRORED,
                "startOperation must return MODE_ERRORED");
        check(!service.isOperationActive(op, uid, pkg),
                "Errored startOperation must not become active");

        service.setMode(op, uid, pkg, AppOpsManager.MODE_DEFAULT);
    }

    public static void testWatchers(AppOpsService service) {
        final int targetOp = AppOpsManager.OP_CAMERA;
        final int otherOp = AppOpsManager.OP_VIBRATE;
        final String targetPkg = "com.test.watch.camera";
        final String otherPkg = "com.test.watch.other";
        final int uid = 10004;

        TestCallback callback = new TestCallback();
        service.startWatchingMode(targetOp, targetPkg, callback);

        // Different op, same package -> no callback
        service.setMode(otherOp, uid, targetPkg, AppOpsManager.MODE_IGNORED);
        check(callback.calls.get() == 0, "Watcher should not be triggered for unmatching op");

        // Same op, different package -> no callback
        service.setMode(targetOp, uid, otherPkg, AppOpsManager.MODE_IGNORED);
        check(callback.calls.get() == 0, "Watcher should not be triggered for unmatching package");

        // Target op and package -> callback triggered
        service.setMode(targetOp, uid, targetPkg, AppOpsManager.MODE_IGNORED);
        check(callback.calls.get() == 1, "Watcher must be triggered on matching setMode");
        check(callback.lastOp == targetOp, "Callback op mismatch");
        check(callback.lastUid == uid, "Callback uid mismatch");
        check(targetPkg.equals(callback.lastPackageName), "Callback package mismatch");

        // Wildcard watcher (OP_NONE, null)
        TestCallback wildcard = new TestCallback();
        service.startWatchingMode(AppOpsManager.OP_NONE, null, wildcard);

        service.setMode(AppOpsManager.OP_FINE_LOCATION, 10005, "com.test.wildcard",
                AppOpsManager.MODE_ALLOWED);
        check(wildcard.calls.get() == 1, "Wildcard watcher must be triggered for any op/pkg");
        check(wildcard.lastOp == AppOpsManager.OP_FINE_LOCATION, "Wildcard lastOp mismatch");

        // Stop watching
        service.stopWatchingMode(callback);
        service.setMode(targetOp, uid, targetPkg, AppOpsManager.MODE_ALLOWED);
        check(callback.calls.get() == 1, "Unregistered watcher must not be triggered again");

        service.stopWatchingMode(wildcard);
    }

    public static void testActiveOperationTracking(AppOpsService service) {
        final int op = AppOpsManager.OP_CAMERA;
        final int uid = 10006;
        final String pkg = "com.test.active";

        check(!service.isOperationActive(op, uid, pkg), "Initial state must not be active");

        TestToken token = new TestToken();
        SyncNotedAppOp start1 = service.startOperation(token.binder, op, uid, pkg, null,
                true, false, null, false, 0, 0);
        check(start1 != null && start1.getOpMode() == AppOpsManager.MODE_ALLOWED,
                "startOperation must succeed with MODE_ALLOWED");
        check(service.isOperationActive(op, uid, pkg), "Operation must be active after startOperation");

        // Normal finish
        service.finishOperation(token.binder, op, uid, pkg, null);
        check(!service.isOperationActive(op, uid, pkg),
                "Operation must not be active after finishOperation");

        // Client death cleanup
        TestToken dyingToken = new TestToken();
        SyncNotedAppOp start2 = service.startOperation(dyingToken.binder, op, uid, pkg, null,
                true, false, null, false, 0, 0);
        check(start2 != null && start2.getOpMode() == AppOpsManager.MODE_ALLOWED,
                "Second startOperation must succeed");
        check(service.isOperationActive(op, uid, pkg),
                "Operation must be active for dyingToken");

        dyingToken.die();
        check(!service.isOperationActive(op, uid, pkg),
                "Operation must be cleaned up immediately upon client death");
    }

    public static void testPermissionToOpCode(AppOpsService service) {
        check(service.permissionToOpCode("POST_NOTIFICATIONS") == AppOpsManager.OP_POST_NOTIFICATION,
                "POST_NOTIFICATIONS mapping");
        check(service.permissionToOpCode("android.permission.POST_NOTIFICATIONS") == AppOpsManager.OP_POST_NOTIFICATION,
                "android.permission.POST_NOTIFICATIONS mapping");
        check(service.permissionToOpCode("CAMERA") == AppOpsManager.OP_CAMERA,
                "CAMERA mapping");
        check(service.permissionToOpCode("android.permission.CAMERA") == AppOpsManager.OP_CAMERA,
                "android.permission.CAMERA mapping");
        check(service.permissionToOpCode("RECORD_AUDIO") == AppOpsManager.OP_RECORD_AUDIO,
                "RECORD_AUDIO mapping");
        check(service.permissionToOpCode("android.permission.RECORD_AUDIO") == AppOpsManager.OP_RECORD_AUDIO,
                "android.permission.RECORD_AUDIO mapping");
        check(service.permissionToOpCode("FINE_LOCATION") == AppOpsManager.OP_FINE_LOCATION,
                "FINE_LOCATION mapping");
        check(service.permissionToOpCode("ACCESS_FINE_LOCATION") == AppOpsManager.OP_FINE_LOCATION,
                "ACCESS_FINE_LOCATION mapping");
        check(service.permissionToOpCode("android.permission.ACCESS_FINE_LOCATION") == AppOpsManager.OP_FINE_LOCATION,
                "android.permission.ACCESS_FINE_LOCATION mapping");
        check(service.permissionToOpCode("COARSE_LOCATION") == AppOpsManager.OP_COARSE_LOCATION,
                "COARSE_LOCATION mapping");
        check(service.permissionToOpCode("ACCESS_COARSE_LOCATION") == AppOpsManager.OP_COARSE_LOCATION,
                "ACCESS_COARSE_LOCATION mapping");
        check(service.permissionToOpCode("android.permission.ACCESS_COARSE_LOCATION") == AppOpsManager.OP_COARSE_LOCATION,
                "android.permission.ACCESS_COARSE_LOCATION mapping");
        check(service.permissionToOpCode("MANAGE_EXTERNAL_STORAGE") == AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE,
                "MANAGE_EXTERNAL_STORAGE mapping");
        check(service.permissionToOpCode("android.permission.MANAGE_EXTERNAL_STORAGE") == AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE,
                "android.permission.MANAGE_EXTERNAL_STORAGE mapping");
        check(service.permissionToOpCode("READ_EXTERNAL_STORAGE") == AppOpsManager.OP_READ_EXTERNAL_STORAGE,
                "READ_EXTERNAL_STORAGE mapping");
        check(service.permissionToOpCode("WRITE_EXTERNAL_STORAGE") == AppOpsManager.OP_WRITE_EXTERNAL_STORAGE,
                "WRITE_EXTERNAL_STORAGE mapping");
        check(service.permissionToOpCode("READ_CONTACTS") == AppOpsManager.OP_READ_CONTACTS,
                "READ_CONTACTS mapping");
        check(service.permissionToOpCode("WRITE_CONTACTS") == AppOpsManager.OP_WRITE_CONTACTS,
                "WRITE_CONTACTS mapping");
        check(service.permissionToOpCode("READ_CALENDAR") == AppOpsManager.OP_READ_CALENDAR,
                "READ_CALENDAR mapping");
        check(service.permissionToOpCode("WRITE_CALENDAR") == AppOpsManager.OP_WRITE_CALENDAR,
                "WRITE_CALENDAR mapping");
        check(service.permissionToOpCode("READ_CALL_LOG") == AppOpsManager.OP_READ_CALL_LOG,
                "READ_CALL_LOG mapping");
        check(service.permissionToOpCode("WRITE_CALL_LOG") == AppOpsManager.OP_WRITE_CALL_LOG,
                "WRITE_CALL_LOG mapping");
        check(service.permissionToOpCode("CALL_PHONE") == AppOpsManager.OP_CALL_PHONE,
                "CALL_PHONE mapping");
        check(service.permissionToOpCode("READ_SMS") == AppOpsManager.OP_READ_SMS,
                "READ_SMS mapping");
        check(service.permissionToOpCode("SEND_SMS") == AppOpsManager.OP_SEND_SMS,
                "SEND_SMS mapping");
        check(service.permissionToOpCode("RECEIVE_SMS") == AppOpsManager.OP_RECEIVE_SMS,
                "RECEIVE_SMS mapping");
        check(service.permissionToOpCode("SYSTEM_ALERT_WINDOW") == AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
                "SYSTEM_ALERT_WINDOW mapping");
        check(service.permissionToOpCode("WRITE_SETTINGS") == AppOpsManager.OP_WRITE_SETTINGS,
                "WRITE_SETTINGS mapping");
        check(service.permissionToOpCode("VIBRATE") == AppOpsManager.OP_VIBRATE,
                "VIBRATE mapping");
        check(service.permissionToOpCode("some.nonexistent.permission") == AppOpsManager.OP_NONE,
                "Unknown permission must map to OP_NONE");
        check(service.permissionToOpCode(null) == AppOpsManager.OP_NONE,
                "null permission must map to OP_NONE");
    }

    public static void testCheckPackage(AppOpsService service) {
        check(service.checkPackage(-1, "com.valid.pkg") == AppOpsManager.MODE_ERRORED,
                "Negative uid must be MODE_ERRORED");
        check(service.checkPackage(10001, null) == AppOpsManager.MODE_ERRORED,
                "null package must be MODE_ERRORED");
        check(service.checkPackage(10001, "") == AppOpsManager.MODE_ERRORED,
                "Empty package must be MODE_ERRORED");
        check(service.checkPackage(10001, "com.valid.pkg") == AppOpsManager.MODE_ALLOWED,
                "Valid package without PM must be MODE_ALLOWED");
    }

    public static void testResetAllModes(AppOpsService service) {
        final int op = AppOpsManager.OP_VIBRATE;
        service.setMode(op, 10007, "pkg.one", AppOpsManager.MODE_IGNORED);
        service.setMode(op, 10008, "pkg.two", AppOpsManager.MODE_IGNORED);

        // Reset single package
        service.resetAllModes(0, "pkg.one");
        check(service.checkOperationRaw(op, 10007, "pkg.one", null) == AppOpsManager.MODE_DEFAULT,
                "pkg.one must be reset to MODE_DEFAULT");
        check(service.checkOperationRaw(op, 10008, "pkg.two", null) == AppOpsManager.MODE_IGNORED,
                "pkg.two must remain MODE_IGNORED");

        // Reset all
        service.resetAllModes(0, null);
        check(service.checkOperationRaw(op, 10008, "pkg.two", null) == AppOpsManager.MODE_DEFAULT,
                "All packages must be reset to MODE_DEFAULT");
    }

    public static void testPersistence() {
        java.io.File temp;
        try {
            temp = java.io.File.createTempFile("appops-test", ".xml");
            temp.deleteOnExit();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        AppOpsService s1 = new AppOpsService(null, temp);
        final int op = AppOpsManager.OP_VIBRATE;
        final int op2 = AppOpsManager.OP_CAMERA;
        s1.setMode(op, 10009, "com.test.persist", AppOpsManager.MODE_IGNORED);
        s1.setUidMode(op2, 10010, AppOpsManager.MODE_ERRORED);

        // Simulate server restart by creating a second service instance loading the same file
        AppOpsService s2 = new AppOpsService(null, temp);
        check(s2.checkOperationRaw(op, 10009, "com.test.persist", null) == AppOpsManager.MODE_IGNORED,
                "Persisted package mode must survive restart");
        check(s2.checkOperation(op, 10009, "com.test.persist") == AppOpsManager.MODE_IGNORED,
                "Persisted package effective mode must survive restart");
        check(s2.checkOperationRaw(op2, 10010, "any.pkg", null) == AppOpsManager.MODE_ERRORED,
                "Persisted UID mode must survive restart");
        check(s2.checkOperation(op2, 10010, "any.pkg") == AppOpsManager.MODE_ERRORED,
                "Persisted UID effective mode must survive restart");

        // Clear modes and verify restart state is cleared
        s2.resetAllModes(0, null);
        AppOpsService s3 = new AppOpsService(null, temp);
        check(s3.checkOperationRaw(op, 10009, "com.test.persist", null) == AppOpsManager.MODE_DEFAULT,
                "Reset modes must survive restart");
        temp.delete();
    }

    public static void main(String[] args) {
        checks = 0;
        AppOpsService service = new AppOpsService(null, null);
        testDefaultModes(service);
        testModeIgnored(service);
        testModeErrored(service);
        testWatchers(service);
        testActiveOperationTracking(service);
        testPermissionToOpCode(service);
        testCheckPackage(service);
        testResetAllModes(service);
        testPersistence();

        System.out.println("AppOpsContractTest all passed: " + checks + " checks verified");
    }
}
