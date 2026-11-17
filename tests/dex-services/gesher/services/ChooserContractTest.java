/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package gesher.services;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.pkg.component.ParsedActivityImpl;
import com.android.internal.pm.pkg.component.ParsedIntentInfoImpl;
import com.gesher.server.ActivityTaskManagerService;
import com.gesher.server.ActivityTaskManagerService.ChooserDispatcher;
import com.gesher.server.ActivityTaskManagerService.ChooserRequest;
import com.gesher.server.ApkPackage;
import com.gesher.server.PackageManagerService;
import com.gesher.server.SharedLibraryGraph;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import sun.misc.Unsafe;

/**
 * Dalvik tests for Track F Item F16: Chooser & Intent Sharing Contracts (ACTION_CHOOSER).
 * Validates EXTRA_INTENT validation, EXTRA_EXCLUDE_COMPONENTS filtering, EXTRA_INITIAL_INTENTS
 * prepending, sole target auto-launch, multi-target resolution, and URI grant propagation.
 */
public final class ChooserContractTest {
    private static int sChecks = 0;
    private static Unsafe sUnsafe;

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        sChecks++;
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ParsedIntentInfoImpl filter(String label, int priority, boolean isDefault,
            String mimeType, String... actions) throws Exception {
        ParsedIntentInfoImpl info = new ParsedIntentInfoImpl();
        info.setNonLocalizedLabel(label);
        info.setHasDefault(isDefault);
        IntentFilter filter = info.getIntentFilter();
        filter.setPriority(priority);
        if (isDefault) {
            filter.addCategory(Intent.CATEGORY_DEFAULT);
        }
        for (String action : actions) {
            filter.addAction(action);
        }
        if (mimeType != null) {
            filter.addDataType(mimeType);
        }
        return info;
    }

    private static ParsedActivityImpl activity(String name, ParsedIntentInfoImpl... filters) {
        ParsedActivityImpl activity = new ParsedActivityImpl();
        activity.setName(name);
        activity.setEnabled(true);
        activity.setExported(true);
        for (ParsedIntentInfoImpl filter : filters) {
            activity.addIntent(filter);
        }
        return activity;
    }

    private static ApkPackage record(String name, ParsedActivityImpl... activities) throws Exception {
        PackageImpl parsed = (PackageImpl) PackageImpl.forTesting(name, "/fixture/" + name + "/base.apk");
        parsed.setTargetSdkVersion(36);
        parsed.setEnabled(true);
        for (ParsedActivityImpl act : activities) {
            act.setPackageName(name);
            parsed.addActivity(act);
        }
        parsed.hideAsFinal();
        ApkPackage pkg = (ApkPackage) sUnsafe.allocateInstance(ApkPackage.class);
        field(pkg, "packageName", name);
        field(pkg, "parsed", parsed);
        field(pkg, "apkPath", "/fixture/" + name + "/base.apk");
        field(pkg, "mUid", 12001);
        field(pkg, "mDataDir", "/fixture/" + name + "/data");
        field(pkg, "sharedLibraries", SharedLibraryGraph.EMPTY);
        return pkg;
    }

    private static PackageManagerService service(ApkPackage... records) throws Exception {
        PackageManagerService service = (PackageManagerService) sUnsafe.allocateInstance(PackageManagerService.class);
        Map<String, ApkPackage> packages = new LinkedHashMap<>();
        for (ApkPackage record : records) {
            packages.put(record.packageName, record);
        }
        field(service, "mPackages", packages);
        field(service, "mRemoving", new LinkedHashMap<String, ApkPackage>());
        field(service, "mPackageRemoval", new ArrayList<>());
        field(service, "mPackageRemovalChecks", new ArrayList<>());
        return service;
    }

    private static ActivityTaskManagerService createAtm(PackageManagerService pm) throws Exception {
        Constructor<ActivityTaskManagerService> constructor =
                ActivityTaskManagerService.class.getDeclaredConstructor(PackageManagerService.class);
        constructor.setAccessible(true);
        ActivityTaskManagerService atm = constructor.newInstance(pm);

        Class<?> amClass = Class.forName("com.gesher.server.ActivityManagerService");
        Object am = sUnsafe.allocateInstance(amClass);
        Constructor<?> callersConstructor = Class.forName("com.gesher.server.CallerIdentity")
                .getDeclaredConstructor(PackageManagerService.class, ActivityTaskManagerService.class);
        callersConstructor.setAccessible(true);
        Object callers = callersConstructor.newInstance(pm, atm);

        Object providers = sUnsafe.allocateInstance(Class.forName("com.gesher.server.ContentProviders"));
        Constructor<?> grantsConstructor = Class.forName("com.gesher.server.UriGrants")
                .getDeclaredConstructor(PackageManagerService.class, ActivityTaskManagerService.class);
        grantsConstructor.setAccessible(true);
        Object grants = grantsConstructor.newInstance(pm, atm);
        field(providers, "mUriGrants", grants);

        field(am, "mPm", pm);
        field(am, "mAtm", atm);
        field(am, "mCallers", callers);
        field(am, "mProviders", providers);

        Method setAm = ActivityTaskManagerService.class.getDeclaredMethod("setActivityManager", amClass);
        setAm.setAccessible(true);
        setAm.invoke(atm, am);

        return atm;
    }

