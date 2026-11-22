// Host fixture for the Android Looper boundary. Uses actual Linux eventfd/poll;
// production sensors_ndk.cc owns all sensor queues, reads and callbacks.
#pragma once
#include <poll.h>

#include <atomic>
#include <mutex>
#include <utility>
#include <vector>

namespace android {

class RefBase {
 public:
  void incStrong(const void*) const { refs_.fetch_add(1); }
  void decStrong(const void*) const { if (refs_.fetch_sub(1) == 1) delete this; }
 protected:
  virtual ~RefBase() = default;
 private:
  mutable std::atomic<int> refs_{0};
};

template<class T> class sp {
 public:
  sp() = default;
  sp(T* pointer) : pointer_(pointer) { Add(); }
  sp(const sp& other) : sp(other.pointer_) {}
  template<class U> sp(const sp<U>& other) : sp(other.get()) {}
  sp(sp&& other) noexcept : pointer_(std::exchange(other.pointer_, nullptr)) {}
  ~sp() { Drop(); }
  sp& operator=(sp other) { std::swap(pointer_, other.pointer_); return *this; }
  T* get() const { return pointer_; }
  T* operator->() const { return pointer_; }
  explicit operator bool() const { return pointer_ != nullptr; }
  template<class... A> static sp make(A&&... arguments) {
    return sp(new T(std::forward<A>(arguments)...));
  }
 private:
  void Add() { if (pointer_ != nullptr) pointer_->incStrong(this); }
  void Drop() { if (pointer_ != nullptr) pointer_->decStrong(this); }
  T* pointer_ = nullptr;
};

class LooperCallback : public RefBase {
 public:
  virtual int handleEvent(int, int, void*) = 0;
};

class Looper : public RefBase {
 public:
  static constexpr int EVENT_INPUT = 1;
  explicit Looper(bool allow_non_callbacks) : allow_(allow_non_callbacks) {}

  int addFd(int fd, int ident, int events, const sp<LooperCallback>& callback, void* data) {
    if (fd < 0 || (!callback && (!allow_ || ident < 0))) return -1;
    std::lock_guard<std::mutex> lock(mutex_);
    RemoveLocked(fd);
    watches_.push_back({fd, callback ? -2 : ident, events, callback, data, ++sequence_});
    return 1;
  }
  int removeFd(int fd) {
    std::lock_guard<std::mutex> lock(mutex_);
    return RemoveLocked(fd);
  }
  size_t Count() { std::lock_guard<std::mutex> lock(mutex_); return watches_.size(); }
  int PollOnce(int timeout, int* output_fd = nullptr, int* output_events = nullptr,
               void** output_data = nullptr) {
    std::vector<Watch> watches;
    { std::lock_guard<std::mutex> lock(mutex_); watches = watches_; }
    std::vector<pollfd> fds;
    for (const Watch& watch : watches) fds.push_back({watch.fd, POLLIN, 0});
    int result = poll(fds.data(), fds.size(), timeout);
    if (result == 0) return -3;
    if (result < 0) return -4;
    for (size_t i = 0; i < fds.size(); ++i) {
      if (fds[i].revents == 0) continue;
      const Watch& watch = watches[i];
      if (output_fd != nullptr) *output_fd = watch.fd;
      if (output_events != nullptr) *output_events = fds[i].revents;
      if (output_data != nullptr) *output_data = watch.data;
      if (!watch.callback) return watch.ident;
      const int keep = watch.callback->handleEvent(watch.fd, fds[i].revents, watch.data);
      if (!keep) {
        std::lock_guard<std::mutex> lock(mutex_);
        for (const Watch& current : watches_) {
          if (current.fd == watch.fd && current.sequence == watch.sequence) {
            RemoveLocked(watch.fd);
            break;
          }
        }
      }
      return -2;
    }
    return -3;
  }

 private:
  struct Watch {
    int fd;
    int ident;
    int events;
    sp<LooperCallback> callback;
    void* data;
    unsigned sequence;
  };
  int RemoveLocked(int fd) {
    for (auto it = watches_.begin(); it != watches_.end(); ++it) {
      if (it->fd == fd) { watches_.erase(it); return 1; }
    }
    return 0;
  }
  bool allow_;
  std::mutex mutex_;
  std::vector<Watch> watches_;
  unsigned sequence_ = 0;
};

}  // namespace android
