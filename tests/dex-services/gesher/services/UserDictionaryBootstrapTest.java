/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.app.ActivityThread;
import android.app.ContentProviderHolder;
import android.app.IActivityManager;
import android.content.AttributionSource;
import android.content.ContentValues;
import android.content.IContentService;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.IContentObserver;
import android.net.Uri;
import android.os.IBinder;
import android.os.Process;
import android.provider.UserDictionary.Words;
import com.gesher.app.Main;
import com.gesher.server.Services;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Actual framework bootstrap, SQLite and in-process provider/observer transport. */
public final class UserDictionaryBootstrapTest {
    private static int checks;
    private static synchronized void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        if (System.getenv("GESHER_BINDER") != null) throw new AssertionError("Bootstrap fixture must have no Binder broker");
        String root = System.getenv("ANDROID_ROOT");
        Main.bootstrap("dictionary-bootstrap", root + "/build.prop", root + "/framework/framework-res.apk");
        check(ActivityThread.currentActivityThread() == null, "Bootstrap attached an application thread");
        Map<String, IBinder> services = Services.createInProcess(Collections.emptyList());
        Services.installInProcess(services);
        IActivityManager activity = IActivityManager.Stub.asInterface(services.get("activity"));
        IPackageManager packages = IPackageManager.Stub.asInterface(services.get("package"));
        IContentService content = IContentService.Stub.asInterface(services.get("content"));
        ProviderInfo info = packages.resolveContentProvider("user_dictionary", 0, 0);
        check(info != null && "com.android.providers.userdictionary".equals(info.packageName), "Builtin metadata is discoverable");
        PackageInfo owner = packages.getPackageInfo(info.packageName, PackageManager.GET_PROVIDERS, 0);
        check(owner != null && owner.providers.length == 1 && info.name.equals(owner.providers[0].name), "Installed owner describes actual provider");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            ContentProviderHolder holder = null;
            Uri inserted = null;
            AttributionSource caller = new AttributionSource.Builder(Process.myUid()).setPackageName("android").build();
            CountDownLatch changed = new CountDownLatch(1);
            AtomicReference<Uri> observed = new AtomicReference<>();
            IContentObserver observer = new IContentObserver.Stub() {
                @Override public void onChange(boolean self, Uri uri, int userId) {
                    observed.set(uri); changed.countDown();
                }
                @Override public void onChangeEtc(boolean self, Uri[] uris, int flags, int userId) {
                    if (uris.length > 0) observed.set(uris[0]); changed.countDown();
                }
            };
            try {
                holder = activity.getContentProviderExternal("user_dictionary", 0, null, "dictionary-bootstrap-contract");
                check(holder != null && holder.provider != null && holder.noReleaseNeeded, "Builtin provider transport is available");
                check(Words.CONTENT_TYPE.equals(holder.provider.getType(caller, Words.CONTENT_URI)), "Transport MIME");
                content.registerContentObserver(Words.CONTENT_URI, true, observer, 0, 36);
                String word = "dictionary-contract-" + UUID.randomUUID();
                ContentValues values = new ContentValues();
                values.put(Words.WORD, word); values.put(Words.LOCALE, "he"); values.put(Words.SHORTCUT, "dc");
                inserted = holder.provider.insert(caller, Words.CONTENT_URI, values, null);
                check(inserted != null, "Transport creates a real SQLite row");
                try (Cursor cursor = holder.provider.query(caller, inserted,
                        new String[]{Words.WORD, Words.LOCALE, Words.SHORTCUT}, null, null)) {
                    check(cursor.moveToFirst() && cursor.getCount() == 1 && word.equals(cursor.getString(0))
                            && "he".equals(cursor.getString(1)) && "dc".equals(cursor.getString(2)), "Transport reads actual SQLite values");
                }
                check(changed.await(5, TimeUnit.SECONDS) && inserted.equals(observed.get()), "Real ContentService delivers item notification");
                ContentProviderHolder again = activity.getContentProviderExternal("user_dictionary", 0, null, "dictionary-repeat");
                check(again.provider.asBinder() == holder.provider.asBinder(), "Repeated acquisition retains provider identity");
            } catch (Throwable error) { failure.set(error); }
            finally {
                try {
                    content.unregisterContentObserver(observer);
                    if (inserted != null) check(holder.provider.delete(caller, inserted, null) == 1, "Fixture row cleanup");
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            }
        }, "dictionary-bootstrap-worker");
        worker.setDaemon(true);
        worker.start();
        worker.join(45000);
        check(!worker.isAlive(), "Worker did not complete without pumping the main Looper");
        if (failure.get() != null) throw new AssertionError("Dictionary bootstrap worker failed", failure.get());
        check(ActivityThread.currentActivityThread() == null, "Provider acquisition attached an application thread");
        System.out.println("UserDictionaryBootstrapTest all passed: " + checks + " checks; actual provider transport, SQLite and ContentService");
    }
}
