/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.gesher.server;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.IClipboard;
import android.content.IOnPrimaryClipChangedListener;
import android.content.Intent;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code clipboard}: ClipboardService, one with Windows' clipboard (devsvc track,
 * docs/services.md "Device services", ADR 0038). An app's primary clip goes to Windows as
 * text (CF_UNICODETEXT: the items' text, HTML as text, URIs and intents as their strings,
 * one line each) and, when an item has HTML, as "HTML Format" too; what Windows' clipboard
 * gets from elsewhere (WM_CLIPBOARDUPDATE, through {@link WindowsHost}) becomes the primary
 * clip (HTML text if Windows has HTML, else plain text; nothing if it has no text), and
 * the apps' OnPrimaryClipChangedListeners are told either way.
 *
 * Android 10+ (API 29+) clipboard security contract:
 * Clipboard read operations (getPrimaryClip, getPrimaryClipDescription, hasPrimaryClip,
 * hasClipboardText, getPrimaryClipSource) and clip change callbacks are restricted to callers
 * that currently have input focus, are the default IME, or hold system/privileged authority.
 * Background applications without focus receive null or false.
 */
public final class ClipboardService extends IClipboard.Default implements WindowsHost.Listener {
    private static final String WINDOWS_LABEL = "Windows";

    private final RemoteCallbackList<IOnPrimaryClipChangedListener> mListeners =
            new RemoteCallbackList<>();
    private final ActivityTaskManagerService mAtm;
    private final WindowManagerService mWm;
    private final PackageManagerService mPm;
    private final CallerIdentity mCallers;

    // Test overrides for focus and default IME
    private static volatile boolean sFocusOverrideActive = false;
    private static volatile int sFocusedUid = -1;
    private static volatile int sDefaultImeUid = -1;
    private static volatile String sDefaultImePackage = null;

    // Guarded by this.
    private ClipData mClip;
    private String mSource;      // the package that set it, null for Windows
    private int mSequence = -1;  // Windows' clipboard sequence number mClip matches
    private boolean mStarted;

    public ClipboardService() {
        this(null, null, null);
    }

    public ClipboardService(ActivityTaskManagerService atm, WindowManagerService wm,
            PackageManagerService pm) {
        this(atm != null && pm != null ? new CallerIdentity(pm, atm) : null, atm, wm, pm);
    }

    public ClipboardService(CallerIdentity callers, ActivityTaskManagerService atm,
            WindowManagerService wm, PackageManagerService pm) {
        mCallers = callers;
        mAtm = atm;
        mWm = wm;
        mPm = pm;
    }

    /** Explicitly set the focused UID (primarily for tests). */
    public static void setFocusedUid(int uid) {
        sFocusOverrideActive = true;
        sFocusedUid = uid;
    }

    /** Explicitly clear the focused UID (no application focused). */
    public static void clearFocusedUid() {
        sFocusOverrideActive = true;
        sFocusedUid = -1;
    }

    /** Explicitly configure the default IME identity (primarily for tests). */
    public static void setDefaultIme(int uid, String packageName) {
        sDefaultImeUid = uid;
        sDefaultImePackage = packageName;
    }

    /** Reset test overrides. */
    public static void resetOverrides() {
        sFocusOverrideActive = false;
        sFocusedUid = -1;
        sDefaultImeUid = -1;
        sDefaultImePackage = null;
        WindowManagerService.clearFocusedApp();
    }

    private static boolean isSystemUid(int uid) {
        return uid == Process.SYSTEM_UID || uid == Process.SHELL_UID || uid == Process.ROOT_UID;
    }

    private boolean isDefaultIme(int uid, String callingPackage, int userId) {
        if (sDefaultImeUid >= 0 && uid == sDefaultImeUid) return true;
        if (sDefaultImePackage != null && sDefaultImePackage.equals(callingPackage)) return true;
        if (InputMethodManagerService.IME_PACKAGE.equals(callingPackage)) return true;
        return false;
    }

