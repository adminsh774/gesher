/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.sensors;

import android.os.Binder;
import android.os.IBinder;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Tests real client ordering/death code across a declared host transport boundary. */
public final class SensorAccessClientHost {
    private static int checks;
    private static native int lastFlags();
    private static native long lastRevision();
    private static final class Service extends Binder {
        final List<IBinder.DeathRecipient> deaths = new ArrayList<>();
        @Override public void linkToDeath(IBinder.DeathRecipient recipient, int flags) { deaths.add(recipient); }
        @Override public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) { return deaths.remove(recipient); }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static Object connect(Service service) throws Exception {
        Class<?> type = Class.forName("com.gesher.sensors.NativeSensorAccess$Subscription");
        Constructor<?> ctor = type.getDeclaredConstructor(IBinder.class);
        ctor.setAccessible(true);
        Object connection = ctor.newInstance(service);
        Field current = NativeSensorAccess.class.getDeclaredField("current");
        current.setAccessible(true); current.set(null, connection);
        service.linkToDeath((IBinder.DeathRecipient)connection, 0);
        return connection;
    }
    private static void apply(Object connection, long generation, int flags) throws Exception {
        Method apply = connection.getClass().getDeclaredMethod("apply", long.class, int.class);
        apply.setAccessible(true); apply.invoke(connection, generation, flags);
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        Service original = new Service();
        Object first = connect(original);
        apply(first, 3, 1); // Async denial overtook the OPEN reply at generation 1.
        long denied = lastRevision();
        apply(first, 1, 3);
        check(lastFlags() == 1 && lastRevision() == denied, "Stale registration reply cannot undo an already observed denial");
        apply(first, 4, 3);
        check(lastFlags() == 3 && lastRevision() > denied, "Later grant uses a new local event epoch");
        ((IBinder.DeathRecipient)first).binderDied();
        long dead = lastRevision();
        check(lastFlags() == 0 && original.deaths.isEmpty(), "Server death fails closed and unlinks the dead connection");
        apply(first, 1000, 3);
        check(lastRevision() == dead && lastFlags() == 0, "Dead server cannot restore access using a larger generation");
        Service restarted = new Service();
        Object second = connect(restarted);
        apply(second, 1, 3);
        check(lastFlags() == 3 && lastRevision() > dead, "Fresh server generation 1 recovers with a new process-local epoch");
        long fresh = lastRevision();
        apply(first, 1001, 1);
        ((IBinder.DeathRecipient)first).binderDied();
        check(lastRevision() == fresh && lastFlags() == 3, "Delayed old callbacks/death cannot revoke or replace the new connection");
        apply(second, 2, 1);
        apply(second, 3, 3);
        long latest = lastRevision();
        apply(second, 2, 1);
        check(lastFlags() == 3 && lastRevision() == latest, "Reordered stale denial cannot overwrite later policy");
        ((IBinder.DeathRecipient)second).binderDied();
        check(lastFlags() == 0 && restarted.deaths.isEmpty(), "Repeated teardown releases its own registration");
        System.out.println("Sensor client death/reconnect/reordering: " + checks + " checks passed");
    }
}
