// Calls the registered production NDK entry points. Only the Windows sensor
// supplier, authenticated policy transport and Android Looper boundary are
// controlled; eventfd/readiness, queue ownership and ABI packing are real.
#include <gesher/bionic_shim.h>
#include <utils/Looper.h>

#include "abi_types.h"
#include "sensors_ndk.h"
#include "sensor_access.h"
#include "sensors.h"

#include <errno.h>
#include <dirent.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <future>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

using namespace std::chrono_literals;
namespace hub = gesher::devices::sensors;
using Event = gesher::bionic::sensors_ndk::SensorEvent;
using FnCallback = int(BIONIC_SYSV*)(int, int, void*);

namespace {
int checks = 0;
int failures = 0;
void Check(bool condition, const char* message) {
  ++checks;
  if (!condition) { ++failures; std::fprintf(stderr, "FAIL %s\n", message); }
}
std::unordered_map<std::string, void*> symbols;
std::atomic<int> loaded_entries{0};
std::mutex access_mutex;
hub::AccessState access_state{true, true, true, false, false, 1};
std::atomic<void (*)()> access_changed{nullptr};
std::atomic<int> initialize_calls{0};
std::mutex source_mutex;
std::vector<hub::Sensor> fixture_sensors;
struct Subscription { hub::Sink* sink; int handle; int period; };
std::vector<Subscription> subscriptions;
int enable_error = 0;

void Access(bool active, bool capped = true, bool debug = false, bool privacy = false,
            bool available = true) {
  {
    std::lock_guard<std::mutex> lock(access_mutex);
    access_state = {available, active, capped, privacy, debug, access_state.generation + 1};
  }
  if (auto notify = access_changed.load()) notify();
}
void Emit(int handle, const hub::Reading& reading, int64_t timestamp) {
  std::lock_guard<std::mutex> lock(source_mutex);
  for (const auto& subscription : subscriptions) {
    if (subscription.handle == handle) subscription.sink->OnEvent(handle, reading, timestamp);
  }
}
int Period(int handle) {
  std::lock_guard<std::mutex> lock(source_mutex);
  for (const auto& subscription : subscriptions) if (subscription.handle == handle) return subscription.period;
  return -1;
}
size_t Fds() {
  DIR* directory = opendir("/proc/self/fd");
  if (directory == nullptr) return 0;
  size_t count = 0;
  while (readdir(directory) != nullptr) ++count;
  closedir(directory);
  return count;
}
template<class T> T Api(const char* name) {
  auto found = symbols.find(name);
  if (found == symbols.end()) { std::fprintf(stderr, "missing symbol %s\n", name); std::exit(2); }
  return reinterpret_cast<T>(found->second);
}

using GetManagerFn = void*(BIONIC_SYSV*)();
using GetListFn = int(BIONIC_SYSV*)(void*, const void* const**);
using DefaultFn = const void*(BIONIC_SYSV*)(void*, int);
using DefaultExFn = const void*(BIONIC_SYSV*)(void*, int, bool);
using CreateFn = void*(BIONIC_SYSV*)(void*, void*, int, FnCallback, void*);
using DestroyFn = int(BIONIC_SYSV*)(void*, void*);
using SensorFn = int(BIONIC_SYSV*)(void*, const void*);
using RateFn = int(BIONIC_SYSV*)(void*, const void*, int32_t);
using RegisterFn = int(BIONIC_SYSV*)(void*, const void*, int32_t, int64_t);
using HasFn = int(BIONIC_SYSV*)(void*);
using ReadFn = int64_t(BIONIC_SYSV*)(void*, Event*, size_t);
using IntGetter = int(BIONIC_SYSV*)(const void*);
using StringGetter = const char*(BIONIC_SYSV*)(const void*);
using FloatGetter = float(BIONIC_SYSV*)(const void*);

struct Client {
  void* manager = Api<GetManagerFn>("ASensorManager_getInstance")();
  const void* accel = Api<DefaultFn>("ASensorManager_getDefaultSensor")(manager, 1);
  const void* light = Api<DefaultFn>("ASensorManager_getDefaultSensor")(manager, 5);
  const void* rotation = Api<DefaultFn>("ASensorManager_getDefaultSensor")(manager, 11);
  android::sp<android::Looper> looper = android::sp<android::Looper>::make(true);
  CreateFn create = Api<CreateFn>("ASensorManager_createEventQueue");
  DestroyFn destroy = Api<DestroyFn>("ASensorManager_destroyEventQueue");
  SensorFn enable = Api<SensorFn>("ASensorEventQueue_enableSensor");
  SensorFn disable = Api<SensorFn>("ASensorEventQueue_disableSensor");
  RateFn rate = Api<RateFn>("ASensorEventQueue_setEventRate");
  RegisterFn reg = Api<RegisterFn>("ASensorEventQueue_registerSensor");
  HasFn has = Api<HasFn>("ASensorEventQueue_hasEvents");
  ReadFn read = Api<ReadFn>("ASensorEventQueue_getEvents");
};

void Metadata() {
  Client api;
  auto list = Api<GetListFn>("ASensorManager_getSensorList");
  const void* const* values = nullptr;
  Check(list(api.manager, &values) == 5 && values != nullptr, "real hub sensor inventory exposed");
  Check(list(nullptr, &values) == -EINVAL && list(api.manager, nullptr) == 5, "list manager validation and optional output");
  Check(Api<void*(BIONIC_SYSV*)(const char*)>("ASensorManager_getInstanceForPackage")("some.other.package") == api.manager,
        "claimed package does not choose another policy");
  Check(api.accel == values[0] && api.light == values[3] && api.rotation == values[4], "stable default sensor identities");
  Check(Api<DefaultExFn>("ASensorManager_getDefaultSensorEx")(api.manager, 1, true) == nullptr,
        "no fabricated wakeup sensor");
  Check(Api<DefaultFn>("ASensorManager_getDefaultSensor")(api.manager, 99) == nullptr, "unknown default sensor absent");
  Check(std::strcmp(Api<StringGetter>("ASensor_getName")(api.accel), "fixture acceleration") == 0,
        "supplier name retained");
  Check(std::strcmp(Api<StringGetter>("ASensor_getVendor")(api.accel), "controlled supplier") == 0,
        "supplier vendor retained");
  Check(std::strcmp(Api<StringGetter>("ASensor_getStringType")(api.light), "android.sensor.light") == 0,
        "Android type string");
  Check(Api<IntGetter>("ASensor_getType")(api.accel) == 1 && Api<IntGetter>("ASensor_getHandle")(api.accel) == 11,
        "type and handle distinct");
  Check(Api<FloatGetter>("ASensor_getResolution")(api.accel) == 0.03125f, "fractional resolution ABI");
  Check(Api<IntGetter>("ASensor_getMinDelay")(api.accel) == 5000, "unprivileged metadata reflects 200Hz cap");
  Access(true, false);
  Check(Api<IntGetter>("ASensor_getMinDelay")(api.accel) == 1000, "allowed high-rate metadata retains hardware interval");
  Check(Api<IntGetter>("ASensor_getReportingMode")(api.light) == 1, "on-change reporting mode");
  Check(Api<IntGetter>("ASensor_getFifoMaxEventCount")(api.accel) == 0 &&
        Api<IntGetter>("ASensor_getFifoReservedEventCount")(api.accel) == 0, "no invented hardware FIFO");
  const void* const* dynamic = nullptr;
  Check(Api<int64_t(BIONIC_SYSV*)(void*, const void* const**)>("ASensorManager_getDynamicSensorList")(api.manager, &dynamic) == 0,
        "dynamic sensor inventory remains empty");
  Check(Api<StringGetter>("ASensor_getName")(nullptr) == nullptr &&
        Api<IntGetter>("ASensor_getType")(nullptr) == -1 &&
        Api<IntGetter>("ASensor_getHandle")(nullptr) == -1 &&
        Api<IntGetter>("ASensor_getReportingMode")(nullptr) == -1 &&
        Api<IntGetter>("ASensor_getMinDelay")(nullptr) == INT32_MIN &&
        Api<IntGetter>("ASensor_getFifoMaxEventCount")(nullptr) == -1 &&
        std::isnan(Api<FloatGetter>("ASensor_getResolution")(nullptr)), "Android null metadata sentinels");
  Check(!Api<bool(BIONIC_SYSV*)(const void*)>("ASensor_isWakeUpSensor")(api.accel) &&
        !Api<bool(BIONIC_SYSV*)(const void*, int)>("ASensor_isDirectChannelTypeSupported")(api.accel, 1) &&
        Api<IntGetter>("ASensor_getHighestDirectReportRateLevel")(api.accel) == 0, "unsupported hardware capabilities remain unavailable");
  Check(Api<int(BIONIC_SYSV*)(void*, int, size_t)>("ASensorManager_createSharedMemoryDirectChannel")(api.manager, 0, 4096) < 0 &&
        Api<int(BIONIC_SYSV*)(void*, const void*, size_t)>("ASensorManager_createHardwareBufferDirectChannel")(api.manager, &api, 4096) < 0,
        "unsupported direct channels fail");
}

void Queues() {
  Access(true);
  Client api;
  const size_t initial_fds = Fds();
  auto no_ident = android::sp<android::Looper>::make(false);
  Check(api.create(nullptr, api.looper.get(), 37, nullptr, nullptr) == nullptr &&
        api.create(api.manager, nullptr, 37, nullptr, nullptr) == nullptr &&
        api.create(api.manager, api.looper.get(), -2, nullptr, nullptr) == nullptr &&
        api.create(api.manager, no_ident.get(), 37, nullptr, nullptr) == nullptr,
        "invalid queue registration rejected and FD released");
  Check(Fds() == initial_fds, "failed registration does not leak FDs");
  void* queue = api.create(api.manager, api.looper.get(), 37, nullptr, &api);
  Check(queue != nullptr, "queue created");
  Event event{};
  Check(api.has(queue) == 0 && api.read(queue, &event, 1) == -EAGAIN, "empty queue contract");
  Check(api.has(nullptr) == -EINVAL && api.read(queue, nullptr, 1) == -EINVAL, "queue API null checks");
  Check(api.rate(queue, api.accel, 10000) == -EINVAL && Period(11) == -1, "rate cannot enable an unregistered sensor");
  Check(api.reg(queue, api.accel, -1, 0) == -EINVAL && api.reg(queue, api.accel, 10000, -1) == -EINVAL,
        "negative periods and latency rejected");
  Check(api.enable(queue, api.accel) == 0 && Period(11) == 200000, "default sampling period is 200ms");
  Check(api.reg(queue, api.accel, 2000000, 0) == 0 && Period(11) == 1000000,
        "registration honors sensor maximum delay");
  Check(api.rate(queue, api.accel, 2000000) == 0 && Period(11) == 2000000,
        "setEventRate follows its distinct minimum-only clamp");
  Check(api.rate(queue, api.accel, 10000) == 0 && Period(11) == 10000, "rate updates existing subscription");
  Check(api.reg(queue, api.rotation, 10000, 900000) == 0, "max batch latency permits immediate streaming without hardware FIFO");
  Check(Api<int(BIONIC_SYSV*)(void*, bool)>("ASensorEventQueue_requestAdditionalInfoEvents")(queue, true) == 0,
        "additional-info selection valid without manufactured events");
  hub::Reading reading; reading.values[0] = 1.25f; reading.values[1] = -2.5f; reading.values[2] = 9.5f;
  reading.values[3] = 0.625f; reading.values[4] = -1; reading.accuracy = 2;
  Emit(11, reading, 1000000000);
  Emit(15, reading, 1010000000);
  Check(api.has(queue) == 1, "real hub events queued");
  int fd = -1, events = 0; void* data = nullptr;
  Check(api.looper->PollOnce(0, &fd, &events, &data) == 37 && fd >= 0 && (events & 1) && data == &api,
        "noncallback Looper returns ident FD event data");
  Check(api.read(queue, &event, 0) == 0 && api.has(queue) == 1, "zero-count read retains pending event");
  Check(api.read(queue, &event, 1) == 1 && event.version == 104 && event.sensor == 11 && event.type == 1 &&
        event.timestamp == 1000000000 && event.data[0] == 1.25f && event.data[1] == -2.5f &&
        reinterpret_cast<unsigned char*>(event.data)[12] == 2 && event.flags == 0 && event.reserved0 == 0,
        "104-byte LP64 vector ABI including accuracy and timestamp");
  Check(api.looper->PollOnce(0) == 37, "partial drain retains readiness");
  Check(api.read(queue, &event, 1) == 1 && event.type == 11 && event.data[3] == 0.625f && event.data[4] == -1,
        "rotation quaternion W is not overwritten by accuracy");
  Check(api.has(queue) == 0 && api.looper->PollOnce(0) == -3 && api.read(queue, &event, 1) == -EAGAIN,
        "full drain clears readiness and subsequent read is EAGAIN");
  enable_error = -EIO;
  Check(api.rate(queue, api.accel, 20000) == -EIO && Period(11) == 10000, "backend rate failure propagated without replacing subscription");
  enable_error = 0;
  for (int i = 0; i < 300; ++i) Emit(11, reading, 2000000000LL + static_cast<int64_t>(i) * 10000000);
  Event backlog[300];
  const int64_t drained = api.read(queue, backlog, 300);
  Check(drained == 256 && backlog[0].timestamp == 2440000000LL && backlog[255].timestamp == 4990000000LL,
        "bounded overflow retains newest ordered samples");
  Check(api.disable(queue, api.accel) == 0 && Period(11) == -1, "disable removes sampler ownership");
  Emit(11, reading, 6000000000LL);
  Check(api.has(queue) == 0 && api.rate(queue, api.accel, 10000) == -EINVAL, "disabled sensor stays silent and is not reenabled by rate");
  Check(api.destroy(api.manager, queue) == 0 && api.destroy(api.manager, queue) == -EINVAL &&
        api.has(queue) == -EINVAL && api.looper->Count() == 0, "destroy invalidates handle and removes Looper registration");
  Check(subscriptions.empty() && Fds() == initial_fds, "destroy releases all subscriptions and FDs");
}

void Policies() {
  Client api;
  Access(false, true);
  void* queue = api.create(api.manager, api.looper.get(), 38, nullptr, nullptr);
  Check(api.reg(queue, api.accel, 1000, 0) == 0 && Period(11) == 5000, "inactive known app can register, release high rate capped");
  hub::Reading reading; reading.values[0] = 4;
  const int old_initializations = initialize_calls.load();
  Emit(11, reading, 1000000000);
  Check(api.has(queue) == 0, "inactive samples are not buffered");
  Access(true, true);
  Emit(11, reading, 1010000000);
  Check(api.has(queue) == 1, "active process resumes delivery");
  Access(false, true);
  Check(api.looper->PollOnce(0) == -3, "observed denial immediately clears pending readiness");
  Access(true, true);
  Event event{};
  Check(api.read(queue, &event, 1) == -EAGAIN, "new access generation cannot recover pre-denial events");
  Emit(11, reading, 1020000000);
  Check(api.read(queue, &event, 1) == 1 && event.timestamp == 1020000000, "fresh generation accepts new events");
  Access(true, true, true);
  Check(api.rate(queue, api.accel, 1000) == -EPERM && Period(11) == 5000, "debuggable missing permission rejects high rate");
  Check(api.rate(queue, api.accel, 5000) == 0, "debuggable capped-rate request works");
  Access(true, false);
  Check(api.rate(queue, api.accel, 1000) == 0 && Period(11) == 1000, "permitted high sampling rate works");
  Access(true, false, false, true);
  Emit(11, reading, 2000000000);
  Emit(11, reading, 2001000000);
  Emit(11, reading, 2005000000);
  Event events[3];
  Check(api.read(queue, events, 3) == 2 && events[1].timestamp == 2005000000,
        "privacy transition caps already-enabled high-rate delivery");
  Check(initialize_calls.load() >= old_initializations && api.has(queue) == 0, "policy path has valid final state");
  const int unchanged_initializations = initialize_calls.load();
  for (int i = 0; i < 20; ++i) { Emit(11, reading, 3000000000LL + i * 10000000LL); api.has(queue); api.read(queue, &event, 1); }
  Check(initialize_calls.load() == unchanged_initializations, "samples and event drains perform no policy IPC initialization");
  auto blocked = std::async(std::launch::async, [&] { return api.looper->PollOnce(2000); });
  Check(blocked.wait_for(20ms) == std::future_status::timeout, "empty queue poll is initially blocked");
  Access(false, true, false, false, false);
  Check(blocked.wait_for(1s) == std::future_status::ready && blocked.get() == 38,
        "policy service loss wakes a blocked Looper reader");
  Check(api.has(queue) == -EPIPE && api.read(queue, &event, 1) == -EPIPE &&
        api.create(api.manager, api.looper.get(), 39, nullptr, nullptr) == nullptr,
        "policy service loss fails closed");
  Access(true);
  Check(api.has(queue) == 0 && api.looper->PollOnce(0) == -3,
        "recovery clears error readiness without restoring samples");
  Check(api.destroy(api.manager, queue) == 0, "destroy remains available after policy loss");
}

struct CallbackState {
  Client* api;
  void* queue = nullptr;
  int calls = 0;
  bool destroy = false;
  int keep = 1;
};
int BIONIC_SYSV OnCallback(int fd, int events, void* data) {
  auto* state = static_cast<CallbackState*>(data);
  ++state->calls;
  Check(fd >= 0 && (events & 1) && loaded_entries.load() > 0, "guest callback receives readiness after loaded-code TLS entry");
  Event event;
  Check(state->api->read(state->queue, &event, 1) == 1, "callback reentry drains event");
  if (state->destroy) Check(state->api->destroy(state->api->manager, state->queue) == 0, "callback can destroy its own queue");
  return state->keep;
}

void CallbacksAndChurn() {
  Client api;
  hub::Reading reading; reading.values[0] = 1;
  const size_t initial_fds = Fds();
  CallbackState callback{&api};
  callback.queue = api.create(api.manager, api.looper.get(), -2, OnCallback, &callback);
  Check(api.enable(callback.queue, api.accel) == 0, "callback queue registered");
  Emit(11, reading, 1000000000);
  Check(api.looper->PollOnce(0) == -2 && callback.calls == 1 && api.looper->Count() == 1, "callback keep-one retains watch");
  callback.keep = 0;
  Emit(11, reading, 2000000000);
  Check(api.looper->PollOnce(0) == -2 && callback.calls == 2 && api.looper->Count() == 0, "callback zero unregisters watch");
  Check(api.destroy(api.manager, callback.queue) == 0, "unregistered callback queue remains destroyable");
  callback.calls = 0; callback.keep = 1; callback.destroy = true;
  callback.queue = api.create(api.manager, api.looper.get(), -2, OnCallback, &callback);
  Check(api.enable(callback.queue, api.accel) == 0, "self-destroy callback registered");
  Emit(11, reading, 3000000000);
  Check(api.looper->PollOnce(0) == -2 && callback.calls == 1 && api.looper->Count() == 0,
        "self-destroy wins over callback return-one without use-after-free");
  for (int i = 0; i < 100; ++i) {
    void* queue = api.create(api.manager, api.looper.get(), 50, nullptr, nullptr);
    Check(api.reg(queue, api.accel, 10000, 0) == 0, "churn registers sensor");
    std::atomic<bool> stop{false};
    std::thread producer([&] { int64_t now = 1; while (!stop.load()) { Emit(11, reading, now); now += 10000000; } });
    Event event;
    for (int read = 0; read < 5; ++read) api.read(queue, &event, 1);
    Check(api.destroy(api.manager, queue) == 0, "destroy serializes against in-flight producer");
    stop = true;
    producer.join();
  }
  Check(subscriptions.empty() && api.looper->Count() == 0 && Fds() == initial_fds, "queue churn has no subscription/FD leak");
}

}  // namespace

