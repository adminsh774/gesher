/* Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0 */
// Built against the NDK's real headers for both ELF ABIs. The fixture's only
// synthetic input is the existing, explicitly enabled GESHER_SENSOR_TEST hub.
#include <android/looper.h>
#include <android/sensor.h>
#include <jni.h>
#include <pthread.h>
#include <time.h>
#include <unistd.h>

#include <cerrno>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstring>

static_assert(sizeof(ASensorEvent) == 104);
static_assert(alignof(ASensorEvent) == 8);
static_assert(offsetof(ASensorEvent, timestamp) == 16);
static_assert(offsetof(ASensorEvent, data) == 24);
static_assert(offsetof(ASensorEvent, flags) == 88);

namespace {
std::atomic<int> checks{0};
std::atomic<int> failures{0};
thread_local unsigned callback_tls;
void Check(bool condition, const char* label) {
    ++checks;
    if (!condition) { ++failures; std::printf("NDK sensors FAIL %s\n", label); }
}
int64_t Now() {
    timespec time{};
    clock_gettime(CLOCK_BOOTTIME, &time);
    return int64_t(time.tv_sec) * 1000000000 + time.tv_nsec;
}
bool Valid(const ASensorEvent& event, const ASensor* sensor) {
    return event.version == sizeof(ASensorEvent) && event.sensor == ASensor_getHandle(sensor)
        && event.type == ASensor_getType(sensor) && event.timestamp > 0
        && event.timestamp <= Now() && event.flags == 0;
}
int AwaitIdent(int expected) {
    const int64_t deadline = Now() + 5000000000LL;
    while (Now() < deadline) {
        int ident = ALooper_pollOnce(50, nullptr, nullptr, nullptr);
        if (ident == expected) return ident;
        if (ident == ALOOPER_POLL_ERROR) return ident;
    }
    return ALOOPER_POLL_TIMEOUT;
}
struct CallbackState {
    ASensorManager* manager;
    ASensorEventQueue* queue = nullptr;
    const ASensor* sensor;
    bool destroy;
    int calls = 0;
    unsigned cookie = 0x7253fa11;
};
int Callback(int fd, int events, void* opaque) {
    auto* state = static_cast<CallbackState*>(opaque);
    Check(state != nullptr && state->cookie == 0x7253fa11, "callback user data");
    Check(callback_tls == 0x1729, "guest callback thread TLS");
    Check(fd >= 0 && (events & ALOOPER_EVENT_INPUT) != 0, "callback readable descriptor");
    ASensorEvent event{};
    Check(ASensorEventQueue_getEvents(state->queue, &event, 1) == 1, "callback event read");
    Check(Valid(event, state->sensor), "callback event ABI and clock");
    ++state->calls;
    if (state->destroy) {
        Check(ASensorManager_destroyEventQueue(state->manager, state->queue) == 0,
              "callback destroys own queue");
        state->queue = nullptr;
        return 1; // Destruction must override this request to keep the watch.
    }
    return 0; // Stops callbacks, but leaves the queue available for destruction.
}

void* Worker(void*) {
    callback_tls = 0x1729;
    ALooper* looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    ASensorManager* manager = ASensorManager_getInstanceForPackage("com.gesher.ndksensors");
    Check(looper != nullptr && manager != nullptr, "manager and worker Looper");
    if (!looper || !manager) return nullptr;
    ASensorList list = nullptr;
    int count = ASensorManager_getSensorList(manager, &list);
    const ASensor* accel = nullptr;
    const ASensor* light = nullptr;
    for (int i = 0; i < count; ++i) {
        const char* name = ASensor_getName(list[i]);
        if (name && std::strcmp(name, "Gesher Test Accelerometer") == 0) accel = list[i];
        if (name && std::strcmp(name, "Gesher Test Light Sensor") == 0) light = list[i];
    }
    Check(accel != nullptr && light != nullptr, "explicit test supplier inventory");
    if (!accel || !light) return nullptr;
    ASensorList again = nullptr;
    Check(ASensorManager_getSensorList(manager, &again) == count && again == list,
          "stable list and sensor identities");
    Check(ASensor_getMinDelay(accel) >= 5000 && ASensor_getResolution(accel) > 0,
          "effective sensor metadata");
    Check(ASensor_getReportingMode(accel) == AREPORTING_MODE_CONTINUOUS
          && ASensor_getReportingMode(light) == AREPORTING_MODE_ON_CHANGE,
          "reporting modes");
    Check(ASensor_getFifoMaxEventCount(accel) == 0 && !ASensor_isWakeUpSensor(accel)
          && !ASensor_isDirectChannelTypeSupported(accel, ASENSOR_DIRECT_CHANNEL_TYPE_SHARED_MEMORY),
          "no invented FIFO wakeup or direct channel");
    Check(ASensorManager_createEventQueue(manager, nullptr, 37, nullptr, nullptr) == nullptr,
          "null Looper rejected");
    ASensorEventQueue* queue = ASensorManager_createEventQueue(manager, looper, 37, nullptr, nullptr);
    Check(queue != nullptr, "identifier queue");
    if (!queue) return nullptr;
    ASensorEvent batch[64]{};
    Check(ASensorEventQueue_getEvents(queue, batch, 64) == -EAGAIN, "empty read error");
    Check(ASensorEventQueue_setEventRate(queue, accel, 50000) == -EINVAL,
          "rate change does not enable sensor");
    Check(ASensorEventQueue_registerSensor(queue, accel, 50000, 0) == 0,
          "accelerometer registration");
    // Release apps accept this request with effective limits. This supplier's
    // hardware minimum is 10 ms, so the guest fixture cannot isolate the 5 ms
    // permission cap; the production host tests cover that and debug denial.
    Check(ASensorEventQueue_setEventRate(queue, accel, 1000) == 0,
          "release target 36 accepts the fast request");
    Check(ASensorEventQueue_setEventRate(queue, accel, 50000) == 0,
          "restore sampling period for queue checks");
    Check(ASensorEventQueue_registerSensor(queue, light, 20000, 0) == 0,
          "light registration");
    Check(AwaitIdent(37) == 37, "Looper identifier readiness");
    usleep(160000); // Produce more than one sample before the first partial read.
    bool saw_accel = false;
    bool saw_light = false;
    int64_t last_accel = 0;
    auto inspect = [&](const ASensorEvent& event) {
        if (event.sensor == ASensor_getHandle(accel)) {
            Check(Valid(event, accel) && event.timestamp >= last_accel, "accelerometer event layout and monotonic time");
            Check(std::fabs(event.acceleration.x) < 0.0001f && std::fabs(event.acceleration.y) < 0.0001f
                  && std::fabs(event.acceleration.z - 9.80665f) < 0.0001f
                  && event.acceleration.status == ASENSOR_STATUS_ACCURACY_HIGH,
                  "accelerometer values and vector status ABI");
            last_accel = event.timestamp;
            saw_accel = true;
        } else if (event.sensor == ASensor_getHandle(light)) {
            Check(Valid(event, light) && event.light == 250.0f, "on-change light value and ABI");
            saw_light = true;
        } else Check(false, "unrequested event");
    };
    const ssize_t first = ASensorEventQueue_getEvents(queue, batch, 1);
    Check(first == 1, "partial read");
    if (first == 1) inspect(batch[0]);
    Check(ASensorEventQueue_hasEvents(queue) == 1 && ALooper_pollOnce(0, nullptr, nullptr, nullptr) == 37,
          "partial drain keeps readiness");
    const int64_t deadline = Now() + 5000000000LL;
    while (Now() < deadline && (!saw_accel || !saw_light)) {
        ssize_t received = ASensorEventQueue_getEvents(queue, batch, 64);
        if (received == -EAGAIN) { ALooper_pollOnce(50, nullptr, nullptr, nullptr); continue; }
        Check(received >= 0, "stream read");
        if (received < 0) break;
        for (ssize_t i = 0; i < received; ++i) inspect(batch[i]);
    }
    Check(saw_accel && saw_light, "both registered streams delivered");
    Check(ASensorEventQueue_disableSensor(queue, accel) == 0
          && ASensorEventQueue_disableSensor(queue, light) == 0, "disable streams");
    while (ASensorEventQueue_getEvents(queue, batch, 64) > 0) {}
    usleep(70000);
    Check(ASensorEventQueue_hasEvents(queue) == 0, "no events after disable");
    Check(ASensorManager_destroyEventQueue(manager, queue) == 0, "identifier queue teardown");

    for (int mode = 0; mode < 2; ++mode) {
        const bool destroy = mode != 0;
        CallbackState state{manager, nullptr, accel, destroy};
        state.queue = ASensorManager_createEventQueue(manager, looper, ALOOPER_POLL_CALLBACK, Callback, &state);
        Check(state.queue != nullptr, "callback queue");
        if (!state.queue) continue;
        Check(ASensorEventQueue_registerSensor(state.queue, accel, 50000, 0) == 0,
              "callback registration");
        const int64_t limit = Now() + 5000000000LL;
        while (!state.calls && Now() < limit) ALooper_pollOnce(50, nullptr, nullptr, nullptr);
        Check(state.calls == 1, "guest callback invoked exactly once");
        const int64_t quiet = Now() + 150000000LL;
        while (Now() < quiet) ALooper_pollOnce(20, nullptr, nullptr, nullptr);
        Check(state.calls == 1, "callback removal honored");
        if (state.queue) Check(ASensorManager_destroyEventQueue(manager, state.queue) == 0,
                               "queue retained after callback returns zero");
    }
    return nullptr;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_gesher_ndksensors_SensorActivity_run(JNIEnv* env, jclass) {
    checks = 0;
    failures = 0;
    pthread_t worker;
    int result = pthread_create(&worker, nullptr, Worker, nullptr);
    Check(result == 0, "native worker creation");
    if (result == 0) Check(pthread_join(worker, nullptr) == 0, "native worker join");
    char report[256];
    std::snprintf(report, sizeof(report), "NDK sensors guest: %s; %d checks, %d failures; inventory, events, Looper, callback TLS, teardown",
                  failures == 0 ? "all passed" : "FAILED", checks.load(), failures.load());
    return env->NewStringUTF(report);
}
