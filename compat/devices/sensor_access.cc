// Copyright (C) 2026 The Gesher Authors
// SPDX-License-Identifier: Apache-2.0
#include "sensor_access.h"

#include <atomic>

namespace gesher::devices::sensors {
namespace {
// Keep flags and revision in one word; no torn permission/lifecycle snapshots.
std::atomic<uint64_t> gAccess{0};
std::atomic<void (*)()> gChanged{nullptr};
constexpr uint64_t kFlagMask = 31;
}

AccessState NdkAccessState() {
  const uint64_t word = gAccess.load(std::memory_order_acquire);
  return {.available = (word & 1) != 0,
          .active = (word & 2) != 0,
          .permissionCapped = (word & 4) != 0,
          .microphonePrivacy = (word & 16) != 0,
          .debuggable = (word & 8) != 0,
          .generation = word >> 5};
}

void UpdateNdkAccess(uint32_t flags, uint64_t revision) {
  if (revision == 0 || revision > (UINT64_MAX >> 5)) return;
  uint64_t old = gAccess.load(std::memory_order_relaxed);
  const uint64_t next = (revision << 5) | (flags & kFlagMask);
  while ((old >> 5) < revision) {
    if (gAccess.compare_exchange_weak(old, next, std::memory_order_release,
                                    std::memory_order_relaxed)) {
      if (auto callback = gChanged.load(std::memory_order_acquire)) callback();
      return;
    }
  }
}

void SetNdkAccessChangedCallback(void (*callback)()) {
  gChanged.store(callback, std::memory_order_release);
}

}  // namespace gesher::devices::sensors
