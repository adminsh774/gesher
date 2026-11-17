/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.provider;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.io.FileNotFoundException;
import java.util.List;

/**
 * Defines the contract between a documents provider and the platform.
 */
public final class DocumentsContract {
    public static final String PROVIDER_INTERFACE = "android.content.action.DOCUMENTS_PROVIDER";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_TARGET_URI = "android.content.extra.TARGET_URI";
    public static final String EXTRA_PARENT_URI = "parentUri";
    public static final String EXTRA_LOADING = "loading";
    public static final String EXTRA_INFO = "info";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_PROMPT = "android.provider.extra.PROMPT";
    public static final String ACTION_DOCUMENT_SETTINGS = "android.provider.action.DOCUMENT_SETTINGS";

    public static final String METHOD_CREATE_DOCUMENT = "android:createDocument";
    public static final String METHOD_RENAME_DOCUMENT = "android:renameDocument";
    public static final String METHOD_DELETE_DOCUMENT = "android:deleteDocument";
    public static final String METHOD_COPY_DOCUMENT = "android:copyDocument";
    public static final String METHOD_MOVE_DOCUMENT = "android:moveDocument";
    public static final String METHOD_REMOVE_DOCUMENT = "android:removeDocument";
    public static final String METHOD_FIND_DOCUMENT_PATH = "android:findDocumentPath";
    public static final String METHOD_CREATE_WEB_LINK_INTENT = "android:createWebLinkIntent";

    public static final String PATH_ROOT = "root";
    public static final String PATH_RECENT = "recent";
    public static final String PATH_DOCUMENT = "document";
    public static final String PATH_CHILDREN = "children";
    public static final String PATH_SEARCH = "search";
    public static final String PATH_TREE = "tree";

    public static final String PARAM_QUERY = "query";
    public static final String PARAM_MANAGE = "manage";

    public static final class Document {
        public static final String COLUMN_DOCUMENT_ID = "document_id";
        public static final String COLUMN_DISPLAY_NAME = "_display_name";
        public static final String COLUMN_MIME_TYPE = "mime_type";
        public static final String COLUMN_LAST_MODIFIED = "last_modified";
        public static final String COLUMN_FLAGS = "flags";
        public static final String COLUMN_SIZE = "_size";
        public static final String COLUMN_SUMMARY = "summary";
        public static final String COLUMN_ICON = "icon";

        public static final String MIME_TYPE_DIR = "vnd.android.document/directory";

        public static final int FLAG_SUPPORTS_THUMBNAIL = 1 << 0;
        public static final int FLAG_SUPPORTS_WRITE = 1 << 1;
        public static final int FLAG_SUPPORTS_DELETE = 1 << 2;
        public static final int FLAG_DIR_SUPPORTS_CREATE = 1 << 3;
        public static final int FLAG_DIR_PREFERS_GRID = 1 << 4;
        public static final int FLAG_DIR_PREFERS_LAST_MODIFIED = 1 << 5;
        public static final int FLAG_SUPPORTS_RENAME = 1 << 6;
        public static final int FLAG_SUPPORTS_COPY = 1 << 7;
        public static final int FLAG_SUPPORTS_MOVE = 1 << 8;
        public static final int FLAG_SUPPORTS_REMOVE = 1 << 10;
        public static final int FLAG_SUPPORTS_SETTINGS = 1 << 11;
        public static final int FLAG_WEB_LINKABLE = 1 << 12;
        public static final int FLAG_PARTIAL = 1 << 13;
        public static final int FLAG_SUPPORTS_METADATA = 1 << 14;

        private Document() {}
    }

    public static final class Root {
        public static final String COLUMN_ROOT_ID = "root_id";
        public static final String COLUMN_FLAGS = "flags";
        public static final String COLUMN_ICON = "icon";
        public static final String COLUMN_TITLE = "title";
        public static final String COLUMN_SUMMARY = "summary";
        public static final String COLUMN_DOCUMENT_ID = "document_id";
        public static final String COLUMN_AVAILABLE_BYTES = "available_bytes";
        public static final String COLUMN_CAPACITY_BYTES = "capacity_bytes";
        public static final String COLUMN_MIME_TYPES = "mime_types";
        public static final String COLUMN_QUERY_ARGS = "query_args";

        public static final int FLAG_SUPPORTS_CREATE = 1 << 0;
        public static final int FLAG_LOCAL_ONLY = 1 << 1;
        public static final int FLAG_SUPPORTS_RECENTS = 1 << 2;
        public static final int FLAG_SUPPORTS_SEARCH = 1 << 3;
        public static final int FLAG_SUPPORTS_IS_CHILD = 1 << 4;
        public static final int FLAG_EMPTY = 1 << 16;
        public static final int FLAG_ADVANCED = 1 << 17;
        public static final int FLAG_HAS_SETTINGS = 1 << 18;
        public static final int FLAG_REMOVABLE_SD = 1 << 19;
        public static final int FLAG_REMOVABLE_USB = 1 << 20;

        private Root() {}
    }

    public static final class Path {
        public static final String COLUMN_PATH = "path";
        private Path() {}
    }

    private DocumentsContract() {}

