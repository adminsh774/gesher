/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.gesher.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Stable Android app identities. This is Binder identity, not a Windows sandbox. */
public final class PackageUids {
    private PackageUids() { }

    public static synchronized int uidFor(String packageName) {
        return uidFor(packageName, null);
    }

    public static synchronized int uidFor(String packageName, String sharedUserId) {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("Missing package name for UID allocation");
        }
        File directory = new File(Device.dataDir(), "system");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create the package identity directory");
        }
        File registry = new File(directory, "package-uids");
        try (FileOutputStream lockFile = new FileOutputStream(
                    new File(directory, "package-uids.lock"), true);
                FileChannel channel = lockFile.getChannel(); FileLock lock = channel.lock()) {
            Properties ids = new Properties();
            if (registry.isFile()) {
                try (FileInputStream input = new FileInputStream(registry)) { ids.load(input); }
            }

            Map<String, Integer> pkgUids = new HashMap<>();
            Map<String, String> pkgToShared = new HashMap<>();
            Map<String, Integer> sharedGroupUids = new HashMap<>();
            Set<Integer> nonSharedUids = new HashSet<>();
            Set<Integer> sharedUids = new HashSet<>();
            int next = 10000;

            for (String key : ids.stringPropertyNames()) {
                String val = ids.getProperty(key);
                if (key.startsWith("shared-pkg:")) {
                    pkgToShared.put(key.substring("shared-pkg:".length()), val);
                } else if (key.startsWith("shared:")) {
                    int uid = Integer.parseInt(val);
                    if (uid < 10000 || uid > 19999 || !sharedUids.add(uid)) {
                        throw new IllegalStateException("Corrupt package UID registry");
                    }
                    sharedGroupUids.put(key.substring("shared:".length()), uid);
                    next = Math.max(next, uid + 1);
                } else {
                    int uid = Integer.parseInt(val);
                    if (uid < 10000 || uid > 19999) {
                        throw new IllegalStateException("Corrupt package UID registry");
                    }
                    pkgUids.put(key, uid);
                    next = Math.max(next, uid + 1);
                }
            }

            // Consistency checks: shared packages must match their shared group;
            // non-shared packages must not conflict with any other non-shared or shared UID.
            for (Map.Entry<String, Integer> entry : pkgUids.entrySet()) {
                String pkg = entry.getKey();
                int uid = entry.getValue();
                if (pkgToShared.containsKey(pkg)) {
                    String sId = pkgToShared.get(pkg);
                    Integer groupUid = sharedGroupUids.get(sId);
                    if (groupUid == null || groupUid != uid) {
                        throw new IllegalStateException("Corrupt package UID registry");
                    }
                } else {
                    if (sharedUids.contains(uid) || !nonSharedUids.add(uid)) {
                        throw new IllegalStateException("Corrupt package UID registry");
                    }
                }
            }

            if (ids.containsKey(packageName)) {
                return Integer.parseInt(ids.getProperty(packageName));
            }

            int allocatedUid;
            boolean hasShared = sharedUserId != null && !sharedUserId.trim().isEmpty();
            if (hasShared) {
                String trimmedShared = sharedUserId.trim();
                if (sharedGroupUids.containsKey(trimmedShared)) {
                    allocatedUid = sharedGroupUids.get(trimmedShared);
                } else {
                    if (next > 19999) throw new IllegalStateException("No application UIDs available");
                    allocatedUid = next;
                    sharedGroupUids.put(trimmedShared, allocatedUid);
                    ids.setProperty("shared:" + trimmedShared, Integer.toString(allocatedUid));
                }
                ids.setProperty("shared-pkg:" + packageName, trimmedShared);
                ids.setProperty(packageName, Integer.toString(allocatedUid));
            } else {
                if (next > 19999) throw new IllegalStateException("No application UIDs available");
                allocatedUid = next;
                ids.setProperty(packageName, Integer.toString(allocatedUid));
            }

            File temporary = File.createTempFile("package-uids-", ".tmp", directory);
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    ids.store(output, "Gesher stable package identities; never reuse allocated UIDs");
                    output.getFD().sync();
                }
                try {
                    Files.move(temporary.toPath(), registry.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unavailable) {
                    Files.move(temporary.toPath(), registry.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                if (temporary.exists()) temporary.delete();
            }
            return allocatedUid;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot allocate a persistent application UID", failure);
        }
    }
}
