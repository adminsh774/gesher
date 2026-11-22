// Copyright (C) 2026 The Gesher Authors
// SPDX-License-Identifier: Apache-2.0
#include "sensor_access.h"
#include <android_runtime/AndroidRuntime.h>
#include <cassert>
#include <cstdio>
#include <string>
#include <thread>

namespace hub = gesher::devices::sensors;
namespace {
JavaVM* gVm;
JNIEnv* gMain;
jclass gClient;
jfieldID gMode, gRevision, gCalls;
unsigned checks = 0;
void check(bool value) { assert(value); ++checks; }
void reset(int mode) {
  uint64_t revision = hub::NdkAccessState().generation + 1;
  hub::UpdateNdkAccess(0, revision);
  gMain->SetStaticLongField(gClient, gRevision, revision);
  gMain->SetStaticIntField(gClient, gMode, mode);
  assert(!gMain->ExceptionCheck());
}
int calls() { return gMain->GetStaticIntField(gClient, gCalls); }
}

namespace android {
JavaVM* AndroidRuntime::getJavaVM() { return gVm; }
JNIEnv* AndroidRuntime::getJNIEnv() {
  JNIEnv* env = nullptr;
  if (gVm != nullptr) gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  return env;
}
int RegisterGesherNatives(JNIEnv* env, const char* name, const JNINativeMethod* methods, int count) {
  jclass type = env->FindClass(name);
  if (type == nullptr) { env->ExceptionClear(); return 0; }
  int result = env->RegisterNatives(type, methods, count);
  env->DeleteLocalRef(type);
  return result;
}
int register_gesher_sensor_access(JNIEnv* env);
}

int main(int argc, char** argv) {
  assert(argc == 2 || argc == 3);
  std::string classpath = std::string("-Djava.class.path=") + argv[1];
  JavaVMOption option{classpath.c_str(), nullptr};
  JavaVMInitArgs args{JNI_VERSION_1_6, 1, &option, JNI_FALSE};
  check(JNI_CreateJavaVM(&gVm, &gMain, &args) == JNI_OK);
  check(android::register_gesher_sensor_access(gMain) == 0);
  if (argc == 3) {
    check(!gMain->ExceptionCheck());
    check(!hub::InitializeNdkAccess());
    check(gVm->DestroyJavaVM() == JNI_OK);
    std::puts("Sensor JNI missing runtime class fails unavailable without pending exception");
    return 0;
  }
  gClient = gMain->FindClass("com/gesher/sensors/NativeSensorAccess");
  assert(gClient != nullptr);
  gMode = gMain->GetStaticFieldID(gClient, "mode", "I");
  gRevision = gMain->GetStaticFieldID(gClient, "revision", "J");
  gCalls = gMain->GetStaticFieldID(gClient, "calls", "I");

  reset(0);
  std::thread fresh([] {
    JNIEnv* env = nullptr;
    check(gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED);
    check(hub::InitializeNdkAccess());
    check(gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED);
  });
  fresh.join();
  check(hub::NdkAccessState().available && hub::NdkAccessState().active);
  int previousCalls = calls();
  std::thread steady([] {
    JNIEnv* env = nullptr;
    check(hub::InitializeNdkAccess());
    check(gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED);
  });
  steady.join();
  check(calls() == previousCalls); // Stable path never attaches or consults Java.

  for (int mode : {0, 1, 2}) {
    reset(mode);
    std::thread worker([mode] {
      JNIEnv* env = nullptr;
      check(hub::InitializeNdkAccess() == (mode == 0));
      check(gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED);
    });
    worker.join();
  }

  reset(0);
  std::thread attached([] {
    JNIEnv* owned = nullptr;
    check(gVm->AttachCurrentThreadAsDaemon(&owned, nullptr) == JNI_OK);
    check(hub::InitializeNdkAccess());
    JNIEnv* after = nullptr;
    check(gVm->GetEnv(reinterpret_cast<void**>(&after), JNI_VERSION_1_6) == JNI_OK && owned == after);
    check(gVm->DetachCurrentThread() == JNI_OK);
  });
  attached.join();

  reset(2);
  check(!hub::InitializeNdkAccess());
  check(!gMain->ExceptionCheck()); // This call's new transport exception is consumed.
  JNIEnv* after = nullptr;
  check(gVm->GetEnv(reinterpret_cast<void**>(&after), JNI_VERSION_1_6) == JNI_OK && after == gMain);
  reset(0);
  jclass failure = gMain->FindClass("java/lang/IllegalStateException");
  previousCalls = calls();
  gMain->ThrowNew(failure, "caller exception");
  check(!hub::InitializeNdkAccess());
  check(gMain->ExceptionCheck()); // Existing caller exception belongs to the caller.
  gMain->ExceptionClear();
  check(calls() == previousCalls);
  gMain->DeleteLocalRef(failure);

  JavaVM* saved = gVm;
  gVm = nullptr;
  check(!hub::InitializeNdkAccess());
  gVm = saved;
  check(gVm->DestroyJavaVM() == JNI_OK);
  std::printf("Production sensor JNI first-use native-thread lifecycle: %u checks passed\n", checks);
}
