/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.net.Uri;
import android.os.FileUtils;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Android request/result semantics around real Windows open/save/folder dialogs. */
final class DocumentPicker {
    static final int OPEN = 0, CREATE = 1, TREE = 2;
    static final int MAX_SELECTION = 256;

    interface Backend {
        boolean available();
        String[] pick(Request request, int ownerPid, BooleanSupplier active) throws IOException;
    }

    static final Backend WINDOWS = new Backend() {
        @Override public boolean available() { return WindowsHost.available(); }
        @Override public String[] pick(Request request, int ownerPid, BooleanSupplier active)
                throws IOException {
            return WindowsHost.pickDocuments(request.mode, request.title, request.suggestedName,
                    request.initialDirectory, request.filter, request.extension, request.multiple,
                    ownerPid, active);
        }
    };

    static boolean handles(Intent intent) {
        if (intent == null || intent.getComponent() != null || intent.getPackage() != null) return false;
        String action = intent.getAction();
        return Intent.ACTION_OPEN_DOCUMENT.equals(action) || Intent.ACTION_CREATE_DOCUMENT.equals(action)
                || Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action) || Intent.ACTION_GET_CONTENT.equals(action);
    }

    static final class Request {
        final int mode, targetSdk;
        final boolean multiple, getContent;
        final String title, suggestedName, initialDirectory, filter, extension;
        final String mimeType;
        final String[] mimeTypes;

        Request(Intent original, String resolvedType, int targetSdk) {
            if (!handles(original)) throw new IllegalArgumentException("Not an implicit document request");
            Intent intent = new Intent(original);
            String action = intent.getAction();
            this.targetSdk = targetSdk;
            mode = Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action) ? TREE
                    : Intent.ACTION_CREATE_DOCUMENT.equals(action) ? CREATE : OPEN;
            getContent = Intent.ACTION_GET_CONTENT.equals(action);
            multiple = mode == OPEN && intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            mimeType = resolvedType != null ? resolvedType : intent.getType();
            if (mode != TREE && !validMime(mimeType)) throw new IllegalArgumentException("Missing or invalid MIME type");
            if (mode == CREATE && (mimeType.indexOf('*') >= 0
                    || DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType))) {
                throw new IllegalArgumentException("CREATE_DOCUMENT requires a concrete file MIME type");
            }
            String[] extras = mode == OPEN ? intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES) : null;
            if (extras != null && extras.length > 64) throw new IllegalArgumentException("Too many MIME types");
            mimeTypes = mode == TREE ? new String[0] : extras != null && extras.length != 0
                    ? extras.clone() : new String[]{mimeType};
            for (String type : mimeTypes) if (!validMime(type)) throw new IllegalArgumentException("Invalid MIME filter");
            title = mode == CREATE ? "Create document" : mode == TREE ? "Select folder" : "Open document";
            String suggested = mode == CREATE ? intent.getStringExtra(Intent.EXTRA_TITLE) : null;
            suggestedName = suggested == null ? "" : FileUtils.buildValidFatFilename(suggested);
            extension = mode == CREATE ? libcore.content.type.MimeMap.getDefault()
                    .guessExtensionFromMimeType(mimeType) : null;
            initialDirectory = initialDirectory(intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.class));
            filter = buildFilter(mimeTypes);
        }

        boolean accepts(String type) {
            for (String requested : mimeTypes) if (ClipDescription.compareMimeTypes(type, requested)) return true;
            return false;
        }
    }

    private static boolean validMime(String value) {
        return value != null && value.length() <= 255
                && value.matches("(?:[A-Za-z0-9!#$&^_.+\\-]+|\\*)/(?:[A-Za-z0-9!#$&^_.+\\-]+|\\*)")
                && (!value.startsWith("*/") || "*/*".equals(value));
    }

    private static String buildFilter(String[] mimeTypes) {
        for (String type : mimeTypes) if ("*/*".equals(type)) return "*";
        Set<String> extensions = new java.util.TreeSet<>();
        libcore.content.type.MimeMap map = libcore.content.type.MimeMap.getDefault();
        for (String extension : map.extensions()) {
            if (!extension.matches("[A-Za-z0-9][A-Za-z0-9_.+\\-]*")) continue;
            String actual = map.guessMimeTypeFromExtension(extension);
            for (String requested : mimeTypes) if (ClipDescription.compareMimeTypes(actual, requested)) {
                extensions.add("*." + extension); break;
            }
        }
        // For an unmapped MIME type, validate the selection against Android's
        // MIME map before any URI or success is returned.
        return extensions.isEmpty() ? "*" : String.join(";", extensions);
    }

    private static String initialDirectory(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())
                || !DocumentsStorageProvider.AUTHORITY.equals(uri.getAuthority())) return null;
        try {
            String id = DocumentsContract.isTreeUri(uri) && uri.getPathSegments().size() == 2
                    ? DocumentsContract.getTreeDocumentId(uri) : DocumentsContract.getDocumentId(uri);
            File file = DocumentsStorageProvider.file(id);
            File directory = file.isDirectory() ? file : file.getParentFile();
            return directory == null ? null : Zygote.windowsPath(directory.getPath());
        } catch (FileNotFoundException | IllegalArgumentException ignoredHint) { return null; }
    }

    static final class Result {
        final Intent intent;
        final List<Uri> uris;
        final File created;
        final Object createdKey;

        Result(Intent intent, List<Uri> uris, File created) throws IOException {
            this.intent = intent; this.uris = List.copyOf(uris); this.created = created;
            createdKey = created == null ? null : Files.readAttributes(created.toPath(), BasicFileAttributes.class).fileKey();
        }

        List<UriGrants.Grant> prepare(UriGrants grants, DocumentsStorageProvider provider, String target) {
            List<UriGrants.Grant> prepared = new ArrayList<>();
            for (Uri uri : uris) prepared.add(grants.prepareFromProvider(provider.registration,
                    target, uri, intent.getFlags()));
            return prepared;
        }

        void abandon() {
            if (created == null || createdKey == null) return;
            try {
                BasicFileAttributes now = Files.readAttributes(created.toPath(), BasicFileAttributes.class);
                if (createdKey.equals(now.fileKey()) && now.isRegularFile() && now.size() == 0) Files.delete(created.toPath());
            } catch (IOException error) { SystemServer.log("document-picker: unused new file could not be removed: " + error); }
        }
    }

    static Result select(Request request, String[] selected, DocumentsStorageProvider provider)
            throws IOException {
        if (selected == null || selected.length == 0) return null;
        if (selected.length > MAX_SELECTION || (!request.multiple && selected.length != 1)) {
            throw new IOException("Selection count does not match the Android request");
        }
        File created = null;
        List<File> files = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String path : selected) {
            if (path == null) throw new IOException("Missing selected path");
            File file;
            if (request.mode == CREATE) {
                File chosen = new File(Zygote.fromWindowsPath(path));
                if (!chosen.isAbsolute() || chosen.getParentFile() == null) throw new IOException("Invalid save location");
                File parent = DocumentsStorageProvider.selectedFile(chosen.getParent());
                if (!parent.isDirectory()) throw new IOException("Save location is not a directory");
                if (blockedAndroidDirectory(parent)) throw new IOException("Android data/obb is not selectable");
                file = DocumentsStorageProvider.file(provider.createDocument(parent.getPath(), request.mimeType, chosen.getName()));
                created = file;
            } else {
                file = DocumentsStorageProvider.selectedFile(path);
                if (!file.canRead() || (request.mode == TREE) != file.isDirectory()) {
                    throw new IOException("Selected object is not available for this request");
                }
                if (request.mode == OPEN && !request.accepts(DocumentsStorageProvider.mimeType(file))) {
                    throw new IOException("Selected file does not match the requested MIME types");
                }
            }
            if (request.mode == TREE && request.targetSdk >= 30 && isBlockedTreeRoot(file)) {
                throw new IOException("A storage-volume root cannot be granted as a tree");
            }
            if (blockedAndroidDirectory(file)) throw new IOException("Android data/obb is not selectable");
            if (unique.add(file.getPath())) files.add(file);
        }
        List<Uri> uris = new ArrayList<>();
        Set<String> mimeTypes = new LinkedHashSet<>();
        for (File file : files) {
            uris.add(request.mode == TREE
                    ? DocumentsContract.buildTreeDocumentUri(DocumentsStorageProvider.AUTHORITY, file.getPath())
                    : DocumentsContract.buildDocumentUri(DocumentsStorageProvider.AUTHORITY, file.getPath()));
            mimeTypes.add(DocumentsStorageProvider.mimeType(file));
        }
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (!request.getContent) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        if (request.mode == TREE) flags |= Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        Intent data = new Intent().addFlags(flags);
        if (uris.size() == 1) data.setDataAndType(uris.get(0), mimeTypes.iterator().next());
        ClipData clip = new ClipData("documents", mimeTypes.toArray(new String[0]), new ClipData.Item(uris.get(0)));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        data.setClipData(clip);
        return new Result(data, uris, created);
    }

    static boolean isBlockedTreeRoot(File file) {
        String path = file.getPath();
        return file.getParentFile() == null || path.matches("/mnt/[A-Za-z]")
                || path.matches("/mnt/unc/[^/]+/[^/]+")
                || sameFile(file, android.os.Environment.getExternalStorageDirectory())
                || sameFile(file, android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS))
                || sameFile(file, new File(android.os.Environment.getExternalStorageDirectory(), "Android"))
                || blockedAndroidDirectory(file);
    }

    private static boolean sameFile(File first, File second) {
        try { return second.exists() && Files.isSameFile(first.toPath(), second.toPath()); }
        catch (IOException unavailable) { return true; }
    }

    static boolean blockedAndroidDirectory(File file) {
        File external = android.os.Environment.getExternalStorageDirectory();
        // AOSP ExternalStorageProvider compares existing directory identities,
        // including aliases and the case-insensitive host filesystem.
        for (String child : new String[]{"Android/data", "Android/obb", "Android/sandbox"}) {
            File blocked = new File(external, child);
            if (!blocked.exists()) continue;
            for (File ancestor = file; ancestor != null; ancestor = ancestor.getParentFile()) {
                if (sameFile(ancestor, blocked)) return true;
            }
        }
        return false;
    }
}
