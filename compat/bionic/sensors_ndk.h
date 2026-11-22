// Android's NDK sensor ABI, shared by x86-64 and translated AArch64 guests.
// The device readings themselves come from compat/devices/sensors.h.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace gesher::bionic::sensors_ndk {

// frameworks/native/include/android/sensor.h, android-16.0.0_r4. Spell the
// wire layout explicitly: Windows long/ssize_t are not Android LP64 types.
struct SensorEvent {
  int32_t version;
  int32_t sensor;
  int32_t type;
  int32_t reserved0;
  int64_t timestamp;
  alignas(8) float data[16];
  uint32_t flags;
  int32_t reserved1[3];
};
static_assert(sizeof(SensorEvent) == 104);
static_assert(alignof(SensorEvent) == 8);
static_assert(offsetof(SensorEvent, timestamp) == 16);
static_assert(offsetof(SensorEvent, data) == 24);
static_assert(offsetof(SensorEvent, flags) == 88);

void Register();

}  // namespace gesher::bionic::sensors_ndk
