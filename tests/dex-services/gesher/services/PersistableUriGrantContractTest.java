/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package gesher.services;

import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.IUriGrantsManager;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.pkg.component.ParsedPermissionImpl;
import com.android.internal.pm.pkg.component.ParsedProviderImpl;
import com.gesher.server.ActivityManagerService;
import com.gesher.server.ActivityTaskManagerService;
import com.gesher.server.ApkPackage;
import com.gesher.server.CallerIdentity;
import com.gesher.server.PackageManagerService;
import com.gesher.server.SharedLibraryGraph;
import com.gesher.server.StandIn;
import com.gesher.server.UriGrants;
import com.gesher.server.UriGrantsManagerService;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sun.misc.Unsafe;

/** Contract tests for F11: Persistable URI Grants Across Runtime Lifecycles. */
public final class PersistableUriGrantContractTest {
    private static final String OWNER = "fixture.uri.owner";
    private static final String CLIENT = "fixture.uri.client";
    private static final String OTHER = "fixture.uri.other";
    private static final int OWNER_UID = 10100;
    private static final int CLIENT_UID = 10200;
    private static final int OTHER_UID = 10300;
    private static final String AUTHORITY = "fixture.uri.provider";
    private static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    private static final int WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final int PERSISTABLE = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;

    private static Unsafe unsafe;
    private static int checks = 0;

    private interface Operation { void run() throws Exception; }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class ThreadToken extends IApplicationThread.Default {
        private final IBinder token = new Binder();
        @Override public IBinder asBinder() { return token; }
    }

    private static ApkPackage createPackage(String name, int uid, boolean withProvider) throws Exception {
        PackageImpl parsed = (PackageImpl) PackageImpl.forTesting(name, "/fixture/" + name + "/base.apk");
        parsed.setTargetSdkVersion(36);
        parsed.setEnabled(true);
        if (withProvider) {
            ParsedPermissionImpl rPerm = new ParsedPermissionImpl();
            rPerm.setName(name + ".READ"); rPerm.setPackageName(name);
            rPerm.setProtectionLevel(PermissionInfo.PROTECTION_NORMAL); parsed.addPermission(rPerm);
            ParsedPermissionImpl wPerm = new ParsedPermissionImpl();
            wPerm.setName(name + ".WRITE"); wPerm.setPackageName(name);
            wPerm.setProtectionLevel(PermissionInfo.PROTECTION_NORMAL); parsed.addPermission(wPerm);

            ParsedProviderImpl provider = new ParsedProviderImpl();
            provider.setName(name + ".Provider");
            provider.setPackageName(name);
            provider.setEnabled(true);
            provider.setExported(true);
            provider.setAuthority(AUTHORITY);
            provider.setGrantUriPermissions(true);
            provider.setReadPermission(name + ".READ");
            provider.setWritePermission(name + ".WRITE");
            parsed.addProvider(provider);
        }
        parsed.hideAsFinal();
        ApkPackage code = (ApkPackage) unsafe.allocateInstance(ApkPackage.class);
        set(code, "packageName", name);
        set(code, "parsed", parsed);
        set(code, "mUid", uid);
        set(code, "apkPath", "/fixture/" + name + "/base.apk");
        set(code, "mDataDir", "/fixture/" + name + "/data");
        set(code, "sharedLibraries", SharedLibraryGraph.EMPTY);
        return code;
    }

    private static final class Harness {
        final Map<String, ApkPackage> records = new LinkedHashMap<>();
        final Map<String, ThreadToken> threads = new LinkedHashMap<>();
        final Map<String, Integer> pids = new LinkedHashMap<>();
        final File stateFile;
        PackageManagerService pm;
        ActivityTaskManagerService atm;
        UriGrants grants;
        ActivityManagerService amService;
        IActivityManager am;
        UriGrantsManagerService ugmService;
        IUriGrantsManager ugm;

