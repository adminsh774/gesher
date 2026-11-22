/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.sensors;

/** Explicit policy-service boundary for testing the production JNI entrypoint on
 * real JVM attached/unattached threads. This class is never packaged in Gesher. */
public final class NativeSensorAccess {
    public static int mode;
    public static int calls;
    public static long revision;
    public static boolean ensureSubscribed() {
        calls++;
        if (mode == 1) return false;
        if (mode == 2) throw new IllegalStateException("Policy transport unavailable");
        nativeUpdate(3, ++revision);
        return true;
    }
    private static native void nativeUpdate(int flags, long revision);
}
