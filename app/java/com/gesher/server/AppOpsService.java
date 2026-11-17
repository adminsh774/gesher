/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.gesher.server;

import android.app.AppOpsManager;
import android.app.SyncNotedAppOp;
import android.content.AttributionSource;
import android.content.AttributionSourceState;
import android.content.pm.IPackageManager;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Xml;

import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Real AppOps Service and mode enforcement state machine.
 *
 * <p>Maintains per-UID and per-(package, op) operation modes, verifies caller packages,
 * tracks active started operations with client binder death cleanup, maps permissions
 * to opcodes, and notifies registered {@link IAppOpsCallback} watchers.
 */
public class AppOpsService extends IAppOpsService.Default {
    private final IPackageManager mPm;
    private final AtomicFile mFile;

    private static final class UidOpKey {
        final int uid;
        final int op;

        UidOpKey(int uid, int op) {
            this.uid = uid;
            this.op = op;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UidOpKey)) return false;
            UidOpKey other = (UidOpKey) o;
            return uid == other.uid && op == other.op;
        }

        @Override
        public int hashCode() {
            return 31 * uid + op;
        }
    }

    private static final class PackageOpKey {
        final String packageName;
        final int op;

        PackageOpKey(String packageName, int op) {
            this.packageName = packageName;
            this.op = op;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PackageOpKey)) return false;
            PackageOpKey other = (PackageOpKey) o;
            return op == other.op && Objects.equals(packageName, other.packageName);
        }

        @Override
        public int hashCode() {
            return 31 * (packageName != null ? packageName.hashCode() : 0) + op;
        }
    }

    private static final class ActiveKey {
        final int code;
        final int uid;
        final String packageName;

        ActiveKey(int code, int uid, String packageName) {
            this.code = code;
            this.uid = uid;
            this.packageName = packageName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ActiveKey)) return false;
            ActiveKey other = (ActiveKey) o;
            return code == other.code && uid == other.uid && Objects.equals(packageName, other.packageName);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * code + uid) + (packageName != null ? packageName.hashCode() : 0);
        }
    }

    private static final class ModeWatcher implements IBinder.DeathRecipient {
        final int op;
        final String packageName;
        final int flags;
        final IAppOpsCallback callback;
        final AppOpsService service;

        ModeWatcher(int op, String packageName, int flags, IAppOpsCallback callback, AppOpsService service) {
            this.op = op;
            this.packageName = packageName;
            this.flags = flags;
            this.callback = callback;
            this.service = service;
        }

        @Override
        public void binderDied() {
            service.onWatcherDied(this);
        }
    }

    private static final class ClientRecord implements IBinder.DeathRecipient {
        final IBinder clientId;
        final Map<ActiveKey, Integer> activeCounts = new HashMap<>();
        final AppOpsService service;

        ClientRecord(IBinder clientId, AppOpsService service) {
            this.clientId = clientId;
            this.service = service;
        }

        @Override
        public void binderDied() {
            service.onClientDied(this);
        }
    }

    private final Map<UidOpKey, Integer> mUidModes = new HashMap<>();
    private final Map<PackageOpKey, Integer> mPackageModes = new HashMap<>();
    private final Map<ActiveKey, Integer> mActiveOps = new HashMap<>();
    private final Map<IBinder, ClientRecord> mClients = new HashMap<>();
    private final List<ModeWatcher> mModeWatchers = new ArrayList<>();

    private static final Map<String, Integer> PERM_MAP = new HashMap<>();
    private static void mapPerm(String name, int op) {
        PERM_MAP.put(name, op);
    }

    static {
        mapPerm("POST_NOTIFICATIONS", AppOpsManager.OP_POST_NOTIFICATION);
        mapPerm("android.permission.POST_NOTIFICATIONS", AppOpsManager.OP_POST_NOTIFICATION);
        mapPerm("CAMERA", AppOpsManager.OP_CAMERA);
        mapPerm("android.permission.CAMERA", AppOpsManager.OP_CAMERA);
        mapPerm("RECORD_AUDIO", AppOpsManager.OP_RECORD_AUDIO);
        mapPerm("android.permission.RECORD_AUDIO", AppOpsManager.OP_RECORD_AUDIO);
        mapPerm("FINE_LOCATION", AppOpsManager.OP_FINE_LOCATION);
        mapPerm("ACCESS_FINE_LOCATION", AppOpsManager.OP_FINE_LOCATION);
        mapPerm("android.permission.FINE_LOCATION", AppOpsManager.OP_FINE_LOCATION);
        mapPerm("android.permission.ACCESS_FINE_LOCATION", AppOpsManager.OP_FINE_LOCATION);
        mapPerm("COARSE_LOCATION", AppOpsManager.OP_COARSE_LOCATION);
        mapPerm("ACCESS_COARSE_LOCATION", AppOpsManager.OP_COARSE_LOCATION);
        mapPerm("android.permission.COARSE_LOCATION", AppOpsManager.OP_COARSE_LOCATION);
        mapPerm("android.permission.ACCESS_COARSE_LOCATION", AppOpsManager.OP_COARSE_LOCATION);
        mapPerm("MANAGE_EXTERNAL_STORAGE", AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE);
        mapPerm("android.permission.MANAGE_EXTERNAL_STORAGE", AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE);
        mapPerm("READ_EXTERNAL_STORAGE", AppOpsManager.OP_READ_EXTERNAL_STORAGE);
        mapPerm("android.permission.READ_EXTERNAL_STORAGE", AppOpsManager.OP_READ_EXTERNAL_STORAGE);
        mapPerm("WRITE_EXTERNAL_STORAGE", AppOpsManager.OP_WRITE_EXTERNAL_STORAGE);
        mapPerm("android.permission.WRITE_EXTERNAL_STORAGE", AppOpsManager.OP_WRITE_EXTERNAL_STORAGE);
        mapPerm("READ_CONTACTS", AppOpsManager.OP_READ_CONTACTS);
        mapPerm("android.permission.READ_CONTACTS", AppOpsManager.OP_READ_CONTACTS);
        mapPerm("WRITE_CONTACTS", AppOpsManager.OP_WRITE_CONTACTS);
        mapPerm("android.permission.WRITE_CONTACTS", AppOpsManager.OP_WRITE_CONTACTS);
        mapPerm("READ_CALENDAR", AppOpsManager.OP_READ_CALENDAR);
        mapPerm("android.permission.READ_CALENDAR", AppOpsManager.OP_READ_CALENDAR);
        mapPerm("WRITE_CALENDAR", AppOpsManager.OP_WRITE_CALENDAR);
        mapPerm("android.permission.WRITE_CALENDAR", AppOpsManager.OP_WRITE_CALENDAR);
        mapPerm("READ_CALL_LOG", AppOpsManager.OP_READ_CALL_LOG);
        mapPerm("android.permission.READ_CALL_LOG", AppOpsManager.OP_READ_CALL_LOG);
        mapPerm("WRITE_CALL_LOG", AppOpsManager.OP_WRITE_CALL_LOG);
        mapPerm("android.permission.WRITE_CALL_LOG", AppOpsManager.OP_WRITE_CALL_LOG);
        mapPerm("READ_PHONE_STATE", AppOpsManager.OP_READ_PHONE_STATE);
        mapPerm("android.permission.READ_PHONE_STATE", AppOpsManager.OP_READ_PHONE_STATE);
        mapPerm("CALL_PHONE", AppOpsManager.OP_CALL_PHONE);
        mapPerm("android.permission.CALL_PHONE", AppOpsManager.OP_CALL_PHONE);
        mapPerm("READ_SMS", AppOpsManager.OP_READ_SMS);
        mapPerm("android.permission.READ_SMS", AppOpsManager.OP_READ_SMS);
        mapPerm("SEND_SMS", AppOpsManager.OP_SEND_SMS);
        mapPerm("android.permission.SEND_SMS", AppOpsManager.OP_SEND_SMS);
        mapPerm("RECEIVE_SMS", AppOpsManager.OP_RECEIVE_SMS);
        mapPerm("android.permission.RECEIVE_SMS", AppOpsManager.OP_RECEIVE_SMS);
        mapPerm("SYSTEM_ALERT_WINDOW", AppOpsManager.OP_SYSTEM_ALERT_WINDOW);
        mapPerm("android.permission.SYSTEM_ALERT_WINDOW", AppOpsManager.OP_SYSTEM_ALERT_WINDOW);
        mapPerm("WRITE_SETTINGS", AppOpsManager.OP_WRITE_SETTINGS);
        mapPerm("android.permission.WRITE_SETTINGS", AppOpsManager.OP_WRITE_SETTINGS);
        mapPerm("BODY_SENSORS", AppOpsManager.OP_BODY_SENSORS);
        mapPerm("android.permission.BODY_SENSORS", AppOpsManager.OP_BODY_SENSORS);
        mapPerm("BLUETOOTH_SCAN", AppOpsManager.OP_BLUETOOTH_SCAN);
        mapPerm("android.permission.BLUETOOTH_SCAN", AppOpsManager.OP_BLUETOOTH_SCAN);
        mapPerm("BLUETOOTH_CONNECT", AppOpsManager.OP_BLUETOOTH_CONNECT);
        mapPerm("android.permission.BLUETOOTH_CONNECT", AppOpsManager.OP_BLUETOOTH_CONNECT);
        mapPerm("BLUETOOTH_ADVERTISE", AppOpsManager.OP_BLUETOOTH_ADVERTISE);
        mapPerm("android.permission.BLUETOOTH_ADVERTISE", AppOpsManager.OP_BLUETOOTH_ADVERTISE);
        mapPerm("ACCESS_MEDIA_LOCATION", AppOpsManager.OP_ACCESS_MEDIA_LOCATION);
        mapPerm("android.permission.ACCESS_MEDIA_LOCATION", AppOpsManager.OP_ACCESS_MEDIA_LOCATION);
        mapPerm("READ_MEDIA_AUDIO", AppOpsManager.OP_READ_MEDIA_AUDIO);
        mapPerm("android.permission.READ_MEDIA_AUDIO", AppOpsManager.OP_READ_MEDIA_AUDIO);
        mapPerm("READ_MEDIA_IMAGES", AppOpsManager.OP_READ_MEDIA_IMAGES);
        mapPerm("android.permission.READ_MEDIA_IMAGES", AppOpsManager.OP_READ_MEDIA_IMAGES);
        mapPerm("READ_MEDIA_VIDEO", AppOpsManager.OP_READ_MEDIA_VIDEO);
        mapPerm("android.permission.READ_MEDIA_VIDEO", AppOpsManager.OP_READ_MEDIA_VIDEO);
        mapPerm("READ_MEDIA_VISUAL_USER_SELECTED", AppOpsManager.OP_READ_MEDIA_VISUAL_USER_SELECTED);
        mapPerm("android.permission.READ_MEDIA_VISUAL_USER_SELECTED", AppOpsManager.OP_READ_MEDIA_VISUAL_USER_SELECTED);
        mapPerm("VIBRATE", AppOpsManager.OP_VIBRATE);
        mapPerm("android.permission.VIBRATE", AppOpsManager.OP_VIBRATE);
    }

    public AppOpsService() {
        this(null);
    }

    public AppOpsService(IPackageManager pm) {
        this(pm, defaultFile());
    }

    public AppOpsService(IPackageManager pm, File storageFile) {
        mPm = pm;
        mFile = storageFile != null ? new AtomicFile(storageFile) : null;
        readState();
    }

    private static File defaultFile() {
        try {
            String data = System.getenv("ANDROID_DATA");
            File base = new File(data != null && !data.isEmpty() ? data : "/data");
            return new File(base, "system/appops.xml");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void readState() {
        if (mFile == null) return;
        synchronized (this) {
            FileInputStream stream = null;
            try {
                stream = mFile.openRead();
                TypedXmlPullParser parser = Xml.resolvePullParser(stream);
                int event;
                do {
                    event = parser.next();
                } while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT);

                if (event != XmlPullParser.START_TAG || !"app-ops".equals(parser.getName())) {
                    return;
                }

                int outerDepth = parser.getDepth();
                while ((event = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (event != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                    if (event != XmlPullParser.START_TAG) {
                        continue;
                    }
                    String tagName = parser.getName();
                    if ("pkg".equals(tagName)) {
                        String pkgName = parser.getAttributeValue(null, "name");
                        int pkgDepth = parser.getDepth();
                        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT
                                && (event != XmlPullParser.END_TAG || parser.getDepth() > pkgDepth)) {
                            if (event != XmlPullParser.START_TAG) {
                                continue;
                            }
                            if ("op".equals(parser.getName())) {
                                int id = parser.getAttributeInt(null, "id",
                                        parser.getAttributeInt(null, "n", -1));
                                int mode = parser.getAttributeInt(null, "mode",
                                        parser.getAttributeInt(null, "m", AppOpsManager.MODE_DEFAULT));
                                if (id >= 0 && mode != AppOpsManager.MODE_DEFAULT && pkgName != null) {
                                    mPackageModes.put(new PackageOpKey(pkgName, id), mode);
                                }
                            }
                        }
                    } else if ("uid".equals(tagName)) {
                        int uid = parser.getAttributeInt(null, "id",
                                parser.getAttributeInt(null, "n", -1));
                        int uidDepth = parser.getDepth();
                        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT
                                && (event != XmlPullParser.END_TAG || parser.getDepth() > uidDepth)) {
                            if (event != XmlPullParser.START_TAG) {
                                continue;
                            }
                            if ("op".equals(parser.getName())) {
                                int id = parser.getAttributeInt(null, "id",
                                        parser.getAttributeInt(null, "n", -1));
                                int mode = parser.getAttributeInt(null, "mode",
                                        parser.getAttributeInt(null, "m", AppOpsManager.MODE_DEFAULT));
                                if (id >= 0 && mode != AppOpsManager.MODE_DEFAULT && uid >= 0) {
                                    mUidModes.put(new UidOpKey(uid, id), mode);
                                }
                            }
                        }
                    }
                }
            } catch (FileNotFoundException ignored) {
                // Initial unconfigured state
            } catch (Exception ignored) {
                // Malformed state file
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException ignored) {}
                }
            }
        }
    }

    private void writeStateLocked() {
        if (mFile == null) return;
        FileOutputStream output = null;
        try {
            File parent = mFile.getBaseFile().getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            output = mFile.startWrite();
            TypedXmlSerializer xml = Xml.resolveSerializer(output);
            xml.startDocument(null, true);
            xml.startTag(null, "app-ops");

            Map<String, Map<Integer, Integer>> byPkg = new HashMap<>();
            for (Map.Entry<PackageOpKey, Integer> entry : mPackageModes.entrySet()) {
                byPkg.computeIfAbsent(entry.getKey().packageName, k -> new HashMap<>())
                        .put(entry.getKey().op, entry.getValue());
            }
            for (Map.Entry<String, Map<Integer, Integer>> pe : byPkg.entrySet()) {
                xml.startTag(null, "pkg");
                xml.attribute(null, "name", pe.getKey());
                for (Map.Entry<Integer, Integer> oe : pe.getValue().entrySet()) {
                    xml.startTag(null, "op");
                    xml.attributeInt(null, "id", oe.getKey());
                    xml.attributeInt(null, "mode", oe.getValue());
                    xml.endTag(null, "op");
                }
                xml.endTag(null, "pkg");
            }

            Map<Integer, Map<Integer, Integer>> byUid = new HashMap<>();
            for (Map.Entry<UidOpKey, Integer> entry : mUidModes.entrySet()) {
                byUid.computeIfAbsent(entry.getKey().uid, k -> new HashMap<>())
                        .put(entry.getKey().op, entry.getValue());
            }
            for (Map.Entry<Integer, Map<Integer, Integer>> ue : byUid.entrySet()) {
                xml.startTag(null, "uid");
                xml.attributeInt(null, "id", ue.getKey());
                for (Map.Entry<Integer, Integer> oe : ue.getValue().entrySet()) {
                    xml.startTag(null, "op");
                    xml.attributeInt(null, "id", oe.getKey());
                    xml.attributeInt(null, "mode", oe.getValue());
                    xml.endTag(null, "op");
                }
                xml.endTag(null, "uid");
            }

            xml.endTag(null, "app-ops");
            xml.endDocument();
            xml.flush();
            output.getFD().sync();
            mFile.finishWrite(output);
            output = null;
        } catch (IOException | RuntimeException e) {
            if (output != null) {
                mFile.failWrite(output);
            }
        }
    }

    public static int getDefaultMode(int code) {
        if (code >= 0 && code < AppOpsManager._NUM_OP) {
            try {
                return AppOpsManager.opToDefaultMode(code);
            } catch (Throwable ignored) {}
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public int checkOperation(int code, int uid, String packageName) {
        int mode = checkOperationRaw(code, uid, packageName, null);
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return getDefaultMode(code);
        }
        return mode;
    }

    @Override
    public int checkOperationForDevice(int code, int uid, String packageName,
            String attributionTag, int virtualDeviceId) {
        return checkOperation(code, uid, packageName);
    }

    @Override
    public int checkOperationRaw(int code, int uid, String packageName,
            String attributionTag) {
        synchronized (this) {
            if (packageName != null) {
                PackageOpKey pkgKey = new PackageOpKey(packageName, code);
                Integer pkgMode = mPackageModes.get(pkgKey);
                if (pkgMode != null && pkgMode != AppOpsManager.MODE_DEFAULT) {
                    return pkgMode;
                }
            }
            UidOpKey uidKey = new UidOpKey(uid, code);
            Integer uidMode = mUidModes.get(uidKey);
            if (uidMode != null && uidMode != AppOpsManager.MODE_DEFAULT) {
                return uidMode;
            }
        }
        return AppOpsManager.MODE_DEFAULT;
    }

    @Override
    public int checkOperationRawForDevice(int code, int uid, String packageName,
            String attributionTag, int virtualDeviceId) {
        return checkOperationRaw(code, uid, packageName, attributionTag);
    }

    @Override
    public int checkAudioOperation(int code, int usage, int uid, String packageName) {
        return checkOperation(code, uid, packageName);
    }

    @Override
    public int checkPackage(int uid, String packageName) {
        if (packageName == null || uid < 0) {
            return AppOpsManager.MODE_ERRORED;
        }
        IPackageManager pm = mPm;
        if (pm == null) {
            try {
                IBinder b = ServiceManager.getService("package");
                if (b != null) {
                    pm = IPackageManager.Stub.asInterface(b);
                }
            } catch (Throwable ignored) {}
        }
        if (pm != null) {
            try {
                int packageUid = pm.getPackageUid(packageName, 0, UserHandle.getUserId(uid));
                if (packageUid == uid || (packageUid != -1 && UserHandle.isSameApp(packageUid, uid))) {
                    return AppOpsManager.MODE_ALLOWED;
                }
                return AppOpsManager.MODE_ERRORED;
            } catch (Exception e) {
                return AppOpsManager.MODE_ERRORED;
            }
        }
        if (packageName.isEmpty()) {
            return AppOpsManager.MODE_ERRORED;
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public int permissionToOpCode(String permission) {
        if (permission == null) {
            return AppOpsManager.OP_NONE;
        }
        Integer op = PERM_MAP.get(permission);
        if (op != null) {
            return op;
        }
        try {
            int code = AppOpsManager.permissionToOpCode(permission);
            if (code != AppOpsManager.OP_NONE) {
                return code;
            }
            if (!permission.startsWith("android.permission.")) {
                code = AppOpsManager.permissionToOpCode("android.permission." + permission);
                if (code != AppOpsManager.OP_NONE) {
                    return code;
                }
                code = AppOpsManager.permissionToOpCode("android.permission.ACCESS_" + permission);
                if (code != AppOpsManager.OP_NONE) {
                    return code;
                }
            }
        } catch (Throwable ignored) {}
        return AppOpsManager.OP_NONE;
    }

    @Override
    public void setMode(int code, int uid, String packageName, int mode) {
        synchronized (this) {
            PackageOpKey key = new PackageOpKey(packageName, code);
            int old = mPackageModes.getOrDefault(key, AppOpsManager.MODE_DEFAULT);
            if (old == mode) {
                return;
            }
            if (mode == AppOpsManager.MODE_DEFAULT) {
                mPackageModes.remove(key);
            } else {
                mPackageModes.put(key, mode);
            }
            writeStateLocked();
        }
        try {
            AppOpsManager.invalidateAppOpModeCache();
        } catch (Throwable ignored) {}
        notifyWatchers(code, uid, packageName);
    }

    @Override
    public void setUidMode(int code, int uid, int mode) {
        synchronized (this) {
            UidOpKey key = new UidOpKey(uid, code);
            int old = mUidModes.getOrDefault(key, AppOpsManager.MODE_DEFAULT);
            if (old == mode) {
                return;
            }
            if (mode == AppOpsManager.MODE_DEFAULT) {
                mUidModes.remove(key);
            } else {
                mUidModes.put(key, mode);
            }
            writeStateLocked();
        }
        try {
            AppOpsManager.invalidateAppOpModeCache();
        } catch (Throwable ignored) {}
        notifyWatchers(code, uid, null);
    }

    @Override
    public SyncNotedAppOp noteOperation(int code, int uid, String packageName,
            String attributionTag, boolean shouldCollectAsyncNotedOp, String message,
            boolean shouldCollectMessage) {
        int mode = checkOperation(code, uid, packageName);
        return new SyncNotedAppOp(mode, code, attributionTag, packageName);
    }

    @Override
    public SyncNotedAppOp noteOperationForDevice(int code, int uid, String packageName,
            String attributionTag, int virtualDeviceId, boolean shouldCollectAsyncNotedOp,
            String message, boolean shouldCollectMessage) {
        return noteOperation(code, uid, packageName, attributionTag, shouldCollectAsyncNotedOp,
                message, shouldCollectMessage);
    }

    @Override
    public SyncNotedAppOp startOperation(IBinder clientId, int code, int uid,
            String packageName, String attributionTag, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            int attributionFlags, int attributionChainId) {
        int raw = checkOperationRaw(code, uid, packageName, attributionTag);
        int mode;
        if (raw == AppOpsManager.MODE_DEFAULT) {
            if (startIfModeDefault) {
                mode = getDefaultMode(code);
            } else {
                mode = AppOpsManager.MODE_DEFAULT;
            }
        } else {
            mode = raw;
        }

        if (mode == AppOpsManager.MODE_ALLOWED) {
            trackActive(clientId, code, uid, packageName);
            return new SyncNotedAppOp(AppOpsManager.MODE_ALLOWED, code, attributionTag, packageName);
        }
        return new SyncNotedAppOp(mode, code, attributionTag, packageName);
    }

    @Override
    public SyncNotedAppOp startOperationForDevice(IBinder clientId, int code, int uid,
            String packageName, String attributionTag, int virtualDeviceId,
            boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, String message,
            boolean shouldCollectMessage, int attributionFlags, int attributionChainId) {
        return startOperation(clientId, code, uid, packageName, attributionTag,
                startIfModeDefault, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                attributionFlags, attributionChainId);
    }

    @Override
    public void finishOperation(IBinder clientId, int code, int uid,
            String packageName, String attributionTag) {
        untrackActive(clientId, code, uid, packageName);
    }

    @Override
    public void finishOperationForDevice(IBinder clientId, int code, int uid,
            String packageName, String attributionTag, int virtualDeviceId) {
        finishOperation(clientId, code, uid, packageName, attributionTag);
    }

    @Override
    public boolean isOperationActive(int code, int uid, String packageName) {
        synchronized (this) {
            ActiveKey key = new ActiveKey(code, uid, packageName);
            Integer count = mActiveOps.get(key);
            return count != null && count > 0;
        }
    }

    private void trackActive(IBinder clientId, int code, int uid, String packageName) {
        synchronized (this) {
            ActiveKey key = new ActiveKey(code, uid, packageName);
            mActiveOps.put(key, mActiveOps.getOrDefault(key, 0) + 1);
            if (clientId != null) {
                ClientRecord record = mClients.get(clientId);
                if (record == null) {
                    record = new ClientRecord(clientId, this);
                    try {
                        clientId.linkToDeath(record, 0);
                    } catch (RemoteException e) {
                        mActiveOps.put(key, mActiveOps.get(key) - 1);
                        if (mActiveOps.get(key) <= 0) mActiveOps.remove(key);
                        return;
                    }
                    mClients.put(clientId, record);
                }
                record.activeCounts.put(key, record.activeCounts.getOrDefault(key, 0) + 1);
            }
        }
    }

    private void untrackActive(IBinder clientId, int code, int uid, String packageName) {
        synchronized (this) {
            ActiveKey key = new ActiveKey(code, uid, packageName);
            Integer count = mActiveOps.get(key);
            if (count != null) {
                if (count <= 1) {
                    mActiveOps.remove(key);
                } else {
                    mActiveOps.put(key, count - 1);
                }
            }
            if (clientId != null) {
                ClientRecord record = mClients.get(clientId);
                if (record != null) {
                    Integer c = record.activeCounts.get(key);
                    if (c != null) {
                        if (c <= 1) {
                            record.activeCounts.remove(key);
                        } else {
                            record.activeCounts.put(key, c - 1);
                        }
                    }
                    if (record.activeCounts.isEmpty()) {
                        mClients.remove(clientId);
                        try {
                            clientId.unlinkToDeath(record, 0);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    void onClientDied(ClientRecord record) {
        synchronized (this) {
            mClients.remove(record.clientId);
            for (Map.Entry<ActiveKey, Integer> entry : record.activeCounts.entrySet()) {
                ActiveKey key = entry.getKey();
                int count = entry.getValue();
                Integer total = mActiveOps.get(key);
                if (total != null) {
                    if (total <= count) {
                        mActiveOps.remove(key);
                    } else {
                        mActiveOps.put(key, total - count);
                    }
                }
            }
        }
        try {
            record.clientId.unlinkToDeath(record, 0);
        } catch (Exception ignored) {}
    }

    @Override
    public void startWatchingMode(int op, String packageName, IAppOpsCallback callback) {
        startWatchingModeWithFlags(op, packageName, 0, callback);
    }

    @Override
    public void startWatchingModeWithFlags(int op, String packageName, int flags,
            IAppOpsCallback callback) {
        if (callback == null) return;
        synchronized (this) {
            IBinder binder = callback.asBinder();
            ModeWatcher watcher = new ModeWatcher(op, packageName, flags, callback, this);
            if (binder != null) {
                try {
                    binder.linkToDeath(watcher, 0);
                } catch (RemoteException e) {
                    return;
                }
            }
            mModeWatchers.add(watcher);
        }
    }

    @Override
    public void stopWatchingMode(IAppOpsCallback callback) {
        if (callback == null) return;
        synchronized (this) {
            IBinder binder = callback.asBinder();
            Iterator<ModeWatcher> it = mModeWatchers.iterator();
            while (it.hasNext()) {
                ModeWatcher watcher = it.next();
                IBinder watcherBinder = watcher.callback.asBinder();
                boolean match = (binder != null && binder.equals(watcherBinder))
                        || (watcher.callback == callback);
                if (match) {
                    if (watcherBinder != null) {
                        try {
                            watcherBinder.unlinkToDeath(watcher, 0);
                        } catch (Exception ignored) {}
                    }
                    it.remove();
                }
            }
        }
    }

    void onWatcherDied(ModeWatcher watcher) {
        synchronized (this) {
            mModeWatchers.remove(watcher);
        }
        if (watcher.callback != null) {
            IBinder binder = watcher.callback.asBinder();
            if (binder != null) {
                try {
                    binder.unlinkToDeath(watcher, 0);
                } catch (Exception ignored) {}
            }
        }
    }

    private void notifyWatchers(int code, int uid, String packageName) {
        List<IAppOpsCallback> toNotify = new ArrayList<>();
        synchronized (this) {
            for (ModeWatcher watcher : mModeWatchers) {
                if (watcher.op == AppOpsManager.OP_NONE || watcher.op == code) {
                    if (watcher.packageName == null || packageName == null
                            || watcher.packageName.equals(packageName)) {
                        toNotify.add(watcher.callback);
                    }
                }
            }
        }
        for (IAppOpsCallback cb : toNotify) {
            try {
                cb.opChanged(code, uid, packageName, null);
            } catch (RemoteException ignored) {}
        }
    }

    @Override
    public void resetAllModes(int reqUserId, String reqPackageName) {
        synchronized (this) {
            if (reqPackageName != null) {
                Iterator<Map.Entry<PackageOpKey, Integer>> it = mPackageModes.entrySet().iterator();
                while (it.hasNext()) {
                    if (reqPackageName.equals(it.next().getKey().packageName)) {
                        it.remove();
                    }
                }
            } else {
                mPackageModes.clear();
                mUidModes.clear();
            }
            writeStateLocked();
        }
        try {
            AppOpsManager.invalidateAppOpModeCache();
        } catch (Throwable ignored) {}
        notifyWatchers(AppOpsManager.OP_NONE, 0, reqPackageName);
    }

    @Override
    public SyncNotedAppOp noteProxyOperation(int code, AttributionSource attributionSource,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyChecks) {
        int uid = attributionSource != null ? attributionSource.getUid() : Binder.getCallingUid();
        String packageName = attributionSource != null ? attributionSource.getPackageName() : null;
        String tag = attributionSource != null ? attributionSource.getAttributionTag() : null;
        return noteOperation(code, uid, packageName, tag, shouldCollectAsyncNotedOp, message,
                shouldCollectMessage);
    }

    @Override
    public SyncNotedAppOp noteProxyOperationWithState(int code, AttributionSourceState attributionSourceState,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyChecks) {
        int uid = attributionSourceState != null ? attributionSourceState.uid : Binder.getCallingUid();
        String packageName = attributionSourceState != null ? attributionSourceState.packageName : null;
        String tag = attributionSourceState != null ? attributionSourceState.attributionTag : null;
        return noteOperation(code, uid, packageName, tag, shouldCollectAsyncNotedOp, message,
                shouldCollectMessage);
    }

    @Override
    public SyncNotedAppOp startProxyOperation(IBinder clientId, int code,
            AttributionSource attributionSource, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyChecks, int proxyFlags, int attributionFlags, int attributionChainId) {
        int uid = attributionSource != null ? attributionSource.getUid() : Binder.getCallingUid();
        String packageName = attributionSource != null ? attributionSource.getPackageName() : null;
        String tag = attributionSource != null ? attributionSource.getAttributionTag() : null;
        return startOperation(clientId, code, uid, packageName, tag, startIfModeDefault,
                shouldCollectAsyncNotedOp, message, shouldCollectMessage, attributionFlags,
                attributionChainId);
    }

    @Override
    public SyncNotedAppOp startProxyOperationWithState(IBinder clientId, int code,
            AttributionSourceState attributionSourceState, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyChecks, int proxyFlags, int attributionFlags, int attributionChainId) {
        int uid = attributionSourceState != null ? attributionSourceState.uid : Binder.getCallingUid();
        String packageName = attributionSourceState != null ? attributionSourceState.packageName : null;
        String tag = attributionSourceState != null ? attributionSourceState.attributionTag : null;
        return startOperation(clientId, code, uid, packageName, tag, startIfModeDefault,
                shouldCollectAsyncNotedOp, message, shouldCollectMessage, attributionFlags,
                attributionChainId);
    }

    @Override
    public void finishProxyOperation(IBinder clientId, int code,
            AttributionSource attributionSource, boolean skipProxyChecks) {
        int uid = attributionSource != null ? attributionSource.getUid() : Binder.getCallingUid();
        String packageName = attributionSource != null ? attributionSource.getPackageName() : null;
        String tag = attributionSource != null ? attributionSource.getAttributionTag() : null;
        finishOperation(clientId, code, uid, packageName, tag);
    }

    @Override
    public void finishProxyOperationWithState(IBinder clientId, int code,
            AttributionSourceState attributionSourceState, boolean skipProxyChecks) {
        int uid = attributionSourceState != null ? attributionSourceState.uid : Binder.getCallingUid();
        String packageName = attributionSourceState != null ? attributionSourceState.packageName : null;
        String tag = attributionSourceState != null ? attributionSourceState.attributionTag : null;
        finishOperation(clientId, code, uid, packageName, tag);
    }

    @Override
    public List<AppOpsManager.PackageOps> getOpsForPackage(int uid, String packageName, int[] ops) {
        if (packageName == null) return Collections.emptyList();
        List<AppOpsManager.OpEntry> entries = new ArrayList<>();
        synchronized (this) {
            if (ops != null) {
                for (int op : ops) {
                    PackageOpKey key = new PackageOpKey(packageName, op);
                    Integer mode = mPackageModes.get(key);
                    if (mode != null) {
                        entries.add(new AppOpsManager.OpEntry(op, mode, Collections.emptyMap()));
                    }
                }
            } else {
                for (Map.Entry<PackageOpKey, Integer> entry : mPackageModes.entrySet()) {
                    if (packageName.equals(entry.getKey().packageName)) {
                        entries.add(new AppOpsManager.OpEntry(entry.getKey().op, entry.getValue(),
                                Collections.emptyMap()));
                    }
                }
            }
        }
        if (entries.isEmpty()) return Collections.emptyList();
        return Collections.singletonList(new AppOpsManager.PackageOps(packageName, uid, entries));
    }

    @Override
    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        Map<String, List<AppOpsManager.OpEntry>> byPkg = new HashMap<>();
        synchronized (this) {
            for (Map.Entry<PackageOpKey, Integer> entry : mPackageModes.entrySet()) {
                int op = entry.getKey().op;
                boolean match = (ops == null);
                if (ops != null) {
                    for (int o : ops) {
                        if (o == op) { match = true; break; }
                    }
                }
                if (match) {
                    byPkg.computeIfAbsent(entry.getKey().packageName, k -> new ArrayList<>())
                            .add(new AppOpsManager.OpEntry(op, entry.getValue(), Collections.emptyMap()));
                }
            }
        }
        List<AppOpsManager.PackageOps> result = new ArrayList<>();
        for (Map.Entry<String, List<AppOpsManager.OpEntry>> entry : byPkg.entrySet()) {
            result.add(new AppOpsManager.PackageOps(entry.getKey(), 0, entry.getValue()));
        }
        return result;
    }

    @Override
    public ParceledListSlice<AppOpsManager.PackageOps> getPackagesForOpsForDevice(int[] ops,
            String persistentDeviceId) {
        return new ParceledListSlice<>(getPackagesForOps(ops));
    }

    @Override
    public List<AppOpsManager.PackageOps> getUidOps(int uid, int[] ops) {
        List<AppOpsManager.OpEntry> entries = new ArrayList<>();
        synchronized (this) {
            if (ops != null) {
                for (int op : ops) {
                    UidOpKey key = new UidOpKey(uid, op);
                    Integer mode = mUidModes.get(key);
                    if (mode != null) {
                        entries.add(new AppOpsManager.OpEntry(op, mode, Collections.emptyMap()));
                    }
                }
            } else {
                for (Map.Entry<UidOpKey, Integer> entry : mUidModes.entrySet()) {
                    if (entry.getKey().uid == uid) {
                        entries.add(new AppOpsManager.OpEntry(entry.getKey().op, entry.getValue(),
                                Collections.emptyMap()));
                    }
                }
            }
        }
        if (entries.isEmpty()) return Collections.emptyList();
        return Collections.singletonList(new AppOpsManager.PackageOps(null, uid, entries));
    }

    @Override
    public boolean shouldCollectNotes(int op) {
        return false;
    }
}
