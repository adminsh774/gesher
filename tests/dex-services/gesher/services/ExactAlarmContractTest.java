/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.IAlarmListener;
import android.app.IAlarmCompleteListener;
import com.android.internal.app.IAppOpsService;
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
 * Pinned Android 16 exact-alarm contracts through the actual permission/AppOps/alarm services.
 * Parsed package/process identities, host windows and timer dispatch are isolated fixtures;
 * this suite does not claim Windows suspend/wake or OS process-termination coverage.
 */
public final class ExactAlarmContractTest {
    private static final String UNPRIV_PKG = "com.gesher.test.unpriv";
    private static final int UNPRIV_UID = 10001, UNPRIV_PID = 9001;

    private static final String EXACT_PKG = "com.gesher.test.exact";
    private static final int EXACT_UID = 10002, EXACT_PID = 9002;

    private static final String USE_EXACT_PKG = "com.gesher.test.useexact";
    private static final int USE_EXACT_UID = 10003, USE_EXACT_PID = 9003;

    private static final String LEGACY_PKG = "com.gesher.test.legacy";
    private static final int LEGACY_UID = 10004, LEGACY_PID = 9004;

    private static final String BOTH_PKG = "com.gesher.test.bothexact";
    private static final int BOTH_UID = 10005, BOTH_PID = 9005;
    private static final String S_PKG = "com.gesher.test.sexact";
    private static final int S_UID = 10006, S_PID = 9006;
    private static final String PRE_USE_PKG = "com.gesher.test.preuseexact";
    private static final int PRE_USE_UID = 10007, PRE_USE_PID = 9007;

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
        int exits;
        final IBinder binder = new Binder();
        java.util.function.Consumer<android.app.servertransaction.ClientTransaction> onTransaction;
        @Override public IBinder asBinder() { return binder; }
        @Override public void scheduleTransaction(android.app.servertransaction.ClientTransaction transaction) {
            if (onTransaction != null) onTransaction.accept(transaction);
        }
        @Override public void scheduleExit() { exits++; }
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
        var activity = new com.android.internal.pm.pkg.component.ParsedActivityImpl();
        activity.setPackageName(name); activity.setName(name + ".FixtureActivity");
        activity.setEnabled(true); activity.setProcessName(name);
        parsed.addActivity(activity);
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

    private static IBinder transport(IBinder target) {
        return new Binder() {
            @Override protected boolean onTransact(int code, Parcel request, Parcel reply, int flags)
                    throws RemoteException { return target.transact(code, request, reply, flags); }
        };
    }

    private static IBinder serviceBinder(Class<?> contract, IInterface implementation) throws Exception {
        Class<?> standIn = type("StandIn");
        Object delegate = standIn.getMethod("of", Class.class, IInterface.class)
                .invoke(null, contract, implementation);
        return transport((IBinder) standIn.getMethod("asBinder").invoke(delegate));
    }

    private static final class TestHarness implements AutoCloseable {
        final Object pm, atm, permissions, broadcasts, alarmImpl;
        final IAppOpsService appOps;
        final Object previousNotifications;
        final List<Object> previousWorkSources;
        final IActivityManager am;
        final IAlarmManager alarmService;
        final AlarmManager unprivAm, exactAm, useExactAm, legacyAm;
        final AppThread thread = new AppThread();

        @SuppressWarnings("unchecked")
        TestHarness(boolean wire) throws Exception {
            previousWorkSources = new ArrayList<>((List<Object>) field(type("DeviceWork"), "sSources").get(null));
            previousNotifications = field(type("NotificationManagerService"), "sLast").get(null);
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
                    List.of(unprivOwner, exactOwner, useExactOwner, legacyOwner,
                        createApkPackage(BOTH_PKG, BOTH_UID, 34, List.of(
                            "android.permission.SCHEDULE_EXACT_ALARM", "android.permission.USE_EXACT_ALARM")),
                        createApkPackage(S_PKG, S_UID, 32, List.of("android.permission.SCHEDULE_EXACT_ALARM")),
                        createApkPackage(PRE_USE_PKG, PRE_USE_UID, 32, List.of("android.permission.USE_EXACT_ALARM"))));
            IAppOpsService opsImpl = (IAppOpsService) invoke(pm, "appOps", new Class<?>[0]);
            appOps = wire ? IAppOpsService.Stub.asInterface(serviceBinder(IAppOpsService.class, opsImpl)) : opsImpl;
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
            set(providers, "mPublishedCode", new HashMap<>());
            set(providers, "mInstalling", new HashMap<>());
            set(providers, "mConnections", new HashMap<>());
            set(providers, "mRequests", new java.util.HashSet<>());
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
            sea.protectionLevel = android.content.pm.PermissionInfo.PROTECTION_SIGNATURE
                    | android.content.pm.PermissionInfo.PROTECTION_FLAG_PRIVILEGED
                    | android.content.pm.PermissionInfo.PROTECTION_FLAG_APPOP;
            framework.put(sea.name, sea);
            android.content.pm.PermissionInfo uea = new android.content.pm.PermissionInfo();
            uea.name = "android.permission.USE_EXACT_ALARM";
            uea.packageName = "android";
            uea.protectionLevel = android.content.pm.PermissionInfo.PROTECTION_NORMAL;
            framework.put(uea.name, uea);