namespace gesher::bionic {
void AddBionicSymbol(const char* name, void* symbol) { symbols[name] = symbol; }
void EnterLoadedCode() { ++loaded_entries; }
}
namespace gesher::devices::sensors {
const std::vector<Sensor>& List() { return fixture_sensors; }
bool InitializeNdkAccess() { ++initialize_calls; return NdkAccessState().available; }
AccessState NdkAccessState() { std::lock_guard<std::mutex> lock(access_mutex); return access_state; }
void SetNdkAccessChangedCallback(void (*callback)()) { access_changed = callback; }
int Enable(Sink* sink, int handle, int period) {
  std::lock_guard<std::mutex> lock(source_mutex);
  if (enable_error != 0) return enable_error;
  for (auto& subscription : subscriptions) {
    if (subscription.sink == sink && subscription.handle == handle) { subscription.period = period; return 0; }
  }
  subscriptions.push_back({sink, handle, period});
  return 0;
}
int Disable(Sink* sink, int handle) {
  std::lock_guard<std::mutex> lock(source_mutex);
  for (auto it = subscriptions.begin(); it != subscriptions.end(); ++it) {
    if (it->sink == sink && it->handle == handle) { subscriptions.erase(it); break; }
  }
  return 0;
}
void DisableAll(Sink* sink) {
  std::lock_guard<std::mutex> lock(source_mutex);
  for (auto it = subscriptions.begin(); it != subscriptions.end();) {
    if (it->sink == sink) it = subscriptions.erase(it);
    else ++it;
  }
}
}

