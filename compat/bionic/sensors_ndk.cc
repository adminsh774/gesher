// libandroid.so's sensor entry points over the same Windows sensor hub as
// SystemSensorManager. Contract sources and qualification: docs/ndk-sensors.md.
#include "sensors_ndk.h"

#include "abi_types.h"
#include "../devices/sensor_access.h"
#include "../devices/sensors.h"

#include <gesher/bionic_shim.h>
#include <utils/Looper.h>

#include <errno.h>
#include <sys/eventfd.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <deque>
#include <limits>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace gesher::bionic::sensors_ndk {
namespace {

namespace hub = gesher::devices::sensors;
using android::Looper;
using Sensor = hub::Sensor;
using Callback = int(BIONIC_SYSV*)(int, int, void*);
constexpr int kNormalPeriodUs = 200000;
constexpr int kCappedPeriodUs = 5000;
constexpr size_t kQueueCapacity = 256;

struct Manager {};
Manager g_manager;

bool IsManager(const Manager* manager) { return manager == &g_manager; }

const std::vector<const Sensor*>& Sensors() {
  static const auto* sensors = [] {
    auto* values = new std::vector<const Sensor*>;
    for (const Sensor& sensor : hub::List()) values->push_back(&sensor);
    return values;
  }();
  return *sensors;
}

bool IsSensor(const Sensor* sensor) {
  if (sensor == nullptr) return false;
  const auto& list = Sensors();
  return std::find(list.begin(), list.end(), sensor) != list.end();
}

bool IsRateCappedType(int type) {
  return type == hub::kAccelerometer || type == hub::kMagneticField || type == hub::kGyroscope;
}

const char* StringType(const Sensor* sensor) {
  switch (sensor->type) {
    case hub::kAccelerometer: return "android.sensor.accelerometer";
    case hub::kMagneticField: return "android.sensor.magnetic_field";
    case hub::kGyroscope: return "android.sensor.gyroscope";
    case hub::kLight: return "android.sensor.light";
    case hub::kRotationVector: return "android.sensor.rotation_vector";
    default: return "";
  }
}

// Android's native service caps release apps at 200 Hz and rejects the same
// high-rate request from a debuggable app. Hardware minDelay is enforced too.
int EffectivePeriod(const Sensor* sensor, int requested, const hub::AccessState& access,
                    bool clamp_maximum) {
  if (!access.available) return -EPIPE;
  if (IsRateCappedType(sensor->type) && requested < kCappedPeriodUs) {
    if (access.permissionCapped && access.debuggable) return -EPERM;
    if (access.permissionCapped || access.microphonePrivacy) requested = kCappedPeriodUs;
  }
  if (clamp_maximum && sensor->maxDelayUs > 0) requested = std::min(requested, sensor->maxDelayUs);
  return std::max(requested, sensor->minDelayUs);
}

class Queue;
struct QueueRegistry {
  std::mutex mutex;
  std::unordered_map<Queue*, std::shared_ptr<Queue>> queues;
};
QueueRegistry& Registry() {
  // The sampler is process-lived too. Explicit queue destruction releases its
  // resources; process teardown must not destroy the registry under its thread.
  static auto* registry = new QueueRegistry;
  return *registry;
}

// The Looper retains the adapter while invoking it; the adapter in turn holds
// the queue only for that invocation. Destroying a queue from its own guest
// callback therefore neither deadlocks nor closes an FD still being used.
class QueueCallback final : public android::LooperCallback {
 public:
  QueueCallback(std::weak_ptr<Queue> queue, Callback callback)
      : queue_(std::move(queue)), callback_(callback) {}
  int handleEvent(int fd, int events, void* data) override;

 private:
  std::weak_ptr<Queue> queue_;
  Callback callback_;
};

class Queue final : public hub::Sink {
 public:
  explicit Queue(Looper* looper) : looper_(looper) {
    fd_ = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
  }
  ~Queue() override {
    if (fd_ >= 0) close(fd_);
  }

  bool Start(const std::shared_ptr<Queue>& self, int ident, Callback callback, void* data) {
    if (fd_ < 0) return false;
    android::sp<android::LooperCallback> adapter;
    if (callback != nullptr) adapter = android::sp<QueueCallback>::make(self, callback);
    return looper_->addFd(fd_, ident, Looper::EVENT_INPUT, adapter, data) == 1;
  }

