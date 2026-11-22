// Copyright (C) 2026 The Gesher Authors
// SPDX-License-Identifier: Apache-2.0
#include "sensor_access.h"

#include <android_runtime/AndroidRuntime.h>
#include <jni.h>
#include "core_jni_helpers.h"

namespace {
jclass gAccessClass = nullptr;
jmethodID gEnsureSubscribed = nullptr;

class ScopedSensorEnv {
 public:
  explicit ScopedSensorEnv(JavaVM* vm) : vm_(vm) {
    if (vm_ == nullptr) return;
    const jint status = vm_->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
      // NDK callers may enter on a plain pthread without ever calling Java.
      // The cached class/method references avoid that thread's class-loader context.
      if (vm_->AttachCurrentThreadAsDaemon(&env_, nullptr) == JNI_OK) owned_ = true;
      else env_ = nullptr;
    } else if (status != JNI_OK) {
      env_ = nullptr;
    }
  }
  ~ScopedSensorEnv() { if (owned_) vm_->DetachCurrentThread(); }
  JNIEnv* get() const { return env_; }
  ScopedSensorEnv(const ScopedSensorEnv&) = delete;
  ScopedSensorEnv& operator=(const ScopedSensorEnv&) = delete;
 private:
  JavaVM* vm_;
  JNIEnv* env_ = nullptr;
  bool owned_ = false;
};

void ReceivePolicy(JNIEnv*, jclass, jint flags, jlong revision) {
  if (revision > 0) gesher::devices::sensors::UpdateNdkAccess(flags, revision);
}
}

namespace gesher::devices::sensors {
bool InitializeNdkAccess() {
  if (NdkAccessState().available) return true;
  if (gAccessClass == nullptr || gEnsureSubscribed == nullptr) return false;
  ScopedSensorEnv scoped(android::AndroidRuntime::getJavaVM());
  JNIEnv* env = scoped.get();
  if (env == nullptr || env->ExceptionCheck()) return false;
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
