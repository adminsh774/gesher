// Copyright (C) 2026 The Gesher Authors
// SPDX-License-Identifier: Apache-2.0
#pragma once
// This test executes Linux host JNI. Windows/guest ABI adaptation is a separate gate.
#define GESHER_JNI(function) reinterpret_cast<void*>(function)