  void Destroy() {
    // API mutations take operation_, then the sampler's lock. OnEvent takes
    // only events_mutex_: never acquire operation_ from a sampler callback.
    std::lock_guard<std::mutex> operation(operation_);
    if (closed_.exchange(true, std::memory_order_acq_rel)) return;
    hub::DisableAll(this);
    looper_->removeFd(fd_);
    std::lock_guard<std::mutex> events(events_mutex_);
    pending_.clear();
    rates_.clear();
    DrainWakeLocked();
  }

  bool Closed() const { return closed_.load(std::memory_order_acquire); }

  int Enable(const Sensor* sensor, int period, bool change_only) {
    const hub::AccessState access = hub::NdkAccessState();
    const int effective = EffectivePeriod(sensor, period, access, !change_only);
    if (effective < 0) return effective;
    std::lock_guard<std::mutex> operation(operation_);
    if (Closed()) return -EINVAL;
    Rate previous{};
    bool had_previous = false;
    {
      std::lock_guard<std::mutex> events(events_mutex_);
      auto old = rates_.find(sensor->handle);
      had_previous = old != rates_.end();
      if (change_only && !had_previous) return -EINVAL;
      if (had_previous) previous = old->second;
      rates_[sensor->handle] = {sensor, period, 0, !change_only};
    }
    // Install the event state before enabling an on-change source: its first
    // reading may arrive immediately and may be its only reading for a while.
    const int result = hub::Enable(this, sensor->handle, effective);
    if (result != 0) {
      std::lock_guard<std::mutex> events(events_mutex_);
      if (had_previous) rates_[sensor->handle] = previous;
      else rates_.erase(sensor->handle);
    }
    return result;
  }

  int Disable(const Sensor* sensor) {
    std::lock_guard<std::mutex> operation(operation_);
    if (Closed()) return -EINVAL;
    const int result = hub::Disable(this, sensor->handle);
    if (result == 0) {
      std::lock_guard<std::mutex> events(events_mutex_);
      rates_.erase(sensor->handle);
    }
    return result;
  }

  uint64_t DeliveryEpoch() const override {
    const hub::AccessState access = hub::NdkAccessState();
    return !Closed() && access.available && access.active ? access.generation : 0;
  }

  void OnEvent(int handle, const hub::Reading& reading, int64_t timestamp) override {
    std::lock_guard<std::mutex> events(events_mutex_);
    // Snapshot under the same lock as policy invalidation: an in-flight
    // producer must not re-publish an older generation after it was cleared.
    const hub::AccessState access = hub::NdkAccessState();
    RefreshAccessLocked(access);
    if (Closed() || !access.available || !access.active) return;
    auto found = rates_.find(handle);
    if (found == rates_.end()) return;
    Rate& rate = found->second;
    const int effective = EffectivePeriod(rate.sensor, rate.requested, access, rate.clamp_maximum);
    if (effective < 0) return;
    // A policy transition may lower the allowed rate while the hub still has
    // the old subscription. Enforce the current limit at delivery as well.
    if (rate.last_timestamp != 0 && timestamp >= rate.last_timestamp &&
        timestamp - rate.last_timestamp < static_cast<int64_t>(effective) * 1000) return;
    rate.last_timestamp = timestamp;
    SensorEvent event{};
    event.version = sizeof(event);
    event.sensor = handle;
    event.type = rate.sensor->type;
    event.timestamp = timestamp;
    std::memcpy(event.data, reading.values, sizeof(event.data));
    if (IsRateCappedType(event.type)) {
      // ASensorVector has three floats followed by int8_t status and padding.
      // Rotation-vector data[3] is quaternion W, and must not be overwritten.
      auto* vector = reinterpret_cast<unsigned char*>(event.data);
      vector[12] = static_cast<unsigned char>(reading.accuracy);
      vector[13] = vector[14] = vector[15] = 0;
    }
    const bool was_empty = pending_.empty();
    if (pending_.size() == kQueueCapacity) pending_.pop_front();
    pending_.push_back(event);
    if (was_empty) WakeLocked();
  }

  // There is no public NDK flush entry point and the shared sampler only
  // flushes an explicitly selected sink. Do not invent completion events.
  int OnFlush(const std::vector<int>&) override { return -EINVAL; }

  int HasEvents() {
    std::lock_guard<std::mutex> events(events_mutex_);
    const hub::AccessState access = hub::NdkAccessState();
    RefreshAccessLocked(access);
    if (Closed()) return -EINVAL;
    if (!access.available) return -EPIPE;
    return pending_.empty() ? 0 : 1;
  }