    public static Uri buildRootsUri(String authority) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_ROOT).build();
    }

    public static Uri buildRootUri(String authority, String rootId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_ROOT).appendPath(rootId).build();
    }

    public static Uri buildRecentDocumentsUri(String authority, String rootId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_ROOT).appendPath(rootId)
                .appendPath(PATH_RECENT).build();
    }

    public static Uri buildSearchDocumentsUri(String authority, String rootId, String query) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_ROOT).appendPath(rootId)
                .appendPath(PATH_SEARCH).appendQueryParameter(PARAM_QUERY, query).build();
    }

    public static Uri buildDocumentUri(String authority, String documentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_DOCUMENT).appendPath(documentId).build();
    }

    public static Uri buildTreeDocumentUri(String authority, String documentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_TREE).appendPath(documentId).build();
    }

    public static Uri buildDocumentUriUsingTree(Uri treeUri, String documentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(treeUri.getAuthority())
                .appendPath(PATH_TREE).appendPath(getTreeDocumentId(treeUri))
                .appendPath(PATH_DOCUMENT).appendPath(documentId).build();
    }

    public static Uri buildChildDocumentsUri(String authority, String parentDocumentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(PATH_DOCUMENT).appendPath(parentDocumentId)
                .appendPath(PATH_CHILDREN).build();
    }

    public static Uri buildChildDocumentsUriUsingTree(Uri treeUri, String parentDocumentId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(treeUri.getAuthority())
                .appendPath(PATH_TREE).appendPath(getTreeDocumentId(treeUri))
                .appendPath(PATH_DOCUMENT).appendPath(parentDocumentId)
                .appendPath(PATH_CHILDREN).build();
    }

    public static boolean isDocumentUri(Context context, Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return false;
        List<String> paths = uri.getPathSegments();
        if (paths.size() == 2 && PATH_DOCUMENT.equals(paths.get(0))) return true;
        if (paths.size() == 4 && PATH_TREE.equals(paths.get(0)) && PATH_DOCUMENT.equals(paths.get(2))) return true;
        return false;
    }

    public static boolean isTreeUri(Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return false;
        List<String> paths = uri.getPathSegments();
        return paths.size() >= 2 && PATH_TREE.equals(paths.get(0));
    }

    public static boolean isRootUri(Context context, Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return false;
        List<String> paths = uri.getPathSegments();
        return (paths.size() == 1 || paths.size() == 2) && PATH_ROOT.equals(paths.get(0));
    }

    public static String getDocumentId(Uri documentUri) {
        final List<String> paths = documentUri.getPathSegments();
        if (paths.size() >= 2 && PATH_DOCUMENT.equals(paths.get(0))) {
            return paths.get(1);
        }
        if (paths.size() >= 4 && PATH_TREE.equals(paths.get(0)) && PATH_DOCUMENT.equals(paths.get(2))) {
            return paths.get(3);
        }
        throw new IllegalArgumentException("Invalid URI: " + documentUri);
    }

    public static String getTreeDocumentId(Uri documentUri) {
        final List<String> paths = documentUri.getPathSegments();
        if (paths.size() >= 2 && PATH_TREE.equals(paths.get(0))) {
            return paths.get(1);
        }
        throw new IllegalArgumentException("Invalid URI: " + documentUri);
    }

    public static String getRootId(Uri rootUri) {
        final List<String> paths = rootUri.getPathSegments();
        if (paths.size() >= 2 && PATH_ROOT.equals(paths.get(0))) {
            return paths.get(1);
        }
        throw new IllegalArgumentException("Invalid URI: " + rootUri);
    }

    public static String getSearchDocumentsQuery(Uri searchDocumentsUri) {
        return searchDocumentsUri.getQueryParameter(PARAM_QUERY);
    }

    public static Uri createDocument(ContentResolver content, Uri parentDocumentUri,
            String mimeType, String displayName) throws FileNotFoundException {
        Bundle in = new Bundle();
        in.putParcelable(EXTRA_URI, parentDocumentUri);
        in.putString(Document.COLUMN_MIME_TYPE, mimeType);
        in.putString(Document.COLUMN_DISPLAY_NAME, displayName);
        Bundle out = content.call(parentDocumentUri.getAuthority(), METHOD_CREATE_DOCUMENT, null, in);
        return out != null ? out.getParcelable(EXTRA_URI, Uri.class) : null;
    }

    public static boolean deleteDocument(ContentResolver content, Uri documentUri)
            throws FileNotFoundException {
        Bundle in = new Bundle();
        in.putParcelable(EXTRA_URI, documentUri);
        content.call(documentUri.getAuthority(), METHOD_DELETE_DOCUMENT, null, in);
        return true;
    }

    public static Uri renameDocument(ContentResolver content, Uri documentUri,
            String displayName) throws FileNotFoundException {
        Bundle in = new Bundle();
        in.putParcelable(EXTRA_URI, documentUri);
        in.putString(Document.COLUMN_DISPLAY_NAME, displayName);
        Bundle out = content.call(documentUri.getAuthority(), METHOD_RENAME_DOCUMENT, null, in);
        return out != null ? out.getParcelable(EXTRA_URI, Uri.class) : null;
    }
}