    private boolean isUidFocused(int uid) {
        if (uid < 0) return false;
        if (sFocusOverrideActive) {
            return uid == sFocusedUid;
        }
        if (mWm != null || mAtm != null) {
            if (mWm != null && mWm.isUidFocused(uid)) return true;
            if (WindowManagerService.isFocused(uid)) return true;
            if (mAtm != null && mAtm.isUidFocused(uid)) return true;
            return false;
        }
        return true;
    }

    private void validateCallingPackage(String callingPackage, int uid, int userId) {
        if (isSystemUid(uid)) {
            return;
        }
        if (callingPackage == null) {
            throw new SecurityException("callingPackage cannot be null");
        }
        if (mCallers != null) {
            mCallers.requirePackage(callingPackage);
        }
        if (mPm != null) {
            int pkgUid = mPm.getPackageUid(callingPackage, 0, userId);
            if (pkgUid != -1 && pkgUid != uid) {
                throw new SecurityException("Package " + callingPackage + " does not belong to uid " + uid);
            }
        }
    }

    private boolean clipboardAccessAllowed(int uid, String callingPackage, int userId) {
        if (isSystemUid(uid)) {
            return true;
        }
        if (mCallers != null && mCallers.isSystem()) {
            return true;
        }
        if (isDefaultIme(uid, callingPackage, userId)) {
            return true;
        }
        if (isUidFocused(uid)) {
            return true;
        }
        return false;
    }

    /** Listens to Windows' clipboard from the first call on. */
    private void start() {
        synchronized (this) {
            if (mStarted) return;
            mStarted = true;
        }
        if (!WindowsHost.available()) return;
        WindowsHost.addListener(this);
        syncFromWindows(false);
        SystemServer.log("clipboard: linked with Windows' clipboard");
    }

    @Override
    public void onWindowsEvents(int events) {
        if ((events & WindowsHost.EVENT_CLIPBOARD) != 0) syncFromWindows(true);
    }

    /**
     * Takes Windows' clipboard as the primary clip if it changed since it was last seen (or
     * set from here); tells the listeners if {@code notify}.
     */
    private void syncFromWindows(boolean notify) {
        if (!WindowsHost.available()) return;
        int seq = 0;
        try {
            seq = WindowsHost.clipboardSequence();
        } catch (Throwable t) {
            return;
        }
        synchronized (this) {
            if (seq == mSequence) return;  // ours, or seen
        }
        String text = null;
        String html = null;
        byte[] image = null;
        try {
            text = WindowsHost.clipboardText();
            html = WindowsHost.clipboardHtml();
            image = WindowsHost.clipboardImage();
        } catch (Throwable t) {
            // keep whatever could be read
        }
        ClipData clip = null;
        if (image != null && image.length > 0) {
            try {
                java.io.File cacheDir = new java.io.File(
                        System.getProperty("java.io.tmpdir", "."), "gesher_clipboard");
                cacheDir.mkdirs();
                java.io.File imgFile = new java.io.File(cacheDir, "clip_" + seq + ".bmp");
                java.nio.file.Files.write(imgFile.toPath(), image);
                android.net.Uri uri = android.net.Uri.fromFile(imgFile);
                ClipDescription desc = new ClipDescription(WINDOWS_LABEL,
                        new String[] {"image/bmp", "image/*"});
                ClipData.Item item = new ClipData.Item(text != null ? text : "", html, null, uri);
                clip = new ClipData(desc, item);
            } catch (Throwable t) {
                SystemServer.log("clipboard: failed to cache image from Windows: " + t);
            }
        }
        if (clip == null) {
            if (text != null && html != null) {
                clip = ClipData.newHtmlText(WINDOWS_LABEL, text, html);
            } else if (text != null) {
                clip = ClipData.newPlainText(WINDOWS_LABEL, text);
            } else if (html != null) {
                clip = ClipData.newHtmlText(WINDOWS_LABEL, android.text.Html.fromHtml(html,
                        android.text.Html.FROM_HTML_MODE_LEGACY).toString(), html);
            }
        }
        synchronized (this) {
            if (seq == mSequence) return;  // another thread took it meanwhile
            mSequence = seq;
            mClip = clip;
            mSource = null;
        }
        SystemServer.log("clipboard: from Windows (" + (clip == null ? "empty"
                : (image != null ? image.length + " bytes image, " : "")
                + (text != null ? text.length() + " characters" : "no text")
                + (html != null ? ", HTML" : "")) + ")");
        if (notify) notifyListeners();
    }

