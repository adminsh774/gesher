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

    @FunctionalInterface
    private interface WindowAction { int edit() throws Exception; }

    /** Controls only executor/window boundaries; admission, lifetime and callbacks stay real. */
    private static final class SettingsWindow implements AutoCloseable {
        final TestHarness h;
        final Object previousAtm, previousPm, requests;
        final List<Runnable> queued = new ArrayList<>();
        SettingsWindow(TestHarness h, java.util.concurrent.Executor executor, WindowAction action) throws Exception {
            this.h = h;
            Class<?> dialogType = type("ExactAlarmAccessRequests$Dialog");
            Object dialog = java.lang.reflect.Proxy.newProxyInstance(dialogType.getClassLoader(),
                    new Class<?>[]{dialogType}, (proxy, method, args) -> action.edit());
            requests = make(type("ExactAlarmAccessRequests"), new Class<?>[]{type("PackageManagerService"),
                    java.util.concurrent.Executor.class, dialogType}, h.pm,
                    executor == null ? (java.util.concurrent.Executor)queued::add : executor, dialog);
            previousAtm = get(h.atm, "mExactAlarmRequests");
            previousPm = get(h.pm, "mExactAlarmAccessRequests");
            set(h.atm, "mExactAlarmRequests", requests); set(h.pm, "mExactAlarmAccessRequests", requests);
        }
        int start(Object source, String target) throws Exception {
            int[] result = {-1};
            runAs(EXACT_UID, EXACT_PID, () -> result[0] = ((android.app.IActivityTaskManager)h.atm)
                    .startActivity(h.thread, EXACT_PKG, null,
                        new Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
                            android.net.Uri.fromParts("package", target, null))
                            .addFlags(source == null ? Intent.FLAG_ACTIVITY_NEW_TASK : 0),
                        null, source == null ? null : (IBinder)get(source, "token"), "lifetime",
                        source == null ? -1 : 81, 0, null, null));
            return result[0];
        }
        int pending() throws Exception { return ((Set<?>)get(h.atm, "mExactAlarmSettingsSessions")).size(); }
        int slots() throws Exception { return ((java.util.concurrent.Semaphore)get(requests, "mSlots")).availablePermits(); }
        void finish() { queued.remove(0).run(); }
        @Override public void close() throws Exception {
            set(h.atm, "mExactAlarmRequests", previousAtm); set(h.pm, "mExactAlarmAccessRequests", previousPm);
        }
    }

    private static Object exactProcess(TestHarness h) throws Exception {
        for (Object process : (List<?>)get(h.atm, "mProcesses")) {
            if (EXACT_PKG.equals(get(process, "packageName"))) return process;
        }
        throw new AssertionError("Missing exact-alarm fixture process");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object settingsRecord(TestHarness h, String name, int task, String initialState) throws Exception {
        Class<?> record = type("ActivityTaskManagerService$ActivityRecord");
        var info = ((android.content.pm.IPackageManager)h.pm).getActivityInfo(
                new android.content.ComponentName(name, name + ".FixtureActivity"), 0, 0);
        Object process = null;
        for (Object candidate : (List<?>)get(h.atm, "mProcesses")) {
            if (name.equals(get(candidate, "packageName"))) process = candidate;
        }
        Object result = make(record, new Class<?>[]{type("ActivityTaskManagerService"),
                android.content.pm.ActivityInfo.class, Intent.class, int.class, int.class, String.class,
                record, String.class, int.class}, h.atm, info, new Intent(), task,
                name.equals(EXACT_PKG) ? EXACT_PID : UNPRIV_PID, name, null, null, -1);
        set(result, "app", process);
        set(result, "state", Enum.valueOf((Class)type("ActivityTaskManagerService$State"), initialState));
        ((List<Object>)get(process, "activities")).add(result);
        ((List<Object>)get(h.atm, "mActivities")).add(result);
        List<Integer> tasks = (List<Integer>)get(h.atm, "mTaskOrder");
        if (!tasks.contains(task)) tasks.add(task);
        return result;
    }

    private static String settingsState(Object record) throws Exception { return get(record, "state").toString(); }

    private static List<String> acknowledgeSettings(TestHarness h) throws Exception {
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        Class<?> record = type("ActivityTaskManagerService$ActivityRecord");
        h.thread.onTransaction = transaction -> {
            try {
                for (var item : transaction.getTransactionItems()) {
                    String kind = item.getClass().getSimpleName(); events.add(kind);
                    Object target = item instanceof android.app.servertransaction.ActivityTransactionItem activity
                            ? invoke(h.atm, "forToken", new Class<?>[]{IBinder.class}, activity.getActivityToken()) : null;
                    if (kind.equals("PauseActivityItem")) invoke(h.atm, "activityPaused", new Class<?>[]{record}, target);
                    if (kind.equals("StopActivityItem")) invoke(h.atm, "activityStopped",
                            new Class<?>[]{record, Bundle.class}, target, new Bundle());
                    if (kind.equals("ResumeActivityItem")) invoke(h.atm, "activityResumed", new Class<?>[]{record}, target);
                }
            } catch (Exception failure) { throw new AssertionError(failure); }
        };
        return events;
    }

    private static void settingsSessionLifetime(boolean wire) throws Exception {
        for (String mode : List.of("close", "runtime", "linkage", "worker-error", "reject", "start-error", "ineligible",
                "queued-caller-death", "open-caller-death")) {
            try (TestHarness h = new TestHarness(wire)) {
                set(h.atm, "mIdleSince", 1L);
                int[] opened = {0};
                java.util.concurrent.Executor executor = switch (mode) {
                    case "reject" -> task -> { throw new java.util.concurrent.RejectedExecutionException("fixture rejection"); };
                    case "start-error" -> task -> { throw new AssertionError("fixture worker start failure"); };
                    case "queued-caller-death" -> null;
                    default -> Runnable::run;
                };
                try (SettingsWindow window = new SettingsWindow(h, executor, () -> {
                    opened[0]++;
                    check((long)get(h.atm, "mIdleSince") == 0 && (long)invoke(h.atm, "idleSince", new Class<?>[0]) == 0,
                            "Real Settings window owns device use without an ActivityRecord: " + mode);
                    if (mode.equals("runtime")) throw new IllegalStateException("fixture native open failure");
                    if (mode.equals("linkage")) throw new UnsatisfiedLinkError("fixture native linkage failure");
                    if (mode.equals("worker-error")) throw new AssertionError("fixture worker failure");
                    if (mode.equals("open-caller-death")) {
                        invoke(h.atm, "processDied", new Class<?>[]{type("ActivityTaskManagerService$ProcessRecord")}, exactProcess(h));
                        check((long)invoke(h.atm, "idleSince", new Class<?>[0]) == 0,
                                "Caller death does not retire the system-owned open Settings window");
                    }
                    return -1;
                })) {
                    long admittedAfter = android.os.SystemClock.uptimeMillis();
                    if (mode.equals("reject")) rejects(java.util.concurrent.RejectedExecutionException.class,
                            "fixture rejection", () -> window.start(null, EXACT_PKG));
                    else if (mode.equals("start-error") || mode.equals("worker-error")) {
                        rejects(AssertionError.class, "fixture", () -> window.start(null, EXACT_PKG));
                    } else check(window.start(null, mode.equals("ineligible") ? "fixture.missing" : EXACT_PKG)
                            == ActivityManager.START_SUCCESS, "Settings admission result: " + mode);
                    if (mode.equals("queued-caller-death")) {
                        check(window.pending() == 1 && opened[0] == 0 && (long)get(h.atm, "mIdleSince") == 0,
                                "Queued Settings admission clears the old idle interval before any poll");
                        invoke(h.atm, "processDied", new Class<?>[]{type("ActivityTaskManagerService$ProcessRecord")}, exactProcess(h));
                        check(window.pending() == 1 && (long)invoke(h.atm, "idleSince", new Class<?>[0]) == 0,
                                "Caller retirement preserves an admitted system Settings request");
                        window.finish();
                    }
                    check(window.pending() == 0 && window.slots() == 16,
                            "Completion/failure releases exactly one reservation and one admission slot: " + mode);
                    check((long)get(h.atm, "mIdleSince") >= admittedAfter,
                            "Final Settings completion starts a fresh idle interval without needing a poll: " + mode);
                    check(opened[0] == (mode.equals("reject") || mode.equals("start-error") || mode.equals("ineligible") ? 0 : 1),
                            "Only accepted eligible work reaches the actual window boundary: " + mode);
                }
            }
        }
        try (TestHarness h = new TestHarness(wire); SettingsWindow window = new SettingsWindow(h, null, () -> -1)) {
            set(h.atm, "mIdleSince", 1L);
            for (int i = 0; i < 16; i++) check(window.start(null, EXACT_PKG) == ActivityManager.START_SUCCESS,
                    "Bounded real Settings coordinator admits available slot");
            check(window.start(null, EXACT_PKG) == ActivityManager.START_CANCELED && window.pending() == 16
                    && window.queued.size() == 16 && window.slots() == 0,
                    "Exhausted Settings admission rejects without allocating a session or worker");
            for (int left = 15; left >= 0; left--) {
                window.finish();
                check(window.pending() == left && window.slots() == 16 - left,
                        "Each accepted session releases only its own bounded slot");
                check(((long)get(h.atm, "mIdleSince") == 0) == (left > 0),
                        "Remaining Settings work retains busy ownership until the last completion");
            }
        }
    }

    private static void settingsTaskCoverage(boolean wire) throws Exception {
        Class<?> record = type("ActivityTaskManagerService$ActivityRecord");
        try (TestHarness h = new TestHarness(wire)) {
            Object lower = settingsRecord(h, EXACT_PKG, 1, "STOPPED");
            Object top = settingsRecord(h, EXACT_PKG, 1, "RESUMED"); set(top, "translucent", true);
            Object other = settingsRecord(h, UNPRIV_PKG, 2, "RESUMING");
            List<String> events = acknowledgeSettings(h);
            check(((List<?>)invoke(h.atm, "starting", new Class<?>[0])).contains(lower),
                    "Positive control: stopped activity beneath a translucent top normally needs Start");
            invoke(h.atm, "makeVisible", new Class<?>[]{record}, lower);
            invoke(h.atm, "pause", new Class<?>[]{record, boolean.class}, lower, false);
            check(settingsState(lower).equals("PAUSED"),
                    "Positive control: translucent task top leaves its started parent visible and paused");
            events.clear();
            try (SettingsWindow window = new SettingsWindow(h, null, () -> {
                check(settingsState(top).equals("STOPPED") && settingsState(lower).equals("STOPPED"),
                        "Opaque Settings covers both its top and the visible activity beneath it");
                invoke(h.atm, "activityResumed", new Class<?>[]{record}, other);
                invoke(h.atm, "makeVisible", new Class<?>[]{record}, lower);
                check(settingsState(top).equals("STOPPED") && settingsState(lower).equals("STOPPED")
                        && !events.contains("StartActivityItem"),
                        "Unrelated resume and a stale make-visible candidate cannot restart a covered activity");
                return -1;
            })) {
                check(window.start(top, EXACT_PKG) == ActivityManager.START_SUCCESS, "Opaque Settings admitted over translucent task top");
                check(!(boolean)invoke(h.atm, "willBeVisible", new Class<?>[]{record}, top)
                        && !(boolean)invoke(h.atm, "willBeVisible", new Class<?>[]{record}, lower),
                        "Visibility queries include the complete Settings-covered task");
                window.finish();
                check(settingsState(top).equals("RESUMED") && settingsState(lower).equals("STARTED")
                        && settingsState(other).equals("RESUMED"),
                        "Closing Settings resumes its current top, starts its visible parent and preserves multi-resume");
                check(Collections.frequency(events, "StartActivityItem") == 1,
                        "Only the newly visible translucent parent receives one Start callback");
            }
        }
        for (boolean detachedSecond : new boolean[]{false, true}) try (TestHarness h = new TestHarness(wire)) {
            Object source = settingsRecord(h, EXACT_PKG, 1, "RESUMED");
            List<String> events = acknowledgeSettings(h);
            try (SettingsWindow window = new SettingsWindow(h, null, () -> -1)) {
                check(window.start(source, EXACT_PKG) == ActivityManager.START_SUCCESS
                        && window.start(detachedSecond ? null : source, EXACT_PKG) == ActivityManager.START_SUCCESS,
                        "Two accepted Settings requests own independent coverage of one task");
                window.finish();
                check(settingsState(source).equals("STOPPED") && !events.contains("ResumeActivityItem")
                        && !(boolean)invoke(h.atm, "willBeVisible", new Class<?>[]{record}, source),
                        "First completion cannot resume or reveal a task still covered by another Settings session");
                window.finish();
                check(settingsState(source).equals("RESUMED") && Collections.frequency(events, "ResumeActivityItem") == 1,
                        "Last completion restores the task exactly once");
            }
        }
        try (TestHarness h = new TestHarness(wire)) {
            Object source = settingsRecord(h, EXACT_PKG, 1, "RESUMED");
            List<String> events = acknowledgeSettings(h);
            try (SettingsWindow window = new SettingsWindow(h, null, () -> {
                set(source, "savedState", null);
                invoke(h.atm, "processDied", new Class<?>[]{type("ActivityTaskManagerService$ProcessRecord")}, exactProcess(h));
                check(((List<?>)get(h.atm, "mActivities")).isEmpty()
                        && (long)invoke(h.atm, "idleSince", new Class<?>[0]) == 0,
                        "Retiring the last covered activity cannot release an open system Settings window");
                return -1;
            })) {
                check(window.start(source, EXACT_PKG) == ActivityManager.START_SUCCESS, "Activity-owned result request admitted");
                window.finish();
                check(window.pending() == 0 && !events.contains("ActivityResultItem") && !events.contains("ResumeActivityItem")
                        && (long)invoke(h.atm, "idleSince", new Class<?>[0]) != 0,
                        "Final completion after caller retirement releases ownership without reviving a stale recipient");
            }
        }
        try (TestHarness h = new TestHarness(wire)) {
            Object source = settingsRecord(h, EXACT_PKG, 1, "RESUMED");
            h.thread.onTransaction = transaction -> { }; // Withhold just this client's pause acknowledgment.
            try (SettingsWindow window = new SettingsWindow(h, Runnable::run, () -> {
                check(settingsState(source).equals("PAUSING"),
                        "Settings may open after Android's bounded missing-pause-acknowledgment wait");
                return -1;
            })) {
                check(window.start(source, EXACT_PKG) == ActivityManager.START_SUCCESS
                        && window.pending() == 0 && settingsState(source).equals("PAUSING"),
                        "Completion before pause acknowledgment releases its session but defers resume");
                List<String> events = acknowledgeSettings(h);
                invoke(h.atm, "activityPaused", new Class<?>[]{record}, source);
                check(settingsState(source).equals("RESUMED") && Collections.frequency(events, "ResumeActivityItem") == 1,
                        "Late pause acknowledgment restores an uncovered source exactly once");
            }
        }
    }

    private static void settingsWithChooser(boolean wire) throws Exception {
        for (String mode : List.of("chooser-first", "settings-first", "headless-settings-first", "headless-chooser-first",
                "detached-settings", "detached-chooser", "retirement")) {
            try (TestHarness h = new TestHarness(wire)) {
                boolean headless = mode.startsWith("headless");
                Object source = headless ? null : settingsRecord(h, EXACT_PKG, 1, "RESUMED");
                List<String> events = acknowledgeSettings(h);
                java.util.concurrent.CountDownLatch shown = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.CountDownLatch close = new java.util.concurrent.CountDownLatch(1);
                Class<?> dialogType = type("IntentChooser$Dialog");
                Object previous = get(h.atm, "mIntentChooserDialog");
                Object chooser = java.lang.reflect.Proxy.newProxyInstance(dialogType.getClassLoader(), new Class<?>[]{dialogType},
                        (proxy, method, args) -> {
                            if (method.getName().equals("available")) return true;
                            shown.countDown();
                            if (!close.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Chooser fixture not released");
                            return -1;
                        });
                set(h.atm, "mIntentChooserDialog", chooser);
                try (SettingsWindow window = new SettingsWindow(h, null, () -> {
                    check((long)invoke(h.atm, "idleSince", new Class<?>[0]) == 0,
                            "Settings remains busy while overlapping a chooser: " + mode);
                    return -1;
                })) {
                    boolean chooserFirst = mode.equals("chooser-first") || mode.equals("retirement") || mode.equals("detached-settings");
                    if (!chooserFirst) check(window.start(source, EXACT_PKG) == ActivityManager.START_SUCCESS, "Settings accepted before chooser");
                    runAs(EXACT_UID, EXACT_PID, () -> {
                        Intent request = Intent.createChooser(new Intent().setComponent(new android.content.ComponentName(
                                EXACT_PKG, EXACT_PKG + ".FixtureActivity")), "Lifetime fixture")
                                .putExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, false);
                        boolean detached = headless || mode.equals("detached-chooser");
                        if (detached) request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        check(((android.app.IActivityTaskManager)h.atm).startActivity(h.thread, EXACT_PKG, null,
                                request, null, detached ? null : (IBinder)get(source, "token"), null, -1,
                                0, null, null) == ActivityManager.START_SUCCESS, "Actual native chooser admitted: " + mode);
                    });
                    check(shown.await(3, java.util.concurrent.TimeUnit.SECONDS), "Actual chooser worker reached its open window: " + mode);
                    if (chooserFirst) {
                        check(settingsState(source).equals("PAUSED"), "Positive control: translucent chooser leaves source paused");
                        check(window.start(mode.equals("detached-settings") ? null : source, EXACT_PKG)
                                == ActivityManager.START_SUCCESS, "Settings accepted above open chooser");
                    }
                    if (!headless) check(settingsState(source).equals("STOPPED"), "Opaque Settings stops the chooser's covered source");
                    if (mode.equals("retirement")) {
                        set(source, "savedState", null);
                        invoke(h.atm, "processDied", new Class<?>[]{type("ActivityTaskManagerService$ProcessRecord")}, exactProcess(h));
                    }
                    boolean finishSettings = mode.equals("chooser-first") || mode.equals("headless-settings-first")
                            || mode.equals("detached-settings") || mode.equals("detached-chooser");
                    if (finishSettings) {
                        window.finish();
                        check((long)invoke(h.atm, "idleSince", new Class<?>[0]) == 0,
                                "Completing Settings retains the real open chooser's busy reservation");
                        if (!headless) check(settingsState(source).equals("STARTED") && !events.contains("ResumeActivityItem"),
                                "Closing opaque Settings restores visibility beneath chooser without resuming its source");
                    }
                    close.countDown();
                    long deadline = android.os.SystemClock.uptimeMillis() + 3000;
                    synchronized (h.atm) {
                        while (!((Map<?, ?>)get(h.atm, "mIntentChoosers")).isEmpty()
                                && android.os.SystemClock.uptimeMillis() < deadline) h.atm.wait(25);
                    }
                    check(((Map<?, ?>)get(h.atm, "mIntentChoosers")).isEmpty(), "Chooser worker completed and released its reservation");
                    if (!finishSettings) {
                        check(window.pending() == 1 && (long)invoke(h.atm, "idleSince", new Class<?>[0]) == 0,
                                "Chooser completion/retirement preserves the independent Settings owner");
                        if (!headless && !mode.equals("retirement")) check(settingsState(source).equals("STOPPED")
                                && !events.contains("ResumeActivityItem"), "Chooser completion cannot resume under opaque Settings");
                        window.finish();
                    }
                    if (headless || mode.equals("retirement")) check((long)invoke(h.atm, "idleSince", new Class<?>[0]) != 0,
                            "Last real window completion starts idle after headless/retired overlapping UI");
                    else {
                        // Reservation removal precedes the worker's ordinary resume callback.
                        long until = android.os.SystemClock.uptimeMillis() + 3000;
                        while (!settingsState(source).equals("RESUMED") && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(5);
                        check(settingsState(source).equals("RESUMED") && Collections.frequency(events, "ResumeActivityItem") == 1,
                                "Source resumes once after both native sessions finish: " + mode);
                    }
                } finally { close.countDown(); set(h.atm, "mIntentChooserDialog", previous); }
            }
        }
    }

    private static void accountSettingsLifetime(boolean wire) throws Exception {
        for (String mode : List.of("cancel", "retire", "runtime", "linkage", "worker-error", "overlap")) {
            try (TestHarness h = new TestHarness(wire)) {
                // Empty isolated account state: these checks neither grant visibility
                // nor run an authenticator or a credential/storage backend.
                Object accounts = make(type("AccountManagerService"), new Class<?>[]{type("ActivityManagerService"),
                        type("PackageManagerService"), boolean.class}, h.am, h.pm, false);
                ((Map<?, ?>)get(accounts, "mAccounts")).clear();
                Object source = settingsRecord(h, EXACT_PKG, 1, "PAUSED");
                acknowledgeSettings(h);
                java.util.concurrent.CountDownLatch shown = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.CountDownLatch close = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicReference<Thread> worker = new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicReference<Throwable> uncaught = new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicReference<java.util.function.BooleanSupplier> active = new java.util.concurrent.atomic.AtomicReference<>();
                Class<?> dialogType = type("AccountChooser$Dialog");
                Object dialog = java.lang.reflect.Proxy.newProxyInstance(dialogType.getClassLoader(), new Class<?>[]{dialogType},
                        (proxy, method, args) -> {
                            worker.set(Thread.currentThread());
                            Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> uncaught.set(failure));
                            active.set((java.util.function.BooleanSupplier)args[3]);
                            check(((String[])args[1]).length == 0 && !(boolean)args[2],
                                    "Account lifetime fixture exposes no account or authenticator choice");
                            shown.countDown();
                            if (!close.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Account window not released");
                            if (mode.equals("runtime")) throw new IllegalStateException("fixture account open failure");
                            if (mode.equals("linkage")) throw new UnsatisfiedLinkError("fixture account native failure");
                            if (mode.equals("worker-error")) throw new AssertionError("fixture account worker failure");
                            return -1;
                        });
                set(h.atm, "mAccountDialog", dialog);
                try (SettingsWindow settings = new SettingsWindow(h, null, () -> -1)) {
                    set(h.atm, "mIdleSince", 1L);
                    runAs(EXACT_UID, EXACT_PID, () -> check(((android.app.IActivityTaskManager)h.atm).startActivity(h.thread,
                            EXACT_PKG, null, new Intent().setComponent(new android.content.ComponentName("android",
                                "android.accounts.ChooseTypeAndAccountActivity")), null, (IBinder)get(source, "token"),
                            "account-lifetime", 82, 0, null, null) == ActivityManager.START_SUCCESS,
                            "Real authenticated account chooser route admitted: " + mode));
                    check(shown.await(3, java.util.concurrent.TimeUnit.SECONDS) && (long)get(h.atm, "mIdleSince") == 0
                            && ((Map<?, ?>)get(h.atm, "mAccountChoosers")).size() == 1,
                            "Account admission registers a real native worker and clears the idle interval");
                    if (mode.equals("overlap")) check(settings.start(source, EXACT_PKG) == ActivityManager.START_SUCCESS,
                            "Settings and account chooser retain separate admitted sessions");
                    if (!mode.equals("cancel")) {
                        set(source, "savedState", null);
                        invoke(h.atm, "processDied", new Class<?>[]{type("ActivityTaskManagerService$ProcessRecord")}, exactProcess(h));
                        check(!active.get().getAsBoolean() && ((List<?>)get(h.atm, "mActivities")).isEmpty()
                                && (long)invoke(h.atm, "idleSince", new Class<?>[0]) == 0,
                                "Retired account source loses authority while the actual native window remains busy");
                    }
                    close.countDown(); worker.get().join(3000);
                    check(!worker.get().isAlive() && ((Map<?, ?>)get(h.atm, "mAccountChoosers")).isEmpty(),
                            "Account completion/failure retires its real worker and reservation: " + mode);
                    check((uncaught.get() instanceof AssertionError) == mode.equals("worker-error"),
                            "Worker Error is released and propagated, not converted into a successful account choice");
                    if (mode.equals("overlap")) {
                        check(settings.pending() == 1 && (long)invoke(h.atm, "idleSince", new Class<?>[0]) == 0,
                                "Account window cleanup cannot clear an independent Settings busy owner");
                        settings.finish();
                    }
                    if (mode.equals("cancel")) check(((List<?>)get(source, "pendingResults")).size() == 1,
                            "Live account source still receives exactly its ordinary canceled result");
                    else check(((List<?>)get(source, "pendingResults")).isEmpty()
                            && (long)invoke(h.atm, "idleSince", new Class<?>[0]) != 0,
                            "Retired account source receives no result and final native completion permits idle");
                } finally { close.countDown(); }
            }
        }
    }

    public static void contracts(boolean wire) throws Exception {
        try (TestHarness h = new TestHarness(wire)) {
            access(h); listeners(h); transitions(h); settingsLifecycle(h); settings(h); settingsInstalledResolution(h);
        }
        settingsSessionLifetime(wire); settingsTaskCoverage(wire); settingsWithChooser(wire);
        accountSettingsLifetime(wire);
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
