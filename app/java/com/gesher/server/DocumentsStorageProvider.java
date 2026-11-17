/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.gesher.server;

import android.app.ContentProviderHolder;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * Storage Access Framework DocumentsProvider implementation for Gesher.
 * Backs {@code content://com.gesher.documents/} URIs for SAF document and tree selections.
 */
public final class DocumentsStorageProvider extends ContentProvider {
    public static final String AUTHORITY = "com.gesher.documents";
    public static final String ROOT_ID_PRIMARY = "primary";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_CAPACITY_BYTES,
            Root.COLUMN_MIME_TYPES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
    };

    private final ProviderInfo mInfo = new ProviderInfo();
    private final PackageManagerService mPm;
    private final ActivityTaskManagerService mAtm;

    public DocumentsStorageProvider() {
        this(null, null);
    }

    public DocumentsStorageProvider(PackageManagerService pm, ActivityTaskManagerService atm) {
        mPm = pm;
        mAtm = atm;
        mInfo.packageName = "android";
        mInfo.name = getClass().getName();
        mInfo.authority = AUTHORITY;
        mInfo.exported = true;
        mInfo.grantUriPermissions = true;
    }

    public ProviderInfo providerInfo() {
        return mInfo;
    }

    public synchronized ContentProviderHolder holder() {
        ContentProviderHolder h = new ContentProviderHolder(mInfo);
        h.provider = getIContentProvider();
        h.noReleaseNeeded = true;
        return h;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private static File getFileForDocId(String docId) {
        if (docId == null || docId.isEmpty() || "/".equals(docId) || "root".equals(docId)) {
            return new File("/");
        }
        String p = docId;
        if (p.startsWith("/") || p.startsWith("\\\\") || (p.length() >= 2 && p.charAt(1) == ':')) {
            p = Zygote.fromWindowsPath(p);
        }
        return new File(p);
    }

    private static String getMimeType(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            switch (ext) {
                case "txt": return "text/plain";
                case "html": case "htm": return "text/html";
                case "xml": return "text/xml";
                case "json": return "application/json";
                case "pdf": return "application/pdf";
                case "zip": return "application/zip";
                case "apk": return "application/vnd.android.package-archive";
                case "jpg": case "jpeg": return "image/jpeg";
                case "png": return "image/png";
                case "gif": return "image/gif";
                case "webp": return "image/webp";
                case "mp3": return "audio/mpeg";
                case "mp4": return "video/mp4";
                case "wav": return "audio/wav";
            }
        }
        return "application/octet-stream";
    }

    private static void includeFile(MatrixCursor cursor, File file, String[] projection) {
        String docId = file.getAbsolutePath();
        int flags = 0;
        if (file.isDirectory()) {
            flags |= Document.FLAG_DIR_SUPPORTS_CREATE | Document.FLAG_SUPPORTS_WRITE
                    | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        } else {
            flags |= Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE
                    | Document.FLAG_SUPPORTS_RENAME;
        }
        String displayName = file.getName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = "/";
        }
        String mimeType = getMimeType(file);
        long size = file.isDirectory() ? 0L : file.length();
        long lastModified = file.lastModified();

        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : projection) {
            switch (col) {
                case Document.COLUMN_DOCUMENT_ID:
                    row.add(docId);
                    break;
                case Document.COLUMN_MIME_TYPE:
                    row.add(mimeType);
                    break;
                case Document.COLUMN_DISPLAY_NAME:
                    row.add(displayName);
                    break;
                case Document.COLUMN_LAST_MODIFIED:
                    row.add(lastModified);
                    break;
                case Document.COLUMN_FLAGS:
                    row.add(flags);
                    break;
                case Document.COLUMN_SIZE:
                    row.add(size);
                    break;
                default:
                    row.add(null);
                    break;
            }
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (projection == null) {
            List<String> paths = uri.getPathSegments();
            if (!paths.isEmpty() && DocumentsContract.PATH_ROOT.equals(paths.get(0))) {
                projection = DEFAULT_ROOT_PROJECTION;
            } else {
                projection = DEFAULT_DOCUMENT_PROJECTION;
            }
        }
        List<String> paths = uri.getPathSegments();
        if (paths.isEmpty()) {
            return new MatrixCursor(projection);
        }
        String first = paths.get(0);
        if (DocumentsContract.PATH_ROOT.equals(first)) {
            MatrixCursor cursor = new MatrixCursor(projection);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String col : projection) {
                switch (col) {
                    case Root.COLUMN_ROOT_ID:
                        row.add(ROOT_ID_PRIMARY);
                        break;
                    case Root.COLUMN_FLAGS:
                        row.add(Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_SEARCH | Root.FLAG_SUPPORTS_IS_CHILD);
                        break;
                    case Root.COLUMN_ICON:
                        row.add(0);
                        break;
                    case Root.COLUMN_TITLE:
                        row.add("Gesher Storage");
                        break;
                    case Root.COLUMN_DOCUMENT_ID:
                        row.add("/");
                        break;
                    case Root.COLUMN_AVAILABLE_BYTES:
                        row.add(1024L * 1024L * 1024L);
                        break;
                    case Root.COLUMN_CAPACITY_BYTES:
                        row.add(1024L * 1024L * 1024L * 10L);
                        break;
                    case Root.COLUMN_MIME_TYPES:
                        row.add("*/*");
                        break;
                    default:
                        row.add(null);
                        break;
                }
            }
            return cursor;
        }

        boolean isChildren = paths.size() >= 3 && DocumentsContract.PATH_CHILDREN.equals(paths.get(paths.size() - 1));
        if (isChildren) {
            String parentDocId;
            if (paths.size() >= 5 && DocumentsContract.PATH_TREE.equals(paths.get(0))) {
                parentDocId = paths.get(3);
            } else {
                parentDocId = paths.get(1);
            }
            File parent = getFileForDocId(parentDocId);
            MatrixCursor cursor = new MatrixCursor(projection);
            File[] files = parent.listFiles();
            if (files != null) {
                for (File f : files) {
                    includeFile(cursor, f, projection);
                }
            }
            return cursor;
        }

        if (isDocumentUri(uri)) {
            String docId = DocumentsContract.getDocumentId(uri);
            File file = getFileForDocId(docId);
            MatrixCursor cursor = new MatrixCursor(projection);
            includeFile(cursor, file, projection);
            return cursor;
        }

        if (DocumentsContract.isTreeUri(uri)) {
            String treeId = DocumentsContract.getTreeDocumentId(uri);
            File file = getFileForDocId(treeId);
            MatrixCursor cursor = new MatrixCursor(projection);
            includeFile(cursor, file, projection);
            return cursor;
        }

        MatrixCursor cursor = new MatrixCursor(projection);
        return cursor;
    }

    private static boolean isDocumentUri(Uri uri) {
        if (uri == null) return false;
        List<String> paths = uri.getPathSegments();
        if (paths.size() == 2) {
            return DocumentsContract.PATH_DOCUMENT.equals(paths.get(0));
        }
        if (paths.size() == 4) {
            return DocumentsContract.PATH_TREE.equals(paths.get(0))
                    && DocumentsContract.PATH_DOCUMENT.equals(paths.get(2));
        }
        return false;
    }

    @Override
    public String getType(Uri uri) {
        try {
            if (isDocumentUri(uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                return getMimeType(getFileForDocId(docId));
            } else if (DocumentsContract.isTreeUri(uri)) {
                return Document.MIME_TYPE_DIR;
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String docId;
        if (isDocumentUri(uri)) {
            docId = DocumentsContract.getDocumentId(uri);
        } else if (DocumentsContract.isTreeUri(uri)) {
            docId = DocumentsContract.getTreeDocumentId(uri);
        } else {
            docId = DocumentsContract.getDocumentId(uri);
        }
        File file = getFileForDocId(docId);
        int modeBits = ParcelFileDescriptor.parseMode(mode);
        if (!file.exists()) {
            if ((modeBits & (ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_READ_WRITE)) != 0) {
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    throw new FileNotFoundException("Cannot create file: " + file);
                }
            } else {
                throw new FileNotFoundException("File not found: " + file);
            }
        }
        return ParcelFileDescriptor.open(file, modeBits);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (DocumentsContract.METHOD_CREATE_DOCUMENT.equals(method)) {
            Uri parentUri = extras != null ? extras.getParcelable(DocumentsContract.EXTRA_URI, Uri.class) : null;
            if (parentUri == null && extras != null) {
                parentUri = extras.getParcelable(DocumentsContract.EXTRA_PARENT_URI, Uri.class);
            }
            if (parentUri == null) {
                throw new IllegalArgumentException("Missing parent URI");
            }
            String mimeType = extras.getString(Document.COLUMN_MIME_TYPE);
            String displayName = extras.getString(Document.COLUMN_DISPLAY_NAME);
            String parentDocId;
            if (isDocumentUri(parentUri)) {
                parentDocId = DocumentsContract.getDocumentId(parentUri);
            } else if (DocumentsContract.isTreeUri(parentUri)) {
                parentDocId = DocumentsContract.getTreeDocumentId(parentUri);
            } else {
                parentDocId = DocumentsContract.getDocumentId(parentUri);
            }
            File parent = getFileForDocId(parentDocId);
            File newFile = new File(parent, displayName != null ? displayName : "new_document");
            try {
                if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                    newFile.mkdirs();
                } else {
                    File p = newFile.getParentFile();
                    if (p != null) p.mkdirs();
                    newFile.createNewFile();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create document: " + e.getMessage(), e);
            }
            Uri newDocUri;
            if (DocumentsContract.isTreeUri(parentUri)) {
                newDocUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, newFile.getAbsolutePath());
            } else {
                newDocUri = DocumentsContract.buildDocumentUri(AUTHORITY, newFile.getAbsolutePath());
            }
            Bundle result = new Bundle();
            result.putParcelable(DocumentsContract.EXTRA_URI, newDocUri);
            return result;
        }

        if (DocumentsContract.METHOD_DELETE_DOCUMENT.equals(method)) {
            Uri docUri = extras != null ? extras.getParcelable(DocumentsContract.EXTRA_URI, Uri.class) : null;
            if (docUri == null) {
                throw new IllegalArgumentException("Missing document URI");
            }
            String docId;
            if (isDocumentUri(docUri)) {
                docId = DocumentsContract.getDocumentId(docUri);
            } else if (DocumentsContract.isTreeUri(docUri)) {
                docId = DocumentsContract.getTreeDocumentId(docUri);
            } else {
                docId = DocumentsContract.getDocumentId(docUri);
            }
            File file = getFileForDocId(docId);
            if (file.exists() && !file.delete()) {
                throw new IllegalStateException("Failed to delete document: " + file);
            }
            return new Bundle();
        }

        if (DocumentsContract.METHOD_RENAME_DOCUMENT.equals(method)) {
            Uri docUri = extras != null ? extras.getParcelable(DocumentsContract.EXTRA_URI, Uri.class) : null;
            String displayName = extras != null ? extras.getString(Document.COLUMN_DISPLAY_NAME) : null;
            if (docUri == null || displayName == null) {
                throw new IllegalArgumentException("Missing URI or display name");
            }
            String docId;
            if (isDocumentUri(docUri)) {
                docId = DocumentsContract.getDocumentId(docUri);
            } else if (DocumentsContract.isTreeUri(docUri)) {
                docId = DocumentsContract.getTreeDocumentId(docUri);
            } else {
                docId = DocumentsContract.getDocumentId(docUri);
            }
            File file = getFileForDocId(docId);
            File target = new File(file.getParentFile(), displayName);
            if (!file.renameTo(target)) {
                throw new IllegalStateException("Failed to rename document to " + displayName);
            }
            Uri newUri;
            if (isDocumentUri(docUri) && DocumentsContract.isTreeUri(docUri)) {
                newUri = DocumentsContract.buildDocumentUriUsingTree(docUri, target.getAbsolutePath());
            } else if (DocumentsContract.isTreeUri(docUri)) {
                newUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, target.getAbsolutePath());
            } else {
                newUri = DocumentsContract.buildDocumentUri(AUTHORITY, target.getAbsolutePath());
            }
            Bundle result = new Bundle();
            result.putParcelable(DocumentsContract.EXTRA_URI, newUri);
            return result;
        }

        return super.call(method, arg, extras);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Insert not supported; use createDocument");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        try {
            String docId;
            if (isDocumentUri(uri)) {
                docId = DocumentsContract.getDocumentId(uri);
            } else if (DocumentsContract.isTreeUri(uri)) {
                docId = DocumentsContract.getTreeDocumentId(uri);
            } else {
                docId = DocumentsContract.getDocumentId(uri);
            }
            File file = getFileForDocId(docId);
            return file.delete() ? 1 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Update not supported");
    }
}