            java.util.function.ToIntBiFunction<android.service.notification.StatusBarNotification, Boolean> toast
                    = (value, update) -> 0;
            Object notifications = make(notificationType,
                    new Class<?>[]{pmType, amType, java.util.function.ToIntBiFunction.class}, pm, am, toast);
            set(am, "mServices", make(type("ActiveServices"),
                    new Class<?>[]{atmType, pmType, notificationType}, atm, pm, notifications));
            broadcasts = make(type("BroadcastController"), new Class<?>[]{atmType, pmType}, atm, pm);
            set(am, "mBroadcasts", broadcasts);
            set(am, "mPendingIntents", make(type("PendingIntents"), new Class<?>[]{amType, pmType}, am, pm));
            invoke(atm, "setActivityManager", new Class<?>[]{amType}, am);
            Object appOpsImpl = invoke(pm, "appOps", new Class<?>[0]);
            java.util.function.Supplier<Map<?, ?>> uidStates = () -> {
                try { return (Map<?, ?>)invoke(am, "appOpsUidStates", new Class<?>[0]); }
                catch (Exception failure) { throw new AssertionError(failure); }
            };
            invoke(appOpsImpl, "attach", new Class<?>[]{callersType, java.util.function.Supplier.class}, callers, uidStates);

            // Register process records so caller authentication recognizes PID -> package
            Class<?> processType = type("ActivityTaskManagerService$ProcessRecord");
            for (Object[] spec : new Object[][]{
                    {UNPRIV_PID, UNPRIV_UID, UNPRIV_PKG},
                    {EXACT_PID, EXACT_UID, EXACT_PKG},
                    {USE_EXACT_PID, USE_EXACT_UID, USE_EXACT_PKG},
                    {LEGACY_PID, LEGACY_UID, LEGACY_PKG},
                    {BOTH_PID, BOTH_UID, BOTH_PKG}, {S_PID, S_UID, S_PKG},
                    {PRE_USE_PID, PRE_USE_UID, PRE_USE_PKG}
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
                set(proc, "packageCode", invoke(pm, "get", new Class<?>[]{String.class}, name));
                ((List<Object>) get(atm, "mProcesses")).add(proc);
            }

            alarmImpl = make(type("AlarmManagerService"), new Class<?>[]{amType, pmType}, am, pm);
            set(alarmImpl, "mThread", new Thread()); // Manual scheduling boundaries, no Windows timer claim.
            alarmService = wire ? IAlarmManager.Stub.asInterface(serviceBinder(IAlarmManager.class,
                    (IAlarmManager)alarmImpl)) : (IAlarmManager)alarmImpl;

            unprivAm = createAlarmManager(alarmService, UNPRIV_PKG, UNPRIV_UID, 34);
            exactAm = createAlarmManager(alarmService, EXACT_PKG, EXACT_UID, 34);
            useExactAm = createAlarmManager(alarmService, USE_EXACT_PKG, USE_EXACT_UID, 34);
            legacyAm = createAlarmManager(alarmService, LEGACY_PKG, LEGACY_UID, 30);
        }

        List<?> getAlarms() throws Exception {
            return (List<?>) get(alarmImpl, "mAlarms");
        }

        @SuppressWarnings("unchecked")
        void reattach(String name, int uid, int pid) throws Exception {
            Object proc = make(type("ActivityTaskManagerService$ProcessRecord"),
                    new Class<?>[]{type("ActivityTaskManagerService"), int.class, IApplicationThread.class},
                    atm, pid, thread);
            set(proc, "uid", uid); set(proc, "packageName", name); set(proc, "processName", name);
            set(proc, "ready", true);
            set(proc, "packageCode", invoke(pm, "get", new Class<?>[]{String.class}, name));
            ((List<Object>) get(atm, "mProcesses")).add(proc);
        }

