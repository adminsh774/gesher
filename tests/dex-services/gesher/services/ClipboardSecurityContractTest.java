/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.IClipboard;
import android.content.IOnPrimaryClipChangedListener;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Android 10+ (API 29+) clipboard security contract verification:
 * - Calling UID must have input focus, be the default IME, or be a system/privileged UID to read.
 * - Background/unfocused applications receive null or false on clipboard read queries.
 * - Focused apps can write and read clipboard data.
 * - Default IME or system UID can access clipboard even without window focus.
 * - PrimaryClipChangedListener only dispatches notifications to callers with focus (or IME/system).
 * - Package mismatch / null package when not system throws SecurityException.
 */
public final class ClipboardSecurityContractTest {
    private static final String APP_PKG = "com.gesher.fixture.app";
    private static final int APP_UID = 10100;
    private static final int APP_PID = 5001;

    private static final String BG_PKG = "com.gesher.fixture.bg";
    private static final int BG_UID = 10200;
    private static final int BG_PID = 5002;

    private static final String IME_PKG = "com.gesher.fixture.ime";
    private static final int IME_UID = 10300;
    private static final int IME_PID = 5003;

    private static final String SYSTEM_PKG = "android";
    private static final int SYSTEM_UID = Process.SYSTEM_UID;
    private static final int SYSTEM_PID = 1000;

