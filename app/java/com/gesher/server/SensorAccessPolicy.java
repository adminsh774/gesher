/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Caller-bound sensor access state, fed by the actual activity/service lifecycle.
 * Notifications are immutable: a quick idle/active pair must never collapse into
 * only the later grant. The listener only queues work; it must not call Binder. */
final class SensorAccessPolicy {
    static final int AVAILABLE = 1, ACTIVE = 2, PERMISSION_CAPPED = 4, DEBUGGABLE = 8;
    record State(long generation, int flags) { }

    final class Subscription {
        final int uid;
        final int fixedFlags;
        final Consumer<State> changed;
        private State state;
        private boolean closed;
        Subscription(int uid, boolean capped, boolean debuggable, Consumer<State> changed) {
            this.uid = uid;
            fixedFlags = AVAILABLE | (capped ? PERMISSION_CAPPED : 0)
                    | (debuggable ? DEBUGGABLE : 0);
            this.changed = changed;
            state = new State(1, flags(this));
        }
        State snapshot() { synchronized (SensorAccessPolicy.this) { return state; } }
        void close() {
            synchronized (SensorAccessPolicy.this) {
                if (closed) return;
                closed = true;
                subscriptions.remove(this);
                state = new State(state.generation + 1, 0);
                changed.accept(state);
            }
        }
    }

    private final Set<Subscription> subscriptions = new HashSet<>();
    private Map<Integer, Boolean> activities = Map.of();
    private Set<Integer> foregroundServices = Set.of();

    synchronized Subscription open(int uid, boolean capped, boolean debuggable,
            Consumer<State> changed) {
        Subscription subscription = new Subscription(uid, capped, debuggable, changed);
        subscriptions.add(subscription);
        return subscription;
    }

    synchronized void activities(Map<Integer, Boolean> next) {
        activities = new HashMap<>(next);
        update();
    }

    synchronized void foregroundServices(Set<Integer> next) {
        foregroundServices = new HashSet<>(next);
        update();
    }

    private int flags(Subscription subscription) {
        return subscription.fixedFlags | (Boolean.TRUE.equals(activities.get(subscription.uid))
                || foregroundServices.contains(subscription.uid) ? ACTIVE : 0);
    }

    private void update() {
        for (Subscription subscription : new ArrayList<>(subscriptions)) {
            int flags = flags(subscription);
            if (flags == subscription.state.flags) continue;
            subscription.state = new State(subscription.state.generation + 1, flags);
            subscription.changed.accept(subscription.state);
        }
    }
}
