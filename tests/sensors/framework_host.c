/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
/* Existing host Binder identity/init boundary. Only the final native policy receiver
 * is observed here; production atomic storage is separately linked in the C++ test. */
#include "../activity-finish/framework_host.c"
#include <stdatomic.h>
static _Atomic jint sensor_flags;
static _Atomic jlong sensor_revision;
JNIEXPORT void JNICALL Java_com_gesher_sensors_NativeSensorAccess_nativeUpdate(
        JNIEnv* env, jclass type, jint flags, jlong revision) {
    atomic_store(&sensor_flags, flags);
    atomic_store(&sensor_revision, revision);
}
JNIEXPORT jint JNICALL Java_com_gesher_sensors_SensorAccessClientHost_lastFlags(
        JNIEnv* env, jclass type) { return atomic_load(&sensor_flags); }
JNIEXPORT jlong JNICALL Java_com_gesher_sensors_SensorAccessClientHost_lastRevision(
        JNIEnv* env, jclass type) { return atomic_load(&sensor_revision); }
