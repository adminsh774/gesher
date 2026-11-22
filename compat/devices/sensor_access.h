// Copyright (C) 2026 The Gesher Authors
// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stdint.h>

namespace gesher::devices::sensors {

struct AccessState {
  bool available = false;
  bool active = false;
  bool permissionCapped = false;
  bool microphonePrivacy = false;
  bool debuggable = false;
  uint64_t generation = 0;
};

// Establishes the authenticated policy subscription. Call outside hub/queue locks.
// Once connected this is an atomic snapshot read, not a per-event Binder request.
bool InitializeNdkAccess();

// A coherent process-local snapshot: no JNI, IPC, allocation or blocking lock.
AccessState NdkAccessState();

// Internal JNI receiver. Revision is process-local and monotonically increasing;
// stale/reordered notifications cannot resurrect a previous access grant.
void UpdateNdkAccess(uint32_t flags, uint64_t revision);
// Installed once by the NDK bridge. Runs after publication with no policy lock.
void SetNdkAccessChangedCallback(void (*callback)());

}  // namespace gesher::devices::sensors
