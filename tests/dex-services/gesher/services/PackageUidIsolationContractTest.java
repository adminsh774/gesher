/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package gesher.services;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Process;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.pkg.component.ParsedPermissionImpl;
import com.android.internal.pm.pkg.component.ParsedUsesPermissionImpl;
import com.gesher.server.ApkPackage;
import com.gesher.server.CallerIdentity;
import com.gesher.server.PackageManagerService;
import com.gesher.server.PackageUids;
import com.gesher.server.PermissionPolicy;
import com.gesher.server.SharedLibraryGraph;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import sun.misc.Unsafe;

/** Contract tests for Roadmap Item F07: Per-Package Identity & UID Isolation. */
public final class PackageUidIsolationContractTest {
    private static Unsafe unsafe;
    private static int checks = 0;

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

    private static ApkPackage createPackage(String name, int uid, String sharedUserId,
            String... permissions) throws Exception {
        PackageImpl parsed = (PackageImpl) PackageImpl.forTesting(name, "/fixture/" + name + "/base.apk");
        if (sharedUserId != null && !sharedUserId.isEmpty()) {
            parsed.setSharedUserId(sharedUserId);
        }
        if (permissions != null) {
            for (String p : permissions) {
                ParsedPermissionImpl perm = new ParsedPermissionImpl();
                perm.setName(p);
                perm.setPackageName(name);
                perm.setProtectionLevel(PermissionInfo.PROTECTION_DANGEROUS);
                parsed.addPermission(perm);
                parsed.addUsesPermission(new ParsedUsesPermissionImpl(p, 0, Set.of()));
            }
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

    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime");
        String root = System.getenv("ANDROID_ROOT");
        if (root != null) {
            try {
                android.content.res.AssetManager.createSystemAssetsInZygoteLocked(false,
                        root + "/framework/framework-res.apk");
            } catch (Throwable ignored) {}
        }
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (Unsafe) f.get(null);

        File tempDir = Files.createTempDirectory("gesher-uid-test-").toFile();
        try {
            android.system.Os.setenv("ANDROID_DATA", tempDir.getAbsolutePath(), true);
            // Verify default non-GMS mode still performs distinct UID allocation
            android.system.Os.setenv("GESHER_MICROG_SPOOF", "0", true);

            // Test 1: Distinct UID allocation for standard packages
            int uid1 = PackageUids.uidFor("com.example.app1");
            int uid2 = PackageUids.uidFor("com.example.app2");
            int uid3 = PackageUids.uidFor("com.example.app3");

            check(uid1 >= 10000 && uid1 <= 19999, "uid1 must be in application UID range [10000, 19999]");
            check(uid2 >= 10000 && uid2 <= 19999, "uid2 must be in application UID range [10000, 19999]");
            check(uid3 >= 10000 && uid3 <= 19999, "uid3 must be in application UID range [10000, 19999]");
            check(uid1 != uid2, "Two different standard packages must get distinct UIDs");
            check(uid1 != uid3 && uid2 != uid3, "All three standard packages must get distinct UIDs");

            // Test 2: Stability of allocated UIDs across subsequent calls
            check(PackageUids.uidFor("com.example.app1") == uid1, "UID allocation for app1 must be stable");
            check(PackageUids.uidFor("com.example.app2") == uid2, "UID allocation for app2 must be stable");
            check(PackageUids.uidFor("com.example.app3") == uid3, "UID allocation for app3 must be stable");

            // Test 3: Shared UID allocation with android:sharedUserId
            String sharedGroup = "com.example.shared.group";
            int sharedUid1 = PackageUids.uidFor("com.example.shared.a", sharedGroup);
            int sharedUid2 = PackageUids.uidFor("com.example.shared.b", sharedGroup);

            check(sharedUid1 >= 10000 && sharedUid1 <= 19999, "Shared UID must be in application UID range");
            check(sharedUid1 == sharedUid2, "Packages with identical sharedUserId must share the same UID");
            check(sharedUid1 != uid1 && sharedUid1 != uid2 && sharedUid1 != uid3,
                    "Shared UID must be distinct from standard package UIDs");
            check(PackageUids.uidFor("com.example.shared.a") == sharedUid1, "Shared app a allocation must be stable");
            check(PackageUids.uidFor("com.example.shared.b") == sharedUid2, "Shared app b allocation must be stable");

            // Test 4: Second distinct shared group gets a different shared UID
            String otherGroup = "com.example.other.group";
            int otherSharedUid = PackageUids.uidFor("com.example.other.c", otherGroup);
            check(otherSharedUid != sharedUid1, "Different sharedUserId groups must receive distinct UIDs");

            // Test 5: Durability across registry reopening
            File registryFile = new File(tempDir, "system/package-uids");
            check(registryFile.isFile(), "package-uids registry file must exist");
            Properties persisted = new Properties();
            try (FileInputStream in = new FileInputStream(registryFile)) { persisted.load(in); }
            check(Integer.toString(uid1).equals(persisted.getProperty("com.example.app1")),
                    "app1 must be persisted in package-uids");
            check(Integer.toString(sharedUid1).equals(persisted.getProperty("com.example.shared.a")),
                    "shared app a must be persisted in package-uids");
            check(Integer.toString(sharedUid1).equals(persisted.getProperty("com.example.shared.b")),
                    "shared app b must be persisted in package-uids");
            check(Integer.toString(sharedUid1).equals(persisted.getProperty("shared:" + sharedGroup)),
                    "shared group mapping must be persisted");

            // Test 6: PackageManagerService integration: getPackageUid & getPackagesForUid
            String perm = "android.permission.ACCESS_FINE_LOCATION";
            ApkPackage pkg1 = createPackage("com.example.app1", uid1, null, perm);
            ApkPackage pkg2 = createPackage("com.example.app2", uid2, null, perm);
            ApkPackage pkgSharedA = createPackage("com.example.shared.a", sharedUid1, sharedGroup, perm);
            ApkPackage pkgSharedB = createPackage("com.example.shared.b", sharedUid1, sharedGroup, perm);

            List<ApkPackage> allPackages = Arrays.asList(pkg1, pkg2, pkgSharedA, pkgSharedB);
            PackageManagerService pms = new PackageManagerService(allPackages);

            // getPackageUid tests
            check(pms.getPackageUid("com.example.app1", 0, 0) == uid1, "getPackageUid for app1 must return uid1");
            check(pms.getPackageUid("com.example.app2", 0, 0) == uid2, "getPackageUid for app2 must return uid2");
            check(pms.getPackageUid("com.example.shared.a", 0, 0) == sharedUid1,
                    "getPackageUid for shared app a must return sharedUid1");
            check(pms.getPackageUid("com.example.shared.b", 0, 0) == sharedUid1,
                    "getPackageUid for shared app b must return sharedUid1");
            check(pms.getPackageUid("com.nonexistent.package", 0, 0) == -1,
                    "getPackageUid for nonexistent package must return -1");

            // getPackagesForUid tests
            String[] pkgsForUid1 = pms.getPackagesForUid(uid1);
            check(pkgsForUid1 != null && pkgsForUid1.length == 1 && "com.example.app1".equals(pkgsForUid1[0]),
                    "getPackagesForUid for uid1 must return only app1");

            String[] pkgsForUid2 = pms.getPackagesForUid(uid2);
            check(pkgsForUid2 != null && pkgsForUid2.length == 1 && "com.example.app2".equals(pkgsForUid2[0]),
                    "getPackagesForUid for uid2 must return only app2");

            String[] pkgsForShared = pms.getPackagesForUid(sharedUid1);
            check(pkgsForShared != null && pkgsForShared.length == 2,
                    "getPackagesForUid for shared UID must return both packages");
            Set<String> sharedSet = new HashSet<>(Arrays.asList(pkgsForShared));
            check(sharedSet.contains("com.example.shared.a") && sharedSet.contains("com.example.shared.b"),
                    "getPackagesForUid for shared UID must contain both shared.a and shared.b");

            check(pms.getPackagesForUid(19998) == null,
                    "getPackagesForUid for unused UID must return null");

            // Test 7: System packages retain system UIDs
            check(pms.getPackageUid("android", 0, 0) == Process.SYSTEM_UID,
                    "Platform package 'android' must retain SYSTEM_UID (1000)");
            String[] systemPkgs = pms.getPackagesForUid(Process.SYSTEM_UID);
            check(systemPkgs != null && systemPkgs.length == 1 && "android".equals(systemPkgs[0]),
                    "getPackagesForUid(SYSTEM_UID) must return 'android'");
            check("android.uid.system".equals(pms.getNameForUid(Process.SYSTEM_UID)),
                    "getNameForUid(SYSTEM_UID) must return 'android.uid.system'");

            check(pms.getPackageUid("com.android.shell", 0, 0) == Process.SHELL_UID,
                    "Shell package 'com.android.shell' must retain SHELL_UID (2000)");
            String[] shellPkgs = pms.getPackagesForUid(Process.SHELL_UID);
            check(shellPkgs != null && shellPkgs.length == 1 && "com.android.shell".equals(shellPkgs[0]),
                    "getPackagesForUid(SHELL_UID) must return 'com.android.shell'");
            check("com.android.shell".equals(pms.getNameForUid(Process.SHELL_UID)),
                    "getNameForUid(SHELL_UID) must return 'com.android.shell'");

            // Test 8: getNameForUid for single vs shared UID
            check("com.example.app1".equals(pms.getNameForUid(uid1)),
                    "getNameForUid for single app must return package name");
            check(sharedGroup.equals(pms.getNameForUid(sharedUid1)),
                    "getNameForUid for shared UID must return sharedUserId");

            String[] namesForUids = pms.getNamesForUids(new int[] {Process.SYSTEM_UID, uid1, sharedUid1});
            check(namesForUids != null && namesForUids.length == 3, "getNamesForUids must return 3 results");
            check("shared:android.uid.system".equals(namesForUids[0]), "Positional system UID must be shared:android.uid.system");
            check("com.example.app1".equals(namesForUids[1]), "Positional app1 name must match");
            check(sharedGroup.equals(namesForUids[2]), "Positional shared group name must match");

            // Test 9: Permission isolation across distinct UIDs and sharing within sharedUserId
            android.system.Os.setenv("GESHER_MICROG_SPOOF", "1", true);
            PermissionPolicy policy = pms.permissions();
            // Initially, permissions are not granted
            check(policy.checkUidPermission(uid1, perm, 0) == PackageManager.PERMISSION_DENIED,
                    "uid1 must initially have permission denied");
            check(policy.checkUidPermission(uid2, perm, 0) == PackageManager.PERMISSION_DENIED,
                    "uid2 must initially have permission denied");
            check(policy.checkUidPermission(sharedUid1, perm, 0) == PackageManager.PERMISSION_DENIED,
                    "sharedUid1 must initially have permission denied");

            // Grant permission to shared app a
            policy.grantRuntime("com.example.shared.a", perm, 0);

            // Both shared packages and their UID must now be granted
            check(policy.checkUidPermission(sharedUid1, perm, 0) == PackageManager.PERMISSION_GRANTED,
                    "sharedUid1 must have permission granted after grant to shared.a");
            check(policy.checkPermission("com.example.shared.a", perm) == PackageManager.PERMISSION_GRANTED,
                    "shared.a must have permission granted");
            check(policy.checkPermission("com.example.shared.b", perm) == PackageManager.PERMISSION_GRANTED,
                    "shared.b must inherit permission granted to shared.a under same sharedUserId");

            // Standard packages uid1 and uid2 MUST REMAIN DENIED (isolation!)
            check(policy.checkUidPermission(uid1, perm, 0) == PackageManager.PERMISSION_DENIED,
                    "uid1 MUST REMAIN DENIED when permission was granted to shared group (isolation)");
            check(policy.checkUidPermission(uid2, perm, 0) == PackageManager.PERMISSION_DENIED,
                    "uid2 MUST REMAIN DENIED when permission was granted to shared group (isolation)");
            check(policy.checkPermission("com.example.app1", perm) == PackageManager.PERMISSION_DENIED,
                    "app1 must remain denied (isolation)");
            check(policy.checkPermission("com.example.app2", perm) == PackageManager.PERMISSION_DENIED,
                    "app2 must remain denied (isolation)");

            // Grant permission to app1
            policy.grantRuntime("com.example.app1", perm, 0);
            check(policy.checkUidPermission(uid1, perm, 0) == PackageManager.PERMISSION_GRANTED,
                    "uid1 must be granted after grant to app1");
            check(policy.checkPermission("com.example.app1", perm) == PackageManager.PERMISSION_GRANTED,
                    "app1 must be granted");
            // app2 MUST STILL BE DENIED (isolation between app1 and app2!)
            check(policy.checkUidPermission(uid2, perm, 0) == PackageManager.PERMISSION_DENIED,
                    "uid2 MUST REMAIN DENIED when permission was granted to app1 (isolation)");
            check(policy.checkPermission("com.example.app2", perm) == PackageManager.PERMISSION_DENIED,
                    "app2 must remain denied when app1 was granted (isolation)");

            // Test 10: Registry corruption detection
            Properties corrupt = new Properties();
            try (FileInputStream in = new FileInputStream(registryFile)) { corrupt.load(in); }
            // Add a duplicate non-shared package entry with same UID as app1
            corrupt.setProperty("com.attacker.duplicate", Integer.toString(uid1));
            try (FileOutputStream out = new FileOutputStream(registryFile)) { corrupt.store(out, "corrupt"); }

            boolean rejected = false;
            try {
                PackageUids.uidFor("com.example.should.fail");
            } catch (IllegalStateException expected) {
                rejected = (expected.getMessage() != null && expected.getMessage().contains("Corrupt package UID registry"))
                        || (expected.getCause() != null && expected.getCause().getMessage() != null && expected.getCause().getMessage().contains("Corrupt package UID registry"));
            }
            check(rejected, "Corrupt UID registry with conflicting non-shared UIDs must fail closed");

            System.out.println("PackageUidIsolationContractTest passed: " + checks + " checks");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }
}
