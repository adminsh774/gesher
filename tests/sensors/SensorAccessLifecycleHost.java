/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import android.Manifest;
import android.content.pm.PermissionInfo;
import android.os.Binder;
import android.os.IBinder;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/** Real caller validation and controller hooks with the established host JNI boundary.
 * APK/process construction reuses the existing AppOps fixture. Binder Parcel dispatch
 * is deliberately not simulated: outgoing callbacks are delayed and inspected as policy state. */
public final class SensorAccessLifecycleHost {
    private static final int UID = 12772, PID = 887720;
    private static int checks;
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object get(Object object, String name) throws Exception { return field(object.getClass(), name).get(object); }
    private static void set(Object object, String name, Object value) throws Exception { field(object.getClass(), name).set(object, value); }
    private static Object call(Object target, String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, signature); method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static SensorAccessPolicy.State state(Object session) throws Exception {
        return ((SensorAccessPolicy.Subscription)get(session, "subscription")).snapshot();
    }
    private static final class Token extends Binder {
        final List<IBinder.DeathRecipient> deaths = new ArrayList<>();
        @Override public void linkToDeath(IBinder.DeathRecipient recipient, int flags) { deaths.add(recipient); }
        @Override public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) { return deaths.remove(recipient); }
        void die() { for (var recipient : new ArrayList<>(deaths)) recipient.binderDied(); }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void lifecycle(int targetSdk, boolean permission) throws Exception {
        Class<?> fixture = Class.forName("gesher.services.AppOpsContractTest$Harness");
        Constructor<?> constructor = fixture.getDeclaredConstructor(Integer.class, String[].class);
        constructor.setAccessible(true);
        Object harness = constructor.newInstance(targetSdk, permission
                ? new String[]{Manifest.permission.HIGH_SAMPLING_RATE_SENSORS} : new String[0]);
        var pm = (PackageManagerService)get(harness, "pm");
        var atm = (ActivityTaskManagerService)get(harness, "atm");
        var am = (ActivityManagerService)get(harness, "am");
        Object process = get(harness, "process");
        ApkPackage owner = (ApkPackage)get(harness, "owner");
        if (permission) {
            Object catalog = get(pm.permissions(), "mCatalog");
            PermissionInfo info = new PermissionInfo();
            info.name = Manifest.permission.HIGH_SAMPLING_RATE_SENSORS;
            info.packageName = "android"; info.protectionLevel = PermissionInfo.PROTECTION_NORMAL;
            ((Map<String, PermissionInfo>)get(catalog, "mFramework")).put(info.name, info);
            call(pm.permissions(), "initializePackages", new Class<?>[0]);
        }
        SensorAccessService access = am.sensorAccess();
        ExecutorService notifications = (ExecutorService)get(access, "notifications");
        CountDownLatch blocked = new CountDownLatch(1);
        notifications.execute(() -> { blocked.countDown(); try { new CountDownLatch(1).await(); }
            catch (InterruptedException done) { Thread.currentThread().interrupt(); } });
        blocked.await();
        long identity = Binder.clearCallingIdentity();
        Token token = new Token();
        try {
            Binder.restoreCallingIdentity(((long)UID << 32) | PID);
            Object session = call(access, "openSubscription", new Class<?>[]{IBinder.class}, token);
            check((state(session).flags() & 1) != 0 && (state(session).flags() & 2) == 0,
                    "Authenticated pre-resume process is available but delivery-inactive");
            check(((state(session).flags() & 4) != 0) == (targetSdk >= 31 && !permission),
                    "Rate policy derives from actual target SDK and installed manifest grant");
            Class stateType = Class.forName("com.gesher.server.ActivityTaskManagerService$State");
            Object stopped = Enum.valueOf(stateType, "STOPPED"), resumed = Enum.valueOf(stateType, "RESUMED");
            set(process, "inProcessActivityState", stopped);
            call(atm, "inProcessActivityState", new Class<?>[]{process.getClass(), stateType}, process, resumed);
            long first = state(session).generation();
            check((state(session).flags() & 2) != 0, "Real resumed-activity hook grants delivery");
            call(atm, "inProcessActivityState", new Class<?>[]{process.getClass(), stateType}, process, stopped);
            check((state(session).flags() & 2) == 0 && state(session).generation() == first + 1,
                    "Real stopped-activity hook records denial before deferred callback dispatch");
            call(atm, "inProcessActivityState", new Class<?>[]{process.getClass(), stateType}, process, resumed);
            check(state(session).generation() == first + 2, "Rapid foreground return retains denial epoch");
            if (targetSdk >= 31 && !permission) {
                call(atm, "inProcessActivityState", new Class<?>[]{process.getClass(), stateType}, process, stopped);
                Object services = get(am, "mServices");
                set(services, "mNotifications", new NotificationManagerService());
                android.content.pm.ServiceInfo info = new android.content.pm.ServiceInfo();
                info.packageName = owner.packageName; info.name = owner.packageName + ".SensorForeground";
                Class<?> record = Class.forName("com.gesher.server.ActiveServices$ServiceRecord");
                Constructor<?> serviceCtor = record.getDeclaredConstructor(android.content.pm.ServiceInfo.class, ApkPackage.class);
                serviceCtor.setAccessible(true);
                Object service = serviceCtor.newInstance(info, owner);
                var component = new android.content.ComponentName(info.packageName, info.name);
                ((Map<Object, Object>)get(services, "mServices")).put(component, service);
                set(service, "app", process); set(service, "foregroundId", 41);
                call(services, "notifySensorStateChanged", new Class<?>[0]);
                check((state(session).flags() & 2) != 0, "Actual foreground-service controller admits inactive activity UID");
                call(services, "setServiceForeground", new Class<?>[]{android.content.ComponentName.class,
                        IBinder.class, int.class, android.app.Notification.class, int.class, int.class},
                        component, service, 0, null, 0, 0);
                check((state(session).flags() & 2) == 0, "Production stopForeground hook revokes the final active source");
            }
            Binder.restoreCallingIdentity(((long)(UID + 1) << 32) | PID);
            try {
                call(access, "openSubscription", new Class<?>[]{IBinder.class}, new Token());
                throw new AssertionError("Mismatched UID accepted");
            } catch (SecurityException expected) { checks++; }
            Binder.restoreCallingIdentity(((long)UID << 32) | (PID + 1));
            try {
                call(access, "openSubscription", new Class<?>[]{IBinder.class}, new Token());
                throw new AssertionError("Unattached PID accepted");
            } catch (SecurityException expected) { checks++; }
            Binder.restoreCallingIdentity(((long)UID << 32) | PID);
            token.die();
            check(state(session).flags() == 0 && token.deaths.isEmpty(), "Callback death retires authority and its death registration");
        } finally {
            Binder.restoreCallingIdentity(identity);
            notifications.shutdownNow();
        }
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        Class<?> locales = Class.forName("com.gesher.app.locale.SystemLocale");
        field(locales, "sIs24Hour").set(null, Boolean.TRUE);
        field(locales, "sLocales").set(null, new android.os.LocaleList(java.util.Locale.ROOT));
        android.app.PropertyInvalidatedCache.setTestMode(true);
        android.app.PropertyInvalidatedCache.disableForTestMode();
        lifecycle(36, false);
        lifecycle(30, false);
        lifecycle(36, true);
        System.out.println("Sensor caller identity + real lifecycle hooks: " + checks + " checks passed");
    }
}
