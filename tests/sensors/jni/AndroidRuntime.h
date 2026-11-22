// Copyright (C) 2026 The Gesher Authors
// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <jni.h>
// Only the runtime's VM accessor is the host boundary. Attachment is performed by
// the actual JVM through the actual production sensor_access_jni.cc code.
namespace android {
class AndroidRuntime {
 public:
  static JavaVM* getJavaVM();
  static JNIEnv* getJNIEnv();
};
}
