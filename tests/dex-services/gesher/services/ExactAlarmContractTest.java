/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.IAlarmManager;
import android.app.IApplicationThread;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.pkg.component.ParsedUsesPermissionImpl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import sun.misc.Unsafe;

/**
 * Android 12+ (API 31) and 13+ (API 33) exact alarm contracts (Track F Item F28):
 * - Unprivileged app (targetSdk >= 31, no exact alarm permission) calling setExact() throws SecurityException.
 * - App with android.permission.SCHEDULE_EXACT_ALARM -> canScheduleExactAlarms() == true, setExact() succeeds.
 * - App with android.permission.USE_EXACT_ALARM -> canScheduleExactAlarms() == true, setExact() succeeds.
 * - Inexact alarms (setWindow, repeating) succeed even without exact alarm permissions.
 * - App with targetSdk < 31 -> backward compatibility fallback allows setExact() without throwing SecurityException.
 * - Revoking SCHEDULE_EXACT_ALARM -> pending exact alarms cancelled, inexact alarms preserved,
 *   and ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED broadcast is sent.
 */
public final class ExactAlarmContractTest {
    private static final String UNPRIV_PKG = "com.gesher.test.unpriv";
    private static final int UNPRIV_UID = 20001, UNPRIV_PID = 9001;

    private static final String EXACT_PKG = "com.gesher.test.exact";
    private static final int EXACT_UID = 20002, EXACT_PID = 9002;

    private static final String USE_EXACT_PKG = "com.gesher.test.useexact";
    private static final int USE_EXACT_UID = 20003, USE_EXACT_PID = 9003;

    private static final String LEGACY_PKG = "com.gesher.test.legacy";
    private static final int LEGACY_UID = 20004, LEGACY_PID = 9004;

    private static final Unsafe UNSAFE;
    public static int checks;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private interface Work {
        void run() throws Exception;
    }