        Harness(File stateFile) throws Exception {
            this.stateFile = stateFile;
            records.put(OWNER, createPackage(OWNER, OWNER_UID, true));
            records.put(CLIENT, createPackage(CLIENT, CLIENT_UID, false));
            records.put(OTHER, createPackage(OTHER, OTHER_UID, false));

            pm = (PackageManagerService) unsafe.allocateInstance(PackageManagerService.class);
            set(pm, "mPackages", new LinkedHashMap<>(records));
            set(pm, "mRemoving", new LinkedHashMap<String, ApkPackage>());
            set(pm, "mPackageRemoval", new ArrayList<>());
            Class<?> policy = Class.forName("com.gesher.server.PermissionPolicy");
            Constructor<?> policyConstructor = policy.getDeclaredConstructor(PackageManagerService.class);
            policyConstructor.setAccessible(true);
            set(pm, "mPermissions", policyConstructor.newInstance(pm));

            atm = (ActivityTaskManagerService) unsafe.allocateInstance(ActivityTaskManagerService.class);
            set(atm, "mPm", pm);
            List<Object> processes = new ArrayList<>();
            set(atm, "mProcesses", processes);
            Class<?> processType = Class.forName("com.gesher.server.ActivityTaskManagerService$ProcessRecord");
            int pid = 911001;
            for (String name : records.keySet()) {
                ThreadToken thread = new ThreadToken(); threads.put(name, thread); pids.put(name, pid);
                Object process = unsafe.allocateInstance(processType);
                set(process, "pid", pid++); set(process, "uid", records.get(name).uid());
                set(process, "thread", thread); set(process, "packageName", name);
                set(process, "processName", name); set(process, "ready", true);
                processes.add(process);
            }

            grants = new UriGrants(pm, atm, stateFile);
            Object providers = unsafe.allocateInstance(Class.forName("com.gesher.server.ContentProviders"));
            set(providers, "mPm", pm); set(providers, "mUriGrants", grants);
            amService = (ActivityManagerService) unsafe.allocateInstance(ActivityManagerService.class);
            set(amService, "mPm", pm); set(amService, "mAtm", atm); set(amService, "mProviders", providers);
            set(amService, "mCallers", new CallerIdentity(pm, atm));

            am = amService;
            ugmService = new UriGrantsManagerService(pm, atm, grants);
            ugm = ugmService;
        }

        void as(String name, Operation operation) throws Exception {
            long previous = Binder.clearCallingIdentity();
            try {
                Binder.restoreCallingIdentity(((long) records.get(name).uid() << 32) | pids.get(name));
                operation.run();
            } finally {
                Binder.restoreCallingIdentity(previous);
            }
        }

        void grant(String sender, String target, Uri uri, int modes) throws Exception {
            as(sender, () -> am.grantUriPermission(threads.get(sender), target, uri, modes, 0));
        }

        void take(String caller, Uri uri, int modes) throws Exception {
            as(caller, () -> ugm.takePersistableUriPermission(uri, modes, caller, 0));
        }

        void release(String caller, Uri uri, int modes) throws Exception {
            as(caller, () -> ugm.releasePersistableUriPermission(uri, modes, caller, 0));
        }

        @SuppressWarnings("unchecked")
        List<UriPermission> getPersisted(String caller, boolean incoming) throws Exception {
            final List<UriPermission>[] holder = new List[1];
            as(caller, () -> holder[0] = (List<UriPermission>) ugm.getUriPermissions(caller, incoming, true).getList());
            return holder[0];
        }

        boolean canAccess(String target, Uri uri, int modes) {
            return grants.canAccess(records.get(target), uri, modes, 0);
        }

