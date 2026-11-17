/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
// Only the host POSIX callback transport, Bionic fixture registrations and
// Win32 primitives are adapted. All registry/catalog/ELF policy is production.
#include <gesher/native_loader.h>
#include <gesher/elf_loader.h>
#include <gesher/elf_dlopen.h>

#include <jni.h>
#include <dlfcn.h>
#include <time.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <condition_variable>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

namespace elf = gesher::elf;
namespace loader = gesher::nativeloader;
namespace {
thread_local std::string pending_error, returned_error;
thread_local JNIEnv* current_env = nullptr;
jclass test_class;
jmethodID callback;
thread_local bool observe_creation = false;
std::mutex schedule_mutex;
std::condition_variable schedule_changed;
bool creation_attempted = false;
bool snapshot_ready = false, resume_creation = false;

void ConstructorCallback() {
  if (current_env == nullptr) std::abort();
  current_env->CallStaticVoidMethod(test_class, callback);
  if (current_env->ExceptionCheck()) { current_env->ExceptionDescribe(); std::abort(); }
}
int NdkValue() { return 70; }
int DeviceValue() { return 71; }
int PrivateValue() { return 72; }

std::string Utf(JNIEnv* env, jstring string) {
  if (string == nullptr) return {};
  const char* chars = env->GetStringUTFChars(string, nullptr);
  std::string value(chars != nullptr ? chars : "");
  if (chars != nullptr) env->ReleaseStringUTFChars(string, chars);
  return value;
}
jstring Create(JNIEnv* env, jclass, jobject object, jint sdk, jboolean shared,
                jstring path, jstring permitted, jstring uses) {
  return loader::CreateClassLoaderNamespace(env, sdk, object, shared, nullptr, path, permitted, uses);
}
jstring RaceCreate(JNIEnv* env, jclass, jobject object) {
  observe_creation = true;
  jstring result = loader::CreateClassLoaderNamespace(env, 31, object, false, nullptr,
                                                       nullptr, nullptr, nullptr);
  observe_creation = false;
  return result;
}
void AwaitCreationAttempt(JNIEnv*, jclass) {
  std::unique_lock<std::mutex> lock(schedule_mutex);
  if (!schedule_changed.wait_for(lock, std::chrono::seconds(5), [] { return creation_attempted; })) {
    std::fputs("FAIL concurrent creator never reached real ELF lock acquisition\n", stderr);
    std::_Exit(124);
  }
}
void AwaitCreationSnapshot(JNIEnv*, jclass) {
  std::unique_lock<std::mutex> lock(schedule_mutex);
  if (!schedule_changed.wait_for(lock, std::chrono::seconds(5), [] { return snapshot_ready; })) {
    std::fputs("FAIL concurrent creator never completed the real provider snapshot\n", stderr);
    std::_Exit(124);
  }
}
void ResumeCreation(JNIEnv*, jclass) {
  {
    std::lock_guard<std::mutex> lock(schedule_mutex);
    resume_creation = true;
  }
  schedule_changed.notify_all();
}
jlong Open(JNIEnv* env, jclass, jobject object, jint sdk, jstring path, jstring search) {
  std::string filename = Utf(env, path);
  bool bridge = false;
  char* error = nullptr;
  JNIEnv* previous = current_env;
  current_env = env;
  void* handle = loader::OpenNativeLibrary(env, sdk, path == nullptr ? nullptr : filename.c_str(),
                                            object, nullptr, search, &bridge, &error);
  current_env = previous;
  if (handle == nullptr && !env->ExceptionCheck()) {
    jclass type = env->FindClass("java/lang/UnsatisfiedLinkError");
    env->ThrowNew(type, error != nullptr ? error : "missing loader error");
    env->DeleteLocalRef(type);
  }
  std::free(error);
  return reinterpret_cast<jlong>(handle);
}
jint Value(JNIEnv*, jclass, jlong handle) {
  void* address = elf::Lookup(reinterpret_cast<elf::Library*>(handle), "namespace_value");
  return address != nullptr ? reinterpret_cast<int(*)()>(address)() : -9999;
}
void SetApplicationTargetSdk(JNIEnv*, jclass, jint version) {
  loader::SetApplicationTargetSdkVersion(static_cast<uint32_t>(version));
}
jint ApplicationTargetSdk(JNIEnv*, jclass) { return elf::DefaultTargetSdkVersion(); }
jobjectArray Builtins(JNIEnv* env, jclass) { return loader::BuiltinCatalog(env); }
jobjectArray PublicFiles(JNIEnv* env, jclass) { return loader::PublicFileCatalog(env); }
jlong LoadedCount(JNIEnv*, jclass) {
  jlong count = 0;
  elf::ForEachLoaded([&](const elf::PhdrInfo&) { ++count; return 0; });
  return count;
}
jlong ProcessRssKiB(JNIEnv*, jclass) {
  std::FILE* input = std::fopen("/proc/self/statm", "r");
  if (input == nullptr) return -1;
  unsigned long total = 0, resident = 0;
  const int read = std::fscanf(input, "%lu %lu", &total, &resident);
  std::fclose(input);
  if (read != 2) return -1;
  return static_cast<jlong>(resident) * sysconf(_SC_PAGESIZE) / 1024;
}
jlong UptimeMillis(JNIEnv*, jclass) {
  // The real framework ApplicationInfo constructor records uptime. Supply the
  // host's actual monotonic clock at this explicit OS boundary, not a constant.
  struct timespec time{};
  if (clock_gettime(CLOCK_MONOTONIC, &time) != 0) std::abort();
  return static_cast<jlong>(time.tv_sec) * 1000 + time.tv_nsec / 1000000;
}

bool Provider(const char* name, elf::ProviderVisibility visibility, const char* symbol, void* address) {
  elf::ProviderOptions options;
  options.soname = name;
  options.visibility = visibility;
  options.architecture_mask = 1;
  options.lookup = [symbol, address](const char* requested) -> void* {
    return std::strcmp(requested, symbol) == 0 ? address : nullptr;
  };
  std::string error;
  if (elf::RegisterProvider(options, &error) != nullptr) return true;
  std::fprintf(stderr, "fixture provider: %s\n", error.c_str());
  return false;
}
}  // namespace

