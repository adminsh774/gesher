// Copyright (C) 2026 The Gesher Authors
// SPDX-License-Identifier: Apache-2.0
#include "sensor_access.h"
#include <atomic>
#include <cassert>
#include <cstdio>
#include <thread>
#include <vector>

namespace hub = gesher::devices::sensors;
std::atomic<unsigned> notifications{0};
void changed() { (void)hub::NdkAccessState(); ++notifications; }

int main() {
  assert(!hub::NdkAccessState().available);
  hub::SetNdkAccessChangedCallback(changed);
  hub::UpdateNdkAccess(15, 1);
  auto state = hub::NdkAccessState();
  assert(state.available && state.active && state.permissionCapped && state.debuggable);
  assert(!state.microphonePrivacy && state.generation == 1);
  hub::UpdateNdkAccess(0, 2);
  hub::UpdateNdkAccess(31, 1);
  state = hub::NdkAccessState();
  assert(!state.available && !state.active && state.generation == 2);
  assert(notifications == 2);
  hub::UpdateNdkAccess(31, 2);
  assert(notifications == 2 && !hub::NdkAccessState().available);

  std::atomic<bool> running{true};
  std::thread reader([&] {
    uint64_t previous = 2;
    while (running) {
      auto next = hub::NdkAccessState();
      assert(next.generation >= previous);
      previous = next.generation;
      if (next.generation >= 3) assert(next.available == ((next.generation & 1) != 0));
    }
  });
  std::vector<std::thread> writers;
  for (uint64_t part = 0; part < 4; ++part) writers.emplace_back([part] {
    for (uint64_t revision = 3 + part; revision < 10003; revision += 4)
      hub::UpdateNdkAccess((revision & 1) ? 3 : 0, revision);
  });
  for (auto& writer : writers) writer.join();
  running = false;
  reader.join();
  assert(hub::NdkAccessState().generation == 10002);
  hub::SetNdkAccessChangedCallback(nullptr);
  std::puts("Sensor access atomic snapshot: denial, stale callbacks, coherent concurrent reads passed");
}
