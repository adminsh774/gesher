#pragma once
// This fixture invokes only JNI_OnLoad(JavaVM*, void*). No JNI operations or
// JavaVM fields are supplied. Full Conscrypt/ART behavior stays in Track 56.
#include <cstdint>
using jint = int32_t;
struct JavaVM;
constexpr jint JNI_ERR = -1;
