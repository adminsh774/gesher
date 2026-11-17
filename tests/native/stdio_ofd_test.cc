// Runs the same public stdio corpus as the Android ELF libc test.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static int checks;
static int failures;
static void check(const char* name, int ok) {
  ++checks;
  if (!ok) { ++failures; fprintf(stderr, "FAIL stdio: %s\n", name); }
}
#include "stdio_ofd_contract.inc"
static FILE* exit_stream;
static void ExitOutput() { fputs("late", exit_stream); }
int main(int argc, char** argv) {
  if (argc == 3 && strcmp(argv[1], "--exit-output") == 0) {
    atexit(ExitOutput);
    exit_stream = fopen(argv[2], "w");
    if (!exit_stream || fputs("early", exit_stream) < 0) return 2;
    return 0;
  }
  test_stdio_ofd_contracts();
  printf("stdio OFD contracts: %d checks, %d failures\n", checks, failures);
  return failures ? 1 : 0;
}