    public static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }

    private static IClipboard newService() throws Exception {
        Class<?> clazz = Class.forName("com.gesher.server.ClipboardService");
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (IClipboard) constructor.newInstance();
    }

    private static void setFocusedUid(int uid) throws Exception {
        Class<?> clazz = Class.forName("com.gesher.server.ClipboardService");
        Method method = clazz.getMethod("setFocusedUid", int.class);
        method.invoke(null, uid);
    }

    private static void setDefaultIme(int uid, String pkg) throws Exception {
        Class<?> clazz = Class.forName("com.gesher.server.ClipboardService");
        Method method = clazz.getMethod("setDefaultIme", int.class, String.class);
        method.invoke(null, uid, pkg);
    }

    private static void resetOverrides() throws Exception {
        Class<?> clazz = Class.forName("com.gesher.server.ClipboardService");
        Method method = clazz.getMethod("resetOverrides");
        method.invoke(null);
    }

    private static final class TestListener implements IOnPrimaryClipChangedListener {
        private final IBinder mBinder = new Binder();
        final AtomicInteger calls = new AtomicInteger();
        volatile CountDownLatch latch;

        @Override
        public IBinder asBinder() {
            return mBinder;
        }

        @Override
        public void dispatchPrimaryClipChanged() {
            calls.incrementAndGet();
            CountDownLatch l = latch;
            if (l != null) {
                l.countDown();
            }
        }
    }

    private static void runAs(int uid, int pid, ThrowingRunnable action) throws Exception {
        long original = Binder.clearCallingIdentity();
        try {
            Binder.restoreCallingIdentity(((long) uid << 32) | (pid & 0xffffffffL));
            action.run();
        } finally {
            Binder.restoreCallingIdentity(original);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Test 1: Focused app can write and read primary clip. */
    private static void testFocusedAppReadWrite(IClipboard service) throws Exception {
        setFocusedUid(APP_UID);

        runAs(APP_UID, APP_PID, () -> {
            ClipData clip = ClipData.newPlainText("test_label", "Hello Focused World");
            service.setPrimaryClip(clip, APP_PKG, null, 0, 0);

            check(service.hasPrimaryClip(APP_PKG, null, 0, 0), "Focused app must see hasPrimaryClip == true");
            check(service.hasClipboardText(APP_PKG, null, 0, 0), "Focused app must see hasClipboardText == true");

            ClipData readClip = service.getPrimaryClip(APP_PKG, null, 0, 0);
            check(readClip != null, "Focused app must be able to read primary clip");
            check(readClip.getItemCount() > 0, "Read clip must have at least one item");
            check("Hello Focused World".equals(readClip.getItemAt(0).getText().toString()),
                    "Read clip content must match what was set");

            ClipDescription desc = service.getPrimaryClipDescription(APP_PKG, null, 0, 0);
            check(desc != null, "Focused app must be able to get clip description");

            String source = service.getPrimaryClipSource(APP_PKG, null, 0, 0);
            check(APP_PKG.equals(source), "Primary clip source must match setter package");
        });
    }

    /** Test 2: Background / unfocused app cannot read primary clip (null or false). */
    private static void testBackgroundAppReadDenied(IClipboard service) throws Exception {
        // Window focus is held by APP_UID, BG_UID is in background without focus
        setFocusedUid(APP_UID);

        runAs(BG_UID, BG_PID, () -> {
            check(!service.hasPrimaryClip(BG_PKG, null, 0, 0),
                    "Unfocused app must see hasPrimaryClip == false");
            check(!service.hasClipboardText(BG_PKG, null, 0, 0),
                    "Unfocused app must see hasClipboardText == false");
            check(service.getPrimaryClip(BG_PKG, null, 0, 0) == null,
                    "Unfocused app getPrimaryClip must return null");
            check(service.getPrimaryClipDescription(BG_PKG, null, 0, 0) == null,
                    "Unfocused app getPrimaryClipDescription must return null");
            check(service.getPrimaryClipSource(BG_PKG, null, 0, 0) == null,
                    "Unfocused app getPrimaryClipSource must return null");

            // However, background write is permitted per Android contract
            ClipData bgClip = ClipData.newPlainText("bg_label", "Written from background");
            service.setPrimaryClip(bgClip, BG_PKG, null, 0, 0);
        });

        // Verify the background write actually updated the clipboard by reading from focused app
        runAs(APP_UID, APP_PID, () -> {
            ClipData readClip = service.getPrimaryClip(APP_PKG, null, 0, 0);
            check(readClip != null && "Written from background".equals(readClip.getItemAt(0).getText().toString()),
                    "Background write must succeed and be visible to focused app");
        });
    }

    /** Test 3: System UID can read clipboard even when unfocused. */
    private static void testSystemUidAccess(IClipboard service) throws Exception {
        // Set focus to a non-system UID
        setFocusedUid(APP_UID);

        runAs(SYSTEM_UID, SYSTEM_PID, () -> {
            check(service.hasPrimaryClip(SYSTEM_PKG, null, 0, 0),
                    "System UID must have access regardless of window focus");
            ClipData clip = service.getPrimaryClip(SYSTEM_PKG, null, 0, 0);
            check(clip != null, "System UID getPrimaryClip must not return null");
        });
    }

    /** Test 4: Default IME can read clipboard even without window focus. */
    private static void testDefaultImeAccess(IClipboard service) throws Exception {
        // App is focused, not IME
        setFocusedUid(APP_UID);
        setDefaultIme(IME_UID, IME_PKG);

        runAs(IME_UID, IME_PID, () -> {
            check(service.hasPrimaryClip(IME_PKG, null, 0, 0),
                    "Default IME must see hasPrimaryClip == true even without focus");
            check(service.hasClipboardText(IME_PKG, null, 0, 0),
                    "Default IME must see hasClipboardText == true even without focus");
            ClipData clip = service.getPrimaryClip(IME_PKG, null, 0, 0);
            check(clip != null, "Default IME must be able to read primary clip without window focus");
            check(service.getPrimaryClipDescription(IME_PKG, null, 0, 0) != null,
                    "Default IME must be able to read clip description without window focus");
        });
    }

    /** Test 5: OnPrimaryClipChangedListener only dispatches to callers with focus (or IME/system). */
    private static void testListenerFocusFiltering(IClipboard service) throws Exception {
        setFocusedUid(APP_UID);
        setDefaultIme(IME_UID, IME_PKG);

        TestListener appListener = new TestListener();
        TestListener bgListener = new TestListener();
        TestListener imeListener = new TestListener();
        TestListener systemListener = new TestListener();

        // Register each listener with its respective identity
        runAs(APP_UID, APP_PID, () -> service.addPrimaryClipChangedListener(appListener, APP_PKG, null, 0, 0));
        runAs(BG_UID, BG_PID, () -> service.addPrimaryClipChangedListener(bgListener, BG_PKG, null, 0, 0));
        runAs(IME_UID, IME_PID, () -> service.addPrimaryClipChangedListener(imeListener, IME_PKG, null, 0, 0));
        runAs(SYSTEM_UID, SYSTEM_PID, () -> service.addPrimaryClipChangedListener(systemListener, SYSTEM_PKG, null, 0, 0));

        // Clip change while APP_UID is focused
        runAs(APP_UID, APP_PID, () -> {
            service.setPrimaryClip(ClipData.newPlainText("l1", "Change 1"), APP_PKG, null, 0, 0);
        });

        // App (focused), IME, and System should be notified; BG (unfocused) must NOT be notified
        check(appListener.calls.get() == 1, "Focused app listener must receive clip change notification");
        check(imeListener.calls.get() == 1, "Default IME listener must receive clip change notification");
        check(systemListener.calls.get() == 1, "System listener must receive clip change notification");
        check(bgListener.calls.get() == 0, "Unfocused background listener must NOT receive notification");

        // Now shift focus to BG_UID and trigger a clip change
        setFocusedUid(BG_UID);
        runAs(BG_UID, BG_PID, () -> {
            service.setPrimaryClip(ClipData.newPlainText("l2", "Change 2"), BG_PKG, null, 0, 0);
        });

        // Now BG_UID was focused so its calls should increment to 1.
        // APP_UID is now unfocused so its calls should stay at 1.
        // IME and System should increment to 2.
        check(bgListener.calls.get() == 1, "Newly focused listener must receive clip change notification");
        check(appListener.calls.get() == 1, "Previously focused app listener must NOT receive notification now that it is unfocused");
        check(imeListener.calls.get() == 2, "Default IME listener must receive notification across focus changes");
        check(systemListener.calls.get() == 2, "System listener must receive notification across focus changes");

        // Cleanup
        service.removePrimaryClipChangedListener(appListener, APP_PKG, null, 0, 0);
        service.removePrimaryClipChangedListener(bgListener, BG_PKG, null, 0, 0);
        service.removePrimaryClipChangedListener(imeListener, IME_PKG, null, 0, 0);
        service.removePrimaryClipChangedListener(systemListener, SYSTEM_PKG, null, 0, 0);
    }

    /** Test 6: Package validation throws SecurityException for null package when not system. */
    private static void testNullPackageSecurityException(IClipboard service) throws Exception {
        runAs(APP_UID, APP_PID, () -> {
            boolean thrown = false;
            try {
                service.getPrimaryClip(null, null, 0, 0);
            } catch (SecurityException expected) {
                thrown = true;
            }
            check(thrown, "Non-system caller with null package must throw SecurityException");
        });
    }

    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime");
        // Disable WindowsHost to avoid external Windows clipboard interference
        Class<?> host = Class.forName("com.gesher.server.WindowsHost");
        java.lang.reflect.Field available = host.getDeclaredField("sAvailable");
        available.setAccessible(true);
        Object prevAvailable = available.get(null);
        available.set(null, false);

        try {
            resetOverrides();
            IClipboard service = newService();

            testFocusedAppReadWrite(service);
            testBackgroundAppReadDenied(service);
            testSystemUidAccess(service);
            testDefaultImeAccess(service);
            testListenerFocusFiltering(service);
            testNullPackageSecurityException(service);

            System.out.println("ClipboardSecurityContractTest all passed: " + checks + " checks");
        } finally {
            resetOverrides();
            available.set(null, prevAvailable);
        }
    }
}