  void AccessChanged() {
    std::lock_guard<std::mutex> events(events_mutex_);
    RefreshAccessLocked(hub::NdkAccessState());
  }

  int64_t Read(SensorEvent* output, size_t count) {
    std::lock_guard<std::mutex> events(events_mutex_);
    const hub::AccessState access = hub::NdkAccessState();
    RefreshAccessLocked(access);
    if (Closed()) return -EINVAL;
    if (!access.available) return -EPIPE;
    if (pending_.empty()) return -EAGAIN;
    const size_t actual = std::min(count, pending_.size());
    for (size_t i = 0; i < actual; ++i) {
      output[i] = pending_.front();
      pending_.pop_front();
    }
    // Keep the level-triggered FD readable across a partial drain. Taking
    // its eventfd count here only when empty prevents lost wakeups.
    if (pending_.empty()) DrainWakeLocked();
    const hub::AccessState after = hub::NdkAccessState();
    if (!after.available || !after.active || after.generation != access.generation) {
      std::memset(output, 0, actual * sizeof(*output));
      RefreshAccessLocked(after);
      return after.available ? -EAGAIN : -EPIPE;
    }
    return static_cast<int64_t>(actual);
  }

 private:
  struct Rate {
    const Sensor* sensor = nullptr;
    int requested = 0;
    int64_t last_timestamp = 0;
    bool clamp_maximum = true;
  };

  void RefreshAccessLocked(const hub::AccessState& access) {
    if (!access.available || !access.active || epoch_ != access.generation) {
      pending_.clear();
      DrainWakeLocked();
      // A lost policy service is a queue error. Wake a blocked Looper reader
      // so getEvents/hasEvents can report -EPIPE; ordinary inactive access
      // instead stays silent. This is error readiness, not a fabricated sample.
      if (!access.available && !Closed()) WakeLocked();
      if (epoch_ != access.generation) {
        for (auto& [handle, rate] : rates_) rate.last_timestamp = 0;
      }
      epoch_ = access.generation;
    }
  }

  void WakeLocked() {
    const uint64_t one = 1;
    ssize_t result;
    do { result = write(fd_, &one, sizeof(one)); } while (result < 0 && errno == EINTR);
    // EAGAIN means the level-triggered counter is already readable.
  }

  void DrainWakeLocked() {
    uint64_t count;
    ssize_t result;
    do { result = read(fd_, &count, sizeof(count)); } while (result > 0 || (result < 0 && errno == EINTR));
  }