        @SuppressWarnings("unchecked")
        @Override public void close() {
            try {
                for (String name : List.of(UNPRIV_PKG, EXACT_PKG, USE_EXACT_PKG, LEGACY_PKG,
                        BOTH_PKG, S_PKG, PRE_USE_PKG)) alarmService.removeAll(name);
                appOps.resetAllModes(0, null);
                List<Object> sources = (List<Object>) field(type("DeviceWork"), "sSources").get(null);
                sources.clear(); sources.addAll(previousWorkSources);
                field(type("NotificationManagerService"), "sLast").set(null, previousNotifications);
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
    }

    private static void access(TestHarness h) throws Exception {
        runAs(UNPRIV_UID, UNPRIV_PID, () -> {
            check(!h.unprivAm.canScheduleExactAlarms(), "Unprivileged PendingIntent access");
            rejects(SecurityException.class, "SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM",
                    () -> h.unprivAm.setExact(AlarmManager.ELAPSED_REALTIME, 10_000, mockOperation(1)));
            rejects(SecurityException.class, null, () -> h.unprivAm.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME, 10_000, mockOperation(2)));
            rejects(SecurityException.class, null, () -> h.unprivAm.setAlarmClock(
                    new AlarmManager.AlarmClockInfo(10_000, mockOperation(3)), mockOperation(4)));
            h.unprivAm.setWindow(AlarmManager.ELAPSED_REALTIME, 10_000, 60_000, mockOperation(5));
            h.unprivAm.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, 10_000, 60_000, mockOperation(6));
        });
        check(h.getAlarms().size() == 2, "Inexact alarms remain available");
        h.alarmService.removeAll(UNPRIV_PKG);
        runAs(EXACT_UID, EXACT_PID, () -> {
            check(!h.exactAm.canScheduleExactAlarms(), "A modern manifest declaration is not special access");
            check(!h.alarmService.hasScheduleExactAlarm(EXACT_PKG, 0), "Default-denied special access");
        });
        check(h.appOps.checkOperationRaw(AppOpsManager.OP_SCHEDULE_EXACT_ALARM, EXACT_UID,
                EXACT_PKG, null) == AppOpsManager.MODE_DEFAULT, "AppOps retains MODE_DEFAULT");
        rejects(IllegalArgumentException.class, "runtime permission", () -> invoke(h.permissions,
                "grantRuntimePermission", new Class<?>[]{String.class, String.class, String.class, int.class},
                EXACT_PKG, "android.permission.SCHEDULE_EXACT_ALARM", "default:0", 0));
        h.appOps.setMode(AppOpsManager.OP_SCHEDULE_EXACT_ALARM, EXACT_UID, EXACT_PKG, AppOpsManager.MODE_ALLOWED);
        runAs(EXACT_UID, EXACT_PID, () -> {
            check(h.exactAm.canScheduleExactAlarms(), "Explicit AppOps grant enables modern exact alarms");
            check(h.alarmService.hasScheduleExactAlarm(EXACT_PKG, 0), "Special-access query sees AppOps");
            h.exactAm.setExact(AlarmManager.ELAPSED_REALTIME, 10_000, mockOperation(7));
        });
        check(h.getAlarms().size() == 1, "Granted exact alarm is published");
        h.alarmService.removeAll(EXACT_PKG);
        runAs(LEGACY_UID, LEGACY_PID, () -> {
            check(h.legacyAm.canScheduleExactAlarms(), "Legacy capability agrees with scheduling");
            check(!h.alarmService.hasScheduleExactAlarm(LEGACY_PKG, 0), "Legacy compatibility is not SEA grant");
            h.legacyAm.setExact(AlarmManager.ELAPSED_REALTIME, 10_000, mockOperation(8));
        });
        h.alarmService.removeAll(LEGACY_PKG);
        runAs(S_UID, S_PID, () -> {
            check(h.alarmService.canScheduleExactAlarms(S_PKG), "S/T compatibility boundary permits target32 default");
            check(h.alarmService.hasScheduleExactAlarm(S_PKG, 0), "Target32 declaration uses default app-op");
        });
        runAs(PRE_USE_UID, PRE_USE_PID, () -> check(!h.alarmService.canScheduleExactAlarms(PRE_USE_PKG),
                "USE_EXACT_ALARM is enabled only for target33+"));
        runAs(USE_EXACT_UID, USE_EXACT_PID, () -> {
            check(h.useExactAm.canScheduleExactAlarms(), "Normal USE_EXACT_ALARM grants its documented capability");
            check(!h.alarmService.hasScheduleExactAlarm(USE_EXACT_PKG, 0), "USE does not invent SEA state");
        });
        check(!h.alarmService.hasScheduleExactAlarm("com.gesher.test.missing", 0), "Unknown target never has SEA");
        check(!h.alarmService.hasScheduleExactAlarm(EXACT_PKG, 10), "Absent user never reports target access");
    }