    private static Method findStartMethod() throws Exception {
        for (Method method : ActivityTaskManagerService.class.getDeclaredMethods()) {
            if (method.getName().equals("start") && method.getParameterCount() == 8) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("ActivityTaskManagerService.start(8 parameters) not found");
    }

    private static int invokeStart(Method startMethod, ActivityTaskManagerService atm, Intent intent)
            throws Exception {
        return (Integer) startMethod.invoke(atm, null, intent, null, "gesher.test", null, null, -1, null);
    }

    public static void testActionChooserWithoutExtraIntent(Method startMethod, ActivityTaskManagerService atm)
            throws Exception {
        // 1. ACTION_CHOOSER without EXTRA_INTENT -> throws IllegalArgumentException
        Intent emptyChooser = new Intent(Intent.ACTION_CHOOSER);
        boolean thrown = false;
        try {
            invokeStart(startMethod, atm, emptyChooser);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            check(cause instanceof IllegalArgumentException,
                    "Expected IllegalArgumentException for null EXTRA_INTENT, got: " + cause);
            thrown = true;
        }
        check(thrown, "ACTION_CHOOSER without EXTRA_INTENT must fail");

        // 2. ACTION_CHOOSER whose EXTRA_INTENT is another ACTION_CHOOSER (prevent recursion)
        Intent recursiveChooser = new Intent(Intent.ACTION_CHOOSER);
        recursiveChooser.putExtra(Intent.EXTRA_INTENT, new Intent(Intent.ACTION_CHOOSER));
        thrown = false;
        try {
            invokeStart(startMethod, atm, recursiveChooser);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            check(cause instanceof IllegalArgumentException,
                    "Expected IllegalArgumentException for recursive ACTION_CHOOSER target, got: " + cause);
            thrown = true;
        }
        check(thrown, "Recursive ACTION_CHOOSER target must fail");
        System.out.println("PASS: testActionChooserWithoutExtraIntent");
    }

    public static void testExcludeComponents(Method startMethod) throws Exception {
        // Create 2 targets matching ACTION_SEND text/plain
        ApkPackage pkg1 = record("com.gesher.test.target1",
                activity("com.gesher.test.target1.ShareActivity1",
                        filter("Share 1", 0, true, "text/plain", Intent.ACTION_SEND)));
        ApkPackage pkg2 = record("com.gesher.test.target2",
                activity("com.gesher.test.target2.ShareActivity2",
                        filter("Share 2", 0, true, "text/plain", Intent.ACTION_SEND)));
        PackageManagerService pm = service(pkg1, pkg2);
        ActivityTaskManagerService atm = createAtm(pm);

        ComponentName comp1 = new ComponentName("com.gesher.test.target1", "com.gesher.test.target1.ShareActivity1");
        ComponentName comp2 = new ComponentName("com.gesher.test.target2", "com.gesher.test.target2.ShareActivity2");

        Intent target = new Intent(Intent.ACTION_SEND);
        target.setType("text/plain");

        // Exclude Target 1 -> Target 2 is sole candidate -> single target auto-launch
        Intent chooser1 = Intent.createChooser(target, "Share");
        chooser1.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[] { comp1 });

        AtomicReference<Intent> launchedIntent = new AtomicReference<>();
        ActivityTaskManagerService.sChooserTargetLaunchConsumer = launchedIntent::set;
        ActivityTaskManagerService.sChooserDispatcher = (serviceAtm, req) -> {
            throw new AssertionError("ChooserDispatcher should NOT be invoked when 1 target remains");
        };

        invokeStart(startMethod, atm, chooser1);
        check(launchedIntent.get() != null, "Target 2 should be auto-launched when Target 1 is excluded");
        check(comp2.equals(launchedIntent.get().getComponent()),
                "Launched component should be Target 2, got: " + launchedIntent.get().getComponent());

        // Exclude BOTH Target 1 and Target 2 -> 0 candidates -> START_INTENT_NOT_RESOLVED
        Intent chooserBoth = Intent.createChooser(target, "Share");
        chooserBoth.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[] { comp1, comp2 });
        launchedIntent.set(null);

