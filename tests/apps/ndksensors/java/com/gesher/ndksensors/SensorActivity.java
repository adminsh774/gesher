/* Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0 */
package com.gesher.ndksensors;

import android.app.Activity;
import android.os.Bundle;

/** The Windows runner explicitly supplies GESHER_SENSOR_TEST for this fixture. */
public final class SensorActivity extends Activity {
    private static native String run();
    private boolean ran;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        System.loadLibrary("ndksensors");
    }

    @Override protected void onResume() {
        super.onResume();
        if (ran) return;
        ran = true;
        try {
            System.out.println(run());
        } catch (Throwable failure) {
            failure.printStackTrace(System.out);
            System.out.println("NDK sensors guest: FAILED " + failure);
        }
        System.out.flush();
    }
}