    private void notifyListeners() {
        List<IOnPrimaryClipChangedListener> targets = new ArrayList<>();
        synchronized (mListeners) {
            int n = mListeners.beginBroadcast();
            try {
                for (int i = 0; i < n; i++) {
                    Object cookie = mListeners.getBroadcastCookie(i);
                    if (cookie instanceof ListenerInfo) {
                        ListenerInfo info = (ListenerInfo) cookie;
                        if (!clipboardAccessAllowed(info.uid, info.packageName, info.userId)) {
                            continue;
                        }
                    }
                    targets.add(mListeners.getBroadcastItem(i));
                }
            } finally { mListeners.finishBroadcast(); }
        }
        // Finish the RemoteCallbackList snapshot before calling application code:
        // local listeners may reenter, and another Binder/Windows thread may notify.
        for (IOnPrimaryClipChangedListener target : targets) {
            try { target.dispatchPrimaryClipChanged(); }
            catch (RemoteException | RuntimeException failed) {
                SystemServer.log("clipboard listener unavailable: " + failed.getClass().getSimpleName());
            }
        }
    }

    /** What Windows gets for {@code clip}: {text, html or null}. */
    private static String[] forWindows(ClipData clip) {
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        boolean anyHtml = false;
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            String t = null;
            if (item.getText() != null) {
                t = item.getText().toString();
            } else if (item.getHtmlText() != null) {
                t = android.text.Html.fromHtml(item.getHtmlText(),
                        android.text.Html.FROM_HTML_MODE_LEGACY).toString();
            } else if (item.getUri() != null) {
                t = item.getUri().toString();
            } else if (item.getIntent() != null) {
                t = item.getIntent().toUri(Intent.URI_INTENT_SCHEME);
            }
            if (t == null) continue;
            if (text.length() > 0) text.append("\r\n");
            text.append(t);
            if (item.getHtmlText() != null) {
                html.append(item.getHtmlText());
                anyHtml = true;
            } else {
                html.append(android.text.Html.escapeHtml(t));
            }
        }
        return new String[] {text.toString(), anyHtml ? html.toString() : null};
    }

    private static byte[] extractImage(ClipData clip) {
        if (clip == null) return null;
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            android.net.Uri uri = item.getUri();
            if (uri != null) {
                String path = uri.getPath();
                if (path != null) {
                    java.io.File file = new java.io.File(path);
                    if (file.exists() && file.isFile()) {
                        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
                        if (name.endsWith(".bmp") || name.endsWith(".png")
                                || name.endsWith(".jpg") || name.endsWith(".jpeg")
                                || name.endsWith(".webp") || name.endsWith(".gif")) {
                            try {
                                return java.nio.file.Files.readAllBytes(file.toPath());
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    // --- IClipboard -------------------------------------------------------------------------

    @Override
    public void setPrimaryClip(ClipData clip, String callingPackage, String attributionTag,
            int userId, int deviceId) {
        int callingUid = Binder.getCallingUid();
        validateCallingPackage(callingPackage, callingUid, userId);
        start();
        if (clip == null || clip.getItemCount() <= 0) {
            throw new IllegalArgumentException("No items");
        }
        String[] windows = forWindows(clip);
        byte[] imageBytes = extractImage(clip);
        boolean written = false;
        if (WindowsHost.available()) {
            if (imageBytes != null && imageBytes.length > 0) {
                written = WindowsHost.setClipboardRich(windows[0], windows[1], imageBytes);
            } else {
                written = WindowsHost.setClipboard(windows[0], windows[1]);
            }
        }
        synchronized (this) {
            mClip = clip;
            mSource = callingPackage;
            if (written) mSequence = WindowsHost.clipboardSequence();
        }
        SystemServer.log("clipboard: set by " + callingPackage + " (" + windows[0].length()
                + " characters" + (windows[1] != null ? ", HTML" : "")
                + (imageBytes != null ? ", " + imageBytes.length + " bytes image" : "") + ")"
                + (written ? ", on Windows' clipboard" : WindowsHost.available()
                        ? ", Windows' clipboard could not be opened" : ""));
        notifyListeners();
    }

    @Override
    public void setPrimaryClipAsPackage(ClipData clip, String callingPackage,
            String attributionTag, int userId, int deviceId, String sourcePackage) {
        setPrimaryClip(clip, sourcePackage, attributionTag, userId, deviceId);
    }

    @Override
    public void clearPrimaryClip(String callingPackage, String attributionTag, int userId,
            int deviceId) {
        int callingUid = Binder.getCallingUid();
        validateCallingPackage(callingPackage, callingUid, userId);
        start();
        boolean written = WindowsHost.available() && WindowsHost.setClipboardRich("", null, null);
        synchronized (this) {
            mClip = null;
            mSource = callingPackage;
            if (written) mSequence = WindowsHost.clipboardSequence();
        }
        SystemServer.log("clipboard: cleared by " + callingPackage);
        notifyListeners();
    }

    @Override
    public ClipData getPrimaryClip(String pkg, String attributionTag, int userId,
            int deviceId) {
        int callingUid = Binder.getCallingUid();
        validateCallingPackage(pkg, callingUid, userId);
        if (!clipboardAccessAllowed(callingUid, pkg, userId)) {
            return null;
        }
        start();
        syncFromWindows(true);
        synchronized (this) {
            return mClip;
        }
    }

    @Override
    public ClipDescription getPrimaryClipDescription(String callingPackage,
            String attributionTag, int userId, int deviceId) {
        int callingUid = Binder.getCallingUid();
        validateCallingPackage(callingPackage, callingUid, userId);
        if (!clipboardAccessAllowed(callingUid, callingPackage, userId)) {
            return null;
        }
        start();
        syncFromWindows(true);
        synchronized (this) {
            return mClip != null ? mClip.getDescription() : null;
        }
    }

    @Override
    public boolean hasPrimaryClip(String callingPackage, String attributionTag, int userId,
            int deviceId) {
        int callingUid = Binder.getCallingUid();
        validateCallingPackage(callingPackage, callingUid, userId);
        if (!clipboardAccessAllowed(callingUid, callingPackage, userId)) {
            return false;
        }
        start();
        syncFromWindows(true);
        synchronized (this) {
            return mClip != null;
        }
    }

    @Override
    public boolean hasClipboardText(String callingPackage, String attributionTag, int userId,
            int deviceId) {
        int callingUid = Binder.getCallingUid();
        validateCallingPackage(callingPackage, callingUid, userId);
        if (!clipboardAccessAllowed(callingUid, callingPackage, userId)) {
            return false;
        }
        start();
        syncFromWindows(true);
        synchronized (this) {
            if (mClip == null || mClip.getItemCount() == 0) return false;
            CharSequence text = mClip.getItemAt(0).getText();
            return text != null && text.length() > 0;
        }
    }

    @Override
    public String getPrimaryClipSource(String callingPackage, String attributionTag,
            int userId, int deviceId) {
        int callingUid = Binder.getCallingUid();
        validateCallingPackage(callingPackage, callingUid, userId);
        if (!clipboardAccessAllowed(callingUid, callingPackage, userId)) {
            return null;
        }
        synchronized (this) {
            return mSource;
        }
    }

    @Override
    public void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener,
            String callingPackage, String attributionTag, int userId, int deviceId) {
        int callingUid = Binder.getCallingUid();
        validateCallingPackage(callingPackage, callingUid, userId);
        start();
        mListeners.register(listener, new ListenerInfo(callingPackage, callingUid, userId));
    }

    @Override
    public void removePrimaryClipChangedListener(IOnPrimaryClipChangedListener listener,
            String callingPackage, String attributionTag, int userId, int deviceId) {
        mListeners.unregister(listener);
    }

    @Override
    public boolean areClipboardAccessNotificationsEnabledForUser(int userId) {
        return false;
    }

    private static final class ListenerInfo {
        final String packageName;
        final int uid;
        final int userId;

        ListenerInfo(String packageName, int uid, int userId) {
            this.packageName = packageName;
            this.uid = uid;
            this.userId = userId;
        }
    }
}
