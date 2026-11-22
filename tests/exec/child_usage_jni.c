#include <jni.h>
#include <stdint.h>
#include <sys/resource.h>
#include <sys/times.h>
#include <unistd.h>

JNIEXPORT jlongArray JNICALL Java_gesher_ChildUsageProcessTest_snapshot(JNIEnv* env, jclass type) {
  (void)type;
  struct rusage r;
  struct tms t;
  if (getrusage(RUSAGE_CHILDREN, &r) || times(&t) == (clock_t)-1) return NULL;
  jlong values[] = {(int64_t)r.ru_utime.tv_sec * 1000000 + r.ru_utime.tv_usec,
                    (int64_t)r.ru_stime.tv_sec * 1000000 + r.ru_stime.tv_usec,
                    t.tms_cutime, t.tms_cstime, sysconf(_SC_CLK_TCK)};
  jlongArray result = (*env)->NewLongArray(env, 5);
  if (result) (*env)->SetLongArrayRegion(env, result, 0, 5, values);
  return result;
}