    private static synchronized void check(boolean value, String why) {
        if (!value) throw new AssertionError(why);
        checks++;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static Object get(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }

    private static Class<?> type(String name) throws Exception {
        return Class.forName("com.gesher.server." + name);
    }

    private static Object make(Class<?> type, Class<?>[] parameters, Object... arguments) throws Exception {
        Constructor<?> value = type.getDeclaredConstructor(parameters);
        value.setAccessible(true);
        try {
            return value.newInstance(arguments);
        } catch (InvocationTargetException failure) {
            return rethrow(failure.getCause());
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method value = target.getClass().getDeclaredMethod(name, parameters);
        value.setAccessible(true);
        try {
            return value.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            return rethrow(failure.getCause());
        }
    }

    private static Object rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
        throw new AssertionError(failure);
    }

    private static void rejects(Class<? extends Throwable> expected, String messageSubstring, Work work) throws Exception {
        try {
            work.run();
        } catch (Throwable failure) {
            check(expected.isInstance(failure), "Expected " + expected.getSimpleName() + " but got " + failure);
            if (messageSubstring != null) {
                check(failure.getMessage() != null && failure.getMessage().contains(messageSubstring),
                        "Expected exception message to contain \"" + messageSubstring + "\", got: " + failure.getMessage());
            }
            return;
        }
        throw new AssertionError("Expected " + expected.getSimpleName() + " was not thrown");
    }

    private static void runAs(int uid, int pid, Work action) throws Exception {
        long original = Binder.clearCallingIdentity();
        try {
            Binder.restoreCallingIdentity(((long) uid << 32) | (pid & 0xffffffffL));
            action.run();
        } finally {
            Binder.restoreCallingIdentity(original);
        }
    }

    private static final class AppThread extends IApplicationThread.Default {
        @Override
        public void scheduleRegisteredReceiver(IIntentReceiver receiver, Intent intent,
                int code, String data, Bundle extras, boolean ordered, boolean sticky, boolean assumed,
                int user, int processState, int uid, String packageName) throws RemoteException {
            receiver.performReceive(intent, code, data, extras, ordered, sticky, user);
        }
    }

    private static PendingIntent mockOperation(int id) {
        return new PendingIntent(new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
            }
        });
    }

    private static AlarmManager createAlarmManager(IAlarmManager service, String pkg, int uid, int targetSdk) throws Exception {
        AlarmManager am = (AlarmManager) UNSAFE.allocateInstance(AlarmManager.class);
        set(am, "mService", service);
        set(am, "mPackageName", pkg);
        set(am, "mTargetSdkVersion", targetSdk);
        set(am, "mMainThreadHandler", UNSAFE.allocateInstance(android.os.Handler.class));
        Context context = new ContextWrapper(null) {
            @Override public String getPackageName() { return pkg; }
            @Override public String getOpPackageName() { return pkg; }
            @Override public int getUserId() { return 0; }
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo();
                info.packageName = pkg;
                info.targetSdkVersion = targetSdk;
                info.uid = uid;
                return info;
            }
        };
        set(am, "mContext", context);
        return am;
    }

    private static Object createApkPackage(String name, int uid, int targetSdk, List<String> permissions) throws Exception {
        PackageImpl parsed = (PackageImpl) PackageImpl.forTesting(name, "/fixture/" + name + "/base.apk");
        parsed.setEnabled(true);
        parsed.setTargetSdkVersion(targetSdk);
        if (permissions != null) {
            for (String perm : permissions) {
                parsed.addUsesPermission(new ParsedUsesPermissionImpl(perm, 0, Collections.emptySet()));
            }
        }
        parsed.hideAsFinal();
        Object owner = UNSAFE.allocateInstance(type("ApkPackage"));
        set(owner, "packageName", name);
        set(owner, "mUid", uid);
        set(owner, "parsed", parsed);
        set(owner, "apkPath", "/fixture/" + name + "/base.apk");
        set(owner, "mDataDir", "/fixture/" + name + "/data");
        set(owner, "sharedLibraries", field(type("SharedLibraryGraph"), "EMPTY").get(null));
        set(owner, "mSigningIdentityChecked", true);
        set(owner, "mVerifiedSignatures", new Signature[]{new Signature(new byte[]{7})});
        return owner;
    }

    private static final class TestHarness implements AutoCloseable {
        final Object pm, atm, permissions, broadcasts;
        final IActivityManager am;
        final IAlarmManager alarmService;
        final AlarmManager unprivAm, exactAm, useExactAm, legacyAm;
        final AppThread thread = new AppThread();

        @SuppressWarnings("unchecked")
        TestHarness() throws Exception {
            Object unprivOwner = createApkPackage(UNPRIV_PKG, UNPRIV_UID, 34, Collections.emptyList());
            Object exactOwner = createApkPackage(EXACT_PKG, EXACT_UID, 34,
                    List.of("android.permission.SCHEDULE_EXACT_ALARM"));
            Object useExactOwner = createApkPackage(USE_EXACT_PKG, USE_EXACT_UID, 34,
                    List.of("android.permission.USE_EXACT_ALARM"));
            Object legacyOwner = createApkPackage(LEGACY_PKG, LEGACY_UID, 30, Collections.emptyList());

            Class<?> pmType = type("PackageManagerService");
            Class<?> atmType = type("ActivityTaskManagerService");
            Class<?> amType = type("ActivityManagerService");
            Class<?> callersType = type("CallerIdentity");
            Class<?> notificationType = type("NotificationManagerService");

            pm = make(pmType, new Class<?>[]{Collection.class},
                    List.of(unprivOwner, exactOwner, useExactOwner, legacyOwner));
            atm = make(atmType, new Class<?>[]{pmType}, pm);
            am = (IActivityManager) UNSAFE.allocateInstance(amType);
            Object callers = make(callersType, new Class<?>[]{pmType, atmType}, pm, atm);
            set(am, "mPm", pm);
            set(am, "mAtm", atm);
            set(am, "mCallers", callers);

            Object providers = UNSAFE.allocateInstance(type("ContentProviders"));
            set(providers, "mClients", new HashMap<>());
            set(providers, "mPublished", new HashMap<>());
            set(providers, "mPublishedBy", new HashMap<>());
            set(am, "mProviders", providers);

            permissions = invoke(pm, "permissions", new Class<?>[0]);
            set(am, "mPermissions", permissions);
            invoke(permissions, "attach", new Class<?>[]{callersType}, callers);

            java.util.Properties decisions = (java.util.Properties) get(permissions, "mDecisions");
            synchronized (permissions) {
                decisions.keySet().removeIf(k -> k.toString().startsWith("com.gesher.test."));
            }

            Object catalog = get(permissions, "mCatalog");
            set(catalog, "mLoaded", true);
            set(catalog, "mFrameworkRead", true);
            ((Set<String>) get(catalog, "mProtectedBroadcasts"))
                    .add(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED);
            Map<String, Object> framework = (Map<String, Object>) get(catalog, "mFramework");
            android.content.pm.PermissionInfo sea = new android.content.pm.PermissionInfo();
            sea.name = "android.permission.SCHEDULE_EXACT_ALARM";
            sea.packageName = "android";
            sea.protectionLevel = android.content.pm.PermissionInfo.PROTECTION_NORMAL;
            framework.put(sea.name, sea);
            android.content.pm.PermissionInfo uea = new android.content.pm.PermissionInfo();
            uea.name = "android.permission.USE_EXACT_ALARM";
            uea.packageName = "android";
            uea.protectionLevel = android.content.pm.PermissionInfo.PROTECTION_NORMAL;
            framework.put(uea.name, uea);

            broadcasts = make(type("BroadcastController"), new Class<?>[]{atmType, pmType}, atm, pm);
            set(am, "mBroadcasts", broadcasts);
            set(am, "mPendingIntents", make(type("PendingIntents"), new Class<?>[]{amType, pmType}, am, pm));
            invoke(atm, "setActivityManager", new Class<?>[]{amType}, am);

            // Register process records so caller authentication recognizes PID -> package
            Class<?> processType = type("ActivityTaskManagerService$ProcessRecord");
            for (Object[] spec : new Object[][]{
                    {UNPRIV_PID, UNPRIV_UID, UNPRIV_PKG},
                    {EXACT_PID, EXACT_UID, EXACT_PKG},
                    {USE_EXACT_PID, USE_EXACT_UID, USE_EXACT_PKG},
                    {LEGACY_PID, LEGACY_UID, LEGACY_PKG}
            }) {
                int pid = (int) spec[0];
                int uid = (int) spec[1];
                String name = (String) spec[2];
                Object proc = make(processType, new Class<?>[]{atmType, int.class, IApplicationThread.class},
                        atm, pid, thread);
                set(proc, "uid", uid);
                set(proc, "packageName", name);
                set(proc, "processName", name);
                set(proc, "ready", true);
                ((List<Object>) get(atm, "mProcesses")).add(proc);
            }

            Object serviceImpl = make(type("AlarmManagerService"), new Class<?>[]{amType, pmType}, am, pm);
            set(serviceImpl, "mThread", new Thread()); // Dummy thread to avoid real timer loop
            alarmService = (IAlarmManager) serviceImpl;

            unprivAm = createAlarmManager(alarmService, UNPRIV_PKG, UNPRIV_UID, 34);
            exactAm = createAlarmManager(alarmService, EXACT_PKG, EXACT_UID, 34);
            useExactAm = createAlarmManager(alarmService, USE_EXACT_PKG, USE_EXACT_UID, 34);
            legacyAm = createAlarmManager(alarmService, LEGACY_PKG, LEGACY_UID, 30);
        }

        List<?> getAlarms() throws Exception {
            return (List<?>) get(alarmService, "mAlarms");
        }

        @Override
        public void close() {
            try {
                alarmService.removeAll(UNPRIV_PKG);
                alarmService.removeAll(EXACT_PKG);
                alarmService.removeAll(USE_EXACT_PKG);
                alarmService.removeAll(LEGACY_PKG);
                java.util.Properties decisions = (java.util.Properties) get(permissions, "mDecisions");
                synchronized (permissions) {
                    decisions.keySet().removeIf(k -> k.toString().startsWith("com.gesher.test."));
                    java.io.File mFile = (java.io.File) get(permissions, "mFile");
                    Class<?> devType = type("Device");
                    Method writeMethod = devType.getDeclaredMethod("write", java.io.File.class, java.util.Properties.class);
                    writeMethod.setAccessible(true);
                    writeMethod.invoke(null, mFile, decisions);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 1. Unprivileged app calling setExact() must throw SecurityException; inexact succeeds. */
    private static void testUnprivilegedApp(TestHarness h) throws Exception {
        runAs(UNPRIV_UID, UNPRIV_PID, () -> {
            check(!h.unprivAm.canScheduleExactAlarms(), "Unprivileged app must have canScheduleExactAlarms == false");
            check(!h.alarmService.hasScheduleExactAlarm(UNPRIV_PKG, 0), "Unprivileged app hasScheduleExactAlarm must be false");

            // setExact() throws SecurityException
            rejects(SecurityException.class, "SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM", () -> {
                h.unprivAm.setExact(AlarmManager.ELAPSED_REALTIME, 10_000L, mockOperation(101));
            });

            // setExactAndAllowWhileIdle() throws SecurityException
            rejects(SecurityException.class, "SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM", () -> {
                h.unprivAm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, 10_000L, mockOperation(102));
            });

            // setAlarmClock() throws SecurityException
            rejects(SecurityException.class, "SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM", () -> {
                AlarmManager.AlarmClockInfo clock = new AlarmManager.AlarmClockInfo(10_000L, mockOperation(103));
                h.unprivAm.setAlarmClock(clock, mockOperation(104));
            });

            // Inexact alarms must still succeed for unprivileged apps
            h.unprivAm.setWindow(AlarmManager.ELAPSED_REALTIME, 10_000L, 60_000L, mockOperation(105));
            h.unprivAm.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, 10_000L, 60_000L, mockOperation(106));
        });

        check(h.getAlarms().size() == 2, "Inexact alarms should have been scheduled successfully");
        h.alarmService.removeAll(UNPRIV_PKG);
        check(h.getAlarms().isEmpty(), "Alarms should be cleared after removeAll");
    }

    /** 2. App with SCHEDULE_EXACT_ALARM -> canScheduleExactAlarms() == true, setExact succeeds. */
    private static void testAppWithScheduleExactAlarm(TestHarness h) throws Exception {
        runAs(EXACT_UID, EXACT_PID, () -> {
            check(h.exactAm.canScheduleExactAlarms(), "App with SCHEDULE_EXACT_ALARM must have canScheduleExactAlarms == true");
            check(h.alarmService.hasScheduleExactAlarm(EXACT_PKG, 0), "App with SCHEDULE_EXACT_ALARM hasScheduleExactAlarm must be true");

            // setExact() succeeds
            h.exactAm.setExact(AlarmManager.ELAPSED_REALTIME, 10_000L, mockOperation(201));

            // setExactAndAllowWhileIdle() succeeds
            h.exactAm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, 15_000L, mockOperation(202));

            // setAlarmClock() succeeds
            AlarmManager.AlarmClockInfo clock = new AlarmManager.AlarmClockInfo(20_000L, mockOperation(203));
            h.exactAm.setAlarmClock(clock, mockOperation(204));
        });

        check(h.getAlarms().size() == 3, "All 3 exact alarms should be scheduled");
        h.alarmService.removeAll(EXACT_PKG);
        check(h.getAlarms().isEmpty(), "Alarms should be cleared");
    }

    /** 3. App with USE_EXACT_ALARM -> canScheduleExactAlarms() == true, setExact succeeds. */
    private static void testAppWithUseExactAlarm(TestHarness h) throws Exception {
        runAs(USE_EXACT_UID, USE_EXACT_PID, () -> {
            check(h.useExactAm.canScheduleExactAlarms(), "App with USE_EXACT_ALARM must have canScheduleExactAlarms == true");
            check(!h.alarmService.hasScheduleExactAlarm(USE_EXACT_PKG, 0),
                    "App with USE_EXACT_ALARM only must have hasScheduleExactAlarm == false");

            // setExact() succeeds
            h.useExactAm.setExact(AlarmManager.ELAPSED_REALTIME, 10_000L, mockOperation(301));
            h.useExactAm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, 15_000L, mockOperation(302));
        });

        check(h.getAlarms().size() == 2, "Exact alarms with USE_EXACT_ALARM should be scheduled");
        h.alarmService.removeAll(USE_EXACT_PKG);
        check(h.getAlarms().isEmpty(), "Alarms should be cleared");
    }

    /** 4. TargetSdk < 31 fallback behavior: setExact() succeeds without permission. */
    private static void testTargetSdkFallback(TestHarness h) throws Exception {
        runAs(LEGACY_UID, LEGACY_PID, () -> {
            // canScheduleExactAlarms reports false when permission is missing even on legacy targetSdk
            check(!h.legacyAm.canScheduleExactAlarms(),
                    "Legacy app without permission should report canScheduleExactAlarms == false");

            // But setExact does NOT throw SecurityException due to targetSdk < 31 backward compatibility
            h.legacyAm.setExact(AlarmManager.ELAPSED_REALTIME, 10_000L, mockOperation(401));
            h.legacyAm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, 15_000L, mockOperation(402));
        });

        check(h.getAlarms().size() == 2, "Legacy app exact alarms should be scheduled without throwing SecurityException");
        h.alarmService.removeAll(LEGACY_PKG);
        check(h.getAlarms().isEmpty(), "Alarms should be cleared");
    }

    /** 5. Revoking SCHEDULE_EXACT_ALARM -> pending exact alarms cancelled, broadcast sent. */
    private static void testRevokePermissionCancelsExactAlarms(TestHarness h) throws Exception {
        AtomicInteger broadcastReceived = new AtomicInteger();
        IIntentReceiver receiver = new IIntentReceiver.Stub() {
            @Override
            public void performReceive(Intent intent, int code, String data,
                    Bundle extras, boolean ordered, boolean sticky, int user) {
                if (AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(intent.getAction())) {
                    broadcastReceived.incrementAndGet();
                }
            }
        };

        // Register receiver for ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        invoke(h.broadcasts, "registerReceiver", new Class<?>[]{IApplicationThread.class, String.class,
                IIntentReceiver.class, IntentFilter.class, int.class, String.class, int.class},
                h.thread, EXACT_PKG, receiver,
                new IntentFilter(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
                EXACT_PID, null, Context.RECEIVER_EXPORTED);

        // Schedule one exact alarm and one inexact windowed alarm
        runAs(EXACT_UID, EXACT_PID, () -> {
            h.exactAm.setExact(AlarmManager.ELAPSED_REALTIME, 50_000L, mockOperation(501));
            h.exactAm.setWindow(AlarmManager.ELAPSED_REALTIME, 60_000L, 30_000L, mockOperation(502));
        });

        check(h.getAlarms().size() == 2, "Expected 1 exact and 1 inexact alarm scheduled");

        // Revoke SCHEDULE_EXACT_ALARM permission via PermissionPolicy
        invoke(h.permissions, "revokeRuntimePermission",
                new Class<?>[]{String.class, String.class, String.class, int.class, String.class},
                EXACT_PKG, "android.permission.SCHEDULE_EXACT_ALARM", "default:0", 0, "revoked in test");

        // Permission should now be denied
        runAs(EXACT_UID, EXACT_PID, () -> {
            check(!h.exactAm.canScheduleExactAlarms(), "After revocation, canScheduleExactAlarms must be false");
            rejects(SecurityException.class, "SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM", () -> {
                h.exactAm.setExact(AlarmManager.ELAPSED_REALTIME, 10_000L, mockOperation(503));
            });
        });

        // Exact alarm must be cancelled, but the inexact alarm must remain!
        List<?> remainingAlarms = h.getAlarms();
        check(remainingAlarms.size() == 1, "Only inexact alarm should remain after exact alarm revocation");
        Object remainingAlarm = remainingAlarms.get(0);
        check(!(boolean) get(remainingAlarm, "exact"), "Remaining alarm must be inexact");

        // Broadcast must have been sent and received
        check(broadcastReceived.get() == 1,
                "ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED broadcast must be delivered to app");

        // Cleanup
        h.alarmService.removeAll(EXACT_PKG);
    }

    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime");
        try (TestHarness h = new TestHarness()) {
            testUnprivilegedApp(h);
            testAppWithScheduleExactAlarm(h);
            testAppWithUseExactAlarm(h);
            testTargetSdkFallback(h);
            testRevokePermissionCancelsExactAlarms(h);

            System.out.println("ExactAlarmContractTest all passed: " + checks + " checks verified");
        }
    }
}