  android::sp<Looper> looper_;
  int fd_ = -1;
  std::atomic<bool> closed_{false};
  std::mutex operation_;
  std::mutex events_mutex_;
  std::unordered_map<int, Rate> rates_;
  std::deque<SensorEvent> pending_;
  uint64_t epoch_ = 0;
};

int QueueCallback::handleEvent(int fd, int events, void* data) {
  auto queue = queue_.lock();
  if (queue == nullptr || queue->Closed()) return 0;
  if ((events & Looper::EVENT_INPUT) != 0 && queue->HasEvents() == 0) return 1;
  EnterLoadedCode();  // System V callback, including translated AArch64 callbacks.
  const int keep = callback_(fd, events, data);
  return queue->Closed() ? 0 : keep;
}

std::shared_ptr<Queue> FindQueue(Queue* queue) {
  auto& registry = Registry();
  std::lock_guard<std::mutex> lock(registry.mutex);
  auto found = registry.queues.find(queue);
  return found == registry.queues.end() ? nullptr : found->second;
}

void AccessChanged() {
  std::vector<std::shared_ptr<Queue>> queues;
  {
    auto& registry = Registry();
    std::lock_guard<std::mutex> lock(registry.mutex);
    queues.reserve(registry.queues.size());
    for (const auto& entry : registry.queues) queues.push_back(entry.second);
  }
  for (const auto& queue : queues) queue->AccessChanged();
}

Manager* BIONIC_SYSV GetManager() {
  hub::InitializeNdkAccess();
  return &g_manager;
}
Manager* BIONIC_SYSV GetManagerForPackage(const char*) {
  // Policy comes from the authenticated process. An application-supplied
  // package string is never authority to use another APK's permissions.
  return GetManager();
}
int BIONIC_SYSV GetSensorList(Manager* manager, const Sensor* const** list) {
  if (!IsManager(manager)) return -EINVAL;
  const auto& sensors = Sensors();
  if (list != nullptr) *list = sensors.data();
  return static_cast<int>(sensors.size());
}
int64_t BIONIC_SYSV GetDynamicSensorList(Manager* manager, const Sensor* const** list) {
  if (!IsManager(manager)) return -EINVAL;
  static const Sensor* const empty[1] = {nullptr};
  if (list != nullptr) *list = empty;
  return 0;
}
const Sensor* BIONIC_SYSV GetDefaultSensorEx(Manager* manager, int type, bool wake_up) {
  if (!IsManager(manager)) return nullptr;
  for (const Sensor* sensor : Sensors()) {
    if (sensor->type == type && ((sensor->flags & 1) != 0) == wake_up) return sensor;
  }
  return nullptr;
}
const Sensor* BIONIC_SYSV GetDefaultSensor(Manager* manager, int type) {
  // The shared hub currently supplies only Android's default non-wakeup
  // accelerometer/magnetic/gyro/light/rotation-vector types.
  return GetDefaultSensorEx(manager, type, false);
}

Queue* BIONIC_SYSV CreateQueue(Manager* manager, void* looper, int ident, Callback callback, void* data) {
  if (!IsManager(manager) || looper == nullptr || (callback == nullptr && ident < 0)) return nullptr;
  if (!hub::InitializeNdkAccess()) return nullptr;
  auto queue = std::make_shared<Queue>(static_cast<Looper*>(looper));
  if (!queue->Start(queue, ident, callback, data)) return nullptr;
  auto& registry = Registry();
  std::lock_guard<std::mutex> lock(registry.mutex);
  registry.queues.emplace(queue.get(), queue);
  return queue.get();
}
int BIONIC_SYSV DestroyQueue(Manager* manager, Queue* handle) {
  if (!IsManager(manager)) return -EINVAL;
  std::shared_ptr<Queue> queue;
  {
    auto& registry = Registry();
    std::lock_guard<std::mutex> lock(registry.mutex);
    auto found = registry.queues.find(handle);
    if (found == registry.queues.end()) return -EINVAL;
    queue = std::move(found->second);
    registry.queues.erase(found);
  }
  queue->Destroy();
  return 0;
}

int BIONIC_SYSV RegisterSensor(Queue* handle, const Sensor* sensor, int32_t period, int64_t latency) {
  if (!IsSensor(sensor) || period < 0 || latency < 0) return -EINVAL;
  auto queue = FindQueue(handle);
  if (queue == nullptr) return -EINVAL;
  if (!hub::InitializeNdkAccess()) return -EPIPE;
  // The hub has no hardware FIFO. Immediate streaming satisfies a maximum
  // batch latency and must not be reported as hardware batching support.
  return queue->Enable(sensor, period, false);
}
int BIONIC_SYSV EnableSensor(Queue* queue, const Sensor* sensor) {
  return RegisterSensor(queue, sensor, kNormalPeriodUs, 0);
}
int BIONIC_SYSV DisableSensor(Queue* handle, const Sensor* sensor) {
  if (!IsSensor(sensor)) return -EINVAL;
  auto queue = FindQueue(handle);
  return queue != nullptr ? queue->Disable(sensor) : -EINVAL;
}
int BIONIC_SYSV SetEventRate(Queue* handle, const Sensor* sensor, int32_t period) {
  if (!IsSensor(sensor) || period < 0) return -EINVAL;
  auto queue = FindQueue(handle);
  if (queue == nullptr) return -EINVAL;
  if (!hub::InitializeNdkAccess()) return -EPIPE;
  return queue->Enable(sensor, period, true);
}
int BIONIC_SYSV HasEvents(Queue* handle) {
  auto queue = FindQueue(handle);
  return queue != nullptr ? queue->HasEvents() : -EINVAL;
}
int64_t BIONIC_SYSV GetEvents(Queue* handle, SensorEvent* events, size_t count) {
  if (events == nullptr) return -EINVAL;
  auto queue = FindQueue(handle);
  return queue != nullptr ? queue->Read(events, count) : -EINVAL;
}
int BIONIC_SYSV RequestAdditionalInfo(Queue* handle, bool) {
  // Selecting additional-info delivery does not assert that a sensor emits
  // such events. None of the current Windows providers produces them.
  return FindQueue(handle) != nullptr ? 0 : -EINVAL;
}

const char* BIONIC_SYSV GetName(const Sensor* sensor) { return IsSensor(sensor) ? sensor->name.c_str() : nullptr; }
const char* BIONIC_SYSV GetVendor(const Sensor* sensor) { return IsSensor(sensor) ? sensor->vendor.c_str() : nullptr; }
const char* BIONIC_SYSV GetStringType(const Sensor* sensor) { return IsSensor(sensor) ? StringType(sensor) : nullptr; }
int BIONIC_SYSV GetType(const Sensor* sensor) { return IsSensor(sensor) ? sensor->type : -1; }
int BIONIC_SYSV GetHandle(const Sensor* sensor) { return IsSensor(sensor) ? sensor->handle : -1; }
float BIONIC_SYSV GetResolution(const Sensor* sensor) { return IsSensor(sensor) ? sensor->resolution : std::nanf(""); }
int BIONIC_SYSV GetMinDelay(const Sensor* sensor) {
  if (!IsSensor(sensor)) return INT32_MIN;
  hub::InitializeNdkAccess();
  const hub::AccessState access = hub::NdkAccessState();
  return IsRateCappedType(sensor->type) && (!access.available || access.permissionCapped)
      ? std::max(sensor->minDelayUs, kCappedPeriodUs) : sensor->minDelayUs;
}
int BIONIC_SYSV GetFifoCount(const Sensor* sensor) { return IsSensor(sensor) ? 0 : -1; }
int BIONIC_SYSV GetReportingMode(const Sensor* sensor) { return IsSensor(sensor) ? (sensor->flags >> 1) & 7 : -1; }
bool BIONIC_SYSV IsWakeUpSensor(const Sensor* sensor) { return IsSensor(sensor) && (sensor->flags & 1) != 0; }
bool BIONIC_SYSV IsDirectChannelSupported(const Sensor*, int) { return false; }
int BIONIC_SYSV HighestDirectRate(const Sensor*) { return 0; }
int BIONIC_SYSV CreateMemoryChannel(Manager*, int, size_t) { return -EINVAL; }
int BIONIC_SYSV CreateBufferChannel(Manager*, const void*, size_t) { return -EINVAL; }
void BIONIC_SYSV DestroyChannel(Manager*, int) {}
int BIONIC_SYSV ConfigureDirectReport(Manager*, const Sensor*, int, int) { return -EINVAL; }

}  // namespace