        int result = invokeStart(startMethod, atm, chooserBoth);
        check(result == ActivityManager.START_INTENT_NOT_RESOLVED,
                "Excluding all targets must yield START_INTENT_NOT_RESOLVED, got: " + result);
        check(launchedIntent.get() == null, "No target should launch when all are excluded");

        System.out.println("PASS: testExcludeComponents");
    }

    public static void testInitialIntents(Method startMethod) throws Exception {
        ApkPackage pkg1 = record("com.gesher.test.target1",
                activity("com.gesher.test.target1.ShareActivity1",
                        filter("Share 1", 0, true, "text/plain", Intent.ACTION_SEND)));
        PackageManagerService pm = service(pkg1);
        ActivityTaskManagerService atm = createAtm(pm);

        Intent target = new Intent(Intent.ACTION_SEND);
        target.setType("text/plain");

        Intent chooser = Intent.createChooser(target, "Share Dialog");
        Intent init1 = new Intent("custom.action.INIT_FIRST");
        Intent init2 = new Intent("custom.action.INIT_SECOND");
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { init1, init2 });

        AtomicReference<ChooserRequest> capturedRequest = new AtomicReference<>();
        ActivityTaskManagerService.sChooserDispatcher = (serviceAtm, req) -> {
            capturedRequest.set(req);
            return ActivityManager.START_SUCCESS;
        };

        invokeStart(startMethod, atm, chooser);
        check(capturedRequest.get() != null, "ChooserDispatcher should be invoked when initial intents are present");
        ChooserRequest req = capturedRequest.get();
        check(req.totalOptions() == 3, "Total options should be 3 (2 initial + 1 resolved), got: " + req.totalOptions());
        check(req.initialIntents.size() == 2, "Initial intents size should be 2, got: " + req.initialIntents.size());
        check("custom.action.INIT_FIRST".equals(req.initialIntents.get(0).getAction()),
                "First initial intent action mismatch: " + req.initialIntents.get(0).getAction());
        check("custom.action.INIT_SECOND".equals(req.initialIntents.get(1).getAction()),
                "Second initial intent action mismatch: " + req.initialIntents.get(1).getAction());
        check(req.targets.size() == 1, "Resolved targets size should be 1");
        check("com.gesher.test.target1".equals(req.targets.get(0).activityInfo.packageName),
                "Resolved candidate package mismatch: " + req.targets.get(0).activityInfo.packageName);

        // Verify launching initial intent option 0
        AtomicReference<Intent> launchedOption = new AtomicReference<>();
        ActivityTaskManagerService.sChooserTargetLaunchConsumer = launchedOption::set;
        req.launchOption(atm, 0);
        check(launchedOption.get() != null, "Launching option 0 should launch initial intent 1");
        check("custom.action.INIT_FIRST".equals(launchedOption.get().getAction()),
                "Launched intent should be custom.action.INIT_FIRST");

        System.out.println("PASS: testInitialIntents");
    }

    public static void testSingleTargetAutoLaunch(Method startMethod) throws Exception {
        ApkPackage pkg1 = record("com.gesher.test.single",
                activity("com.gesher.test.single.SingleActivity",
                        filter("Single", 0, true, "text/plain", Intent.ACTION_SEND)));
        PackageManagerService pm = service(pkg1);
        ActivityTaskManagerService atm = createAtm(pm);

        Intent target = new Intent(Intent.ACTION_SEND);
        target.setType("text/plain");

        Intent chooser = Intent.createChooser(target, "Single Chooser");

        AtomicBoolean dispatcherCalled = new AtomicBoolean(false);
        ActivityTaskManagerService.sChooserDispatcher = (serviceAtm, req) -> {
            dispatcherCalled.set(true);
            return ActivityManager.START_SUCCESS;
        };

        AtomicReference<Intent> launched = new AtomicReference<>();
        ActivityTaskManagerService.sChooserTargetLaunchConsumer = launched::set;

        invokeStart(startMethod, atm, chooser);
        check(!dispatcherCalled.get(), "ChooserDispatcher must NOT be called for single target auto-launch");
        check(launched.get() != null, "Target should be auto-launched");
        check(new ComponentName("com.gesher.test.single", "com.gesher.test.single.SingleActivity")
                .equals(launched.get().getComponent()), "Target component mismatch");

        System.out.println("PASS: testSingleTargetAutoLaunch");
    }

    public static void testMultiTargetResolution(Method startMethod) throws Exception {
        ApkPackage pkg1 = record("com.gesher.test.multi1",
                activity("com.gesher.test.multi1.MultiActivity1",
                        filter("Multi 1", 0, true, "text/plain", Intent.ACTION_SEND)));
        ApkPackage pkg2 = record("com.gesher.test.multi2",
                activity("com.gesher.test.multi2.MultiActivity2",
                        filter("Multi 2", 0, true, "text/plain", Intent.ACTION_SEND)));
        ApkPackage pkg3 = record("com.gesher.test.multi3",
                activity("com.gesher.test.multi3.MultiActivity3",
                        filter("Multi 3", 0, true, "text/plain", Intent.ACTION_SEND)));
        PackageManagerService pm = service(pkg1, pkg2, pkg3);
        ActivityTaskManagerService atm = createAtm(pm);

        Intent target = new Intent(Intent.ACTION_SEND);
        target.setType("text/plain");

        Intent chooser = Intent.createChooser(target, "Share with multi targets");

        AtomicReference<ChooserRequest> captured = new AtomicReference<>();
        ActivityTaskManagerService.sChooserDispatcher = (serviceAtm, req) -> {
            captured.set(req);
            return ActivityManager.START_SUCCESS;
        };

        invokeStart(startMethod, atm, chooser);
        check(captured.get() != null, "ChooserDispatcher must be invoked for multi-target resolution");
        ChooserRequest req = captured.get();
        check("Share with multi targets".equals(req.title.toString()),
                "Title mismatch: " + req.title);
        check(req.totalOptions() == 3, "Total options should be 3, got: " + req.totalOptions());
        check(req.targets.size() == 3, "Targets count should be 3, got: " + req.targets.size());
        check(req.initialIntents.isEmpty(), "Initial intents should be empty");

        // Dispatch selection of option 2
        AtomicReference<Intent> launched = new AtomicReference<>();
        ActivityTaskManagerService.sChooserTargetLaunchConsumer = launched::set;
        req.launchOption(atm, 2);
        check(launched.get() != null, "Option 2 should be launched");
        check("com.gesher.test.multi3".equals(launched.get().getComponent().getPackageName()),
                "Launched package should be multi3, got: " + launched.get().getComponent());

        System.out.println("PASS: testMultiTargetResolution");
    }

    public static void testUriGrantPropagation(Method startMethod) throws Exception {
        ApkPackage pkg1 = record("com.gesher.test.uri",
                activity("com.gesher.test.uri.UriActivity",
                        filter("Uri Act", 0, true, "text/plain", Intent.ACTION_SEND)));
        PackageManagerService pm = service(pkg1);
        ActivityTaskManagerService atm = createAtm(pm);

        Uri testUri = Uri.parse("content://com.gesher.test.provider/images/photo.png");
        Intent target = new Intent(Intent.ACTION_SEND);
        target.setType("text/plain");
        ArrayList<Uri> streams = new ArrayList<>();
        streams.add(testUri);
        target.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);

        Intent chooser = Intent.createChooser(target, "Share Image");
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        chooser.setClipData(ClipData.newRawUri("photo", testUri));

        AtomicReference<Intent> launched = new AtomicReference<>();
        ActivityTaskManagerService.sChooserTargetLaunchConsumer = launched::set;
        ActivityTaskManagerService.sChooserDispatcher = null;

        invokeStart(startMethod, atm, chooser);
        check(launched.get() != null, "Target should be launched");
        Intent it = launched.get();

        int flags = it.getFlags();
        check((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
                "FLAG_GRANT_READ_URI_PERMISSION should be propagated to target, flags: " + flags);
        check((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0,
                "FLAG_GRANT_WRITE_URI_PERMISSION should be propagated to target, flags: " + flags);
        check(it.getClipData() != null, "ClipData should be forwarded from chooser to target");
        check(testUri.equals(it.getClipData().getItemAt(0).getUri()),
                "Forwarded ClipData URI mismatch: " + it.getClipData().getItemAt(0).getUri());

        System.out.println("PASS: testUriGrantPropagation");
    }

    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime");

        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sUnsafe = (Unsafe) unsafeField.get(null);

        Method startMethod = findStartMethod();
        PackageManagerService emptyPm = service();
        ActivityTaskManagerService emptyAtm = createAtm(emptyPm);

        testActionChooserWithoutExtraIntent(startMethod, emptyAtm);
        testExcludeComponents(startMethod);
        testInitialIntents(startMethod);
        testSingleTargetAutoLaunch(startMethod);
        testMultiTargetResolution(startMethod);
        testUriGrantPropagation(startMethod);

        // Reset static fields
        ActivityTaskManagerService.sChooserTargetLaunchConsumer = null;
        ActivityTaskManagerService.sChooserDispatcher = (atm, req) -> req.launchOption(atm, 0);

        System.out.println("ChooserContractTest all passed: " + sChecks + " checks");
    }
}
