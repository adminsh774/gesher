// Copyright (C) 2026 The Gesher Authors
// SPDX-License-Identifier: Apache-2.0
#include "sensor_access.h"

#include <android_runtime/AndroidRuntime.h>
#include <jni.h>
#include "core_jni_helpers.h"

namespace {
jclass gAccessClass = nullptr;
jmethodID gEnsureSubscribed = nullptr;

void ReceivePolicy(JNIEnv*, jclass, jint flags, jlong revision) {
  if (revision > 0) gesher::devices::sensors::UpdateNdkAccess(flags, revision);
}
}

namespace gesher::devices::sensors {
bool InitializeNdkAccess() {
  if (NdkAccessState().available) return true;
  JNIEnv* env = android::AndroidRuntime::getJNIEnv();
  if (env == nullptr || gAccessClass == nullptr || env->ExceptionCheck()) return false;
  // This is only connection establishment. The sampler reads the atomic snapshot.
  const jboolean available = env->CallStaticBooleanMethod(gAccessClass, gEnsureSubscribed);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return false;
  }
  return available && NdkAccessState().available;
}
}

namespace android {
int RegisterGesherNatives(JNIEnv*, const char*, const JNINativeMethod*, int);
int register_gesher_sensor_access(JNIEnv* env) {
  const JNINativeMethod methods[] = {
      {"nativeUpdate", "(IJ)V", GESHER_JNI(ReceivePolicy)},
  };
  const char* name = "com/gesher/sensors/NativeSensorAccess";
  int result = RegisterGesherNatives(env, name, methods, 1);
  if (result != 0) return result;
  jclass local = env->FindClass(name);
  if (local == nullptr) { env->ExceptionClear(); return 0; }
  gAccessClass = static_cast<jclass>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  if (gAccessClass == nullptr) return -1;
  gEnsureSubscribed = env->GetStaticMethodID(gAccessClass, "ensureSubscribed", "()Z");
  return gEnsureSubscribed == nullptr ? -1 : 0;
}
}
