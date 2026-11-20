/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ClipData;
import android.os.Binder;
import com.gesher.server.WindowsHost;
import static gesher.services.ClipboardFixture.*;

/** Read-only observations around the unchanged real backend and original failing gate. */
public final class ClipboardSourceDiagnostic {
    private static void snapshot(ClipboardFixture h, String phase) throws Exception {
        int stored; String source; Object clip;
        synchronized (get(h.service, "mClipboardLock")) {
            stored = (Integer)get(h.service, "mSequence");
            source = (String)get(h.service, "mSource");
            clip = get(h.service, "mClip");
        }
        int nativeSequence = (Integer)callStatic(WindowsHost.class, "clipboardSequence");
        System.out.println("CLIP_SOURCE phase=" + phase + " storedSequence=" + stored
                + " nativeSequence=" + nativeSequence + " source=" + source
                + " clipPresent=" + (clip != null) + " session=" + WindowsHost.sessionLockState());
    }
    private static Object callStatic(Class<?> type, String name) throws Exception {
        java.lang.reflect.Method method = type.getDeclaredMethod(name);
        method.setAccessible(true); return method.invoke(null);
    }
    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime");
        if (!WindowsHost.available()) throw new AssertionError("Actual Windows backend is required");
        // Preserve the original negative, including all callback assertions preceding it.
        boolean reproduced = false;
        try { ClipboardCallbackTest.main(new String[0]); }
        catch (AssertionError failure) {
            if (!"Native publication sequence must preserve the Android source until an external change".equals(failure.getMessage())) throw failure;
            reproduced = true;
            System.out.println("CLIP_SOURCE originalNegative=" + failure.getMessage());
        }
        if (!reproduced) throw new AssertionError("The exact source-attribution negative did not reproduce");
        String savedText = WindowsHost.clipboardText(), savedHtml = WindowsHost.clipboardHtml();
        byte[] savedImage = WindowsHost.clipboardImage();
        try (ClipboardFixture h = new ClipboardFixture(true, true)) {
            System.out.println("CLIP_SOURCE actualProcess=" + android.os.Process.myPid()
                    + " fixturePid=" + (PID + h.privileged.uid() - UID)
                    + " fixtureUid=" + h.privileged.uid()
                    + " fixtureGeneration=" + get(h.processes.get(2), "nativeStartTime")
                    + " packageCurrent=" + (h.pm.get(PRIVILEGED) == h.privileged)
                    + " backgroundPermission=" + h.pm.permissions().checkPermission(PRIVILEGED, Manifest.permission.READ_CLIPBOARD_IN_BACKGROUND)
                    + " sourcePermission=" + h.pm.permissions().checkPermission(PRIVILEGED, Manifest.permission.SET_CLIP_SOURCE));
            h.as(h.privileged, () -> {
                System.out.println("CLIP_SOURCE authenticatedPid=" + Binder.getCallingPid()
                        + " authenticatedUid=" + Binder.getCallingUid()
                        + " readAllowed=" + call(h.service, "readAllowed",
                                new Class<?>[]{String.class, String.class, boolean.class}, PRIVILEGED, null, false));
            });
            long identity = Binder.clearCallingIdentity();
            try { System.out.println("CLIP_SOURCE readAppOp=" + h.ops.checkOperation(AppOpsManager.OP_READ_CLIPBOARD, h.privileged.uid(), PRIVILEGED)); }
            finally { Binder.restoreCallingIdentity(identity); }
            String text = "gesher-source-diagnostic-" + System.nanoTime();
            snapshot(h, "before-write");
            h.as(h.privileged, () -> h.api.setPrimaryClip(ClipData.newHtmlText("fixture", text, "<b>" + text + "</b>"), PRIVILEGED, "clipboard", 0, 0));
            snapshot(h, "after-write");
            String actualText = WindowsHost.clipboardText();
            snapshot(h, "after-native-read");
            if (!text.equals(actualText)) throw new AssertionError("Actual native text mismatch");
            h.as(h.privileged, () -> System.out.println("CLIP_SOURCE actualSourceGetter=" + h.api.getPrimaryClipSource(PRIVILEGED, null, 0, 0)));
            snapshot(h, "after-source-getter");
        } finally {
            if (!(savedImage == null ? WindowsHost.setClipboard(savedText, savedHtml)
                    : WindowsHost.setClipboardRich(savedText, savedHtml, savedImage))) throw new AssertionError("Native clipboard restoration failed");
        }
        System.out.println("CLIP_SOURCE diagnostic completed with exact original negative and real admission observations");
    }
}