    private static final class TrackedListener extends IAlarmListener.Stub {
        final List<IBinder.DeathRecipient> deaths = new ArrayList<>();
        int links, unlinks;
        boolean dieDuringLink;
        @Override public void doAlarm(IAlarmCompleteListener completion) { }
        @Override public void linkToDeath(IBinder.DeathRecipient recipient, int flags) {
            deaths.add(recipient); links++;
            if (dieDuringLink) recipient.binderDied();
        }
        @Override public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
            if (!deaths.remove(recipient)) return false;
            unlinks++; return true;
        }
        void die() { for (IBinder.DeathRecipient recipient : new ArrayList<>(deaths)) recipient.binderDied(); }
    }

    private static void listeners(TestHarness h) throws Exception {
        runAs(UNPRIV_UID, UNPRIV_PID, () -> {
            AlarmManager.OnAlarmListener publicListener = () -> { };
            h.unprivAm.setExact(AlarmManager.ELAPSED_REALTIME, 10_000, "public", publicListener, null);
            check(h.getAlarms().size() == 1, "Public OnAlarmListener is permission-exempt");
            check(!h.unprivAm.canScheduleExactAlarms(), "Listener exemption does not claim PendingIntent access");
            h.unprivAm.cancel(publicListener);
            check(h.getAlarms().isEmpty(), "Public listener cancellation releases pending state");
            TrackedListener listener = new TrackedListener();
            h.alarmService.set(UNPRIV_PKG, AlarmManager.ELAPSED_REALTIME, 10_000, 0, 0,
                    0, null, listener, "pending", null, null);
            check(listener.links == 1 && h.getAlarms().size() == 1, "Pending listener death is linked at registration");
            listener.die();
            check(h.getAlarms().isEmpty() && listener.deaths.isEmpty(), "Dead pending listener is removed before firing");
            TrackedListener dead = new TrackedListener(); dead.dieDuringLink = true;
            h.alarmService.set(UNPRIV_PKG, AlarmManager.ELAPSED_REALTIME, 10_000, 0, 0,
                    0, null, dead, "already-dead", null, null);
            check(h.getAlarms().isEmpty() && dead.deaths.isEmpty(), "Death during registration cannot publish a listener");
            rejects(IllegalArgumentException.class, "Repeating", () -> h.alarmService.set(UNPRIV_PKG,
                    AlarmManager.ELAPSED_REALTIME, 10_000, 0, 60_000, 0, null, listener, "repeat", null, null));
        });
    }

    private static void transitions(TestHarness h) throws Exception {
        AtomicInteger grants = new AtomicInteger();
        IIntentReceiver receiver = new IIntentReceiver.Stub() {
            @Override public void performReceive(Intent intent, int code, String data, Bundle extras,
                    boolean ordered, boolean sticky, int user) {
                if (AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(intent.getAction())) {
                    check((intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) != 0, "Grant broadcast foreground delivery flag");
                    grants.incrementAndGet();
                }
            }
        };
        invoke(h.broadcasts, "registerReceiver", new Class<?>[]{IApplicationThread.class, String.class,
                IIntentReceiver.class, IntentFilter.class, int.class, String.class, int.class}, h.thread,
                EXACT_PKG, receiver, new IntentFilter(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
                EXACT_PID, null, Context.RECEIVER_EXPORTED);
        runAs(EXACT_UID, EXACT_PID, () -> {
            h.exactAm.setExact(AlarmManager.ELAPSED_REALTIME, 50_000, mockOperation(9));
            h.exactAm.setWindow(AlarmManager.ELAPSED_REALTIME, 60_000, 30_000, mockOperation(10));
        });
        h.appOps.setMode(AppOpsManager.OP_SCHEDULE_EXACT_ALARM, EXACT_UID, EXACT_PKG, AppOpsManager.MODE_ERRORED);
        check(h.getAlarms().size() == 1 && !(boolean)get(h.getAlarms().get(0), "exact"),
                "Effective revoke removes exact alarms and preserves inexact alarms");
        check(grants.get() == 0, "No grant broadcast on revocation");
        check(h.thread.exits == 1, "Effective revoke terminates the old process generation");
        h.reattach(EXACT_PKG, EXACT_UID, EXACT_PID);
        runAs(EXACT_UID, EXACT_PID, () -> check(!h.exactAm.canScheduleExactAlarms(), "Restart sees revoked AppOps state"));
        invoke(h.broadcasts, "registerReceiver", new Class<?>[]{IApplicationThread.class, String.class,
                IIntentReceiver.class, IntentFilter.class, int.class, String.class, int.class}, h.thread,
                EXACT_PKG, receiver, new IntentFilter(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
                EXACT_PID, null, Context.RECEIVER_EXPORTED);
        h.appOps.setMode(AppOpsManager.OP_SCHEDULE_EXACT_ALARM, EXACT_UID, EXACT_PKG, AppOpsManager.MODE_ALLOWED);
        check(grants.get() == 1, "One protected broadcast on the access grant edge");
        h.appOps.setMode(AppOpsManager.OP_SCHEDULE_EXACT_ALARM, EXACT_UID, EXACT_PKG, AppOpsManager.MODE_ALLOWED);
        check(grants.get() == 1, "No duplicate broadcast for an unchanged mode");
        h.alarmService.removeAll(EXACT_PKG);
        h.appOps.setMode(AppOpsManager.OP_SCHEDULE_EXACT_ALARM, BOTH_UID, BOTH_PKG, AppOpsManager.MODE_ALLOWED);
        runAs(BOTH_UID, BOTH_PID, () -> h.alarmService.set(BOTH_PKG, AlarmManager.ELAPSED_REALTIME,
                60_000, 0, 0, 0, mockOperation(11), null, null, null, null));
        int exits = h.thread.exits;
        h.appOps.setMode(AppOpsManager.OP_SCHEDULE_EXACT_ALARM, BOTH_UID, BOTH_PKG, AppOpsManager.MODE_ERRORED);
        check(h.getAlarms().size() == 1 && h.thread.exits == exits,
                "USE_EXACT_ALARM retains effective authority when SEA is denied");
        h.alarmService.removeAll(BOTH_PKG);
    }

    private static void settingsResolution(TestHarness h) throws Exception {
        android.content.pm.IPackageManager manager = (android.content.pm.IPackageManager)h.pm;
        String action = "android.settings.REQUEST_SCHEDULE_EXACT_ALARM";
        String list = "com.android.settings.Settings$AlarmsAndRemindersActivity";
        String detail = "com.android.settings.Settings$AlarmsAndRemindersAppActivity";
        Intent app = new Intent(action, android.net.Uri.fromParts("package", EXACT_PKG, null));
        var resolved = manager.resolveIntent(app, null, PackageManager.MATCH_DEFAULT_ONLY, 0);
        check(resolved != null && detail.equals(resolved.activityInfo.name),
                "PackageManager exposes the available app-specific Settings handler");
        var component = new android.content.ComponentName(resolved.activityInfo.packageName,
                resolved.activityInfo.name);
        check(manager.getActivityInfo(component, 0, 0) != null,
                "Resolved Settings component has real dispatchable activity metadata");
        check(manager.queryIntentActivities(app, null, PackageManager.MATCH_DEFAULT_ONLY, 0)
                .getList().size() == 1, "Settings query and resolve agree");
        List<?> options = manager.queryIntentActivityOptions(null, null, null, app, null,
                PackageManager.MATCH_DEFAULT_ONLY, 0).getList();
        check(options.size() == 1 && detail.equals(((android.content.pm.ResolveInfo)options.get(0)).activityInfo.name),
                "Activity options report the same implemented native Settings handler");
        var filtered = manager.resolveIntent(app, null, PackageManager.GET_RESOLVED_FILTER, 0);
        check(filtered.filter != null && filtered.filter.hasAction(action)
                && filtered.filter.hasCategory(Intent.CATEGORY_DEFAULT)
                && filtered.filter.hasDataScheme("package") && filtered.system,
                "Resolved filter describes the implemented system handler");
        check(manager.resolveIntent(app, null, PackageManager.MATCH_DIRECT_BOOT_AWARE, 0) != null
                && manager.resolveIntent(app, null, PackageManager.MATCH_DIRECT_BOOT_UNAWARE, 0) == null,
                "Settings preserves the direct-boot-aware manifest classification");
        check(list.equals(manager.resolveIntent(new Intent(action), null,
                PackageManager.MATCH_DEFAULT_ONLY, 0).activityInfo.name),
                "Request without a package resolves to the permission list");
        check(manager.resolveIntent(new Intent(app).setPackage(EXACT_PKG), null, 0, 0) == null,
                "Native Settings never intercepts another explicitly requested package");
        check(manager.resolveIntent(new Intent(action, android.net.Uri.parse("https://example.invalid/")),
                null, 0, 0) == null, "Unmatched URI schemes do not resolve as Settings");
        check(manager.resolveIntent(new Intent(app).setDataAndType(app.getData(), "text/plain"),
                "text/plain", 0, 0) == null, "Settings filter does not accept MIME types");
        check(manager.resolveIntent(new Intent(app).addCategory(Intent.CATEGORY_BROWSABLE),
                null, 0, 0) == null, "Settings filter does not accept unmatched categories");
        check(manager.resolveIntent(app, null, 0, 10) == null
                && manager.getActivityInfo(component, 0, 10) == null,
                "Native Settings is not advertised for an unavailable user");
        check(manager.resolveIntent(new Intent("fixture.explicit").setComponent(component),
                null, 0, 0) != null, "Explicit Settings component resolves without filter matching");
        Intent selector = new Intent("fixture.selector"); selector.setSelector(app);
        check(detail.equals(manager.resolveIntent(selector, null, 0, 0).activityInfo.name),
                "An Intent selector resolves the same native Settings component");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void settingsLifecycle(TestHarness h) throws Exception {
        Class<?> recordType = type("ActivityTaskManagerService$ActivityRecord");
        android.content.pm.ActivityInfo info = (android.content.pm.ActivityInfo)invoke(h.pm,
                "getActivityInfo", new Class<?>[]{android.content.ComponentName.class, long.class, int.class},
                new android.content.ComponentName(EXACT_PKG, EXACT_PKG + ".FixtureActivity"), 0L, 0);
        Object source = make(recordType, new Class<?>[]{type("ActivityTaskManagerService"),
                android.content.pm.ActivityInfo.class, Intent.class, int.class, int.class, String.class,
                recordType, String.class, int.class}, h.atm, info, new Intent(), 1, EXACT_PID, EXACT_PKG,
                null, null, -1);
        Object process = null;
        for (Object candidate : (List<?>)get(h.atm, "mProcesses")) {
            if (EXACT_PKG.equals(get(candidate, "packageName"))) process = candidate;
        }
        check(process != null, "Settings caller process is attached");
        set(source, "app", process);
        Class<? extends Enum> state = (Class<? extends Enum>)type("ActivityTaskManagerService$State");
        set(source, "state", Enum.valueOf(state, "RESUMED"));
        ((List<Object>)get(process, "activities")).add(source);
        ((List<Object>)get(h.atm, "mActivities")).add(source);
        ((List<Integer>)get(h.atm, "mTaskOrder")).add(1);
        List<String> order = new ArrayList<>();
        List<android.app.ResultInfo> delivered = new ArrayList<>();
        List<Boolean> userLeaving = new ArrayList<>();
        h.thread.onTransaction = transaction -> {
            try {
                for (var item : transaction.getTransactionItems()) {
                    String name = item.getClass().getSimpleName(); order.add(name);
                    Object recipient = item instanceof android.app.servertransaction.ActivityTransactionItem activity
                            ? invoke(h.atm, "forToken", new Class<?>[]{IBinder.class}, activity.getActivityToken()) : null;
                    if (name.equals("ActivityResultItem")) delivered.addAll(
                            (List<android.app.ResultInfo>)get(item, "mResultInfoList"));
                    if (name.equals("PauseActivityItem")) {
                        userLeaving.add((Boolean)get(item, "mUserLeaving"));
                        invoke(h.atm, "activityPaused", new Class<?>[]{recordType}, recipient);
                    }
                    if (name.equals("StopActivityItem")) invoke(h.atm, "activityStopped",
                            new Class<?>[]{recordType, Bundle.class}, recipient, new Bundle());
                    if (name.equals("ResumeActivityItem")) invoke(h.atm, "activityResumed", new Class<?>[]{recordType}, recipient);
                }
            } catch (Exception failure) { throw new AssertionError(failure); }
        };
        AtomicInteger selection = new AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicReference<Object> covered = new java.util.concurrent.atomic.AtomicReference<>(source);
        Class<?> dialogType = type("ExactAlarmAccessRequests$Dialog");
        Object dialog = java.lang.reflect.Proxy.newProxyInstance(dialogType.getClassLoader(),
                new Class<?>[]{dialogType}, (proxy, method, arguments) -> {
                    check(get(covered.get(), "state").toString().equals("STOPPED"),
                            "The activity covered by Settings pauses and stops before the window opens");
                    return selection.get();
                });
        Object requests = make(type("ExactAlarmAccessRequests"), new Class<?>[]{type("PackageManagerService"),
                java.util.concurrent.Executor.class, dialogType}, h.pm, (java.util.concurrent.Executor)Runnable::run, dialog);
        Object previous = get(h.atm, "mExactAlarmRequests");
        Object previousPm = get(h.pm, "mExactAlarmAccessRequests");
        set(h.atm, "mExactAlarmRequests", requests);
        set(h.pm, "mExactAlarmAccessRequests", requests);
        try {
            settingsResolution(h);
            for (int choice : new int[]{-1, 1}) {
                selection.set(choice); order.clear(); delivered.clear();
                runAs(EXACT_UID, EXACT_PID, () -> {
                    int result = ((android.app.IActivityTaskManager)h.atm).startActivity(h.thread, EXACT_PKG,
                            null, new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
                                android.net.Uri.fromParts("package", EXACT_PKG, null)), null,
                            (IBinder)get(source, "token"), "settings", 42, 0, null, null);
                    check(result == ActivityManager.START_SUCCESS, "Actual activity starter dispatches exact alarm Settings");
                });
                check(order.indexOf("PauseActivityItem") >= 0 && order.indexOf("StopActivityItem") > order.indexOf("PauseActivityItem"),
                        "Settings start sends ordered pause and stop callbacks");
                check(order.indexOf("ActivityResultItem") > order.indexOf("StopActivityItem")
                        && order.indexOf("ResumeActivityItem") > order.indexOf("ActivityResultItem"),
                        "Settings dismissal reports its result before resuming the caller");
                int expected = choice == 1 ? android.app.Activity.RESULT_OK : android.app.Activity.RESULT_CANCELED;
                check(delivered.size() == 1 && delivered.get(0).mResultCode == expected
                        && delivered.get(0).mRequestCode == 42 && "settings".equals(delivered.get(0).mResultWho),
                        "Settings delivers the Android result for the actual user choice");
                check(get(source, "state").toString().equals("RESUMED") && (int)get(source, "exactAlarmSettings") == 0,
                        "Closing Settings restores the live source activity once");
            }
            // startActivity without a result token still covers the visible activity.
            selection.set(-1); order.clear(); delivered.clear();
            runAs(EXACT_UID, EXACT_PID, () -> {
                int result = ((android.app.IActivityTaskManager)h.atm).startActivity(h.thread, EXACT_PKG,
                        null, new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM"), null,
                        null, null, -1, 0, null, null);
                check(result == ActivityManager.START_SUCCESS, "Settings can open without requesting a result");
            });
            check(order.contains("PauseActivityItem") && order.contains("StopActivityItem")
                    && order.contains("ResumeActivityItem") && delivered.isEmpty(),
                    "Settings without resultTo still preserves foreground lifecycle without inventing a result");

            // A stopped parent can start for a result while another instance is on top.
            Object foreground = make(recordType, new Class<?>[]{type("ActivityTaskManagerService"),
                    android.content.pm.ActivityInfo.class, Intent.class, int.class, int.class, String.class,
                    recordType, String.class, int.class}, h.atm, info, new Intent(), 1, EXACT_PID, EXACT_PKG,
                    null, null, -1);
            set(foreground, "app", process); set(foreground, "state", Enum.valueOf(state, "RESUMED"));
            set(source, "state", Enum.valueOf(state, "STOPPED"));
            ((List<Object>)get(process, "activities")).add(foreground);
            ((List<Object>)get(h.atm, "mActivities")).add(foreground);
            covered.set(foreground); order.clear(); delivered.clear(); selection.set(1);
            try {
                runAs(EXACT_UID, EXACT_PID, () -> {
                    int result = ((android.app.IActivityTaskManager)h.atm).startActivity(h.thread, EXACT_PKG,
                            null, new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
                                android.net.Uri.fromParts("package", EXACT_PKG, null)), null,
                            (IBinder)get(source, "token"), "background-settings", 43, 0, null, null);
                    check(result == ActivityManager.START_SUCCESS, "A live background source can start Settings in its task");
                });
                check(order.contains("PauseActivityItem") && order.contains("StopActivityItem")
                        && order.contains("ResumeActivityItem") && delivered.isEmpty(),
                        "Settings covers the task top and leaves the background recipient's result pending");
                check(get(source, "state").toString().equals("STOPPED")
                        && get(foreground, "state").toString().equals("RESUMED")
                        && ((List<?>)get(source, "pendingResults")).size() == 1,
                        "Dismissing Settings does not bring its background result recipient to the front");
            } finally {
                ((List<Object>)get(process, "activities")).remove(foreground);
                ((List<Object>)get(h.atm, "mActivities")).remove(foreground);
                set(h.atm, "mTopResumed", null); covered.set(source);
            }
            // The fixture removes the covering instance; the actual resume path consumes the result.
            order.clear(); invoke(h.atm, "resume", new Class<?>[]{recordType}, source);
            check(delivered.size() == 1 && delivered.get(0).mRequestCode == 43
                    && delivered.get(0).mResultCode == android.app.Activity.RESULT_OK
                    && order.indexOf("ActivityResultItem") < order.indexOf("ResumeActivityItem"),
                    "The background result is delivered once before its recipient's later resume");

            order.clear(); delivered.clear(); userLeaving.clear();
            runAs(EXACT_UID, EXACT_PID, () -> {
                int result = ((android.app.IActivityTaskManager)h.atm).startActivity(h.thread, EXACT_PKG,
                        null, new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
                            android.net.Uri.fromParts("package", EXACT_PKG, null)).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION), null,
                        (IBinder)get(source, "token"), "new-task-settings", 44, 0, null, null);
                check(result == ActivityManager.START_SUCCESS, "New-task Settings starts after canceling its result dependency");
            });
            check(delivered.size() == 1 && delivered.get(0).mResultCode == android.app.Activity.RESULT_CANCELED
                    && delivered.get(0).mRequestCode == 44
                    && order.indexOf("ActivityResultItem") < order.indexOf("PauseActivityItem"),
                    "NEW_TASK sends only the immediate Android canceled result even when access is then granted");
            check(userLeaving.size() == 1 && !userLeaving.get(0),
                    "NO_USER_ACTION suppresses the user-leaving hint while preserving pause and resume");
        } finally {
            h.thread.onTransaction = null;
            set(h.atm, "mExactAlarmRequests", previous);
            set(h.pm, "mExactAlarmAccessRequests", previousPm);
            ((List<Object>)get(process, "activities")).remove(source);
            ((List<Object>)get(h.atm, "mActivities")).remove(source);
            ((List<Integer>)get(h.atm, "mTaskOrder")).remove(Integer.valueOf(1));
            set(h.atm, "mTopResumed", null);
        }
    }

    private static void settings(TestHarness h) throws Exception {
        Class<?> dialogType = type("ExactAlarmAccessRequests$Dialog");
        Class<?> requestType = type("ExactAlarmAccessRequests");
        java.util.concurrent.Executor direct = Runnable::run;
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger activityResult = new AtomicInteger(99);
        java.util.function.IntConsumer completion = result -> { activityResult.set(result); completed.incrementAndGet(); };
        Object cancel = java.lang.reflect.Proxy.newProxyInstance(dialogType.getClassLoader(),
                new Class<?>[]{dialogType}, (proxy, method, arguments) -> {
                    String[] names = (String[])arguments[0];
                    check(names.length == 1 && EXACT_PKG.equals(names[0]), "Settings names the actual target package");
                    return -1;
                });
        Object requests = make(requestType, new Class<?>[]{type("PackageManagerService"),
                java.util.concurrent.Executor.class, dialogType}, h.pm, direct, cancel);
        int original = h.appOps.checkOperationRaw(AppOpsManager.OP_SCHEDULE_EXACT_ALARM,
                EXACT_UID, EXACT_PKG, null);
        invoke(requests, "request", new Class<?>[]{String.class, Runnable.class, java.util.function.IntConsumer.class},
                EXACT_PKG, (Runnable)() -> { }, completion);
        check(completed.get() == 1 && activityResult.get() == android.app.Activity.RESULT_CANCELED
                && original == h.appOps.checkOperationRaw(AppOpsManager.OP_SCHEDULE_EXACT_ALARM, EXACT_UID, EXACT_PKG, null),
                "Closing Settings preserves AppOps and completes with RESULT_CANCELED");
        Object grant = java.lang.reflect.Proxy.newProxyInstance(dialogType.getClassLoader(),
                new Class<?>[]{dialogType}, (proxy, method, arguments) -> 1);
        requests = make(requestType, new Class<?>[]{type("PackageManagerService"),
                java.util.concurrent.Executor.class, dialogType}, h.pm, direct, grant);
        invoke(requests, "request", new Class<?>[]{String.class, Runnable.class, java.util.function.IntConsumer.class},
                EXACT_PKG, (Runnable)() -> { }, completion);
        check(h.appOps.checkOperationRaw(AppOpsManager.OP_SCHEDULE_EXACT_ALARM, EXACT_UID,
                EXACT_PKG, null) == AppOpsManager.MODE_ALLOWED && activityResult.get() == android.app.Activity.RESULT_OK,
                "Settings writes real uid AppOps access and returns RESULT_OK for the selected grant");
        Object replacement = createApkPackage(EXACT_PKG, EXACT_UID, 34,
                List.of("android.permission.SCHEDULE_EXACT_ALARM"));
        Object replaced = java.lang.reflect.Proxy.newProxyInstance(dialogType.getClassLoader(),
                new Class<?>[]{dialogType}, (proxy, method, arguments) -> {
                    try { invoke(h.pm, "add", new Class<?>[]{type("ApkPackage")}, replacement); }
                    catch (Exception failure) { throw new AssertionError("Fixture replacement failed", failure); }
                    check(invoke(h.pm, "get", new Class<?>[]{String.class}, EXACT_PKG) == replacement,
                            "The stale-choice fixture actually publishes the replacement package");
                    return 0; // A decision about old installed code must not reach its replacement.
                });
        requests = make(requestType, new Class<?>[]{type("PackageManagerService"),
                java.util.concurrent.Executor.class, dialogType}, h.pm, direct, replaced);
        invoke(requests, "request", new Class<?>[]{String.class, Runnable.class, java.util.function.IntConsumer.class},
                EXACT_PKG, (Runnable)() -> { }, completion);
        check(h.appOps.checkOperationRaw(AppOpsManager.OP_SCHEDULE_EXACT_ALARM, EXACT_UID,
                EXACT_PKG, null) == AppOpsManager.MODE_ALLOWED && activityResult.get() == android.app.Activity.RESULT_CANCELED,
                "Stale Settings choice cannot mutate replacement code or report an accepted grant");
        check(completed.get() == 3, "Every Settings session completes once");
    }

    private static Object settingsHandler(String name, int uid, int priority, boolean system) throws Exception {
        Object owner = createApkPackage(name, uid, 36, Collections.emptyList());
        PackageImpl parsed = (PackageImpl)get(owner, "parsed");
        var activity = (com.android.internal.pm.pkg.component.ParsedActivityImpl)parsed.getActivities().get(0);
        activity.setExported(true);
        var filter = new com.android.internal.pm.pkg.component.ParsedIntentInfoImpl();
        filter.setHasDefault(true);
        filter.getIntentFilter().addAction("android.settings.REQUEST_SCHEDULE_EXACT_ALARM");
        filter.getIntentFilter().addCategory(Intent.CATEGORY_DEFAULT);
        filter.getIntentFilter().addDataScheme("package");
        filter.getIntentFilter().setPriority(priority);
        activity.addIntent(filter);
        set(owner, "mSystemAddon", system);
        return owner;
    }

    private static void settingsInstalledResolution(TestHarness h) throws Exception {
        Class<?> dialogType = type("ExactAlarmAccessRequests$Dialog");
        Object dialog = java.lang.reflect.Proxy.newProxyInstance(dialogType.getClassLoader(),
                new Class<?>[]{dialogType}, (proxy, method, arguments) -> -1);
        Object requests = make(type("ExactAlarmAccessRequests"), new Class<?>[]{type("PackageManagerService"),
                java.util.concurrent.Executor.class, dialogType}, h.pm, (java.util.concurrent.Executor)Runnable::run, dialog);
        Object previous = get(h.pm, "mExactAlarmAccessRequests"); set(h.pm, "mExactAlarmAccessRequests", requests);
        try {
            String low = "com.gesher.test.alowsettings", high = "com.gesher.test.zhighsettings";
            String ordinary = "com.gesher.test.ordinarysettings";
            for (Object owner : List.of(settingsHandler(low, 10111, 0, true),
                    settingsHandler(high, 10112, 2, true), settingsHandler(ordinary, 10113, 50, false))) {
                invoke(h.pm, "add", new Class<?>[]{type("ApkPackage")}, owner);
            }
            android.content.pm.IPackageManager manager = (android.content.pm.IPackageManager)h.pm;
            Intent request = new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
                    android.net.Uri.fromParts("package", EXACT_PKG, null));
            List<?> matches = manager.queryIntentActivities(request, null,
                    PackageManager.MATCH_DEFAULT_ONLY, 0).getList();
            check(matches.size() == 4
                    && high.equals(((android.content.pm.ResolveInfo)matches.get(0)).activityInfo.packageName)
                    && "com.android.settings".equals(((android.content.pm.ResolveInfo)matches.get(1)).activityInfo.packageName),
                    "Actual handler priority, not package iteration order, places native Settings among installed candidates");
            check(matches.stream().map(value -> (android.content.pm.ResolveInfo)value)
                    .filter(value -> ordinary.equals(value.activityInfo.packageName)).allMatch(value -> value.priority == 0),
                    "Ordinary installed activity priority is normalized before selecting Settings");
            check(high.equals(manager.resolveIntent(request, null, PackageManager.MATCH_DEFAULT_ONLY, 0)
                    .activityInfo.packageName), "Implicit resolve selects the same highest-priority installed handler");
            Object installedSettings = settingsHandler("com.android.settings", 10114, 1, true);
            invoke(h.pm, "add", new Class<?>[]{type("ApkPackage")}, installedSettings);
            var resolved = manager.resolveIntent(new Intent(request).setPackage("com.android.settings"),
                    null, PackageManager.MATCH_DEFAULT_ONLY, 0);
            check(resolved != null && resolved.activityInfo.name.endsWith(".FixtureActivity")
                    && resolved.activityInfo.applicationInfo.sourceDir != null,
                    "An installed Settings package takes precedence over the native backend");
            check(manager.getActivityInfo(new android.content.ComponentName("com.android.settings",
                    "com.android.settings.Settings$AlarmsAndRemindersAppActivity"), 0, 0) == null,
                    "Native component metadata disappears when a real Settings package owns the namespace");
        } finally { set(h.pm, "mExactAlarmAccessRequests", previous); }
    }

    public static void contracts(boolean wire) throws Exception {
        try (TestHarness h = new TestHarness(wire)) {
            access(h); listeners(h); transitions(h); settingsLifecycle(h); settings(h); settingsInstalledResolution(h);
        }
        System.out.println("ExactAlarmContractTest all passed: " + checks + " checks verified");
    }

    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime");
        String previous = System.getenv("ANDROID_DATA");
        java.nio.file.Path data = java.nio.file.Files.createTempDirectory("gesher-exact-alarm-contract-");
        android.system.Os.setenv("ANDROID_DATA", data.toString(), true);
        try { contracts(true); }
        finally {
            if (previous == null) android.system.Os.unsetenv("ANDROID_DATA");
            else android.system.Os.setenv("ANDROID_DATA", previous, true);
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(data)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