void Register() {
  hub::SetNdkAccessChangedCallback(AccessChanged);
#define E(name, fn) AddBionicSymbol(name, reinterpret_cast<void*>(&fn))
  E("ASensorManager_getInstance", GetManager);
  E("ASensorManager_getInstanceForPackage", GetManagerForPackage);
  E("ASensorManager_getSensorList", GetSensorList);
  E("ASensorManager_getDynamicSensorList", GetDynamicSensorList);
  E("ASensorManager_getDefaultSensor", GetDefaultSensor);
  E("ASensorManager_getDefaultSensorEx", GetDefaultSensorEx);
  E("ASensorManager_createEventQueue", CreateQueue);
  E("ASensorManager_destroyEventQueue", DestroyQueue);
  E("ASensorManager_createSharedMemoryDirectChannel", CreateMemoryChannel);
  E("ASensorManager_createHardwareBufferDirectChannel", CreateBufferChannel);
  E("ASensorManager_destroyDirectChannel", DestroyChannel);
  E("ASensorManager_configureDirectReport", ConfigureDirectReport);
  E("ASensorEventQueue_registerSensor", RegisterSensor);
  E("ASensorEventQueue_enableSensor", EnableSensor);
  E("ASensorEventQueue_disableSensor", DisableSensor);
  E("ASensorEventQueue_setEventRate", SetEventRate);
  E("ASensorEventQueue_hasEvents", HasEvents);
  E("ASensorEventQueue_getEvents", GetEvents);
  E("ASensorEventQueue_requestAdditionalInfoEvents", RequestAdditionalInfo);
  E("ASensor_getName", GetName);
  E("ASensor_getVendor", GetVendor);
  E("ASensor_getStringType", GetStringType);
  E("ASensor_getType", GetType);
  E("ASensor_getMinDelay", GetMinDelay);
  E("ASensor_getFifoMaxEventCount", GetFifoCount);
  E("ASensor_getFifoReservedEventCount", GetFifoCount);
  E("ASensor_getReportingMode", GetReportingMode);
  E("ASensor_getHighestDirectReportRateLevel", HighestDirectRate);
  E("ASensor_getHandle", GetHandle);
  E("ASensor_getResolution", GetResolution);
  E("ASensor_isWakeUpSensor", IsWakeUpSensor);
  E("ASensor_isDirectChannelTypeSupported", IsDirectChannelSupported);
#undef E
}

}  // namespace gesher::bionic::sensors_ndk