namespace gesher::elf {
void ObserveNamespaceOperationAttempt() {
  if (!observe_creation) return;
  {
    std::lock_guard<std::mutex> lock(schedule_mutex);
    creation_attempted = true;
  }
  schedule_changed.notify_one();
}
Namespace* ObservedCreateNamespace(const NamespaceOptions& options, std::string* error) {
  ObserveNamespaceOperationAttempt();
  return CreateNamespace(options, error);
}
std::vector<ProviderInfo> ObservedAvailableProviders() {
  auto result = AvailableProviders();
  if (observe_creation) {
    std::unique_lock<std::mutex> lock(schedule_mutex);
    snapshot_ready = true;
    schedule_changed.notify_all();
    if (!schedule_changed.wait_for(lock, std::chrono::seconds(5), [] { return resume_creation; })) {
      std::fputs("FAIL constructor never resumed the concurrent creator\n", stderr);
      std::_Exit(124);
    }
  }
  return result;
}
}  // namespace gesher::elf

namespace gesher::bionic {
void InstallJniBridge() {}  // explicit fixture provider boundary, not a production catalog claim
}

extern "C" void* gesher_dlopen_in_namespace(const char* path, int flags,
                                             const gesher_elf_load_context* context) {
  elf::LoadOptions options;
  options.flags = flags;
  options.context.name_space = static_cast<elf::Namespace*>(context->name_space);
  options.context.architecture = static_cast<elf::Architecture>(context->architecture);
  options.context.caller_address = context->caller_address;
  return elf::Load(path, &pending_error, options);
}
extern "C" void* gesher_dlopen_host(const char* path, int flags) { return dlopen(path, flags); }
extern "C" char* gesher_registry_host_dlerror() noexcept {
  returned_error = std::move(pending_error);
  pending_error.clear();
  return returned_error.empty() ? nullptr : returned_error.data();
}

int main(int argc, char** argv) {
  if (argc != 3 && argc != 4) return 2;
  if (!Provider("libregistry_events.so", elf::ProviderVisibility::kNdkPublic,
                  "registry_ctor_event", reinterpret_cast<void*>(&ConstructorCallback)) ||
      !Provider("libregistry_ndk.so", elf::ProviderVisibility::kNdkPublic,
                  "namespace_value", reinterpret_cast<void*>(&NdkValue)) ||
      !Provider("libregistry_device.so", elf::ProviderVisibility::kDevicePublic,
                  "namespace_value", reinterpret_cast<void*>(&DeviceValue)) ||
      !Provider("libregistry_private.so", elf::ProviderVisibility::kPrivate,
                  "namespace_value", reinterpret_cast<void*>(&PrivateValue))) return 1;
  std::string classpath = std::string("-Djava.class.path=") + argv[2];
  JavaVMOption options[] = {{classpath.data(), nullptr}, {const_cast<char*>("-Xcheck:jni"), nullptr}};
  JavaVMInitArgs args{};
  args.version = JNI_VERSION_1_8;
  args.nOptions = 2;
  args.options = options;
  JavaVM* vm = nullptr;
  JNIEnv* env = nullptr;
  if (JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &args) != JNI_OK) return 3;
  jclass local_test_class = env->FindClass("NativeNamespaceRegistryTest");
  if (local_test_class == nullptr) { env->ExceptionDescribe(); return 4; }
  test_class = static_cast<jclass>(env->NewGlobalRef(local_test_class));
  env->DeleteLocalRef(local_test_class);