        void rejected(Class<? extends Throwable> expected, Operation op) {
            try {
                op.run();
                throw new AssertionError("Expected " + expected.getSimpleName() + " was not thrown");
            } catch (Throwable t) {
                if (expected.isInstance(t)) {
                    checks++;
                } else if (t instanceof InvocationTargetException && expected.isInstance(t.getCause())) {
                    checks++;
                } else {
                    throw new AssertionError("Wrong exception: expected " + expected + ", got " + t, t);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        contracts();
    }

    public static void contracts() throws Exception {
        try {
            System.loadLibrary("android_runtime");
            Class<?> shared = Class.forName("com.android.internal.os.ApplicationSharedMemory");
            Object region = shared.getMethod("create").invoke(null);
            shared.getMethod("setInstance", shared).invoke(null, region);
        } catch (Throwable ignored) {
        }

        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        unsafe = (Unsafe) unsafeField.get(null);

        File stateFile = new File(System.getProperty("java.io.tmpdir"), "urigrants-f11-test-" + System.nanoTime() + ".xml");
        stateFile.deleteOnExit();

        Harness h = new Harness(stateFile);
        Uri uri1 = Uri.parse("content://" + AUTHORITY + "/item1");
        Uri uri2 = Uri.parse("content://" + AUTHORITY + "/item2");
        Uri uriTransient = Uri.parse("content://" + AUTHORITY + "/transient");
        Uri uriPersisted = Uri.parse("content://" + AUTHORITY + "/persisted");

        // 1. Test taking persistable grant without FLAG_GRANT_PERSISTABLE_URI_PERMISSION -> SecurityException.
        h.grant(OWNER, CLIENT, uri1, READ);
        check(h.canAccess(CLIENT, uri1, READ), "Client has transient read access to uri1");
        check(h.getPersisted(CLIENT, true).isEmpty(), "No persisted grants exist yet for CLIENT");

        h.rejected(SecurityException.class, () -> h.take(CLIENT, uri1, READ));
        check(h.getPersisted(CLIENT, true).isEmpty(), "Persisted grant was not created after rejected take");

        // 2. Test taking persistable grant with flag -> succeeds and appears in getPersistedUriPermissions.
        h.grant(OWNER, CLIENT, uri2, READ | PERSISTABLE);
        check(h.canAccess(CLIENT, uri2, READ), "Client has read access to uri2");
        check(h.getPersisted(CLIENT, true).isEmpty(), "Grant is not persisted before takePersistableUriPermission");

        // Validation checks on takePersistableUriPermission
        h.rejected(IllegalArgumentException.class, () -> h.take(CLIENT, uri2, 0));
        h.rejected(IllegalArgumentException.class, () -> h.take(CLIENT, uri2, PERSISTABLE));
        h.rejected(IllegalArgumentException.class, () -> h.take(CLIENT, uri2, READ | 0x40000000));
        h.rejected(SecurityException.class, () -> h.take(CLIENT, uri2, WRITE));
        h.rejected(SecurityException.class, () -> h.take(OTHER, uri2, READ));

        // Successful take
        h.take(CLIENT, uri2, READ);
        List<UriPermission> clientIncoming = h.getPersisted(CLIENT, true);
        check(clientIncoming.size() == 1, "Exactly one persisted grant found for CLIENT");
        UriPermission p1 = clientIncoming.get(0);
        check(uri2.equals(p1.getUri()), "Persisted permission URI matches");
        check(p1.isReadPermission(), "Persisted permission is read");
        check(!p1.isWritePermission(), "Persisted permission is not write");
        check(p1.getPersistedTime() != UriPermission.INVALID_TIME, "Persisted permission timestamp is valid");

        // Outgoing persisted permission from OWNER perspective
        List<UriPermission> ownerOutgoing = h.getPersisted(OWNER, false);
        check(ownerOutgoing.size() == 1, "Owner sees outgoing persisted grant");
        check(uri2.equals(ownerOutgoing.get(0).getUri()), "Owner outgoing URI matches");

        // 3. Test releasing persistable grant -> removed from list.
        h.release(CLIENT, uri2, READ);
        check(h.getPersisted(CLIENT, true).isEmpty(), "Persisted grant removed from list after release");
        check(h.getPersisted(OWNER, false).isEmpty(), "Persisted grant removed from owner outgoing list");

        // Releasing again throws SecurityException (not found)
        h.rejected(SecurityException.class, () -> h.release(CLIENT, uri2, READ));

        // 4. Test server restart (simulate reload from file) -> persistable grant restored.
        // 5. Test non-persistable transient grants are NOT restored across restart.
        h.grant(OWNER, CLIENT, uriTransient, READ);
        h.grant(OWNER, CLIENT, uriPersisted, READ | WRITE | PERSISTABLE);
        h.take(CLIENT, uriPersisted, READ | WRITE);

        check(h.canAccess(CLIENT, uriTransient, READ), "Client has access to transient URI before restart");
        check(h.canAccess(CLIENT, uriPersisted, READ | WRITE), "Client has access to persisted URI before restart");
        check(h.getPersisted(CLIENT, true).size() == 1, "Persisted grant registered before restart");

        // Simulate server shutdown and restart by creating a new Harness reusing the state file.
        Harness hRestarted = new Harness(stateFile);
        check(!hRestarted.canAccess(CLIENT, uriTransient, READ),
                "Non-persistable transient grant is NOT restored across restart");
        check(hRestarted.canAccess(CLIENT, uriPersisted, READ | WRITE),
                "Persistable grant is restored into active grant table across restart");

        List<UriPermission> restoredList = hRestarted.getPersisted(CLIENT, true);
        check(restoredList.size() == 1, "Restored persisted grant list contains exactly 1 entry");
        UriPermission restoredPerm = restoredList.get(0);
        check(uriPersisted.equals(restoredPerm.getUri()), "Restored grant URI matches");
        check(restoredPerm.isReadPermission(), "Restored grant is read");
        check(restoredPerm.isWritePermission(), "Restored grant is write");
        check(restoredPerm.getPersistedTime() != UriPermission.INVALID_TIME, "Restored grant timestamp is valid");

        // Releasing on the restarted server removes it from memory, list and disk
        hRestarted.release(CLIENT, uriPersisted, READ | WRITE);
        check(hRestarted.getPersisted(CLIENT, true).isEmpty(), "Restored grant removed after release");
        check(!hRestarted.canAccess(CLIENT, uriPersisted, READ | WRITE),
                "Access is revoked after releasing restored persistable grant");

        // Second reload to verify release was persisted to disk
        Harness hSecondRestart = new Harness(stateFile);
        check(hSecondRestart.getPersisted(CLIENT, true).isEmpty(),
                "Released grant remains gone across subsequent restart");
        check(!hSecondRestart.canAccess(CLIENT, uriPersisted, READ | WRITE),
                "Access remains revoked across subsequent restart");

        stateFile.delete();
        System.out.println("PersistableUriGrantContractTest all passed: " + checks + " checks");
    }
}
