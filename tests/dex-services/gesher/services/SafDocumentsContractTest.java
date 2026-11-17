/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package gesher.services;

import android.app.ApplicationPackageManager;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.gesher.server.ActivityManagerService;
import com.gesher.server.ActivityTaskManagerService;
import com.gesher.server.ApkPackage;
import com.gesher.server.DocumentsStorageProvider;
import com.gesher.server.PackageManagerService;
import com.gesher.server.SharedLibraryGraph;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sun.misc.Unsafe;

/**
 * Contract tests for F13: Storage Access Framework (SAF) & Tree URI Provider.
 */
public final class SafDocumentsContractTest {
    private static int checks = 0;
    private static Unsafe unsafe;

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

    private static ApkPackage createPackage(String name, int uid) throws Exception {
        PackageImpl parsed = (PackageImpl) PackageImpl.forTesting(name, "/fixture/" + name + "/base.apk");
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
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        unsafe = (Unsafe) singleton.get(null);

        // -----------------------------------------------------------------------------------------
        // 1. Audit SAF Picker Result Logic: content:// URIs with DocumentsContract contracts
        // -----------------------------------------------------------------------------------------
        Class<?> atmsClass = Class.forName("com.gesher.server.ActivityTaskManagerService");
        Method buildResultMethod = atmsClass.getDeclaredMethod("buildDocumentPickerResult", String.class, String.class);
        buildResultMethod.setAccessible(true);

        // ACTION_OPEN_DOCUMENT: must return content://com.gesher.documents/document/... with grant flags
        Intent openDocResult = (Intent) buildResultMethod.invoke(null, Intent.ACTION_OPEN_DOCUMENT, "C:\\test\\document.pdf");
        check(openDocResult != null, "ACTION_OPEN_DOCUMENT result must not be null");
        Uri openDocUri = openDocResult.getData();
        check(openDocUri != null, "ACTION_OPEN_DOCUMENT URI must not be null");
        check("content".equals(openDocUri.getScheme()), "SAF Intent result must carry content:// URI, not file://");
        check(DocumentsStorageProvider.AUTHORITY.equals(openDocUri.getAuthority()), "URI authority must be " + DocumentsStorageProvider.AUTHORITY);
        check(openDocUri.toString().startsWith("content://com.gesher.documents/document/"), "Must be a document URI: " + openDocUri);
        check((openDocResult.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0, "FLAG_GRANT_READ_URI_PERMISSION must be attached");
        check((openDocResult.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0, "FLAG_GRANT_WRITE_URI_PERMISSION must be attached");
        check((openDocResult.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0, "FLAG_GRANT_PERSISTABLE_URI_PERMISSION must be attached");
        check((openDocResult.getFlags() & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) == 0, "FLAG_GRANT_PREFIX_URI_PERMISSION must not be on single doc");
        check(openDocResult.getClipData() != null, "ClipData must be set");
        check(openDocUri.equals(openDocResult.getClipData().getItemAt(0).getUri()), "ClipData URI must match data URI");

        // ACTION_CREATE_DOCUMENT: must return content://com.gesher.documents/document/...
        Intent createDocResult = (Intent) buildResultMethod.invoke(null, Intent.ACTION_CREATE_DOCUMENT, "C:\\test\\new_file.txt");
        check(createDocResult != null, "ACTION_CREATE_DOCUMENT result must not be null");
        Uri createDocUri = createDocResult.getData();
        check(createDocUri != null, "ACTION_CREATE_DOCUMENT URI must not be null");
        check("content".equals(createDocUri.getScheme()), "ACTION_CREATE_DOCUMENT must carry content:// URI");
        check(createDocUri.toString().startsWith("content://com.gesher.documents/document/"), "Must be a document URI: " + createDocUri);
        check((createDocResult.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0, "FLAG_GRANT_PERSISTABLE_URI_PERMISSION on create document");

        // ACTION_OPEN_DOCUMENT_TREE: must return content://com.gesher.documents/tree/... with prefix flag
        Intent treeDocResult = (Intent) buildResultMethod.invoke(null, Intent.ACTION_OPEN_DOCUMENT_TREE, "C:\\test\\workspace");
        check(treeDocResult != null, "ACTION_OPEN_DOCUMENT_TREE result must not be null");
        Uri treeDocUri = treeDocResult.getData();
        check(treeDocUri != null, "ACTION_OPEN_DOCUMENT_TREE URI must not be null");
        check("content".equals(treeDocUri.getScheme()), "ACTION_OPEN_DOCUMENT_TREE must carry content:// URI");
        check(treeDocUri.toString().startsWith("content://com.gesher.documents/tree/"), "Must be a tree URI: " + treeDocUri);
        check((treeDocResult.getFlags() & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0, "FLAG_GRANT_PREFIX_URI_PERMISSION must be on tree URI");
        check((treeDocResult.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0, "FLAG_GRANT_PERSISTABLE_URI_PERMISSION on tree URI");

        // Null / empty path returns null
        check(buildResultMethod.invoke(null, Intent.ACTION_OPEN_DOCUMENT, null) == null, "Null path returns null");
        check(buildResultMethod.invoke(null, Intent.ACTION_OPEN_DOCUMENT, "") == null, "Empty path returns null");

        // -----------------------------------------------------------------------------------------
        // 2. DocumentsContract: URI Builders & Parsers
        // -----------------------------------------------------------------------------------------
        PackageManagerService pm = new PackageManagerService(Collections.emptyList());
        Map<String, ApkPackage> pkgMap = new LinkedHashMap<>();
        set(pm, "mPackages", pkgMap);

        PackageManager testPm = new ApplicationPackageManager(null, pm) {
            @Override
            public List<ResolveInfo> queryIntentContentProviders(Intent intent, int flags) {
                ParceledListSlice<ResolveInfo> slice = pm.queryIntentContentProviders(intent, null, flags, 0);
                return slice != null ? slice.getList() : Collections.emptyList();
            }
        };
        Context testContext = new ContextWrapper(null) {
            @Override
            public PackageManager getPackageManager() {
                return testPm;
            }
        };

        String auth = DocumentsStorageProvider.AUTHORITY;
        String docPath = "/mnt/c/data/sample.pdf";
        Uri builtDoc = DocumentsContract.buildDocumentUri(auth, docPath);
        check(DocumentsContract.isDocumentUri(testContext, builtDoc), "isDocumentUri must be true for built document URI");
        check(!DocumentsContract.isTreeUri(builtDoc), "isTreeUri must be false for document URI");
        check(docPath.equals(DocumentsContract.getDocumentId(builtDoc)), "getDocumentId must match input document ID");

        String treePath = "/mnt/c/data/folder";
        Uri builtTree = DocumentsContract.buildTreeDocumentUri(auth, treePath);
        check(DocumentsContract.isTreeUri(builtTree), "isTreeUri must be true for built tree URI");
        check(treePath.equals(DocumentsContract.getTreeDocumentId(builtTree)), "getTreeDocumentId must match input tree ID");

        // Tree document traversal: DocumentsContract.buildDocumentUriUsingTree
        String childDocPath = "/mnt/c/data/folder/nested.txt";
        Uri docUnderTree = DocumentsContract.buildDocumentUriUsingTree(builtTree, childDocPath);
        check(DocumentsContract.isDocumentUri(testContext, docUnderTree), "isDocumentUri must be true for document under tree");
        check(childDocPath.equals(DocumentsContract.getDocumentId(docUnderTree)), "getDocumentId must return child doc ID");
        check(treePath.equals(DocumentsContract.getTreeDocumentId(docUnderTree)), "getTreeDocumentId must return tree ID from docUnderTree");

        // Child documents query URI under tree
        Uri childrenUnderTree = DocumentsContract.buildChildDocumentsUriUsingTree(builtTree, treePath);
        check(childrenUnderTree.toString().endsWith("/children"), "buildChildDocumentsUriUsingTree must append /children");
        check(childrenUnderTree.toString().startsWith(builtTree.toString()), "Child documents URI must be prefixed by tree URI");

        // Root URIs
        Uri rootsUri = DocumentsContract.buildRootsUri(auth);
        check("content://com.gesher.documents/root".equals(rootsUri.toString()), "buildRootsUri match: " + rootsUri);
        Uri singleRootUri = DocumentsContract.buildRootUri(auth, "primary");
        check(DocumentsContract.isRootUri(testContext, singleRootUri), "isRootUri must be true for root URI");
        check("primary".equals(DocumentsContract.getRootId(singleRootUri)), "getRootId must return root ID");

        // -----------------------------------------------------------------------------------------
        // 3. Provider Query Contract: Cursor columns (COLUMN_DOCUMENT_ID, COLUMN_DISPLAY_NAME, etc.)
        // -----------------------------------------------------------------------------------------
        File tmpDir = new File(System.getProperty("java.io.tmpdir"), "saf_test_" + System.currentTimeMillis());
        tmpDir.mkdirs();
        File sampleFile = new File(tmpDir, "hello_saf.txt");
        try (FileOutputStream fos = new FileOutputStream(sampleFile)) {
            fos.write("Hello SAF DocumentsContract!".getBytes(StandardCharsets.UTF_8));
        }

        DocumentsStorageProvider provider = new DocumentsStorageProvider(null, null);

        // Query Document URI
        Uri sampleDocUri = DocumentsContract.buildDocumentUri(auth, sampleFile.getAbsolutePath());
        Cursor docCursor = provider.query(sampleDocUri, null, null, null, null);
        check(docCursor != null, "Document query cursor must not be null");
        check(docCursor.moveToFirst(), "Document query cursor must have a row");
        int colDocId = docCursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID);
        int colName = docCursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
        int colMime = docCursor.getColumnIndex(Document.COLUMN_MIME_TYPE);
        int colSize = docCursor.getColumnIndex(Document.COLUMN_SIZE);
        int colFlags = docCursor.getColumnIndex(Document.COLUMN_FLAGS);
        int colMod = docCursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED);
        check(colDocId >= 0, "COLUMN_DOCUMENT_ID must be present in projection");
        check(colName >= 0, "COLUMN_DISPLAY_NAME must be present in projection");
        check(colMime >= 0, "COLUMN_MIME_TYPE must be present in projection");
        check(colSize >= 0, "COLUMN_SIZE must be present in projection");
        check(colFlags >= 0, "COLUMN_FLAGS must be present in projection");
        check(colMod >= 0, "COLUMN_LAST_MODIFIED must be present in projection");

        check(sampleFile.getAbsolutePath().equals(docCursor.getString(colDocId)), "Document ID must be file absolute path");
        check("hello_saf.txt".equals(docCursor.getString(colName)), "Display name must match file name");
        check("text/plain".equals(docCursor.getString(colMime)), "MIME type must be text/plain for .txt");
        check(sampleFile.length() == docCursor.getLong(colSize), "Size must match file length");
        check((docCursor.getInt(colFlags) & Document.FLAG_SUPPORTS_WRITE) != 0, "FLAG_SUPPORTS_WRITE must be set");
        check((docCursor.getInt(colFlags) & Document.FLAG_SUPPORTS_DELETE) != 0, "FLAG_SUPPORTS_DELETE must be set");
        check((docCursor.getInt(colFlags) & Document.FLAG_SUPPORTS_RENAME) != 0, "FLAG_SUPPORTS_RENAME must be set");
        docCursor.close();

        // Query Children URI
        Uri dirChildrenUri = DocumentsContract.buildChildDocumentsUri(auth, tmpDir.getAbsolutePath());
        Cursor childrenCursor = provider.query(dirChildrenUri, null, null, null, null);
        check(childrenCursor != null, "Children query cursor must not be null");
        check(childrenCursor.getCount() == 1, "Children count must be 1");
        check(childrenCursor.moveToFirst(), "Children cursor must move to first");
        check("hello_saf.txt".equals(childrenCursor.getString(childrenCursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME))),
                "Child item display name must match");
        childrenCursor.close();

        // Query Roots URI
        Cursor rootsCursor = provider.query(rootsUri, null, null, null, null);
        check(rootsCursor != null, "Roots cursor must not be null");
        check(rootsCursor.moveToFirst(), "Roots cursor must have a row");
        check(rootsCursor.getColumnIndex(Root.COLUMN_ROOT_ID) >= 0, "Root COLUMN_ROOT_ID must be present");
        check(rootsCursor.getColumnIndex(Root.COLUMN_DOCUMENT_ID) >= 0, "Root COLUMN_DOCUMENT_ID must be present");
        check(rootsCursor.getColumnIndex(Root.COLUMN_TITLE) >= 0, "Root COLUMN_TITLE must be present");
        check(rootsCursor.getColumnIndex(Root.COLUMN_FLAGS) >= 0, "Root COLUMN_FLAGS must be present");
        check("primary".equals(rootsCursor.getString(rootsCursor.getColumnIndex(Root.COLUMN_ROOT_ID))), "Root ID primary");
        rootsCursor.close();

        // Provider Call: createDocument, renameDocument, deleteDocument
        Bundle createArgs = new Bundle();
        createArgs.putParcelable(DocumentsContract.EXTRA_URI, DocumentsContract.buildDocumentUri(auth, tmpDir.getAbsolutePath()));
        createArgs.putString(Document.COLUMN_MIME_TYPE, "text/plain");
        createArgs.putString(Document.COLUMN_DISPLAY_NAME, "created_via_call.txt");
        Bundle createOut = provider.call(DocumentsContract.METHOD_CREATE_DOCUMENT, null, createArgs);
        check(createOut != null, "createDocument call must return result bundle");
        Uri createdDocUri = createOut.getParcelable(DocumentsContract.EXTRA_URI, Uri.class);
        check(createdDocUri != null, "Created document URI must not be null");
        File createdFile = new File(tmpDir, "created_via_call.txt");
        check(createdFile.exists(), "Created file must exist on disk");

        // Rename
        Bundle renameArgs = new Bundle();
        renameArgs.putParcelable(DocumentsContract.EXTRA_URI, createdDocUri);
        renameArgs.putString(Document.COLUMN_DISPLAY_NAME, "renamed_via_call.txt");
        Bundle renameOut = provider.call(DocumentsContract.METHOD_RENAME_DOCUMENT, null, renameArgs);
        check(renameOut != null, "renameDocument call must return result bundle");
        Uri renamedDocUri = renameOut.getParcelable(DocumentsContract.EXTRA_URI, Uri.class);
        check(renamedDocUri != null, "Renamed document URI must not be null");
        File renamedFile = new File(tmpDir, "renamed_via_call.txt");
        check(renamedFile.exists(), "Renamed file must exist on disk");
        check(!createdFile.exists(), "Old file must not exist after rename");

        // Delete
        Bundle deleteArgs = new Bundle();
        deleteArgs.putParcelable(DocumentsContract.EXTRA_URI, renamedDocUri);
        provider.call(DocumentsContract.METHOD_DELETE_DOCUMENT, null, deleteArgs);
        check(!renamedFile.exists(), "Deleted file must no longer exist");

        // Provider Call using Tree URI as parentUri directly
        Uri parentTreeUri = DocumentsContract.buildTreeDocumentUri(auth, tmpDir.getAbsolutePath());
        Bundle createTreeArgs = new Bundle();
        createTreeArgs.putParcelable(DocumentsContract.EXTRA_URI, parentTreeUri);
        createTreeArgs.putString(Document.COLUMN_MIME_TYPE, "text/plain");
        createTreeArgs.putString(Document.COLUMN_DISPLAY_NAME, "created_under_tree.txt");
        Bundle createTreeOut = provider.call(DocumentsContract.METHOD_CREATE_DOCUMENT, null, createTreeArgs);
        check(createTreeOut != null, "createDocument under tree must return result bundle");
        Uri createdUnderTreeUri = createTreeOut.getParcelable(DocumentsContract.EXTRA_URI, Uri.class);
        check(createdUnderTreeUri != null, "Created document URI under tree must not be null");
        check(DocumentsContract.isDocumentUri(testContext, createdUnderTreeUri), "Must be document URI");
        check(DocumentsContract.isTreeUri(createdUnderTreeUri), "Must be tree-backed document URI");
        File createdUnderTreeFile = new File(tmpDir, "created_under_tree.txt");
        check(createdUnderTreeFile.exists(), "File created under tree must exist");

        // Verify openFile on tree-backed doc URI
        android.os.ParcelFileDescriptor pfd = provider.openFile(createdUnderTreeUri, "rw");
        check(pfd != null, "openFile on tree-backed doc URI must succeed");
        pfd.close();

        // Delete file created under tree
        Bundle deleteTreeArgs = new Bundle();
        deleteTreeArgs.putParcelable(DocumentsContract.EXTRA_URI, createdUnderTreeUri);
        provider.call(DocumentsContract.METHOD_DELETE_DOCUMENT, null, deleteTreeArgs);
        check(!createdUnderTreeFile.exists(), "Deleted tree-backed file must no longer exist");

        // -----------------------------------------------------------------------------------------
        // 4. URI Permissions & takePersistableUriPermission
        // -----------------------------------------------------------------------------------------
        String testAppPkg = "com.test.safapp";
        int testAppUid = 10555;
        ApkPackage appPkg = createPackage(testAppPkg, testAppUid);

        pkgMap.put(testAppPkg, appPkg);

        ActivityTaskManagerService atm = (ActivityTaskManagerService) unsafe.allocateInstance(ActivityTaskManagerService.class);
        set(atm, "mPm", pm);

        ActivityManagerService am = (ActivityManagerService) unsafe.allocateInstance(ActivityManagerService.class);
        Constructor<?> cpCtor = Class.forName("com.gesher.server.ContentProviders")
                .getDeclaredConstructor(PackageManagerService.class, ActivityTaskManagerService.class);
        cpCtor.setAccessible(true);
        Object contentProviders = cpCtor.newInstance(pm, atm);
        set(am, "mProviders", contentProviders);

        Method uriGrantsMethod = contentProviders.getClass().getDeclaredMethod("uriGrants");
        uriGrantsMethod.setAccessible(true);
        Object grants = uriGrantsMethod.invoke(contentProviders);
        Class<?> grantsClass = grants.getClass();
        Method grantMethod = grantsClass.getDeclaredMethod("grant", String.class, Uri.class, int.class, int.class);
        grantMethod.setAccessible(true);
        Method canAccessMethod = grantsClass.getDeclaredMethod("canAccess", ApkPackage.class, Uri.class, int.class, int.class);
        canAccessMethod.setAccessible(true);
        Method listMethod = grantsClass.getDeclaredMethod("list", String.class, boolean.class, boolean.class);
        listMethod.setAccessible(true);

        int read = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        int write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        int persistable = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        int prefix = Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

        // Grant Tree URI to test package
        Uri grantTreeUri = DocumentsContract.buildTreeDocumentUri(auth, tmpDir.getAbsolutePath());
        grantMethod.invoke(grants, testAppPkg, grantTreeUri, read | write | persistable | prefix, 0);

        // Verify direct access to tree URI
        check((Boolean) canAccessMethod.invoke(grants, appPkg, grantTreeUri, read, 0), "Package must have read access to granted tree URI");
        check((Boolean) canAccessMethod.invoke(grants, appPkg, grantTreeUri, write, 0), "Package must have write access to granted tree URI");

        // Verify document traversal under tree: child document URI is authorized via tree prefix grant
        Uri childDocUri = DocumentsContract.buildDocumentUriUsingTree(grantTreeUri, sampleFile.getAbsolutePath());
        check((Boolean) canAccessMethod.invoke(grants, appPkg, childDocUri, read, 0), "Child document URI under tree must be authorized via prefix grant");
        check((Boolean) canAccessMethod.invoke(grants, appPkg, childDocUri, write, 0), "Child document URI under tree write access authorized");

        // Test takePersistableUriPermission & getUriPermissions
        Class<?> ugmClass = Class.forName("com.gesher.server.UriGrantsManagerService");
        Constructor<?> ugmCtor = ugmClass.getDeclaredConstructor(PackageManagerService.class, ActivityTaskManagerService.class, ActivityManagerService.class);
        ugmCtor.setAccessible(true);
        Object ugm = ugmCtor.newInstance(pm, atm, am);
        Method takeMethod = ugmClass.getDeclaredMethod("takePersistableUriPermission", Uri.class, int.class, String.class, int.class);
        takeMethod.setAccessible(true);
        Method releaseMethod = ugmClass.getDeclaredMethod("releasePersistableUriPermission", Uri.class, int.class, String.class, int.class);
        releaseMethod.setAccessible(true);

        // Before taking persistable permission: list(persistedOnly=true) is empty
        @SuppressWarnings("unchecked")
        List<UriPermission> preList = (List<UriPermission>) listMethod.invoke(grants, testAppPkg, true, true);
        check(preList.isEmpty(), "Persisted permissions must be empty before taking");

        // Call takePersistableUriPermission
        takeMethod.invoke(ugm, grantTreeUri, read | write, testAppPkg, 0);

        // After taking: list(persistedOnly=true) contains the granted tree URI
        @SuppressWarnings("unchecked")
        List<UriPermission> postList = (List<UriPermission>) listMethod.invoke(grants, testAppPkg, true, true);
        check(postList.size() == 1, "Persisted permissions must contain 1 grant after take");
        UriPermission perm = postList.get(0);
        check(grantTreeUri.equals(perm.getUri()), "Persisted URI must match tree URI");
        check(perm.isReadPermission(), "Persisted read flag must match");
        check(perm.isWritePermission(), "Persisted write flag must match");
        check(perm.getPersistedTime() > 0, "Persisted timestamp must be positive");

        // Release persistable URI permission
        releaseMethod.invoke(ugm, grantTreeUri, read | write, testAppPkg, 0);
        @SuppressWarnings("unchecked")
        List<UriPermission> releasedList = (List<UriPermission>) listMethod.invoke(grants, testAppPkg, true, true);
        check(releasedList.isEmpty(), "Persisted permissions must be empty after release");

        // Unrelated/ungranted URI takePersistableUriPermission must throw UnsupportedOperationException
        Uri bogusUri = Uri.parse("content://fixture.unregistered/doc");
        boolean threw = false;
        try {
            takeMethod.invoke(ugm, bogusUri, read, testAppPkg, 0);
        } catch (InvocationTargetException ite) {
            if (ite.getCause() instanceof UnsupportedOperationException) {
                threw = true;
            }
        }
        check(threw, "takePersistableUriPermission on ungranted URI must throw UnsupportedOperationException");

        // Cleanup temporary files
        sampleFile.delete();
        tmpDir.delete();

        System.out.println("SafDocumentsContractTest passed: " + checks + " checks");
    }
}
