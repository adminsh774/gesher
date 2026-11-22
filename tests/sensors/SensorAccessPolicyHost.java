/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exercises the production state machine. No sensor data or Android identity is mocked here. */
public final class SensorAccessPolicyHost {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static boolean active(SensorAccessPolicy.State state) {
        return (state.flags() & SensorAccessPolicy.ACTIVE) != 0;
    }
    public static void main(String[] args) {
        SensorAccessPolicy policy = new SensorAccessPolicy();
        List<SensorAccessPolicy.State> pending = new ArrayList<>();
        var one = policy.open(10123, true, true, pending::add);
        check(one.snapshot().flags() == 13, "Attached but inactive app retains capped/debuggable identity");
        check(one.snapshot().generation() == 1, "Initial subscription generation");
        policy.activities(Map.of(10123, true));
        check(active(one.snapshot()), "Visible activity admits sensor delivery");
        policy.activities(Map.of());
        policy.activities(Map.of(10123, true));
        check(pending.size() == 3, "Rapid active-idle-active changes are never coalesced");
        check(active(pending.get(0)) && !active(pending.get(1)) && active(pending.get(2)),
                "Deferred notifications retain denial before later grant");
        check(pending.get(0).generation() == 2 && pending.get(1).generation() == 3
                && pending.get(2).generation() == 4, "Ordered immutable policy generations");
        long before = one.snapshot().generation();
        policy.activities(Map.of(10123, true, 10888, true));
        check(one.snapshot().generation() == before, "Unrelated UID activity cannot reset a stream");
        policy.foregroundServices(Set.of(10123));
        policy.activities(Map.of());
        check(active(one.snapshot()) && one.snapshot().generation() == before,
                "Foreground service survives loss of activity visibility without false denial");
        policy.foregroundServices(Set.of());
        check(!active(one.snapshot()), "Retiring final foreground service suppresses delivery");
        policy.activities(Map.of(10123, true));
        var sibling = policy.open(10123, false, false, state -> { });
        check(active(sibling.snapshot()), "Shared UID uses the same real lifecycle state");
        check((sibling.snapshot().flags() & SensorAccessPolicy.PERMISSION_CAPPED) == 0,
                "Rate permission metadata is distinct from activity state");
        one.close();
        check(one.snapshot().flags() == 0, "Retired owner fails closed");
        long retired = one.snapshot().generation();
        policy.activities(Map.of());
        policy.activities(Map.of(10123, true));
        check(one.snapshot().flags() == 0 && one.snapshot().generation() == retired,
                "Lifecycle changes cannot resurrect a retired installation");
        one.close();
        check(one.snapshot().generation() == retired, "Close is idempotent");
        check(active(sibling.snapshot()), "Retiring one process preserves the other subscription");
        System.out.println("Sensor access production policy: " + checks + " checks passed");
    }
}