int main(int argc, char** argv) {
  const bool sensorless = argc > 1 && std::strcmp(argv[1], "--sensorless") == 0;
  const bool inventory_only = argc > 1 && std::strcmp(argv[1], "--inventory-only") == 0;
  if (!sensorless) {
    const int types[] = {1, 2, 4, 5, 11};
    for (int i = 0; i < 5; ++i) {
      hub::Sensor sensor;
      sensor.handle = 11 + i; sensor.type = types[i];
      sensor.name = i == 0 ? "fixture acceleration" : "fixture sensor";
      sensor.vendor = "controlled supplier"; sensor.resolution = 0.03125f;
      sensor.minDelayUs = i == 3 ? 0 : 1000;
      sensor.maxDelayUs = 1000000;
      sensor.flags = i == 3 ? hub::kOnChange : hub::kContinuous;
      fixture_sensors.push_back(sensor);
    }
  }
  gesher::bionic::sensors_ndk::Register();
  Check(symbols.size() == 32, "all NDK sensor exports registered exactly once");
  if (inventory_only) {
    Client api;
    const void* const* list = nullptr;
    Check(Api<GetListFn>("ASensorManager_getSensorList")(api.manager, &list) == 5,
          "five available supplier sensors reachable through NDK");
    Check(api.accel != nullptr && api.light != nullptr && api.rotation != nullptr,
          "available default sensors reachable through NDK");
  } else if (sensorless) {
    Client api;
    const void* const* list = nullptr;
    Check(Api<GetListFn>("ASensorManager_getSensorList")(api.manager, &list) == 0 && api.accel == nullptr,
          "sensorless host remains sensorless");
  } else {
    Metadata(); Queues(); Policies(); CallbacksAndChurn();
  }
  std::printf("NDK sensor production bridge: %d checks, %d failures\n", checks, failures);
  return failures ? 1 : 0;
}
