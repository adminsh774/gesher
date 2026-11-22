/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.sensors;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;

/** Private host transport for NDK sensor policy, never a caller-selected package. */
public final class NativeSensorAccess {
    public static final String SERVICE = "gesher_sensor_access";
    public static final String DESCRIPTOR = "com.gesher.ISensorAccess";
    public static final String SESSION_DESCRIPTOR = "com.gesher.ISensorAccessSession";
    public static final String CALLBACK_DESCRIPTOR = "com.gesher.ISensorAccessCallback";
    public static final int OPEN = IBinder.FIRST_CALL_TRANSACTION;
    public static final int CLOSE = IBinder.FIRST_CALL_TRANSACTION;
    public static final int CHANGED = IBinder.FIRST_CALL_TRANSACTION;
    private static final Object LOCK = new Object();
    private static final Object CONNECT = new Object();
    private static Subscription current;
    private static long revision;

    private NativeSensorAccess() { }

    /** Called before native subscriptions, outside all native sampler/queue locks. */
    public static boolean ensureSubscribed() {
        synchronized (CONNECT) {
            Subscription previous;
            synchronized (LOCK) {
                if (current != null && current.available) return true;
                previous = current;
            }
            if (previous != null) previous.retire();
            IBinder service = ServiceManager.checkService(SERVICE);
            if (service == null) return false;
            Subscription next = new Subscription(service);
            synchronized (LOCK) { current = next; }
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                service.linkToDeath(next, 0);
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeStrongBinder(next);
                if (!service.transact(OPEN, data, reply, 0)) throw new RemoteException("Sensor access unavailable");
                reply.readException();
                next.session = reply.readStrongBinder();
                long generation = reply.readLong();
                int flags = reply.readInt();
                if (next.session == null) throw new RemoteException("Missing sensor policy session");
                next.apply(generation, flags);
                synchronized (LOCK) { return next.available; }
            } catch (RemoteException | RuntimeException unavailable) {
                next.retire();
                return false;
            } finally { data.recycle(); reply.recycle(); }
        }
    }

    private static final class Subscription extends Binder implements IBinder.DeathRecipient {
        final IBinder service;
        IBinder session;
        long generation;
        boolean available;
        boolean retired;
        Subscription(IBinder service) { super(CALLBACK_DESCRIPTOR); this.service = service; }

        void apply(long incomingGeneration, int flags) {
            long nextRevision;
            synchronized (LOCK) {
                if (current != this || retired || incomingGeneration <= generation) return;
                generation = incomingGeneration;
                available = (flags & 1) != 0;
                nextRevision = ++revision;
            }
            nativeUpdate(flags, nextRevision);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code != CHANGED) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(CALLBACK_DESCRIPTOR);
            long generation = data.readLong();
            int policy = data.readInt();
            data.enforceNoDataAvail();
            apply(generation, policy);
            return true;
        }

        void retire() {
            long nextRevision = 0;
            synchronized (LOCK) {
                if (retired) return;
                retired = true;
                available = false;
                if (current == this) { current = null; nextRevision = ++revision; }
            }
            if (nextRevision != 0) nativeUpdate(0, nextRevision);
            service.unlinkToDeath(this, 0);
            if (session == null) return;
            Parcel data = Parcel.obtain();
            try {
                data.writeInterfaceToken(SESSION_DESCRIPTOR);
                session.transact(CLOSE, data, null, IBinder.FLAG_ONEWAY);
            } catch (RemoteException | RuntimeException ignored) { }
            finally { data.recycle(); }
        }

        @Override public void binderDied() { retire(); }
    }

    private static native void nativeUpdate(int flags, long revision);
}
