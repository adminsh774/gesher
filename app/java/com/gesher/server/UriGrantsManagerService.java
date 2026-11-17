/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.gesher.server;

import android.app.IUriGrantsManager;
import android.content.pm.ParceledListSlice;
import android.net.Uri;
import android.os.IBinder;

/** Explicit API outcomes for persistence/owner tokens beyond the ephemeral AM grants. */
public final class UriGrantsManagerService extends IUriGrantsManager.Default {
    private final CallerIdentity mCallers;
    private final UriGrants mGrants;

    public UriGrantsManagerService(PackageManagerService pm, ActivityTaskManagerService atm,
            ActivityManagerService am) {
        this(pm, atm, am != null ? am.uriGrants() : null);
    }

    public UriGrantsManagerService(PackageManagerService pm, ActivityTaskManagerService atm,
            UriGrants uriGrants) {
        mCallers = new CallerIdentity(pm, atm);
        mGrants = uriGrants;
        if (mGrants != null) {
            mGrants.load();
        }
    }

    private void user(int userId) {
        if (userId != 0) throw new SecurityException("Only user 0 is available");
    }
    private void host() {
        if (!mCallers.isSystem()) throw new SecurityException("URI administration requires the host");
    }
    private void target(String packageName) {
        if (!mCallers.isSystem()) mCallers.requirePackage(packageName);
    }
    @Override public void takePersistableUriPermission(Uri uri, int modes, String packageName, int userId) {
        user(userId);
        if (packageName == null) {
            if (!mCallers.isSystem()) packageName = mCallers.callerPackage();
        } else {
            target(packageName);
        }
        mGrants.takePersistable(packageName, uri, modes, userId);
    }
    @Override public void releasePersistableUriPermission(Uri uri, int modes, String packageName, int userId) {
        user(userId);
        if (packageName == null) {
            if (!mCallers.isSystem()) packageName = mCallers.callerPackage();
        } else {
            target(packageName);
        }
        mGrants.releasePersistable(packageName, uri, modes, userId);
    }
    @Override public void grantUriPermissionFromOwner(IBinder owner, int fromUid, String targetPackage,
            Uri uri, int modes, int sourceUserId, int targetUserId) {
        host(); user(sourceUserId); user(targetUserId);
        throw new UnsupportedOperationException("URI owner-token and Intent grants are not implemented");
    }
    @Override public ParceledListSlice getGrantedUriPermissions(String packageName, int userId) {
        host(); user(userId);
        return new ParceledListSlice(mGrants.list(packageName, false, true));
    }
    @Override public void clearGrantedUriPermissions(String packageName, int userId) {
        host(); user(userId);
        if (packageName == null) throw new IllegalArgumentException("Missing URI grant package");
        mGrants.clear(packageName);
    }
    @Override public ParceledListSlice getUriPermissions(String packageName, boolean incoming,
            boolean persistedOnly) {
        target(packageName);
        if (packageName == null) throw new IllegalArgumentException("Missing URI grant package");
        return new ParceledListSlice(mGrants.list(packageName, incoming, persistedOnly));
    }
    @Override public int checkGrantUriPermission_ignoreNonSystem(int sourceUid, String targetPackage,
            Uri uri, int modes, int userId) {
        if (!mCallers.isSystem()) return -1;
        user(userId);
        throw new UnsupportedOperationException("URI owner-token and Intent grants are not implemented");
    }
}