#define METHOD(name, signature, fn) {const_cast<char*>(name), const_cast<char*>(signature), reinterpret_cast<void*>(fn)}
  const JNINativeMethod methods[] = {
      METHOD("create", "(Ljava/lang/ClassLoader;IZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", Create),
      METHOD("open", "(Ljava/lang/ClassLoader;ILjava/lang/String;Ljava/lang/String;)J", Open),
      METHOD("value", "(J)I", Value), METHOD("builtins", "()[[Ljava/lang/String;", Builtins),
      METHOD("publicFiles", "()[[Ljava/lang/String;", PublicFiles), METHOD("loadedCount", "()J", LoadedCount),
      METHOD("processRssKiB", "()J", ProcessRssKiB),
      METHOD("setApplicationTargetSdk", "(I)V", SetApplicationTargetSdk),
      METHOD("applicationTargetSdk", "()I", ApplicationTargetSdk),
      METHOD("raceCreate", "(Ljava/lang/ClassLoader;)Ljava/lang/String;", RaceCreate),
      METHOD("awaitCreationAttempt", "()V", AwaitCreationAttempt),
      METHOD("awaitCreationSnapshot", "()V", AwaitCreationSnapshot),
      METHOD("resumeCreation", "()V", ResumeCreation)};
#undef METHOD
  if (env->RegisterNatives(test_class, methods, sizeof(methods) / sizeof(methods[0])) != 0) return 5;
  callback = env->GetStaticMethodID(test_class, "constructorCallback", "()V");
  jmethodID entry = env->GetStaticMethodID(test_class, "main", "([Ljava/lang/String;)V");
  jclass strings = env->FindClass("java/lang/String");
  jobjectArray arguments = env->NewObjectArray(1, strings, nullptr);
  jstring root = env->NewStringUTF(argv[1]);
  env->SetObjectArrayElement(arguments, 0, root);
  env->CallStaticVoidMethod(test_class, entry, arguments);
  bool failed = env->ExceptionCheck();
  if (failed) { env->ExceptionDescribe(); env->ExceptionClear(); }
  if (!failed && argc == 4) {
    jclass clock = env->FindClass("android/os/SystemClock");
    const JNINativeMethod clock_methods[] = {
        {const_cast<char*>("uptimeMillis"), const_cast<char*>("()J"), reinterpret_cast<void*>(UptimeMillis)}};
    if (clock == nullptr || env->RegisterNatives(clock, clock_methods, 1) != 0) {
      env->ExceptionDescribe();
      return 6;
    }
    env->DeleteLocalRef(clock);
    jclass catalog = env->FindClass("com/gesher/server/NativeLibraryCatalog");
    const JNINativeMethod catalog_methods[] = {
        {const_cast<char*>("nativeBuiltinCatalog"), const_cast<char*>("()[[Ljava/lang/String;"),
         reinterpret_cast<void*>(Builtins)},
        {const_cast<char*>("nativePublicFileCatalog"), const_cast<char*>("()[[Ljava/lang/String;"),
         reinterpret_cast<void*>(PublicFiles)}};
    if (catalog == nullptr || env->RegisterNatives(catalog, catalog_methods, 2) != 0) failed = true;
    if (!failed) {
      jclass policy = env->FindClass("NativeLibraryPolicyTest");
      jmethodID policy_main = policy == nullptr ? nullptr :
          env->GetStaticMethodID(policy, "main", "([Ljava/lang/String;)V");
      if (policy_main != nullptr) env->CallStaticVoidMethod(policy, policy_main, arguments);
      failed = env->ExceptionCheck() || policy_main == nullptr;
      if (policy != nullptr) env->DeleteLocalRef(policy);
    }
    if (failed && env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    if (catalog != nullptr) env->DeleteLocalRef(catalog);
  }
  env->DeleteLocalRef(root);
  env->DeleteLocalRef(arguments);
  env->DeleteLocalRef(strings);
  env->DeleteGlobalRef(test_class);
  loader::Reset();
  vm->DestroyJavaVM();
  return failed ? 1 : 0;
}
