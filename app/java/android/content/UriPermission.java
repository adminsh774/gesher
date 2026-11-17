/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package android.content;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/** Description of a single URI permission granted to an application. */
public final class UriPermission implements Parcelable {
    public static final long INVALID_TIME = Long.MIN_VALUE;

    private final Uri mUri;
    private final int mModeFlags;
    private final long mPersistedTime;

    public UriPermission(Uri uri, int modeFlags, long persistedTime) {
        mUri = uri;
        mModeFlags = modeFlags;
        mPersistedTime = persistedTime;
    }

    public UriPermission(Parcel in) {
        mUri = in.readParcelable(null);
        mModeFlags = in.readInt();
        mPersistedTime = in.readLong();
    }

    public Uri getUri() {
        return mUri;
    }

    public boolean isReadPermission() {
        return (mModeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
    }

    public boolean isWritePermission() {
        return (mModeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
    }

    public long getPersistedTime() {
        return mPersistedTime;
    }

    @Override
    public String toString() {
        return "UriPermission {uri=" + mUri + ", flags=" + mModeFlags + ", persistedTime=" + mPersistedTime + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mUri, flags);
        dest.writeInt(mModeFlags);
        dest.writeLong(mPersistedTime);
    }

    public static final Creator<UriPermission> CREATOR = new Creator<UriPermission>() {
        @Override
        public UriPermission createFromParcel(Parcel source) {
            return new UriPermission(source);
        }

        @Override
        public UriPermission[] newArray(int size) {
            return new UriPermission[size];
        }
    };
}
