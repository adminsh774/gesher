// Copyright (C) 2026 The Gesher Authors
// SPDX-License-Identifier: Apache-2.0
// Android 16 Bionic's public realtime range, via both ordinary imports and
// runtime symbol lookup. Included in the same actual x64/ARM64 libc guest.
#ifndef GESHER_BIONIC_SIGNAL_RANGE_CONTRACTS_H
#define GESHER_BIONIC_SIGNAL_RANGE_CONTRACTS_H
#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>

static int BionicSignalRangeContracts(void) {
  int failures = 0;
  const int original_errno = errno;
  printf("Bionic realtime range contracts: begin\n");
#define RANGE_CHECK(label, expression) do { \
    const int ok = (expression); \
    printf("  realtime range: %-45s %s\n", label, ok ? "ok" : "FAIL"); \
    if (!ok) ++failures; \
  } while (0)
  int (*minimum)(void) = (int (*)(void))dlsym(RTLD_DEFAULT, "__libc_current_sigrtmin");
  int (*maximum)(void) = (int (*)(void))dlsym(RTLD_DEFAULT, "__libc_current_sigrtmax");
  RANGE_CHECK("both public function exports resolve", minimum != NULL && maximum != NULL);
  if (minimum != NULL && maximum != NULL) {
    errno = EDOM;
    const int low = SIGRTMIN;
    const int high = SIGRTMAX;
    const int macro_errno = errno;
    RANGE_CHECK("SIGRTMIN excludes ten Bionic reserved signals", low == 42);
    RANGE_CHECK("SIGRTMAX includes the highest kernel signal", high == 64);
    RANGE_CHECK("public macro calls preserve errno", macro_errno == EDOM);

    errno = ERANGE;
    const int looked_up_low = minimum();
    const int looked_up_high = maximum();
    const int lookup_errno = errno;
    RANGE_CHECK("resolved functions agree with ordinary imports",
                looked_up_low == low && looked_up_high == high);
    RANGE_CHECK("resolved function calls preserve errno", lookup_errno == ERANGE);

    int stable = 1;
    for (int i = 0; i != 32; ++i)
      stable = stable && minimum() == low && maximum() == high;
    RANGE_CHECK("repeated queries keep the same public range", stable);
    sigset_t mask;
    RANGE_CHECK("both public endpoints belong to a signal set",
                sigemptyset(&mask) == 0 && sigaddset(&mask, low) == 0 &&
                sigaddset(&mask, high) == 0 && sigismember(&mask, low) == 1 &&
                sigismember(&mask, high) == 1);
  }
#undef RANGE_CHECK
  printf("Bionic realtime range contracts: %s (%d failures)\n",
         failures ? "FAILED" : "all passed", failures);
  errno = original_errno;
  return failures;
}
#endif
